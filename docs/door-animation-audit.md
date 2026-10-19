# 门动画链路审查

> 2026-10-15 初版；**2026-10-18 依据实测大幅修正**（推翻初版多处判断，见下）。

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

## 四、仍未确认的事项（不得贸然实现）

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

## 五、正确的下一步

1. **先取 MTR 4.x 上游**的 `properties` schema 与门动画实现，确认第 4 节 4 个问题。
   **在上游语义确认前不得改渲染代码**——猜错方向只是把缺陷换个方向继续错。
2. `Properties4xLoader` 增加 `parts[]` 解析（纯数据，可离线测试）：
   `names[]` / `doorXMultiplier` / `doorZMultiplier` / `doorAnimationType` / `condition` / `type`。
3. `BbModelLoader` 解析 `outliner`，为每个 element 记录**祖先分组名**，
   使 `parts[].names` 能按组名匹配到具体盒（当前只读 `element.name`，永远匹配不上）。
4. 确认左右映射后，再实现 `doorSlideLeft` / `doorSlideRight` 双通道插值，
   并按需把偏移从「仅 Z」扩展为 X/Z 双轴。
5. 扩充 `ParserSelfTest` 覆盖 parts[] 解析与组名归属（`docs/client-verification-boundary.md`
   允许的离线回归面：解析层是纯数据逻辑）。

在 1~3 完成前，**不得**仅凭 `element.name` 的 contains 判定就驱动平移。
