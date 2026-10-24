# MC 1.20.1 `ModelPart$Cube` / `ModelPart$Polygon` 字节码实证

> 目的：闭合 mtr-next 长期开放问题 —— **MC `Cube` 的多边形顺序在 `mirror` 与
> "可见面变体"（`skipMissingFaces`）下是否仍为 DOWN, UP, WEST, NORTH, EAST, SOUTH。**
> 此前只实测过「全面、非镜像」的情形。

**结论：无论 `mirror` 与否、无论跳过哪些可见面，多边形的相对顺序恒为
`DOWN, UP, WEST, NORTH, EAST, SOUTH`。**

---

## 证据来源

| 项 | 值 |
|---|---|
| 目标 jar | `minecraft-merged-1d8e11bf1b-1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2.jar`（mtr-turbo 的 Loom 缓存） |
| 提取物 | `net/minecraft/client/model/geom/ModelPart$Cube.class`（5450 B）、`ModelPart$Polygon.class`（1809 B） |
| 反汇编 | `javap -p -c`（输出全文见同目录 `ModelPart_Cube.javap.txt` / `ModelPart_Polygon.javap.txt`） |

复现命令：

```powershell
$jar = 'F:\vibe coding工程\mtr-turbo\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-1d8e11bf1b\1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2\minecraft-merged-1d8e11bf1b-1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2.jar'
$tmp = "$env:TEMP\mccube"
New-Item $tmp -ItemType Directory -Force | Out-Null
Push-Location $tmp
& 'C:\jdk17\bin\jar.exe' xf $jar 'net/minecraft/client/model/geom/ModelPart$Cube.class' 'net/minecraft/client/model/geom/ModelPart$Polygon.class'
Pop-Location
# PowerShell 里 $ 要转义：
& 'C:\jdk17\bin\javap.exe' -p -c "$tmp\net\minecraft\client\model\geom\ModelPart`$Cube.class"
```

---

## 证据 1 — 六个面的写入顺序

`ModelPart$Cube` 构造器里六次 `ModelPart$Polygon."<init>"` 调用，
每次前面紧跟一个 `getstatic Direction.<X>`：

| 字节码偏移 | Direction | 面 |
|---|---|---|
| 365 / 427 | `Direction.DOWN` | 下 |
| 436 / 498 | `Direction.UP` | 上 |
| 507 / 569 | `Direction.WEST` | 西 |
| 578 / 640 | `Direction.NORTH` | 北 |
| 649 / 711 | `Direction.EAST` | 东 |
| 720 / 779 | `Direction.SOUTH` | 南 |

（每面出现两次是因为 `getstatic` 既用于 `contains` 守卫又用于 `Polygon` 构造。）

⇒ **基础顺序 = DOWN, UP, WEST, NORTH, EAST, SOUTH**，与此前「全面、非镜像」的
UV 实测表（见 `docs/door-animation-audit.md` 与 `tools/probe_uv/ProbeUv.java`）一致。

## 证据 2 — 可见面守卫**不改变**剩余面的相对顺序

数组写入用**独立递增的计数器**，而不是固定下标：

```
660: aload_0
661: getfield  #73   // Field polygons:[Lnet/minecraft/client/model/geom/ModelPart$Polygon;
664: iload  36       // 局部变量 36 = 下一个可用下标
666: iinc   36, 1
669: new     #14     // class ModelPart$Polygon
...
717: aastore         // 存到 polygons[计数器]
```

守卫形如：

```
647: aload  15                  // skipMissingFaces (Set<Direction>)
649: getstatic Direction.EAST
652: invokeinterface Set.contains
657: ifeq  718                  // 不含 EAST => 跳过整个 EAST 分支
```

`iload 36 / iinc 36,1` 是**后置递增**（先取当前值再自增），
所以任何一个面被跳过时，后续面**依次前移**填坑，
但**相对顺序不变**。

⇒ **「可见面变体」不会打乱顺序。**

## 证据 3 — `mirror` 的确切作用（`ModelPart$Polygon` 构造器）

`Cube` 构造器的第 13 个参数（`Z` 布尔）透传给 `Polygon` 构造器的第 8 个参数：

```
Polygon."<init>":([LVertex;FFFFFFZLnet/minecraft/core/Direction;)V
                                                ↑ 这个 Z = mirror
```

`Polygon` 构造器里 `mirror` 的两处作用：

**① 翻转顶点顺序**（`i` ↔ `len-1-i` 交换）：

```
117: iload  8            // mirror
119: ifeq  174           // 不镜像则跳过
122: aload_1             // vertices[]
124: istore 12           // len
138: aload_1
139: iload  13           // i
141: aaload
144: aload_1
145: iload  13
146: aload 12
148: iconst_1
150: isub                // len-1-i
152: iload 13
154: isub                // i
155: aaload
156: aastore             // vertices[i] = old[len-1-i]
157: aload_1
158: iload 12
160: iconst_1
161: isub                // len-1
162: iload 13
164: isub                // i
165: aaload
167: aastore             // vertices[len-1-i] = old[i]
```

**② 法线 X 分量取反**：

```
183: iload  8            // mirror
185: ifeq  200
188: aload_0
189: getfield #48        // normal
192: ldc   #49           // float -1.0f
194: fconst_1
195: fconst_1
196: invokevirtual Vector3f.mul:(FFF)Lorg/joml/Vector3f;
199: pop
```

**③ UV 区域不受 mirror 影响** —— `Vertex.remap(u, v)` 的调用
（偏移 88 / 113）**全部位于 119 的 mirror 分支之前**，
所以 `mirror` 只改顶点绕序与法线，**不改 UV 采样区域**。

> 这与 mtr-next `BbModelTree.addBox` 里 `mirror(!shade || mirrorUv)` 的用法一致：
> `CubeListBuilder.mirror(true)` 翻转**顶点绕序**，不是 UV 区域。

---

## 对 mtr-next 的影响

**无影响，不需要改代码。**

mtr-next 走 MC 原生 `ModelPart.CubeListBuilder.addBox(...)`，
**自己不算 UV**（UV 由 MC 内部按上述固定顺序计算），
所以只要用原生 API 就自动得到正确结果。

`BbModelTreeTest` 的「box-UV 六面逐面对比 MC 实测表」用例因此仍然有效。

## 仍未闭合的部分（**不是结论，是待查**）

- 上游 MTR 4 的 `OptimizedModel.MaterialGroup.addCube` 的 cube 抽取方式
  尚未取源码（Minecraft-Mappings 仓库 `1.20` 分支）——
  当前不影响（走非优化路径）。**这条依然待查，不要当已解决。**
