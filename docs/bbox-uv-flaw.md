# bbmodel→MC 几何映射缺陷审查（0.29 发现，P0 视觉缺陷）

## 问题

`buildCube` 里每个**有 UV 的面**都独立调用 `addFace` → `texOffs().addBox(...)`——
即一张 bbmodel 盒（六面各带独立 UV）会生成 **多达 6 个重叠的完整盒**，
每个盒用自己的 UV offset。后果：

1. **六面 UV 全错**：MC 的 `addBox` 是整盒六面共享一个 texOffs——
   用面 A 的 UV 画整个盒，其他五面的 UV 全错位
2. **六倍顶点**：6 个重叠盒 = 6 倍顶点/面数（热路径浪费）
3. 我们自己审查 5 轮都没抓到它——因为空世界冒烟看不到渲染效果

## 正确方案

bbmodel 的 per-face UV 与 MC 的整盒 texOffs 模型不兼容。正确做法是
**绕开 CubeListBuilder 的整盒语义**，直接用低层顶点（ModelPart 不可行，
但 BbModelTrainRenderer 已有自定义 quad 绘制基础设施——
drawConnectionFace/quad 方法）：

- `BbModelLoader.bake` 返回自建的"盒+六面 UV"数据结构（不做 MC bake）
- `BbModelTrainRenderer.renderCar` 用 `quad(...)` 逐面绘制，
  每面用自己的 bbmodel UV
- 门部件动画改在这套自定义盒上实现（替换 part.z 平移为
  自定义的 z 偏移字段）

## 影响范围

- BbModelLoader 的 CubeListBuilder/MeshDefinition 链全换
- BbModelTrainRenderer.renderCar 改绘制路径
- DoorAnimator 适配
- FormationPreviewRenderer 适配

这是一个 ~2 小时的重构。ERIDU 样本（驾驶面板 bbmodel 有 12 elements、
面 UV 普遍非零）在当前实现下**贴图是错位的**——只是 3D 预览缩放小、
没有实车截图对比而未暴露。

## 行动

立即实施（视觉正确性 P0）。
