# MTR 4.0 车包格式实证（ERIDU-DG1000A 样本）

> 整理：2026-10-19 初版；2026-10-20 扩充（补 0.36.x 的门动画 / 闪烁 / 粒度实测）。
> 全部字段来自 `F:\vibe coding工程\mtr-turbo\build\eridu-pack\assets\mtr\`
> 的实际 JSON，逐项核验过，不是从上游文档抄的。
> 本文档是**实证记录**，不是上游规范。上游对应源码见 `docs/_upstream/`。

## 1. 三级结构

```
assets/<ns>/
├── mtr_custom_resources.json     ← 入口：vehicles[] 段（本 mod 只读此段）
├── <vehicle>.bbmodel             ← BlockBench 模型（像素坐标，Y 向下）
├── properties_*.json             ← 每层渲染属性（parts[]/gangway/barrier）
├── definition_*.json             ← positionDefinitions（乘客站位锚点）
└── *.png                         ← 各层贴图
```

- `mtr_custom_resources.json` 可为**双格式混合**：同时含 vehicles（4.0 段）+
  signs/rails/objects/lifts（3.x 段）→ YMTR 3.x 按键读取、未知段忽略，
  本 mod 只取 vehicles，互不冲突（`docs/dual-format-audit.md`）。
- **格式不接管**：仅当 `mtr_custom_resources.json` 存在且含非空 `vehicles` 段时
  才按 4.0 车包解析；否则交回 YMTR 的 `CustomResources` 原样处理（MTR Next 零侵入）。

## 2. vehicles[] 条目（实测 3 辆：M_F 头车 / M_T 中车 / M_B 尾车）

关键字段：`id`、几何（长度/宽度/`_bogiePositions`…）、`models[]`、
`bogie1Models[]` / `bogie2Models[]`、`legacy*` 字段（双版本意图）。

### models[] 是"叠放图层"，不是变体互斥

实测 M_F 头车 **5 层**：

| # | modelResource | textureResource | modelPropertiesResource | flipTextureV |
|---|---|---|---|---|
| 0 | **（空串）** | （空） | mtr:properties_db4c1ea7.json | true |
| 1 | mtr:eridudanguei.bbmodel | mtr:cheche1xa.png | mtr:properties_1fa88c30.json | true |
| 2 | mtr:dangueilcdxa.bbmodel | mtr:chechelcdxa.png | mtr:properties_daf3f17a.json | true |
| 3 | mtr:eridudgjiashimianban.bbmodel | mtr:mianbanlcd.png | mtr:properties_2a694095.json | true |
| 4 | mtr:gaofengg.bbmodel | mtr:gfgg.png | mtr:properties_e08b4f08.json | true |

**第 0 层是 props-only 层**：modelResource 空串，仅通过 modelPropertiesResource
承载整车 gangway/barrier。任何"遍历 models[] 渲染"的代码必须容忍空 modelResource
（`BbModelLoader.load` 已做空串短路）。

> **坑（0.36.1 已修）**：`Properties4xLoader.load()` 早期版本用
> `ConcurrentHashMap.computeIfAbsent`，对 props-only 层传 null key 会 **NPE**。
> 现改为先做 null/empty 短路。

`flipTextureV`：实测该包**全部层为 true**。默认值为 true（JSON 缺省时）。
**已确证：对 bbmodel 无效** —— 上游 `StoredModelResourceBase.load` L23 判定
`.bbmodel` 后走 `BlockbenchModel`→`DynamicVehicleModel` 分支（L28-31），
**从不消费 `flipTextureV`**；该字段只在 OBJ 分支（L42）被用。不实现是正确的。

## 3. properties_*.json 顶层键（实测全集）

```
parts[], modelYOffset,
gangwayInnerSideResource / gangwayInnerTopResource / gangwayInnerBottomResource,
gangwayOuterSideResource / gangwayOuterTopResource / gangwayOuterBottomResource,
gangwayWidth, gangwayHeight, gangwayYOffset, gangwayZOffset,
barrierInnerSideResource / barrierInnerTopResource / barrierInnerBottomResource,
barrierOuterSideResource / barrierOuterTopResource / barrierOuterBottomResource,
barrierWidth, barrierHeight, barrierYOffset, barrierZOffset
```

gangway/barrier 是**程序化几何**（宽高 + 偏移 + 六向贴图），不是 bbmodel 内容。

### parts[] 条目键（实测）

| 键 | 实测取值 |
|---|---|
| `names[]` | 匹配 **outliner 分组名**（65 个引用点名 35 个命中 eridudanguei 组名，其余属同车其他层模型） |
| `positionDefinitions[]` | definition_*.json 的锚点 uuid |
| `condition` | NORMAL / DOORS_CLOSED / DOORS_OPENED / AT_DEPOT / ON_ROUTE_FORWARDS / ON_ROUTE_BACKWARDS |
| `renderStage` | EXTERIOR / INTERIOR / INTERIOR_TRANSLUCENT / LIGHT / ALWAYS_ON_LIGHT |
| `type` | NORMAL / DOORWAY / FLOOR / DISPLAY |
| `doorXMultiplier` | 0.0 / 1.0 |
| `doorZMultiplier` | **men2=+11.0, men3=−11.0**（左右门符号相反）、doordengshan_n=0.01 |
| `doorAnimationType` | STANDARD / STANDARD_SLOW（eridu 样本） |
| `display*` 系列 | DISPLAY 类型的屏幕渲染参数 |

`doorXMultiplier`/`doorZMultiplier` **不在 YMTR 3.6.4.2 源码中**——4.0 与 3.x 是
不同代 schema（3.x 用 `door_offset` 枚举）。

> **0.36.x 更新**：倍率→位移的换算与符号约定**已由上游源码确证**
> （`docs/door-animation-audit.md`），门动画不再阻塞；
> `doorAnimationType` 的完整枚举见 `docs/_upstream/DoorAnimationType.java`（12 种）。

### 3.1 门部件实测统计（14 个 properties 文件 / 189 条部件记录）

- **42 个部件带非零门倍率**：`STANDARD` 18 个 + `STANDARD_SLOW` 24 个。
- `men2` 的 `doorZMultiplier = +11.0`、`men3 = −11.0`（左右门符号相反）。
- `parts[].names` 匹配的是 bbmodel 的 **outliner 分组名**，
  **不是** `element.name` —— 实包的 `element.name` 全是 `cube`/`cude`，
  门信息根本不在那里。

### 3.2 门时序窗口实测

| 部件 | from | until | flashOn | flashOff |
|---|---|---|---|---|
| `doordengguan_n` | 100 | 0 | 0 | 0 |
| `doordengshan_n` | 50 | 0 | 500 | 500 |
| `lcdp_dooryincang` | 1 | 0 | 0 | 0 |

### 3.3 闪烁配置实测

**189 条部件记录中仅 6 条带非零 flash 配置，且每个 properties 文件只有 1 条**：

| 文件 | 部件 | on | off | 周期 |
|---|---|---|---|---|
| `properties_1fa88c30.json` | `doordengshan_n` | 500 | 500 | 1000 ms |
| `properties_3a294190.json` | `doordengshan_n` | 500 | 500 | 1000 ms |
| `properties_86c70718.json` | `doordengshan_n` | 500 | 500 | 1000 ms |
| `properties_181bc46e.json` | `lcdp_doorshanshuo` | 800 | 800 | 1600 ms |
| `properties_77d26273.json` | `lcdp_doorshanshuo` | 800 | 800 | 1600 ms |
| `properties_daf3f17a.json` | `lcdp_doorshanshuo` | 800 | 800 | 1600 ms |

> 复现命令：`node tools/verify_flash_semantics.js`
> （早期版本 `tools/verify_flash_semantics.py` 同理，但需 python 可用。）

**上游语义**：flash 是 **per-part**（`ModelPropertiesPartWrapper.java:80-105`
每条 wrapper 传自己的配置给新建的 `ModelPropertiesPart` 实例），
不是整层共享 —— 详见 `docs/door-animation-audit.md` §五g。

### 3.4 positionDefinitions 实测

样本 `properties_1fa88c30.json` 的 39 条部件**全部**有 `positionDefinitions`，
且这些 position definition 的 x/y/z **全为 0**。

**⚠️ 粒度陷阱**：`men2` / `men3` 各出现**两条**部件记录
（`men2` z=+11 一条、`men3` z=−11 一条，且各重复两次）。
⇒ 渲染粒度是「**部件记录 × 位置实例**」，**按组名折叠会丢记录**。
见 `docs/bbmodel-native-construction.md`。

## 4. bbmodel 本体（eridudanguei.bbmodel，5,091,773 B）

复现命令：`node tools/probe_bbmodel_outliner.js`

- 970 个 elements；`elements[].name` 分布：cube 690、cude 57、yj 56、8x 45、
  16x 33、qiang 30、bb 28、gueidao 15、fv 8、db 6、lcdred 2——**无 door 字样**
- 整车 bbox：X=[-56.00,46.00] Y=[-110.00,-15.63] Z=[-120.00,126.00]（像素）
- 贴图 512×512
- **outliner 结构（实测）**：
  - **顶层组 36 个**；
  - **全部分组名（去重）58 个**；
  - `outliner[].children` 是**混合数组**：**970 个字符串**（element uuid）
    + **22 个对象**（子组对象，键为
    `name/origin/color/uuid/export/mirror_uv/isOpen/locked/visibility/autouv/selected/children`）
    → 解析必须按类型分支。
  - 门相关分组名 **15 个**：door / door2 / door3 / door_n / men2 / men2_n /
    men3 / men3_n / doordeng / doordengkai_n / doordengguan_n / doordengshan_n /
    doordiban / doorrmd / mengan
- **镜像相关（实测）**：
  - **309 / 970** 个元素带 `mirror_uv=true`；
  - **13 个**顶层组（含嵌套子树）内含 `mirror_uv=true` 的元素：
    `1` / `end` / `end_n` / `enddeng` / `hand` / `hand_n` / `chedeng_qj` /
    `chedeng_ht` / `endboli` / `handdeng` / `gan_tc` / `gan_qj` / `gan_ht`；
  - 顶层组**自身**的 `mirror_uv` 标记全为 false（0 个）。

> **口径说明**：36 是**顶层** outliner 组数，58 是**全部**分组名（去重，含嵌套子组）。
> 两者不矛盾。上游 `DynamicVehicleModel` 只为**顶层** outliner 组建锚点
> （`nameToPart.put(groupName, anchor)`），嵌套组作为链上的旋转/位移节点。
> 注意 `mirror_uv` 判据是**元素级**的，**不是组级**——上游
> `BlockbenchElement.setModelPart` 用 `addCuboid(..., !shade || mirror_uv)`，
> 即由**元素**的 `mirror_uv` 决定该 cube 是否镜像。

## 5. 引用本文档的代码位置

| 文件 | 行 | 引用内容 |
|---|---|---|
| `pack/VehiclePackLoader.java` | 24 | 三级结构总览 |
| `pack/VehicleResource.java` | 19 | `vehicles[]` 字段实证 |
| `client/render/Properties4xLoader.java` | 109 | 42 个带门倍率部件 |

## 6. 未解项（**不要当成已知事实**）

- `doorOverrideValue`（上游开门道调试覆盖）—— YMTR 宿主无对应输入。
- `openDoorways` 的逐部件 Box 匹配、`canOpen` 判定 —— 同上，属宿主差异。
- 上游 `OptimizedModel` 优化渲染路径未复刻；上游 bbmodel 走的
  就是**非优化** `ModelPart.render`，与 mtr-next 现状一致。
- `flipTextureV` 对 bbmodel **无效**（上游 bbmodel 分支不消费它）——已确证。
- MC `Cube` 多边形顺序 —— **已闭合（2026-10-21）**：
  字节码实证（MC 1.20.1 `ModelPart$Cube` / `ModelPart$Polygon`）确证
  **无论 `mirror` 与否、无论跳过哪些可见面，相对顺序恒为
  DOWN, UP, WEST, NORTH, EAST, SOUTH**；
  `mirror` 只翻转**顶点绕序**与**法线 X 分量**，**不改 UV 采样区域**。
  证据：`docs/_upstream/binary-evidence/mc-cube-1.20.1/README.md`。
  （mtr-next 走原生 `addBox` 不用自己算 UV ⇒ 渲染行为无变化，**不需要改代码**。）
