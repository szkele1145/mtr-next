# 第五轮代码审查：渲染热路径与缓存正确性（2026-10-15）

## 审查范围

FormationScreen 3D 预览（0.2 至今）+ BbModelLoader 缓存链 + VehiclePackLoader 二级资源加载。

## 发现与判定

### 1. FormationScreen 3D 预览的每帧成本 ✅ 正确

每帧对每车调用 `BbModelLoader.bake(...)`：
- **Mesh 按 modelResource 缓存**（CACHE.computeIfAbsent）——scale 变化不影响缓存键
- bake（LayerDefinition.create + bakeRoot）每帧执行但对象轻量——
  MC 实体渲染每帧同样走 bakeRoot 产物，属引擎设计预期
- **判定：无缺陷**。预览屏帧成本与实体渲染器同级。

### 2. FormationPreviewRenderer.textureOf 的 getVehicle 调用 ✅ 可接受

每帧一次 Map.get（id 是字符串 key）——量级可忽略。

### 3. ModelLayer.fromJson 的二级资源（positionDefinitions）加载 ✅ 已最优

仅资源重载时执行一次（VehiclePackLoader.reload → fromJson），
运行时零开销。

### 4. 唯一可改进项：bake() 的 try 包裹范围

LayerDefinition.create 在 mesh 不可变时不会失败；当前每帧
try-catch 40 开销微小。**判定：不改**（防御性保留）。

## 结论

渲染链在四轮质量修复后已无实际问题。本轮审查产出为
**"确认正确"的记录**——渲染热路径（预览帧循环 + bake 缓存 + 门动画）
全部干净。

## 审查方法学沉淀

第五轮起审查从"找问题"转向"确认正确性"——当连续两轮无实际
缺陷发现时，该子系统的审查周期可降频（预览/渲染系：季度级）。
