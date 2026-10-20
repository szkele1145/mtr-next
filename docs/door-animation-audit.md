# 门动画链路审查

> 2026-10-15 初版；**2026-10-18 依据实测大幅修正**（推翻初版多处判断，见下）；
> **2026-10-20 §4 四个开放问题全部关闭**——已取得 MTR 4.x 上游 MIT 源码并逐行比对，
> 见 §4b；**2026-10-20 §五c 关闭 `flipped` 开放项**——`positionsFlipped` 镜像
> 展开已实现（0.35.3），离线回归 **216 项全绿**，真实 ERIDU 车包端到端验证通过；
> **2026-10-20 §五d 补齐门时序/闪烁字段**（0.35.4，纯数据层），并修正
> **`DOOR_MOVE_TIME = 3200`**（此前误设为 500）。离线回归 **243 项全绿**。
> **2026-10-20 §五e 记录 0.36.0 发布后的复核结果**，并留一条**尚未修复的真实
> 偏差**：上游 `getInterpolatedDoorValue` 的 500 ms 门位移插值层未实现
> （影响 ERIDU 全部 42 个门部件）。
> **2026-10-20 §五f**：该插值层已在 0.36.2 实现并发布双渠道
> （Modrinth `SpyfJ7xr` / GitHub `v0.36.2`）；并查明其真实语义——
> **只在开门↔关门方向翻转时生效**，同方向变化不插值（与直觉相反）。
> 另留两条待办：`opening` 应由门开度变化方向推导（现用布尔
> `doorsOpen`）、门时序窗口/闪烁仍未接入渲染。

## 一、3.x 内置渲染器的门动画（不迁移）

3.x 的 `DoorAnimationType` 挂在 **ModelSimpleTrainBase**（内置车型渲染器）上——
`CustomResources.reload` 从车包 JSON 读取该字段后传给
`ModelSimpleTrainBase.doorAnimationType`（已读 `yomis-mtr` 源码确认）。

`BbModelTrainRenderer` 是自定义 `TrainRendererBase`（**不在** `ModelSimpleTrainBase`
继承链里），所以 3.x 的门解析路径**故意不迁移**。4.0 车包的门语义由
`renderCar(..., boolean doorLeftOpen, boolean doorRightOpen)` 的两个布尔参数驱动
（基类抽象签名已 javap 实证）。

## 二、实测：当前门识别对 4.0 参考包完全失效

用真实 4.0 参考包实测（`mtr-turbo/build/eridu-pack/assets/mtr/eridudanguei.bbmodel`，
ERIDU-DG1000A，**970 个 elements**）：

### 事实 1：elements[].name 不含门信息

`elements[].name` 取值分布（实测）：`cube` 690、`cude` 57、`yj` 56、`8x` 45、
`16x` 33、`qiang` 30、`bb` 28、`gueidao` 15、`fv` 8、`db` 6、`lcdred` 2。
**没有任何一个含 "door"**。

### 事实 2：门信息在 properties_*.json 的 parts[] 里

4.0 包的门归属**不靠命名猜**，而是 `properties_*.json` 中每个 part 的显式字段。
实测 eridu 包共 14 个 properties 文件，其中 42 个部件带非零门倍率：

| 字段 | 实测取值 | 说明 |
|---|---|---|
| `names[]` | `men2`、`men3`、`door_n`、`doordengguan_n`、`lcdp_doorshanshuo` 等 | 匹配 **outliner 分组名**（已验证：65 个被引用的点名中 35 个命中 `eridudanguei` 的 outliner 组名，其余属同一车辆的其他层模型） |
| `doorXMultiplier` | `0.0` / `1.0` | X 轴倍率 |
| `doorZMultiplier` | **`men2`=+11.0、`men3`=−11.0**、`doordengshan_n`=0.01、`lcdp_doorshanshuo`=0.01 | Z 轴倍率；**左右门符号相反**（这是左右分离的权威依据） |
| `doorAnimationType` | `STANDARD`、`STANDARD_SLOW` | 插值曲线选择 |
| `condition` | `NORMAL`、`DOORS_CLOSED`、`DOORS_OPENED`、`AT_DEPOT`、`ON_ROUTE_FORWARDS`、`ON_ROUTE_BACKWARDS` | 渲染条件 |
| `type` | `NORMAL`、`DOORWAY`、`FLOOR`、`DISPLAY` | 部件类别 |
| `renderStage` | `EXTERIOR`、`INTERIOR`、`INTERIOR_TRANSLUCENT`、`LIGHT`、`ALWAYS_ON_LIGHT` | 渲染阶段 |

（`doorZMultiplier` 的 ±11.0 是**像素**：men2 与 men3 一正一负，正好对应左右两扇门反向滑动。）

