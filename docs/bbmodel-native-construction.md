# bbmodel → MC 原生模型树（0.36.0 重写）

> 本文记录 mtr-next 4.0 车包渲染从「手算四边面」改为「复刻上游构造过程」的
> 全部依据与结论。所有断言均来自上游 MTR 4 源码或对 MC 1.20.1 字节码/运行时的实测，
> 不含推测。

## 一、为什么改

旧实现（`BbModelLoader` + 逐面绘制）把 bbmodel 拆成自制的 `BbBox`（六面 + 各自 UV），
再在渲染时手工画六个四边形。问题不是「算得不准」，而是**丢掉了 bbmodel 的结构语义**：

| bbmodel 语义 | 旧实现 | 结果 |
| --- | --- | --- |
| 元素 `rotation` | 完全忽略 | 斜置的车窗/铰链全错位 |
| 元素 `origin`（旋转中心） | 忽略 | 旋转绕世界原点而非元素原点 |
| outliner 嵌套组层级 | 扁平化 | 多级组的 pivot 链丢失 |
| `inflate`（元素膨胀） | 部分支持 | 与组内其他元素不一致 |
| `shade` / `mirror_uv` | 近似 | 镜像贴图朝向错 |
| box-UV 布局 | 自己推 | 早期反复推错（见 `docs/bbox-uv-flaw.md`） |

逐项打补丁的路子已经走到尽头——上游 MTR 4 根本没有这套手算路径。

## 二、上游怎么做的

上游 MTR 4 **不手算几何/UV**。它把 bbmodel 翻译成 MC 原生的 `ModelPart` 树：

```
bbmodel ──(BlockbenchModel/BlockbenchOutline/BlockbenchElement)──▶ GroupTransformations
                                                                    │
                       MeshDefinition ──▶ PartDefinition ──▶ CubeListBuilder
                                                                    │
                                              LayerDefinition.create(...).bakeRoot()
                                                                    │
                                                        ModelPart.render(...)
```

对应上游类（副本见 `tools/upstream_*.java`）：

- `BlockbenchModel` — 读 bbmodel
- `BlockbenchOutline` — outliner 节点（组）
- `BlockbenchElement` — elements 条目
- `GroupTransformations` — **变换链**（pivot 差分 + 旋转）
- `ModelPartExtension` — `PartDefinition` 的薄封装
- `EntityModelExtension` — bake 成 `ModelPart`
- `DynamicVehicleModel` — 按车包装配
- `ModelPropertiesPart` — 逐部件渲染 + 门位移

**结论：mtr-next 应当复刻「构造过程」，而不是复刻「手算结果」。**

## 三、构造语义（逐条对照上游源码）

### 3.1 组的变换链

`BlockbenchOutline.add(gt, previous, id)`（L39-48）：

- `previous == null`（顶层组）：**rotation 被强制为 0**，并 WARN
  `Skipping Blockbench outline rotation`；origin 用**绝对值**
- `previous != null`（嵌套组）：origin 用**与父组的差**
  `origin[i] - previous.origin[i]`，rotation 用自己的

`GroupTransformations(gt, origin, rotation)`（L16-26）把一节存成：

```
pivot = (-origin[0], -origin[1], +origin[2])
rot   = (rad(-rotation[0]), rad(-rotation[1]), rad(+rotation[2]))
```

### 3.2 建树

`GroupTransformations.create(rootModelPart, modelYOffset)`（L28-44）：

```java
float offset = modelYOffset * 16;
for (GroupTransformation gt : list) {
    modelPart.setPivot(gt.pivotX - combinedPivotX,
                       gt.pivotY - combinedPivotY - offset,
                       gt.pivotZ - combinedPivotZ);
    modelPart.setRotation(gt.rotateX, gt.rotateY, gt.rotateZ);
    combinedPivotX += gt.pivotX;  // …Y / …Z 同理
    modelPart = modelPart.addChild();
    offset = 0;                   // ← offset 只作用在第一节
}
```

要点：

- **pivot 逐级差分**（减掉之前所有节的累计）
- **modelYOffset 只落在第一节**，之后清零
- 每节 `addChild()` 产生一个新节点 → 链长 = 节数

### 3.3 元素

`BlockbenchElement.setModelPart(...)`（L21-59）：

