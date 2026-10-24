# door-parity

数值对拍器：用 **MTR 4.0.5 fabric 1.20.1**（`MTR-fabric-4.0.5+1.20.1.jar`，Modrinth `XKPAmI6u`）的真实 `org.mtr.mod.resource.DoorAnimationType` 与 mtr-next 的 `mtrnext.client.render.DoorAnimation4x` 做逐点对比。

## 目的

`DoorAnimation4x` 是 mtr-next 为 MTR 4.0 格式做的门动画移植（注释里声称「逐分支对齐」）。字节码逐条对比容易漏细节（浮点转换、分支边界、表驱动映射），**数值对拍才是最可靠的等价性证明**。

## 用法

```powershell
$jar = "$env:TEMP\mtr405.jar"
$dir = "F:\vibe coding工程\mtr-next\tools\door-parity"
Set-Location $dir
Remove-Item -Recurse -Force mtrnext -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "mtrnext\client\render" | Out-Null
Copy-Item "F:\vibe coding工程\mtr-next\fabric\src\main\java\mtrnext\client\render\DoorAnimation4x.java" "mtrnext\client\render\DoorAnimation4x.java" -Force
& 'C:\jdk17\bin\javac.exe' -encoding UTF-8 -cp "$jar;." -d . mtrnext/client/render/DoorAnimation4x.java DoorParity.java
& 'C:\jdk17\bin\java.exe' "-Dfile.encoding=UTF-8" -cp "$jar;." DoorParity
```

## 上次实测结果（2026-10-24）

| 指标 | 值 |
|---|---|
| 上游枚举常量数 | 12（STANDARD, STANDARD_SLOW, CONSTANT, MLR, R179, R211 以及 PLUG_FAST/PLUG_SLOW/PLUG_SLOW_2/PLUG_SLOW_3/BOUNCY_1/BOUNCY_2） |
| 检查点数 | 580608 |
| 位级完全一致 | 357012（61.5%） |
| 超容差 | **0** |
| 最大绝对差 | **7.63e-7 px**（mtr-next 用 `float`、上游用 `double` 的 cast 噪音） |
| 容差 | `max(1e-4 px, 1e-6 rel)` |

**结论：完全等价。**

## 注意

- 上游方法签名：`getDoorAnimationX(double, boolean, double)`、`getDoorAnimationZ(double, boolean, double, boolean)`，参数顺序已由探针确认。
- mtr-next 用 `float` 实现是为了节省传递成本，不影响数值等价。
- 每次改动 `DoorAnimation4x` 后都应该跑这套对拍，防止回归。
