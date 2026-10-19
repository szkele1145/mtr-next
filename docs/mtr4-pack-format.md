# MTR 4.0 车包格式实证（ERIDU-DG1000A 样本）

> 2026-10-19 整理。全部字段来自 `F:\vibe coding工程\mtr-turbo\build\eridu-pack\assets\mtr\`
> 的实际 JSON，逐项核验过，不是从上游文档抄的。

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

## 2. vehicles[] 条目（实测 3 辆：M_F 头车 / M_T 中车 / M_B 尾车）

关键字段：`id`、几何（长度/宽度/_bogiePositions…）、`models[]`、
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

`flipTextureV`：实测该包**全部层为 true**。默认值为 true（JSON 缺省时）。
语义仍未客户端验证（开放问题，见 `docs/door-animation-audit.md` §4）。

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
不同代 schema（3.x 用 `door_offset` 枚举）。倍率单位/符号约定待上游确认，
门动画实现阻塞于此（`docs/door-animation-audit.md` §4）。

## 4. bbmodel 本体（eridudanguei.bbmodel，5,091,773 B）

- 970 个 elements；`elements[].name` 分布：cube 690、cude 57、yj 56、8x 45、
  16x 33、qiang 30、bb 28、gueidao 15、fv 8、db 6、lcdred 2——**无 door 字样**
- 整车 bbox：X=[-56.00,46.00] Y=[-110.00,-15.63] Z=[-120.00,126.00]（像素）
- outliner 58 个分组名；门相关 15 个：door / door2 / door3 / door_n / men2 /
  men2_n / men3 / men3_n / doordeng / doordengkai_n / doordengguan_n /
  doordengshan_n / doordiban / doorrmd / mengan
- `outliner[].children` 是**混合数组**：door 组 21 个 children =
  20 个 element uuid 字符串 + 1 个子分组对象 → 解析必须按类型分支