```java
float originX = -origin[0];
float originY = -origin[1] - modelYOffset * 16;   // ← 第一次减 offset
float originZ = +origin[2];

// 元素自身的 origin/rotation 追加为链的最后一节
GroupTransformations newGt = new GroupTransformations(gt, origin, rotation);
ModelPartExtension modelPart = newGt.create(parentModelPart, modelYOffset);
modelPart.setTextureUVOffset(uv_offset[0], uv_offset[1]);

float x = -to[0] - originX;
float y = -to[1] - originY - modelYOffset * 16;     // ← 第二次减 offset
float z = +from[2] - originZ;
int sizeX = round(to[0] - from[0]);  // …Y / …Z 同理

modelPart.addCuboid(x, y, z, sizeX, sizeY, sizeZ, inflate, !shade || mirror_uv);
```

- 元素自身也构成一节链（origin/rotation 参与）
- cube 挂在**链末节**之下，**旋转在链节上而非 cube 上**
- `modelYOffset` 在元素处被减**两次**（L24 的 originY、L35 的 y）——两处符号相反的部分互相抵消，
  净效果是整体 Y 偏移 −modelYOffset*16
- 镜像条件：`!shade || mirror_uv`

### 3.4 顶层组不建节点

`DynamicVehicleModel` 对每个顶层 outliner 组：

- 建一个**零姿态锚点**（`createModelPart()`）作为该组的 ModelPart 根
- 组内元素从锚点各自建链
- **组本身不占 ModelPart 节点**（只有元素占）
- `nameToPart.put(groupName, anchor)` —— 键是**顶层组名**

这解释了 ERIDU 的 `men2` / `men2_n` 等：两个不同的顶层组，各自带一半车身。

## 四、MC 1.20.1 的 box-UV 布局（实测）

对 16³ 盒、`uv_offset = (100, 200)`，实测 `ModelPart$Cube` 的 6 个多边形：

| 面 | u 区间 | v 区间 |
| --- | --- | --- |
| down | 116..132 | 200..216 |
| up | 132..148 | 200..216 |
| west | 100..116 | 216..232 |
| north | 116..132 | 216..232 |
| east | 132..148 | 216..232 |
| south | 148..164 | 216..232 |

多边形添加顺序固定为 **DOWN, UP, WEST, NORTH, EAST, SOUTH**。

与 Blockbench 的面名对照：MC `west` ↔ BB `east`，MC `east` ↔ BB `west`，
MC `up` ↔ BB `down`，MC `down` ↔ BB `up`——这正是上游用 **X 取负、Y 取负、Z 保持**
的坐标约定所补偿的。

**mirror 的真实作用**（实测，非推测）：`CubeListBuilder.mirror(true)` **不改变 UV 区域**，
只翻转**顶点顺序**（效果是贴图沿 V 镜像）。旧文档里「mirror 改 UV 布局」的说法是错的。

## 五、渲染与门位移

`ModelPropertiesPart.renderNormal`（L347-364）：

```java
x = partDetails.x + doorAnimationType.getDoorAnimationX(doorXMultiplier, partDetails.flipped, max(doorValue, doorOverrideValue));
z = partDetails.z + getInterpolatedDoorValue(doorAnimationType, doorZMultiplier, partDetails.flipped, …);
…
partDetails.modelParts.forEach(modelPart ->
    modelPart.render(graphicsHolder, x, y, z, partDetails.flipped ? (float) Math.PI : 0, light, overlay));
```

- 位移单位是**像素**（MC 在 `render` 内部 /16）
- 翻转位 = 绕 Y 轴 180°（`rotateY(π)`）
- 渲染对象是**组锚点**（`nameToPart` 的值），门位移移动整个顶层组

`iteratePositions`（L654-656）：

```java
positions.forEach(p -> cb.accept(p.getX(), p.getY(), p.getZ(), false));
positionsFlipped.forEach(p -> cb.accept(-p.getX(), p.getY(), p.getZ(), true));
```

- 正位 → `flipped=false`
- 翻转位 → **X 取反** + `flipped=true`
- 两者都渲染

粒度是「**部件记录 × 位置实例**」：同一顶层组可被多条 `parts[]` 记录引用
（ERIDU 的 `men2` 有两条：一条 +11 只走正位、一条 −11 只走翻转位），
**按组名折叠会丢记录**。

## 六、mtr-next 的实现

