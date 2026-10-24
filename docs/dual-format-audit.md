# 双格式并存审查（2026-10-15 深夜）

## 问题

ERIDU-DG1000A 类"双格式混合包"的 `mtr_custom_resources.json` 同时含
vehicles（4.0 段）+ signs/rails/objects/lifts（3.x 段）。三方并存关系：

- YMTR 的 `CustomResources.reload` 读该文件（常量池实证含
  `mtr_custom_resources.json`）——它读哪些段？遇到不认识的 vehicles 段会怎样？
- MTR Next 的 `VehiclePackLoader` 只取 vehicles 段。
- 3.x CustomResources 读到未知段（JsonObject 的多余键）→ Gson 手工解析
  按"读已知键、未知键忽略"的模式（getOrDefault 风格，javap 实证
  `getOrDefault` 私有静态）→ **不会崩**，只解析它认识的部分。

## 结论

双格式并存天然安全：
1. 3.x CustomResources：按键读取，未知段忽略 → rails/signs 等正常生效；
2. MTR Next：只读 vehicles 段 → 4.0 车辆注册；
3. 同一个文件被两个解析器各取所需，零冲突——**这正是 4.0 官方在
   legacy* 字段里体现的"双版本意图"在解析层的体现**。

无需任何代码改动，此为架构验证记录。
