# 0.32 轨道几何类型无关性 —— 字节码实证

## 结论

`RailPreviewGeometry.sampleCurve` 传入任意 `RailType` 都不影响采样结果。
此前代码里 `RailType.values()[0]` 旁的注释"曲线几何同源"只是断言，本版本用
`javap -p -c -cp <ymtr-jar> mtr.data.Rail` 把它升级为实证。

## 证据

对 YMTR 3.6.4.2 发布 jar 中的 `mtr/data/Rail.class` 反汇编：

### 1. `getPosition(double)` 只读纯几何字段

指令序列中出现的字段读取（`getfield`）：

- `tEnd1`, `tStart1`, `tEnd2`, `tStart2`
- `h1`, `k1`, `r1`, `reverseT1`, `isStraight1`
- `h2`, `k2`

调用序列：`Math.abs` → `MathHelper.method_15350`（clamp）→ 私有
`getPositionY(double)` → 静态 `getPositionXZ(DDDDDZ)` →
`class_243.method_1031(DDD)`（Vec3 构造）。

**全程没有 `getfield railType`，没有 `railSlopeStyle`，没有 `speedLimit`。**

### 2. 六参构造器对 RailType 仅一次 putfield

```
18: putfield #135 // Field railType:Lmtr/data/RailType;
```

即纯存储：没有 RailType 常量的 `getstatic`，没有 `RailSlopeStyle` 引用，
没有按类型/模式分支的跳转。

## 推论

贝塞尔几何与 `RailType` / `TransportMode` / `railSlopeStyle` /
`speedLimit` 全部无关。预览用任何轨道类型构造都得到与正式轨道逐点一致的
采样曲线。

## 顺带修复（0.32）

- `RailType.values()[0]` 从每帧调用改为 `static final` 缓存
  （`values()` 每次克隆 16 元素数组，属于无谓的逐帧分配）。
- 删除 `sampleCurve(Minecraft mc, …)` 中从未使用的 `mc` 参数。
- `sourcePackId()` 方法经 `mappings.tiny` 反查确认存在：
  `()Ljava/lang/String;` → intermediary `method_14480`，类为
  `net/minecraft/class_3298`（named: `net.minecraft.server.packs.resources.Resource`）。
