# 上游二进制证据（2026-10-21 采集）

本目录保存**从上游发行 jar 中直接提取**的 class 文件，作为 mtr-next 复刻上游行为的
**一手证据**。以前这些语义只能靠读上游源码推测；现在可以 `javap` 反汇编逐条核对。

## 来源

| 项 | 值 |
|---|---|
| 上游仓库 | `Minecraft-Transit-Railway/Minecraft-Transit-Railway` |
| 分支 / tag | `4.0.6` |
| 原始 jar | `libs/Minecraft-Mappings-fabric-1.20.1-0.0.1.jar`（767 457 B） |
| 下载 URL | `https://raw.githubusercontent.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway/4.0.6/libs/Minecraft-Mappings-fabric-1.20.1-0.0.1.jar` |
| 提取命令 | `jar xf a.jar org/mtr/mapping/holder/Box.class org/mtr/mapping/holder/Vector3d.class` |

> **注意**：Maven 坐标 `org.mtr:Minecraft-Mappings-common:0.0.1` 在 codemc /
> architectury / maven central 上**均 404**。`Minecraft-Mappings` 那个 GitHub 仓库
> 也**不含** `holder` 包（`rewrite` 分支 1900 条目，`1.20` 等分支各 31 条目）。
> `holder` 包只存在于**版本专属 jar**（`Minecraft-Mappings-<loader>-<mc>-0.0.1.jar`），
> 且这些 jar 只在 MTR 主仓库的 `libs/` 目录里。

## 核实结论（2026-10-21）

### 1. `Box` 的构造与归一化

`org.mtr.mapping.holder.Box(double, double, double, double, double, double)`
的字节码就是一行：

```
new class_238(x1, y1, z1, x2, y2, z2)
```

即上游 `Box` **直接**把 6 个参数交给 MC 的 `AABB`（`class_238`），
**不做任何排序**。

而 MC 1.20.1 的 `AABB` 六参构造器**内部自行归一化**：

```
minX = Math.min(x1, x2);   maxX = Math.max(x1, x2);
minY = Math.min(y1, y2);   maxY = Math.max(y1, y2);
minZ = Math.min(z1, z2);   maxZ = Math.max(z1, z2);
```

**⇒ 上游拿到的 `Box` 始终有序（min ≤ max），`testDoors` 能正常检测门重叠。**

> 这**更正**了 mtr-next 0.36.5 文档里的错误断言
> （「上游 Box 三轴倒序 ⇒ `testDoors` 永不报重叠」）。
> mtr-next 的 `Element.upstreamBox()` 归一化与上游**行为等效**，实现无需改动。

### 2. `Box.intersects`

`Box.intersects(Vector3d, Vector3d)` → `class_238.method_993`，
即 MC 的 `AABB.intersects(Vec3, Vec3)`；`intersects(Box)` → `class_238` 的对应重载。
⇒ 上游门重叠检测走的就是 MC 原生 AABB 语义。

### 3. `Vector3d` 的旋转

`rotateX/rotateY/rotateZ` 分别转发到 MC `Vec3` 的
`method_1037` / `method_1024` / `method_31033`
（MC 1.20.1 里分别叫 `xRot` / `yRot` / `zRot`）。

反编译 MC `Vec3` 得到的数学（**绕原点**的标准右手旋转）：

| 方法 | 公式 |
|---|---|
| `xRot(θ)` | `y' = y·cosθ − z·sinθ`；`z' = y·sinθ + z·cosθ`；`x' = x` |
| `yRot(θ)` | `x' = x·cosθ + z·sinθ`；`z' = z·cosθ − x·sinθ`；`y' = y` |
| `zRot(θ)` | `x' = x·cosθ − y·sinθ`；`y' = x·sinθ + y·cosθ`；`z' = z` |

上游 `BlockbenchElement.java:48-49` 的组合顺序是
`rotateX → rotateY → rotateZ`（**先 X 后 Y 后 Z**），
角度取 `rx=rad(-r0)`、`ry=rad(-r1)`、`rz=rad(+r2)`。

**⇒ mtr-next `Element.rotate()` 与上游逐项一致**，
已由 `ParserSelfTest.testUpstreamBoxRotation()` 固化为可回归断言
（绕 X / Y / Z 各 90° 的盒坐标 + 归一化等效性，共 14 项）。

## 复现命令

```powershell
# 反汇编本目录的 class
javap -p -c 'org\mtr\mapping\holder\Box.class'
javap -p -c 'org\mtr\mapping\holder\Vector3d.class'
```

MC 侧 `AABB` / `Vec3` 的字节码在 mtr-next 的 loom 缓存里：

```
F:\vibe coding工程\mtr-next\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-3dd8ea0749\1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2\minecraft-merged-3dd8ea0749-1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2.jar
```

## 仍未闭合

- `MutableBox`（3.x 时代，`org.mtr.mod.MutableBox`）的 min/max 合并语义已读，
  与本目录的 `Box` 结论一致，但它是**另一个类**，不作为 `Box` 的证据。
- `OptimizedModel.MaterialGroup.addCube` 源码未取（mtr-next 走非优化路径，暂不影响）。
