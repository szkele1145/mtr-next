# [MTR] 段参数消费评估（0.27 评估）

## 现状

BveSoundConfig 已解析 [MTR] 段的：
- `MotorOutputAtCoast = 0.5`（惰行时的电机输出比例）
- `RegenerationLimit = 0`（再生制动限流）
- `BreakerDelay = 0`（断路器延迟）

均未消费——当前 motor 播放固定用档位 1。

## 评估结论：BreakerDelay 接线，另两个记为"数据已备、语义不可达"

### MotorOutputAtCoast / RegenerationLimit
这两个参数的语义是**牵引电机状态机**的输入（惰行时电机输出降到 50%、
再生制动限流）——正确的消费需要知道列车当前的牵引/制动/惰行状态。
TrainClient 的该状态在私有字段 speedCallback（SpeedCallback 对象）
里，不暴露档位。Y 检测速度变化率可以近似"加速中/减速中/惰行"：
- 速度上升 → 牵引档
- 速度下降 → 制动档
- 速度平稳 → 惰行（此时按 MotorOutputAtCoast 降音量）

这可行（速度差分我们已有 tickSpeedEdges 基础），但档位选择本身
（motor0-3 的表列）在 YMTR 侧没有对应的档位信号——查表列固定用 1。
**结论：档位列的选择留给后续需要真实档位接入时再做**（需要 hook
YMTR 的驾驶输入或速度回调），当前记录为已知边界。

### BreakerDelay
语义：发车时断路器闭合延迟（主断路器合闸音的间隔）。我们已有发车
边沿（tickSpeedEdges 的 departure edge）——BcReleaseHigh 在该沿
播放。BreakerDelay 可作为"起音到 motor 音之间的静默期"，但
playSound 是即时的、无调度能力，延迟播放需要 tick 计数器。
**结论：3.x 播放模型无延迟调度，BreakerDelay 记为不可达**（除非
引入调度器，成本高于收益）。

## 行动

- 两个"不可达"参数的注释已在 BveSoundConfig 标注 [0.27 评估]；
- 若社区反馈需要真实档位选择，路径是 SpeedCallback hook（另立项）。