### 事实 3：outliner 分组名承载门的命名

`eridudanguei` 的 outliner 含 58 个分组名，其中门相关：`door`、`door2`、`door3`、
`door_n`、`men2`、`men2_n`、`men3`、`men3_n`、`doordeng`、`doordengkai_n`、
`doordengguan_n`、`doordengshan_n`、`doordiban`、`doorrmd`、`mengan`。

**注意 `outliner` 的 `children` 是混合数组**：既可包含子分组对象，也可包含
element 的 uuid 字符串（实测 `door` 组 21 个 children = 20 个 element uuid 字符串
+ 1 个子分组对象）。即：**必须按类型分支处理 children**，不能假定同质。

### 结论：当前实现是静默失效

`BbModelLoader.buildBox()` 用

```java
final boolean isDoor = name.toLowerCase(Locale.ROOT).contains("door");
```

判定，`name` 取自 **`element.name`**。对 4.0 参考包而言元素名全是 `cube` 之类，
`isDoor` **恒为 false** → 门盒永不获得偏移 → 门动画从未生效。

**连锁影响**：0.34.1 为编组预览加的"门开态 `-0.75F` 偏移"
（`FormationPreviewRenderer` L68）同样恒为空转——它依赖同一个 `isDoor`。

且 `Properties4xLoader`（114 行）只解析 `modelYOffset` + gangway/barrier 贴图与几何，
**`parts[]` 完全没有解析**，`doorXMultiplier` / `doorZMultiplier` / `doorAnimationType`
这些字段当前无人读取。

这与 0.34.2 修掉的轨道预览 mixin 是同一类缺陷：**构建与运行都不报错，功能静默不存在**。

## 三、反向核查：并非所有车包都失效

实测 46 个 bbmodel（`element.name` 含 door 的个数 / `outliner` 组名含 door 的个数）：

- **含 door 元素名**：`class_377`(33/23)、`r_train`(28/14)、`a_train`(27/23)、
  `r211`(27/15)、`s_train`(22/15)、`london_underground_1995`(20/10)、`light_rail`(18/20)、
  `sp1900`(18/12)、`cm_stock`(17/12)、`r179`(17/15)、`drl`(16/12)、`m_train`(16/11)、
  `e44`(14/14)、`mlr`(12/11)、`mpl_85`(12/12)、`k_train`(12/14)、`london_underground_d78`(10/12)、
  `class_802`(15/11)、`a320`(6/4)、`door_overlay`(6/4)、`br_423`(5/9)、`c_train`(8/10)、
  `ngong_ping_360`(5/3)
- **0 个**（含 door 命名）：`eridudanguei`、`dangueilcdxa`（仅组名有 2/10）、
  `ac`、`passing_*` 系列、`belief_beacon`、`erailing_door`、`iron_frame`、`security_check_pole`、
  `gaofengg`、`eridudgjiashimianban`、`lift_1`(仅组名 10)、`bogie_1`、`cable_car_grip`

**因此不能说"门识别普遍失效"**。事实是：

- 这些 bbmodel 位于 `yomis-mtr/models/`，其中带 `door_left_*` / `door_right_*` 命名的
  （如 `sp1900.bbmodel`：`door_left_bottom`、`door_right_top_r2`、`driver_door_bottom_1`）
  是 YMTR **内置 Java 模型**（`ModelATrain` / `ModelClass377` 等）的导出产物，
  由 `JonModelTrainRenderer` + `DynamicTrainModel` 消费，**与 mtr-next 的 4.0 路径无关**。
- mtr-next 消费的是 4.0 格式包（`models[]` 含 `modelResource`/`modelPropertiesResource`），
  其参考样本 `eridu-pack` 恰好是元素名无 door 的那一类。

## 四、仍未确认的事项（**已于 2026-10-20 全部关闭，见 §4b**）

1. **`doorXMultiplier` / `doorZMultiplier` 的权威语义未确认**：
   这两个字段在 `yomis-mtr`（YMTR 3.6.4.2 源码，版本与 `libs/ymtr-fabric-1.20.1.jar`
   一致）中**搜索不到**。YMTR 3.x 用的是另一套：`DynamicTrainModel` 读
   `door_offset` 枚举（`NONE`/`LEFT_POSITIVE`/`RIGHT_POSITIVE`/`LEFT_NEGATIVE`/`RIGHT_NEGATIVE`），
   配合 `DoorAnimationType.getDoorAnimationX/Z` 计算偏移。
   即 **4.0 包与 YMTR 3.x 是不同代的车包 schema**，4.0 的权威实现不在本项目手上。
   需查 MTR 4.x 上游源码才能定论倍率单位与符号约定。
