# 四方共存实测（2026-10-15）：YMTR + MTR Turbo + MTR Next + ANTE + ERIDU 双格式车包

## 实测配置

- mods：`mtr-turbo-1.1.4` + `mtr-next-0.26.0` + `MTR-ANTE-1.1.1-continue.2`
- resourcepacks：`eridu.zip`（ERIDU-DG1000A——4.0 vehicles 段 + ANTE rail.json 混合包）

## 结果

`Done (9.464s)` 全绿：
- MTR-Turbo 检测到 ANTE → **U5 自动让位**（日志明示"U1-U4 remain fully active"）
- MTR-Next 正常初始化（车包解析在客户端资源重载时，服务端冒烟验证加载无冲突）
- 三 mod 的 RailwayData/RenderTrains/SidingScreen 挂钩全部共存零冲突

## 车包分工终审

| 资源 | 路径 | 解析者 |
|---|---|---|
| vehicles 段（4.0 格式） | assets/mtr/mtr_custom_resources.json | **MTR Next**（vehicles 段特征） |
| rail.json（ANTE 轨道样式） | assets/mtrsteamloco/rails/ | **ANTE**（自有路径，零交集） |
| 其余 3.x 段（signs 等） | 同主文件 | **YMTR**（按键读取，未知段忽略） |

**一个车包，三个解析器，各取所需**——4.0 官方 legacy* 字段的双版本意图
在混合包场景完全落地。