| 文件 | 职责 |
| --- | --- |
| `client/render/BbModelTree.java` | bbmodel → MC 原生 `ModelPart` 树（复刻 §3 全部语义） |
| `client/render/BbModelTreeCache.java` | 资源加载 + 缓存（键 = modelResource + "\|" + propertiesResource） |
| `client/render/BbModelTrainRenderer.java` | 按部件记录 × 位置实例渲染，门位移对齐 §5 |
| `client/render/FormationPreviewRenderer.java` | 编组屏预览，**共用同一棵树**（保证预览与实车一致） |
| `client/render/DoorOverlapDetector.java` | 复刻上游 `DynamicVehicleModel.testDoors`（门重叠检测），用 BB 坐标系的门盒 |
| ~~`client/render/BbModelLoader.java`~~ | 旧手算路径，**[0.36.5] 已删除**（自测改用 `BbModelTree.parseForTest` + `DoorOverlapDetector`） |

`BbModelTree` 的节点结构：

```
锚点（零姿态，groupParts 的值）
 └─ 链节 1（组节：pivot = 组 origin，rotation = 顶层强制 0 / 嵌套用自己的）
     └─ 链节 2（…嵌套组节…）
         └─ 链节 N（元素节：pivot = 元素 origin 相对父组差，rotation = 元素 rotation）
             └─ cube 节点（带几何 box-UV）
```

### 自测

`fabric/src/test/java/mtrnext/BbModelTreeTest.java`（gradle 任务 `bbModelTreeTest`）覆盖：

- 单盒几何（中心/尺寸，上游 X/Y 取负 Z 保持）
- 组链 pivot 差分（含链长）
- 元素 rotation 入链（且 cube 节点自身无旋转）
- modelYOffset 落点（首节承担，末节清零）
- **box-UV 布局逐面对比 MC 实测表**（6/6 面）
- mirror 的真实作用（顶点 V 顺序翻转，非 UV 区域变化）
- 嵌套组归属顶层组名
- 扁平模型退化
- 顶层组 rotation 被强制 0

`fabric/src/test/java/mtrnext/ParserSelfTest.java`（gradle 任务 `parserSelfTest`）是原有的
纯数据层回归（sound.cfg / 调速表 / 编组规则 / 门动画 / 位定义 / ERIDU 实包）。

两个任务都**不需要图形客户端**（边界见 `docs/client-verification-boundary.md`）。

## 七、仍然未验证的部分

诚实标注——以下**没有**离线验证手段，只能靠客户端目视：

- 实际贴图采样是否与上游一致（需要真实客户端 + 资源包）
- 光照/透明批次分层（`isTranslucentBatch`）与上游的差异
- `OptimizedModel`（优化渲染）路径：上游在 `OptimizedRenderer.hasOptimizedRendering()` 为真时
  走 `MaterialGroup.addCube` 路径，mtr-next 用的是**非优化**的 `ModelPart.render`。
  **★ 2026-10-21 字节码确证（已大幅下调风险）**：
  `MaterialGroup.addCube` **并不抽取 cube 几何** —— 它把整个 `ModelPart` 挂到
  `CapturingVertexConsumer`（MC `VertexConsumer` 的实现）上**照常走 `ModelPart.render`**，
  优化点只是**顶点批次合并**（减少 draw call）。
  ⇒ **渲染语义与 mtr-next 等价**，mtr-next 仅少了这一层性能优化。
  证据：`docs/_upstream/binary-evidence/optimized-model-addcube-4.0.6/README.md`。
  **⚠️ 残留风险（与 `addCube` 无关）**：上游 `ModelPropertiesPart.renderNormal`
  的优化分支画的是**另一个** ModelPart（`partDetails.optimizedModelDoor`），
  而常规分支画 `partDetails.modelParts`；上游的分支是**二选一**。
  所以当资源包**启用优化渲染且同时提供 `optimizedModelDoor`** 时，
  上游画面里**没有**常规门几何，而 mtr-next **仍然会画** ⇒ **可能多出一层门（重影）**。
  本地暂无启用优化渲染的 MTR 4 资源包可验证，故**只标注不修**；
  若日后遇到"门重影"类问题，**先查资源包是否启用了优化渲染**。
- 三个 mod（mtr-next + mtr-turbo + mtr-ante-continue）同时加载的运行时共存