2. **倍率单位未确认**：11.0 是像素还是格？bbmodel 坐标本身是像素（`eridudanguei`
   整车 Z 跨 [-120, 126]），若 11 是像素则 ≈0.6875 格，与现有硬编码 `-0.75F` 同量级；
   但这是**推测**，需上游确认。
3. **`doorLeftOpen` / `doorRightOpen` 与倍率正负的映射未确认**：数据只给了
   「`men2` 用 +11、`men3` 用 −11」，没说哪个对应 `doorLeftOpen`。
4. **`drawBoxFaces` 只支持 Z 轴偏移**：其 `dz` 参数作用于全部 6 面的 `z` 分量
   （L105-136）。而 4.0 数据同时含 `doorXMultiplier`，当前渲染路径无法表达 X 轴位移。

## 四b、§4 四个问题的上游定论（2026-10-20 实证）

**上游仓库已定位**：`Minecraft-Transit-Railway/Minecraft-Transit-Railway`
（**MIT 许可**，默认分支 `master`，2026-07-16 有提交）。
注意：先前尝试的 `MTRDev/MTR` **不存在**（GitHub API 返回 404，
`api.github.com/users/MTRDev/repos` 只有无关的 `handlebars.js`）。
上游源码快照存于 `docs/_upstream/`（仅供比对，不参与构建）。

逐问回答：

| §4 问题 | 上游定论 | 依据（上游文件:行） |
|---|---|---|
| **Q1** 倍率权威语义 | 4.0 权威实现在 MTR 4.x 上游，字段直读上游 `ModelPropertiesPart` schema；`isDoor()` = `doorXMultiplier != 0 \|\| doorZMultiplier != 0` | `ModelPropertiesPart.java:311-312` |
| **Q2** 倍率单位 | **像素**（Blockbench 像素），渲染时 **/16 转格**。上游三处独立同款换算 | `ModelPropertiesPart.java:241-242`（`getOpenDoorBounds`）、`:524`（`addCube`）、`:536`（`addObjModelPosition`） |
| **Q3** 左右映射 | 上游**不**用 `doorLeftOpen/RightOpen` 二分；门板靠**倍率符号**区分（ERIDU `men2`=+11 / `men3`=−11 天然对滑）。位移方向 = `copySign(abs曲线值, (flipped ? -1 : 1) * multiplier)`，flipped 来自 `positionDefinitions` 的 `positionsFlipped` | `DoorAnimationType.java:15-28`；`ModelPropertiesPart.java:649-650`（flipped 时 X/Z 整体镜像）、`:347-348` |
| **Q4** X 轴位移 | 确认存在：塞拉门（`PLUG_FAST`/`PLUG_SLOW`/`PLUG_SLOW_2`/`PLUG_SLOW_3`）沿 **X 外摆** + 沿 **Z 滑动**；其余类型 X 恒 0 | `DoorAnimationType.java:32-43`（`getDoorAnimationXAbsolute`） |

**连带确认的两项（本轮新发现，均为真实缺陷）**：

- **尺度换算**：上游 `BlockbenchElement.setModelPart` 注释明写
  "Normalize dimensions (16 Blockbench units = 1 Minecraft block)"，返回 `Box` 的
  六个坐标**全部 /16**。mtr-next 0.34.3 之前**未做此换算** → 车体比实际大 16 倍。
  （上游 `docs/_upstream/BlockbenchElement.java:51-58`）
- **UV 归一化除数**：上游用 bbmodel 自身 `resolution`
  （`BlockbenchModel.getTextureWidth/Height` ← `BlockbenchResolution`），
  而非固定 24。实测 ERIDU 四个模型 resolution = **512 / 128 / 24 / 64**，
  `eridudanguei` 的 UV 最大值 u=384、v=448 —— 旧代码硬编码 `/24` 会让 UV
  **严重越界**（u 最大到 16.0），贴图整体错位。3.x 时代的 `24` 是 MC 方块贴图
  边长，对 bbmodel 不适用。

## 五、已实现（0.35）

1. `Properties4xLoader.parseParts` 解析 `parts[]`
   （`names[]`/`doorXMultiplier`/`doorZMultiplier`/`doorAnimationType`/`condition`/`type`）。
2. `BbModelLoader.walkOutliner` 记录每个 element 的**祖先分组名链**，
   `annotate` 按组名匹配 parts 并把倍率/动画型写入盒字段。
3. `DoorAnimation4x` 逐分支移植上游 `DoorAnimationType`（12 个枚举值，
   含 3.x 没有的 `PLUG_SLOW_2`/`PLUG_SLOW_3`），签名带 `flipped`，
   `copySign` 语义与上游一致。
