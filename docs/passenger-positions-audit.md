# 乘客站位桥评估（0.28 评估）

## 现状

4.0 车包的 definition_*.json（positionDefinitions：乘客站位锚点，含
positions / positionsFlipped 变体）已在 VehicleResource.ModelLayer
解析为名字列表——但 TrainTypeBridge / BbModelTrainRenderer 均未消费。

3.x 乘客系统的工作方式（javap 实证链）：
- TrainClient.updatePlayerRiding → moveSeat(Player, x, y, z)
- 乘客位置由**车型 TrainType 的 riderOffset**（TrainProperties 字段）
  与车厢几何推导，**没有逐站位概念**
- 4.x 的站位锚点语义（多点位+Flipped）在 3.x 无消费端

## 评估结论：不桥接（与 [MTR] 段参数同理的语义边界）

1. **消费端缺失**：3.x 乘客系统是"每车厢单一位"模型——站位锚点的
   "多点分布"无处安放
2. **riderOffset 已接**：TrainTypeBridge 构造 TrainProperties 时
   riderOffset 已填（当前 0，4.0 车包的 legacyRiderOffset 未映射——
   这是唯一可做的小改进，但 ERIDU 样本该字段=0.0，实际无差异）
3. **成本**：桥接需要在 TrainClient 的 ridingPlayer 位置计算里
   hook 站位选择逻辑（私有 updatePlayerRiding 链）——约一天工，
   收益仅"乘客分布站位更真实"（视觉细节）

## 行动

- **legacyRiderOffset 映射**作为唯一 actionable：VehicleResource
  解析时读 legacyRiderOffset，TrainTypeBridge 构造时填入
  riderOffset——5 分钟改动，0.28 顺手做
- 站位锚点的完整桥接记为边界（3.x 无消费端），与 [MTR] 段
  参数同款留档
