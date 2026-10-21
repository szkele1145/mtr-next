# 上游 `OptimizedModel$MaterialGroup.addCube` 字节码实证

> 目的：闭合 mtr-next 长期开放问题 ——
> **上游 `OptimizedModel.MaterialGroup.addCube` 的 cube 抽取方式**。
> 此前只知道「优化渲染路径走 `MaterialGroup.addCube` 抽取 cube」，
> 但**从未取得该类源码**（Maven 坐标 `org.mtr:Minecraft-Mappings-common:0.0.1` 全 404）。
> 本文件用**上游发行 jar**反汇编确证其真实行为。

## ★ 结论（**纠正了一个此前的错误理解**）

**`addCube` 并不抽取 cube 几何。** 它把整个 `ModelPart` 挂到一个
**捕获顶点消费者**（`CapturingVertexConsumer`）上，**照常走 `ModelPart.render`**。

所谓「优化渲染」= **顶点批次合并**（把多个 ModelPart 的顶点写进同一个
`RawMesh` / `RawModel`，减少 draw call），**不是**替换渲染路径、
**不是**改变几何、**不是**改变 UV 采样。

⇒ **mtr-next 不复刻优化渲染路径，在渲染语义上是等价的**
（只是少了批次合并的性能优化）。这比之前「走的是抽取 cube 的路径、
可能行为不同」的说法**乐观得多**。

## 证据来源

| 项 | 值 |
|---|---|
| 上游 jar | `https://raw.githubusercontent.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway/4.0.6/libs/Minecraft-Mappings-fabric-1.20.1-0.0.1.jar`（767 457 B） |
| 提取物 | `org/mtr/mapping/mapper/OptimizedModel$MaterialGroup.class`、`OptimizedModel.class`、`ModelPartExtension.class`、`org/mtr/mapping/render/vertex/CapturingVertexConsumer.class` |
| 反汇编 | `javap -p -c`（全文见同目录 `MaterialGroup.javap.txt`） |

复现命令：

```powershell
$out = "$env:TEMP\mtr-upstream-src"
New-Item $out -ItemType Directory -Force | Out-Null
Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway/4.0.6/libs/Minecraft-Mappings-fabric-1.20.1-0.0.1.jar' -OutFile "$out\mappings.jar" -UseBasicParsing
Push-Location $out
& 'C:\jdk17\bin\jar.exe' xf mappings.jar 'org/mtr/mapping/mapper/OptimizedModel$MaterialGroup.class'
Pop-Location
& 'C:\jdk17\bin\javap.exe' -p -c "$out\org\mtr\mapping\mapper\OptimizedModel`$MaterialGroup.class"
```

> ⚠️ PowerShell 里路径中的 `$` 需转义（`` `$MaterialGroup ``）。

---

## 证据 1 — `addCube` 的签名与行为

```java
public void addCube(ModelPartExtension, double, double, double, boolean, int);
```

字节码：

```
 0: aload_1
 1: getfield  #58   // Field ModelPartExtension.modelPart : Lnet/minecraft/class_630;
 4: ifnull        32      // modelPart == null ⇒ 直接 return（不添加 consumer）
 7: aload_0
 8: getfield  #34   // Field modelPartConsumers : Ljava/util/List;
11: dload_2        // x
12: dload  4        // y
14: dload  6        // z
16: iload  8        // flipped
18: aload_1        // modelPartExtension
19: iload  9        // light
21: invokedynamic #78, 0   // 捕获 (x,y,z,flipped,ext,light) 的 lambda
26: invokeinterface List.add
31: pop
32: return
```

要点：
1. **`modelPart == null` ⇒ 直接返回**（该部件不产生几何）；
2. 每次调用**只往 list 里塞一个 consumer**，**不遍历子节点、不复制几何数据**。

## 证据 2 — consumer 实际做的事（`lambda$addCube$0`）

```java
private static void lambda$addCube$0(double x, double y, double z, boolean flipped,
                                      ModelPartExtension ext, int light,
                                      CapturingVertexConsumer vc);
```

字节码：