4. `BbModelLoader.buildBox` 坐标 **/16**（上游 `BlockbenchElement` 同源）；
   UV 除数改为 per-model `resolution`。
5. `BbModelTrainRenderer.drawBoxFaces` 扩展为 **X/Z 双轴**位移；
   `renderCar` 按 `isAnimatedDoor()` 走上游曲线，否则沿用旧固定偏移。
6. `ParserSelfTest` 增至 **155 项**（新增门曲线 40+ 项、尺度/UV 13 项、门标注 17 项）。

**曾开放、0.35.3 已关闭**：`flipped` 恒为 `false`——mtr-next 当时尚未实现上游
`positionDefinitions` 的 `positionsFlipped` 位置组。**0.35.3 已实现**，见 §五c。

### 附：`flipTextureV` 已定论（2026-10-20 上游实证）——**不实装是正确的**

上游 `VehicleModel.createModel` 的分支（`VehicleModel.java:114-150`）：

```java
if (modelResource.endsWith(".bbmodel")) {
    return new DynamicVehicleModel(new BlockbenchModel(...), textureId, modelProperties, positionDefinitions, id);
    //                                    ↑ 构造器签名里没有 flipTextureV
} else if (ModelResourceLoader.isSupportedModelResource(modelResource)) {
    return new DynamicVehicleModel(ModelResourceLoader.loadModel(modelResource, textureId, flipTextureV, ...), ...);
    //                                                                      ↑ 只有 .obj/.mqo/.mqoz 路径接收
}
```

即 **上游对 bbmodel 同样不消费 `flipTextureV`**——该字段只影响 OBJ/MQO 的
UV 导入（`ModelResourceLoader.loadModel` → `OptimizedModel.ObjModel.loadModel`）。
Blockbench 导出的 UV 原点本就与 MC 一致，无需翻转。
mtr-next 不实装该字段 = **与上游行为一致**，不是遗漏。

## 五b、已实现（0.35.2 追加）

**门重叠离线检测**（上游 `DynamicVehicleModel.testDoors` 的移植，
`DynamicVehicleModel.java:119-145`）：模拟门从 t=0 到 t=1 走 5 个时间片，
若两扇门"初始不相交、中途相交"，说明门 X/Z 倍率配错（车包作者配置错误），
上游打 WARN。移植为 `BbModelLoader.detectDoorOverlaps(boxes)`，纯数据逻辑可离线回归。

**本次实测得出的两条算法事实**（已写进测试用例）：

1. **同向同量滑动的两扇门永远不会相撞**——相对距离恒定。故能触发告警的
   错误配置是**倍率不等**（快门追上慢门），不是"同向"。
2. ERIDU 的 +11/−11 反向对滑配置，两门**初始包围盒本就相交**（实测 men2
   z 跨 [-56.8, 63.5]、men3 z 跨 [-59.5, 40.0] 像素）→ 上游"初始相交则跳过"
   分支会直接放过，不误报。

> 由此可知上游 `testDoors` 的实际覆盖能力有限：它只能发现"倍率明显配错"的
> 极端情形，对 ERIDU 这类正确配置无诊断价值。移植保留其语义，不夸大其作用。

## 五c、已实现（0.35.3 追加）：`positionsFlipped` 镜像展开

### 上游语义（逐行实证）

| 上游位置 | 代码 | 语义 |
|---|---|---|
| `ModelPropertiesPart.java:654-656` | `iteratePositions(positions, positionsFlipped, cb)`：`positions` → `cb(..., false)`；`positionsFlipped` → `cb(-x, y, z, true)` | **两个列表都渲染**，不是二选一；翻转槽的 X 取反且 `flipped=true` |
| `ModelPropertiesPart.java:364` | `modelPart.render(..., partDetails.flipped ? (float) Math.PI : 0, ...)` | 翻转副本**绕 Y 轴转 180°** |
| `ModelPropertiesPart.java:649-650` | `addBox`: `(flipped ? -1 : 1) * minX + x/16`、`(flipped ? 1 : -1) * minZ + z/16` | 包围盒同步镜像 **X 与 Z** |
| `DoorAnimationType.java:25,29` | `Math.copySign(abs, (flipped ? -1 : 1) * multiplier)` | 门位移方向随 `flipped` **反号** |
| `DynamicVehicleModel.java:41-62` | `nameToPart.put(blockbenchOutline.getName(), ...)` | 镜像粒度 = **整个顶层 outliner 组** |

刚体绕 Y 轴转 180° 保定向 ⇒ 顶点逐点 `(x,z)→(−x,−z)`，每面的绕向（winding）
需同步反转，UV 跟面走不变。

