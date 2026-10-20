# MTR 4.x 车包格式实测（ERIDU-DG1000A 样本）

> 写入：2026-10-20。本文档是**实证记录**，不是上游规范。
> 样本来源：`mtr-turbo/build/eridu-pack/assets/mtr/`。
> 上游对应源码见 `docs/_upstream/`。

---

## 1. 三级结构

4.0 车包由三个文件层级组成（`VehiclePackLoader` 的解析入口）：

| 层级 | 文件 | 内容 |
|---|---|---|
| ① 车定义 | `mtr_custom_resources.json` → `vehicles[]` | 车 id / 几何 / 转向架 / 连接面 / `models[]` 多模型层 / `legacy*` 字段 |
| ② 连接面渲染属性 | `properties_*.json` | `parts[]` —— 每个部件的名称、门倍率、时序窗口、闪烁 |
| ③ 乘客站位锚点 | `definition_*.json` | `positionDefinitions` —— 车厢内的位置实例 |

**入口路径固定**：`assets/<ns>/mtr_custom_resources.json`。

**格式不接管**：仅当 `mtr_custom_resources.json` 存在且含非空 `vehicles` 段时
才按 4.0 车包解析；否则交回 YMTR 的 `CustomResources` 原样处理（MTR Next 零侵入）。

---

## 2. `vehicles[]` 的 `models[]` 多模型层

每辆车可有多层模型（实测样本的头车厢有 **5 层**）：

| 层 | 特征 |
|---|---|
| props-only | 只有 `propertiesResource`，**无** `modelResource`（仅承载 gangway/barrier 参数，不产出几何） |
| body | 主体几何 |
| LCD | 显示屏 |
| dashboard | 司机室 |
| roof | 车顶 |

**坑**：props-only 层的 `propertiesResource` 非空而 `modelResource` 为空。
`Properties4xLoader.load()` 早期版本用 `ConcurrentHashMap.computeIfAbsent`，
传 null key 会 **NPE**（0.36.1 修复：先做 null/empty 短路）。

---

## 3. `properties_*.json` 的 `parts[]`（实测 14 个文件 / 189 条部件记录）

### 3.1 字段

```json
{
  "names": ["men2"],
  "positionDefinitions": ["<hash>"],
  "condition": "NORMAL",
  "renderStage": "INTERIOR" | "EXTERIOR",
  "type": "NORMAL",
  "doorXMultiplier": 0.0,
  "doorZMultiplier": 11.0,
  "doorAnimationType": "STANDARD_SLOW",
  "renderFromOpeningDoorTime": 0,
  "renderUntilOpeningDoorTime": 0,
  "renderFromClosingDoorTime": 0,
  "renderUntilClosingDoorTime": 0,
  "flashOffTime": 0,
  "flashOnTime": 0
}
```

### 3.2 门部件实测统计

- **42 个部件带非零门倍率**：`STANDARD` 18 个 + `STANDARD_SLOW` 24 个。
- `men2` 的 `doorZMultiplier = +11.0`、`men3 = −11.0`（左右门符号相反）。
- `parts[].names` 匹配的是 bbmodel 的 **outliner 分组名**，
  **不是** `element.name` —— 实包的 `element.name` 全是 `cube`/`cude`，
  门信息根本不在那里。

### 3.3 门时序窗口实测

| 部件 | from | until | flashOn | flashOff |
|---|---|---|---|---|
| `doordengguan_n` | 100 | 0 | 0 | 0 |
| `doordengshan_n` | 50 | 0 | 500 | 500 |
| `lcdp_dooryincang` | 1 | 0 | 0 | 0 |

### 3.4 闪烁配置实测

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

### 3.5 `positionDefinitions` 全部指向 `positionDefinition` 记录

样本 `properties_1fa88c30.json` 的 39 条部件**全部**有 `positionDefinitions`，
且这些 position definition 的 x/y/z **全为 0**。

**⚠️ 粒度陷阱**：`men2` / `men3` 各出现**两条**部件记录
（`men2` z=+11 一条、`men3` z=−11 一条，且各重复两次）。
⇒ 渲染粒度是「**部件记录 × 位置实例**」，**按组名折叠会丢记录**。
见 `docs/bbmodel-native-construction.md`。

---

## 4. bbmodel 侧实测（`eridudanguei.bbmodel`）

| 项 | 值 |
|---|---|
| 元素数 | 970 |
| 顶层 outliner 组数 | 36 |
| 贴图 | 512×512 |
| 需要镜像的顶层组 | 17 / 36 |

**`element.name` 不可用于业务匹配**（全是 `cube`/`cude`）——
业务标识一律用**顶层 outliner 组名**。

---

## 5. 引用本文档的代码位置

| 文件 | 行 | 引用内容 |
|---|---|---|
| `pack/VehiclePackLoader.java` | 24 | 三级结构总览 |
| `pack/VehicleResource.java` | 19 | `vehicles[]` 字段实证 |
| `client/render/Properties4xLoader.java` | 109 | 42 个带门倍率部件 |

---

## 6. 未解项（**不要当成已知事实**）

- `doorOverrideValue`（上游开门道调试覆盖）—— YMTR 宿主无对应输入。
- `openDoorways` 的逐部件 Box 匹配、`canOpen` 判定 —— 同上，属宿主差异。
- 上游 `OptimizedModel` 优化渲染路径未复刻；上游 bbmodel 走的
  就是**非优化** `ModelPart.render`，与 mtr-next 现状一致。
- `flipTextureV` 对 bbmodel **无效**（上游 bbmodel 分支不消费它）。
- MC `Cube` 多边形顺序仅在「全六面 + 非 mirror」情形下实测确认；
  mirror / 可见面变体未实证。
