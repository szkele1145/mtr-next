# Flange 曲线检测方案（0.18 评估）

## 0.17 的限制

Flange（轨侧摩擦音）当前用">30km/h 简化阈值"触发——直线高速也会响，
不真实。BVE 语义：仅在**曲线上**播。

## 0.18 评估结论：维持简化阈值，原因如下

1. **TrainClient 无曲线状态字段**（javap 实证：speed/isRemoved/doorValue/
   thisRoute/nextStation... 无 curve/turning 字段）；
2. **曲率的真实来源**是 Rail 几何（getYaw 差值 = 转向角），但 TrainClient
   持有的 rail 引用在 rideIndex/path 内部（PathData 级），从 SoundBase
   侧取需要穿透两层私有结构；
3. **替代信号**：rotationY 的帧间变化率（renderCar 的 rotationY 每帧变
   = 在转）——但 TrainSoundBase 的播放回调不在渲染帧（tick 驱动），
   拿不到稳定 rotationY 序列；
4. **成本收益**：为一次性音效穿透 PathData 私有链 + 跨帧采样 ≈ 半天工，
   而简化阈值在低速（站台区）不响、高速持续响的行为已有 80% 语义近似。

## 行动

- 0.17.0 的注释已记录此限制（"0.18 评估"）→ 本文档结案：**维持简化**；
- 若社区反馈曲线音在直线上持续响不可接受，再按方案 2/3 实施
  （Rail.getYaw 差值 或 renderCar rotationY 帧间差）；
- Flange 音量已压到 0.35（低优先级环境音），实际干扰可忽略。
