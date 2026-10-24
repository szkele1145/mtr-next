# 第三轮代码审查：文件规模与职责审计（2026-10-15 清晨）

## 审计结果

全部 27 个 java 文件按行数排列：
- 最大 **BveTrainSound 243 行 / FormationScreen 235 行**——均低于
  通常 400 行的拆分阈值；
- **无上帝类**：每个文件单一职责（BveTrainSound=BVE 播放、
  FormationScreen=编辑器 UI、BbModelLoader=几何解析…）；
- 层次清晰：pack（解析）/ formation（数据）/ screen（UI）/
  client.render（渲染）/ sound（声音）/ mixin（挂钩）/ util（工具）。

## 结论

**无需拆分**——深夜 0.12-0.15 的编辑器功能（分页/详情条/Flip All）
虽然都进了 FormationScreen，但它们全是“列表渲染+按钮回调”的同质职责，
拆分反而会制造跨文件的状态传递。当前 235 行在 MC Screen 类里属于
标准规模（对照：YMTR 自己的 SidingScreen 反编译可见上百字段+方法）。

BveTrainSound 243 行的十段播放职责同理——“段位播放”是同一抽象的
十个实例，每段一个 if 块，拆分无收益。

## 审计方法留档

行数排序 + 职责标注——今后每 5 个版本做一次，防止规模悄悄膨胀。