```
 0: new  class_4587              // new Pose()
 7: astore 10
 9: aload 10
11: dload_0; dload_2; dload_4
15: invokevirtual class_4587.method_22904:(DDD)V   // pose.translate(x, y, z)
18: iload 6
20: ifeq 38                     // flipped ?
23: aload 10
25: getstatic class_7833.field_40716              // Axis.YP
28: ldc  180.0f
30: invokeinterface Quaternionf.rotationDegrees:(F)Lorg/joml/Quaternionf;
35: invokevirtual class_4587.method_22907:(Lorg/joml/Quaternionf;)V   // pose.mulPose(Axis.YP.rotationDegrees(180))
38: aload 7
40: getfield ModelPartExtension.modelPart : class_630
43: aload 10                    // pose
45: aload 9                     // light
47: iload 8                     // ??? —— 实际是 overlay
49: invokestatic OverlayTexture.getDefaultUvMapped:()I
52: invokevirtual class_630.method_22698:(Lnet/minecraft/class_4587;Lnet/minecraft/class_4588;II)V
                                       // modelPart.render(pose, vc, light, overlay)
55: return
```

⇒ 消费者做的事 = **「translate →（flipped 时）绕 Y 轴转 180° → `modelPart.render(...)`」**。

**这与 mtr-next `BbModelTrainRenderer.drawInstance` 的做法完全一致**：

| 步骤 | 上游 `addCube` | mtr-next `drawInstance` |
|---|---|---|
| 位移 | `pose.translate(x, y, z)` | `matrices.translate(ox, oy, oz)` |
| 翻转 | `pose.mulPose(Axis.YP.rotationDegrees(180f))` | `matrices.mulPose(Axis.YP.rotation(Math.PI))` |
| 绘制 | `modelPart.render(pose, vc, light, overlay)` | `anchor.render(matrices, vc, 0xF000F0, OverlayTexture.NO_OVERLAY)` |

## 证据 3 — `CapturingVertexConsumer` 是 MC `VertexConsumer` 的实现

```
public final class org.mtr.mapping.render.vertex.CapturingVertexConsumer
        implements net.minecraft.class_4588 {          // ← MC 的 VertexConsumer
    private RawMesh buildingMesh;
    private Vertex buildingVertex;
    public final RawModel rawModel;
    ...
    public class_4588 method_22912(double, double, double);   // vertex(x,y,z)
    public class_4588 method_1336(int, int, int, int);         // color
    public class_4588 method_22913(float, float);               // uv
    ...
}
```

它把 MC 的顶点写入调用**重定向**到自己的 `RawMesh` / `RawModel`，
**接口语义完全一致** ⇒ 用它渲染出来的图像与用普通 `VertexConsumer` **完全相同**，
只是顶点数据被攒起来批量提交。

## 证据 4 — `ModelPartExtension` 的渲染入口

```
public void render(GraphicsHolder, float, float, float, int, int);       // 无旋转
public void render(GraphicsHolder, float, float, float, float, int, int); // 带 rotationY
```

第二个重载多一个 `float`（rotationY），对应上游 `ModelPropertiesPart.renderNormal` 里
`partDetails.flipped ? (float) Math.PI : 0` 那个参数。

---

## 对 mtr-next 的影响

1. **渲染语义无差异** —— mtr-next 走 `ModelPart.render` 与上游优化路径**等价**；
2. **只少了性能优化**（批次合并 ⇒ draw call 更少）；
   mtr-next 在多车厢场景下的 draw call 会比上游优化模式多，**这是性能差异不是正确性差异**；
3. **之前的「门重影」风险描述需要下调**：
   上游优化分支里画的是 `partDetails.optimizedModelDoor`（**另一个** ModelPart），
   而 `addCube` 走的是**同一个** `ModelPart`。
   所以「优化模式下上游不画常规门、mtr-next 画了 ⇒ 重影」的推断**依然成立**
   （依据是 `ModelPropertiesPart` 的分支，不是 `addCube`），
   但**与 `addCube` 无关**，且上游优化模式下**确实只画优化门**。
   本地无启用优化渲染的资源包 ⇒ **仍无法验证，维持「只标注」**。

## 仍未闭合（**不是结论，是待查**）

- 上游**何时**启用 `OptimizedRenderer.hasOptimizedRendering()`（资源包侧的开关）——
  本次只取到 `Mapping` 库（模型构建侧），**没有**取到 `RenderVehicles` / 资源包加载侧
  的优化开关判定逻辑。**不猜**。
- `MaterialGroup.addCube` 的**调用点**（谁在什么条件下调用它、传什么 x/y/z）——
  在 `OptimizedModel.class` 与渲染侧，本次未展开反汇编。**不猜**。