### 实测事实（ERIDU 车包）

- `eridudanguei.bbmodel` 970 元素；`men2`/`men3`/`win`/`door` 等顶层组
  **几何只建了 +X 半边**：`men2` x∈[19,20]px、`men3` x∈[20,21.52]px、
  `win` 右 24 盒 / 左 0 盒。另一侧完全靠 flipped 副本补齐。
- ⇒ **0.35.2 及以前左半侧车门/车窗/门灯/门地板全部不渲染**。
- 189 个 positionDefinitions 中 143 个含非空 `positionsFlipped`，
  43 个只有 `positionsFlipped`（无正位）。
- 64 个顶层组引用了含翻转位的位定义，**全部是多记录**（同一 `names`
  被 NORMAL 位记录与 FLIPPED 位记录各引用一次）；其中 59 组门参数
  完全一致，6 组不一致的是**不同 properties 文件**的 `condition` 差异
  （`ON_ROUTE_FORWARDS`/`ON_ROUTE_BACKWARDS`，车头/车尾朝向），
  分属不同层模型，不是同一部件的两侧。
- 实测需镜像的顶层组 **17/36**。

### mtr-next 实现

- 新增 `PositionDefinitions4xLoader`：`definition_*.json` → 名字 →
  `PositionGroup{positions, positionsFlipped}`（`flippedOnly()`/`hasNormal()`）。
- `Properties4xLoader.PartInfo` 增加 `positionDefinitionNames`。
- `VehicleResource.ModelLayer` 增加 `positionDefinitionsResource`。
- `BbModelLoader`：`BbBox` 增 `topGroup`/`singleSidedGroup`/`mirrorNeeded`/
  `doorFlipped`；`buildBoxes` 统计各顶层组 X 侧分布；`annotate` **双条件**
  判定镜像；`loadAnnotated` 第三缓存键。
- `BbModelTrainRenderer.drawBoxFaces(..., mirror)`：镜像时 `(x,z)→(−x,−z)`
  且每面 winding 反转；`drawMirrorPair`：本体 `flipped=false`、
  镜像 `flipped=true`（由上游 `copySign` 自动反号）。
- `FormationPreviewRenderer` 同步。

### 与上游的一处有意偏离：对称组跳过镜像

上游对 `end`/`hand` 等**本就左右对称**的组也会画 flipped 副本，落点与
对面正本重叠（Z-fighting / 重复面）。mtr-next 用 `singleSidedGroup`
（该顶层组的盒是否只落在 X 一侧）跳过这些组。

这是**渲染优化取舍，不是语义修正**：重叠副本在视觉上与单份不可区分，
但会浪费 2× 顶点并引入深度冲突。若日后发现某对称组依赖重叠副本
（例如两侧 UV 不同），此判定需回退。

### 验证

- 离线自测 **PASS=216 FAIL=0**（新增 33 条）。
- 真实 ERIDU 车包端到端（`ParserSelfTest.testRealPackMirror`）：
  8 个门/窗组全部判为需镜像；`end`/`end_n`/`hand`/`hand_n` 判为
  双侧组不镜像；`men2` dz=+11 / `men3` dz=−11 与实测一致。
- **边界**：headless 环境无法验证客户端渲染观感——镜像的顶点变换、
  winding、门位移符号均有上游源码逐行对应，且真实车包验证了判定逻辑，
  但"左半侧车门看起来正确"仍需图形客户端确认
  （见 `docs/client-verification-boundary.md`）。

## 五d、已实现（0.35.4 追加）：门时序窗口与闪烁（数据层）

### 触发线索：上游 schema 的权威字段清单

上游 `modelPropertiesPart.json`（MIT 仓库
`Minecraft-Transit-Railway/Minecraft-Transit-Railway`，路径
`buildSrc/src/main/resources/schema/resource/modelPropertiesPart.json`，
原始 2763 B）列出 **25 个字段**。mtr-next 的 `PartInfo` 此前只读 8 个。
新增补上的 6 个：

| 字段 | 上游消费点 | 语义 |
|---|---|---|
| `renderFromOpeningDoorTime` | `ModelPropertiesPart.java:342` | 开门时的渲染窗口下界（ms） |
| `renderUntilOpeningDoorTime` | 同上 | 开门时的渲染窗口上界（ms） |
| `renderFromClosingDoorTime` | `ModelPropertiesPart.java:344` | 关门时的窗口下界（ms） |
| `renderUntilClosingDoorTime` | 同上 | 关门时的窗口上界（ms） |
| `flashOnTime` | `ModelPropertiesPart.java:317` | 闪烁周期亮段（ms） |
| `flashOffTime` | 同上 | 闪烁周期灭段（ms） |

