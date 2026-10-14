# 门动画链路审查（2026-10-15 深夜续）

## 发现

3.x 的门动画（`DoorAnimationType`）挂在 **ModelSimpleTrainBase**（内置车型
渲染器）上——`CustomResources.reload` 从车包 JSON 读取该字段后传给
`ModelSimpleTrainBase.doorAnimationType`。

## 对 MTR Next 的影响

`BbModelTrainRenderer` 是自定义 TrainRendererBase（非 ModelSimpleTrainBase
体系），3.x 车包的门动画解析/复用不适用——**4.0 车包的门动画语义在
renderCar 的 doorLeftOpen/doorRightOpen 布尔参数里**（基类抽象签名
实证），由车体模型自身的门部件（bbmodel elements）演绎。

0.9 阶段：bbmodel 盒体全静态（无门部件动画）——门开时 4.0 车体不动画。
**0.10 计划**：解析 bbmodel element name 含 "door" 的部件为独立 ModelPart，
renderCar 的 door 参数驱动其平移/旋转（门动画桥）。

## 无需改动记录

架构确认：门动画是渲染器职责（3.x 内置渲染器的方案不迁移），
MTR Next 的 bbmodel 渲染器将在 0.10 以门部件识别实现等价能力。