> 此前 `docs/_upstream/modelPropertiesPart.json` 的副本是被 PowerShell
> 对象化后写坏的 139 B 版本，本版用 `Invoke-WebRequest`（而非
> `Invoke-RestMethod`）取原始字节重取，并校验长度与 GitHub tree
> 报告的 size 一致。

### 上游语义

```java
// ModelPropertiesPart.java:342-345
if (opening) {
    shouldRender = renderFromOpeningDoorTime == 0 && renderUntilOpeningDoorTime == 0
        || Utilities.isBetween(Math.abs(doorValue) * Vehicle.DOOR_MOVE_TIME,
                               renderFromOpeningDoorTime, renderUntilOpeningDoorTime);
} else {
    shouldRender = renderFromClosingDoorTime == 0 && renderUntilClosingDoorTime == 0
        || Utilities.isBetween(Math.abs(doorValue) * Vehicle.DOOR_MOVE_TIME,
                               renderFromClosingDoorTime, renderUntilClosingDoorTime);
}

// ModelPropertiesPart.java:317
final boolean flashOn = flashOnTime + flashOffTime == 0
    || (System.currentTimeMillis() % (flashOnTime + flashOffTime)) > flashOffTime;
// 不闪时：y = Integer.MAX_VALUE（移出视野），不是跳过绘制
```

### 关键常量修正：`DOOR_MOVE_TIME = 3200`

**此前一直假定门行程是 500 ms——错了。** 上游定义在
`Transport-Simulation-Core` 仓库（**不在** `Minecraft-Transit-Railway`
仓库里——那个仓库只有 `fabric/`/`forge/`/`buildSrc/`，core 是独立仓库）：

```
Transport-Simulation-Core/src/main/java/org/mtr/core/data/Vehicle.java:59
  public static final int DOOR_MOVE_TIME = 3200;
```

`isBetween` 语义（`core/tool/Utilities.java:61-63`）：
`value >= min(v1,v2) && value <= max(v1,v2)`——**倒序区间同样成立**。

已取快照到 `docs/_upstream/core_Vehicle.java` 与 `core_Utilities.java`。

### ERIDU 实测：这些字段确有非零值（39 个非零条目）

| 部件 | 字段 | 值 |
|---|---|---|
| `doordengguan_n` | `renderFromOpeningDoorTime` / `renderFromClosingDoorTime` | 100 / 100 |
| `doordengshan_n` | `renderFromOpeningDoorTime` | 50 |
| `doordengshan_n` | `flashOnTime` / `flashOffTime` | 500 / 500 |
| `lcdp_dooryincang` | `renderFromOpeningDoorTime` / `renderFromClosingDoorTime` | 1 / 1 |
| `lcdp_doorshanshuo` | `flashOnTime` / `flashOffTime` | 800 / 800 |

语义解读：**部件只在门行程落在 [from, until] 毫秒窗口内才渲染**。
`until = 0` 时窗口是 `[0, from]`（因 `isBetween` 取 min/max）——
即"门刚开始动的一小段"。典型用途：门灯管只在开门最初 100 ms 显示。

### mtr-next 实现（0.35.4，纯数据层）

- `Properties4xLoader.PartInfo` 增 6 个字段 + 两个判定方法：
  `shouldRenderAt(doorValue, opening)`、`flashOn(currentTimeMillis)`。
- `PartInfo.DOOR_MOVE_TIME = 3200.0` 常量（与上游一致）。
- 离线自测 **PASS=243 FAIL=0**（新增 27 条，含 ERIDU 真实取值）。

### 边界（务必读）

**渲染端尚未接入** `shouldRenderAt` / `flashOn`——本次只做数据层。
渲染接入需等另一条进行中的渲染重构（bbmodel → MC 原生 `ModelPart`
树）定型后再做，否则两条渲染路径会打架。

## 五e、0.36.0 发布后复核（2026-10-20 09:42）

### 发布事实

0.35.4 的数据层已被 **0.36.0**（bbmodel → MC 原生 `ModelPart` 树）吸收，
双方渠道均已发布并校验：

| 渠道 | 标识 | 文件大小 | sha512 |
|---|---|---|---|
| Modrinth | 版本 id `gqItNHdg` | 100841 B | `e062c922…7540a65b…` |
| GitHub | release `417915159` / tag `v0.36.0` | 100841 B | 同上（**双渠道一致**） |

GitHub 下载回读与 Modrinth 声明的 sha512 **逐字节相同**。

**已确认 0.36.0 的 jar 内包含 0.35.4 的全部成果**（反编译类常量池校验）：
`Properties4xLoader$PartInfo` 含 `shouldRenderAt`、`flashOn`、
`DOOR_MOVE_TIME`、`renderFromOpeningDoorTime`、`renderUntilOpeningDoorTime`、
`renderFromClosingDoorTime`、`renderUntilClosingDoorTime`、
`flashOnTime`、`flashOffTime`、`positionDefinitionNames`。

**0.36.0 未回退 0.35.3 的能力**：`positionsFlipped` 在新渲染路径
（`BbModelTrainRenderer` L135 / `FormationPreviewRenderer` L100）仍被消费；
`DoorAnimation4x` 仍被两条路径使用。

### 未修复的真实偏差：上游 500 ms 门位移插值层

**上游**（`ModelPropertiesPart.java:348`）：

```java
z = shouldRender ? (float) (partDetails.z
      + (canOpen
         ? vehicle.persistentVehicleData.getInterpolatedDoorValue(
               doorAnimationType, doorZMultiplier, partDetails.flipped,
               doorOverrideValue, opening)
         : 0))
  : Integer.MAX_VALUE;
```

`getInterpolatedDoorValue`（`PersistentVehicleData.java:98-113`）先算曲线值，
再过一层 `DoorMovementInterpolation`（同文件 L164-178）：

```java
private static class DoorMovementInterpolation {
    private boolean opening;
    private final Interpolation interpolation = new Interpolation(500); // 500 ms

    private float setAndGet(double value, boolean opening) {
        interpolation.setValue(value, this.opening != opening); // 方向翻转 → 重启插值
        this.opening = opening;
        return (float) interpolation.getValue();
    }
}
```

`Interpolation`（上游 `org.mtr.mod.resource.Interpolation`，快照
`docs/_upstream/Interpolation.java`）是**线性**混合：

```java
final long difference = System.currentTimeMillis() - startMillis;
if (difference < duration) {
    return (1 - difference / duration) * oldValue
         + difference / duration * latestValue;
} else { oldValue = latestValue; return latestValue; }
```

**mtr-next 现状**（`BbModelTrainRenderer.java:184`）：

```java
anim.getDoorAnimationZ(info.doorZMultiplier, flipped, time, doorsOpen)
```

——**直接用曲线值，没有 500 ms 插值层**。

**影响面**：ERIDU 实测门部件共 42 个（`STANDARD` 18 + `STANDARD_SLOW` 24），
上游对**所有**部件统一走 `getInterpolatedDoorValue`，故**全部受影响**。
视觉表现：门启动瞬间位置不是从曲线值瞬跳，而是 500 ms 线性过渡；
门开↔关方向翻转时同样有过渡。上游这层的作用就是消除曲线值在方向
翻转处的跳变。

**尚未修复的原因**：0.36.0 的渲染路径由另一条会话的重构产出，
插值层需要 per-（部件记录 × 位置实例 × 方向）的有状态对象，
与重构中的 `BbModelTree` 状态管理耦合，需要在同一状态机里实现。

**修复方案（待实施）**：
1. 新增 `DoorMovementInterpolation` 等价类（500 ms 线性，
   方向翻转时 `oldValue = 当前插值值; startMillis = now`）；
2. 上游的 key 是 `doorZMultiplier + "_" + doorAnimationType + "_" + flipped`
   ——即**每个（倍率, 类型, 翻转）组合一个插值器**，
   缓存于 `PersistentVehicleData.doorMovementInterpolations`；
   mtr-next 需在渲染侧维护同样的 per-key 缓存；
3. `getDoorAnimationX`（上游 L347）**不走**插值层，只走 `getInterpolatedDoorValue`
   （即 Z）走——注意 X 与 Z 的差别。

### 另一处待核（低优先级）

`BbModelTree.addElement`（L508-510）算出的 `centerX/Y/Z` **不含元素旋转**：

```java
final float centerX = (x + sizeX / 2.0F) / 16.0F;
```

而上游同名概念的 `Box`（`BlockbenchElement.java:48-58`）会把两个角点
`rotateX/rotateY/rotateZ` 后再取边界。实测 ERIDU：787/970 元素（81%）
带非零旋转，其中 779 个的 center 偏移 > 0.01 格，中位 0.51 格、最大 6.5 格。

**但该字段当前仅被测试断言使用，渲染路径不消费**（渲染由 MC 的
`ModelPart` 姿态系统处理旋转），因此**不是渲染缺陷**，只是元数据
与上游 `Box` 语义不符。修复前应先确认 `center` 的用途。

## 五f、0.36.2 发布与插值层的真实语义（2026-10-20 10:17）

### 发布事实

| 渠道 | 标识 | 大小 | sha512 回读 |
|---|---|---|---|
| Modrinth | 版本 id `SpyfJ7xr` | 103249 B | ✅ 与本地一致 |
| GitHub | release `417932491` / 资产 `658022617` / tag `v0.36.2` | 103249 B | ✅ 与本地一致 |

sha512：`3cb2593a27cb8e8ec5f4ea27d78d5b35d0630b1e939a77e884d83e2dd404d40cbe6505679ce978a168996937c7c1509984d75be0fccfd78a0c49858f8f4cb27e`

tag `v0.36.2` → `a390c6a`（测试提交）。此后 0.36.2 的实现提交被 amend
为 `f8efcc1`（把发布脚本并入），`a390c6a` 仍是其祖先，tag 有效。

验证：`parserSelfTest` PASS=254 FAIL=0；`bbModelTreeTest` PASS=46 FAIL=0；
构建 0 警告。

### 插值层的真实语义：**只在方向翻转时生效**（与直觉相反）

写回归测试时按直觉写的三条期望全错，实测后按上游真实行为更正。
关键在 `Interpolation.setValue`：

```java
public void setValue(double value, boolean startInterpolation) {
    if (startInterpolation && oldValue != value) {   // ← 只在 startInterpolation 为真时才检查
        oldValue = getValue();
        startMillis = System.currentTimeMillis();     // ← startMillis 仅此时更新
    }
    latestValue = value;
}
```

而 `setAndGet` 的 `startInterpolation` 参数是 `this.opening != opening`
——**仅在开关方向翻转时为真**。

推论：

1. **同方向连续变化不产生任何过渡**——`startMillis` 不变，
   `difference` 持续增长，超出 500 ms 后 `getValue()` 直接返回
   `latestValue`。
2. **过渡只在开门↔关门方向翻转的那一刻发生**：翻转时
   `oldValue = getValue()`（当前插值结果），`startMillis = now`，
   于是从当前值用 500 ms 线性走到新曲线值，**不跳变**。
3. **首次调用返回 0**（不是曲线值）：初始 `oldValue = 0`、
   `startMillis = 0`；`setAndGet(v, opening≠初始 opening)` 触发重启，
   `oldValue = getValue() = 0`，同毫秒内 `getValue()` 返回 `oldValue`。
   即插值器从 0 起步。

**这正是上游这层插值的意义**：门动画曲线在方向翻转处（如
`STANDARD_SLOW` 开门↔关门两套分段曲线交替）会跳变，插值层抹平这个跳变。

### mtr-next 与上游的 `opening` 语义差异（尚未修，已记录）

| | 上游 | mtr-next |
|---|---|---|
| `opening` 来源 | `getAdjustedDoorMultiplier(...) > 0`（**门运动方向**，`ModelPropertiesPart.java:338`） | `doorsOpen`（**门是否处于打开状态**） |

两者在"门开↔关翻转"时刻都会翻转，故**插值效果等价**；
但曲线分支选择不完全等价——上游 `STANDARD_SLOW` 的
`!opening` 分支是**关门专用的分段曲线**（`DoorAnimationType.java:142-151`），
mtr-next 传 `doorsOpen` 时，门关闭期间（`time = 0`）两条分支都得 0，
视觉无差；但"门正在关"这一瞬间，若 `doorsOpen` 仍为 true，
mtr-next 会走开门分支而上游走关门分支。

ERIDU 实测门部件 42 个（`STANDARD` 18 + `STANDARD_SLOW` 24），
`STANDARD_SLOW` 的关门分段曲线在 mtr-next 当前模型下未被触发。

**待办**：把 `opening` 改为由门开度的**变化方向**推导
（`doorValue` 相对上一帧上升 = opening，下降 = 关门），
而不是用布尔 `doorsOpen`。这需要 per-列车的上一帧门开度状态。

### 门时序窗口与闪烁仍未接入渲染

0.35.4 已实现 `PartInfo.shouldRenderAt` / `flashOn`，但 0.36.x 的渲染路径
**尚未调用它们**——`BbModelTrainRenderer` 目前无条件渲染所有部件。
上游语义是"部件只在门行程落在 [from, until] 毫秒窗口内才渲染"
（ERIDU 的 `doordengguan_n` from=100、`doordengshan_n` from=50 +
flash 500/500、`lcdp_dooryincang` from=1）。

这需要在渲染循环里拿到每帧的 `doorValue` 与 `opening` 方向后调用，
与上面的 `opening` 语义修复合并处理更合适。

因此 0.35.4 **不发版**：工作区当时含另一会话未完成的渲染重构，
构建产物会混入未验证代码；且纯数据层改动对用户无可见价值。
等渲染重构完成、整体验证通过后一并发布。
