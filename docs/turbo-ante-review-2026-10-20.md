# mtr-turbo / mtr-ante 代码审查报告

> 审查时间：2026-10-20 11:00–11:20 +08
> 审查范围：mtr-turbo（33 个 Java 文件，fabric + forge 双端）、mtr-ante（283 个 Java 文件，
> 聚焦 common 层与近期改动的脚本系统 / 轨道渲染层）
> 方法：只读审查 + 可复现脚本验证；**未修改任何被审工程的生产代码**
> 已有审查参考：mtr-turbo `docs/code-review-log.md`（A~T 审查点，2026-10-14）

---

## 一、mtr-turbo

### 1. 【高】`scheduleRebuildTicks = 1` 时排班数据永久冻结

- **位置**
  - `fabric/src/main/java/mtrultra/mixin/RailwayDataMixin.java:57`
  - `forge-project/src/main/java/mtrultra/mixin/RailwayDataMixin.java:57`（两端同一行）

- **问题**

  ```java
  rebuild = mtrultra$scheduleTickCounter % period == 1;
  ```

  `period = 1` 时 `counter % 1` 恒为 `0`，`0 == 1` 恒为 **false** ⇒ `rebuild` 永远为
  false ⇒ 排班表永远不重建。

  连锁反应（三处联动）：
  1. `RailwayDataMixin.java:59` → `ScheduleRebuildFlag.set(false)`
  2. `RailwayDataMixin.java:72-79`（`@Redirect` Map.clear）→ 条件
     `enableScheduleThrottle && !ScheduleRebuildFlag.isSet()` 成立 → **跳过 clear**
  3. `TrainServerMixin.java:117-125`（`@Redirect` List.add）→ 同样条件成立 →
     **跳过排班写入**

  ⇒ 排班表被**完整冻结**在首次构建的内容上，客户端到站信息永久不更新。

- **可复现路径**

  1. 在 `config/mtr-turbo.json` 写 `"scheduleRebuildTicks": 1`
     （用户的自然语义是"每 tick 重建"= 关闭节流）
  2. `MtrUltraConfig.java:70` 的钳制是 `Math.max(1, scheduleRebuildTicks)`
     → **1 是合法值，被接受**
  3. 启动后查看排班/PIDS：内容不再变化

- **为什么之前没被发现**：`docs/code-review-log.md:45` 记的是
  「`period >= 1`（取模崩溃）」——修的是**除零崩溃**，没有发现 `% 1 == 1`
  **恒假**这个逻辑问题。

- **建议修法**：判据从 `% period == 1` 改为 `% period == 0`，并同步调整
  counter 的递增起点（或改用 `(counter - 1) % period == 0`）；同时补一条
  `period = 1` 的离线单元测试，断言「每 tick 都重建」。

- **严重程度**：高　**确信度**：确认（逻辑推演 + 双端源码一致 + 配置可达性已验证）

---

### 2. 【中】`org.gradle.java.home = Z:/jdk17` 在本工程不可用

- **位置**：`gradle.properties:2`、`forge-project/gradle.properties`（同名行）

- **问题**

  `Z:` 盘由 `F:\vibe coding工程\build.ps1` 的 `subst Z: "F:\vibe coding工程"` 临时
  挂载，脚本 `finally` 里立刻 `subst Z: /d` 删除。
  实测 `Test-Path Z:\jdk17` → **False**。

  而 **mtr-turbo 自己没有 `build.ps1`**（`Test-Path .\build.ps1` → False），
  也没有任何脚本会创建 `Z:`。

  ⇒ 任何独立执行 `gradlew build` 的场景（IDE 导入、CI、新人克隆、别的
  `build.ps1` 不带 subst 的调用）**必然失败**：
  `Java home supplied is invalid`。

- **对比**
  - mtr-next：`org.gradle.java.home=C:/jdk17`（该 junction 存在，已修）
  - mtr-ante：自己的 `build.ps1`… 但见 ante 问题 12，那个脚本本身是坏的
  - turbo 的 HEAD 提交 `732f79e` 标题正是
    「chore: forge-project java.home C:/jdk17 -> Z:/jdk17」⇒ **这是一次回归**

- **建议修法**：改回 `C:/jdk17`（`C:\jdk17` junction 已验证存在）。若确实需要
  ASCII 路径规避中文路径 mojibake，则给 turbo 也补一个自带 `subst` 的
  `build.ps1`，并同步 `forge-project/gradle.properties`。

- **严重程度**：中　**确信度**：确认（路径存在性已实测，脚本缺失已实测）

---

### 3. 【中】LOD 阈值钳制在 `simulationLodMidBlocks <= 0` 时产出非法值

- **位置**：`fabric/.../MtrUltraConfig.java:69`（forge 端同行）

  ```java
  simulationLodNearBlocks = Math.min(simulationLodNearBlocks, simulationLodMidBlocks - 1);
  ```

- **问题**：`midBlocks = 0` → `nearBlocks = -1`；`midBlocks = 1` → `nearBlocks = 0`。
  随后 `TrainServerMixin.java:91-93` 用 `nearBlocks * nearBlocks` 比较，
  `(-1)² = 1`、`0² = 0` ⇒ 近档几乎永不命中，全部列车落到中/远档 ⇒
  **LOD 语义静默错乱**（不崩溃，但用户写的配置不生效）。

  注释只覆盖了「`near >= mid` 会让分级永假」，没覆盖 `mid <= 0`。

- **建议修法**：先 `simulationLodMidBlocks = Math.max(1, simulationLodMidBlocks);`
  再钳 near；并对 `nearBlocks` 也做 `Math.max(0, ...)`。

- **严重程度**：中　**确信度**：确认

---

### 4. 【中·存疑】车厢剔除用的相机快照可能过期

- **位置**：`fabric/.../mixin/TrainClientMixin.java:38-41`、`:48-54`、`:61-82`

- **问题**

  ```java
  private Vec3 mtrultra$cameraPos = Vec3.ZERO;
  private boolean mtrultra$playerRiding = false;
  ```

  快照在 `TrainClient.simulateTrain` 的 HEAD 刷新，而剔除判定读这两个字段；
  两者都是 **per-`TrainClient` 实例**（每列车一份）的普通字段。

  - 初值是 `(0,0,0)` / `false`：若在 `simulateTrain` 之前发生一次
    `simulateCar` 剔除判定，会拿**世界原点**当相机位置 ⇒ 玩家附近的车厢被误剔除。
  - 若 `RenderTrains` 对每列车**分别**调用 `simulateTrain` 与 `simulateCar`
    且顺序在前，则用的是**上一帧/上一列车**的快照。

- **存疑理由**：需要 MTR 源码确认 `RenderTrains` 里 `simulateTrain` 与
  `simulateCar` 的调用顺序，以及 `simulateCar` 是否只由 `simulateTrain` 触发。
  本次审查环境无法取到 MTR 运行时代码，**未验证**。

- **建议修法**：无论调用顺序如何，把相机位置改为**每帧一次**的全局快照
  （渲染线程级 holder，在 `RenderTrains.render` HEAD 采集），而不是
  per-`TrainClient` 实例字段。

- **严重程度**：中　**确信度**：**存疑**（调用顺序未验证）

---

### 5. 【低】`@Redirect` 未做 `require = 0` 降级，MTR 升级会崩客户端

- **位置**：`fabric/.../mixin/RRenderTrainsMixin.java:72-75`

  ```java
  @Redirect(method = "render(Lnet/minecraft/class_1297;FLnet/minecraft/class_4587;Lnet/minecraft/class_4597;)V",
            at = @At(value = "NEW", target = "Ljava/util/HashMap;<init>()V"))
  ```

- **问题**：`@Redirect` 不带 `ordinal` 时要求目标方法内**唯一**匹配。新增一处
  `new HashMap()` 即注入失败；该 mixin **没有 `require = 0`**（默认 require=1）
  ⇒ **注入失败直接崩客户端**。

  对比：同工程 `RailwayDataMixin` 的所有注入点都写了 `require = 0` 并附带
  「失败仅警告不崩溃」的降级说明——同一策略没有贯彻到 U3。

- **建议修法**：加 `require = 0`（失败时保留原版每帧 new，行为退化但安全）。

- **严重程度**：低（当前 MTR 版本可用，但升级即炸）　**确信度**：确认

---

### 6. 【低】注释与实现不符

- **位置**：`fabric/.../mixin/TrainServerMixin.java:41-42`

  注释写「通过 @ModifyVariable 参数捕获」，实际实现是
  `TrainServerTickScaleMixin.java:31-35` 的 **`@ModifyArg`**。

- **建议修法**：改注释。　**严重程度**：低　**确信度**：确认

---

## 二、mtr-ante

### 7. 【高】`CONFIG_INFO` 注入双重转义 → 资源包脚本加载直接抛异常

- **位置**：`common/src/main/java/cn/zbx1425/mtrsteamloco/scripting/ScriptHolderBase.java:349-351`

  ```java
  String configInfo = new GsonBuilder().disableHtmlEscaping().create().toJson(config);
  configInfo = configInfo.replace("\"", "\\\"");
  eval("CONFIG_INFO = JSON.parse(`" + configInfo + "`);");
  ```

- **问题**

  Gson 的 `toJson` 已把特殊字符编码为 **JSON 转义序列**；这段代码又把它塞进
  **JS 模板字符串（反引号）**，模板字符串会**再解释一次**转义序列。

  实测（`tools/verify_script_injection.mjs`，本次新增）：

  | 配置值 | 结果 |
  |---|---|
  | `C:\mc\server`（反斜杠） | `JSON.parse` → `Bad escaped character in JSON at position 12` |
  | 含换行 `line1\nline2` | `JSON.parse` → `Bad control character in string literal` |
  | 含制表符 | `JSON.parse` → `Bad control character in string literal` |
  | 中文 `地铁列车` | ✅ 正常 |
  | 普通 ASCII | ✅ 正常 |

  ⇒ **配置里写一个 Windows 路径，整个资源包脚本 `load()` 抛异常。**
  因为中文和普通 ASCII 正常，现网很可能尚未暴露。

  注：`inject(String key, String value)`（L338-340）这个重载本身**安全**——
  全工程只有两处调用（`"SIDE"`、`"MOD_ENV"`），值都是内部常量。

- **建议修法**：不要做字符串拼接 + 模板字符串。直接把 JSON 文本放进 bindings：

  ```java
  context.getBindings("js").putMember("CONFIG_INFO", gson.toJson(config));
  ```

  脚本侧改成 `JSON.parse(CONFIG_INFO)`。这样只有一次编码，无二次转义。
  若必须保持现有写法，则需要对 configInfo 做**完整**的 JS 模板字符串转义
  （`\`、`` ` ``、`${`、换行、CR），而不是只处理 `"`。

- **严重程度**：高　**确信度**：确认（已用可执行脚本复现）

---

### 8. 【中】`boundingBox` 跨线程写入，缺可见性保证

- **位置**
  - 写：`common/.../render/rail/RailChunkBase.java:55-61`（`setBoundingBox`），
    由后台线程调用（`InstancedRailChunk.java:110`、`MeshBuildingRailChunk` 同理）
  - 读：`common/.../render/rail/RailRenderDispatcher.java:205`
    （`getCameraDistManhattanXZ` 内部读 `boundingBox`）、`:212`
    （`cullingFrustum.isVisible(chunk.boundingBox)`）

- **问题**：`boundingBox`、`isDirty`、`bufferBuilding` 都是**普通字段**
  （`RailChunkBase.java:41-44`），后台 `EXECUTOR` 线程写、渲染线程读，
  无 `volatile` / 无同步 ⇒ JMM 下**无 happens-before 关系**。

  渲染线程可能读到**半更新**的 AABB（`minX` 是新值、`maxX` 还是旧值）⇒
  视锥剔除用错误包围盒 ⇒ **轨道被错误剔除或错误渲染**。

- **建议修法**：`boundingBox` 改 `volatile`；更稳妥的是构建完成后用
  **一次性发布的不可变对象**（`volatile AABB boundingBox` + 整体替换引用）。

- **严重程度**：中　**确信度**：确认（线程归属已逐处核实：写入在 `EXECUTOR`
  任务内，读取在 `RailRenderDispatcher` 渲染方法内）

---

### 9. 【中】脚本执行无超时，死循环会永久卡死脚本线程

- **位置**：`common/.../scripting/ScriptHolderBase.java:42`、`:388-421`、`:449-451`

- **问题**

  ```java
  private static ExecutorService SCRIPT_THREAD = Executors.newSingleThreadExecutor();
  ...
  SCRIPT_THREAD.submit(() -> { ... function.executeVoid(allArgs); ... });
  ```

  脚本里写 `while(true){}` 永不返回 ⇒ 该**单线程** executor 的唯一工作线程
  永久卡死 ⇒ 之后所有脚本调用（create / render / dispose / use）全部排队阻塞
  ⇒ **整个脚本系统僵死**。

  `duringFailTimeout()`（L449-451）只在**抛过异常**后 4 秒内跳过，覆盖不了
  「没抛异常但卡住」这种最常见的情况。

- **建议修法**：给每次脚本调用设时限。GraalVM 侧可用
  `Context` 在独立线程 + `Thread.interrupt`（GraalVM 的 polyglot 调用对中断
  有响应），超时后 `context.close()` 并重建；或用 GraalVM 25+ 的超时选项。

- **严重程度**：中　**确信度**：确认

---

### 10. 【中】脚本抛异常时 `finishCallback` 不执行，导致每帧无限重试

- **位置**：`common/.../scripting/ScriptHolderBase.java:406-418`、`:431-438`

- **问题**

  ```java
  for (Value function : functions) { function.executeVoid(allArgs); }
  if (finishCallback != null) finishCallback.run();   // ← 在 try 内
  failException = null; failTime = 0;
  } catch (Exception ex) { ...记日志... }              // ← catch 里不调 callback
  ```

  `create` 回调不跑 ⇒ `scriptCtx.created` 永为 false ⇒
  `tryCallRenderFunctionAsync`（L431-438）每帧都走 `"create"` 分支 ⇒
  **无限重试**；且 `scriptCtx.renderFunctionFinished()` 永不被调用 ⇒
  渲染状态卡死。

- **建议修法**：把 `finishCallback.run()` 放进 `finally`（或 catch 里也调）。

- **严重程度**：中　**确信度**：确认

---

### 11. 【中】约 120 行死代码里存着「全沙箱打开」的配置

- **位置**：`common/.../scripting/ScriptHolderBase.java:181-301`

  ```java
  if (false) {
      context = Context.newBuilder("js")
          .allowPolyglotAccess(true ? PolyglotAccess.ALL : PolyglotAccess.NONE)
          .allowNativeAccess(true)
          .allowCreateProcess(true)
          .allowHostClassLookup(className -> true)
          .allowIO(trust ? IOAccess.ALL : IOAccess.NONE)
          ...
  }
  ```

- **问题**：当前不执行，但把 `if (false)` 改成 `if (true)` 就会**立刻开全沙箱**
  （任意进程创建 + 任意类加载 + 任意 IO）。这类"备用宽松配置"留在源码里是
  典型的风险信号，也增加审查成本。

- **建议修法**：删除该死代码块（git 历史里还找得回）。若要保留调试用途，
  移到独立分支并显式标注「仅本地调试，勿启用」。

- **严重程度**：中　**确信度**：确认

---

### 12. 【低】`build.ps1` 硬编码了不存在的路径

- **位置**：`mtr-ante/build.ps1:1-2`

  ```powershell
  $gradlewPath = "F:\mtr-ante\gradlew.bat"
  Start-Process -FilePath "$gradlewPath" -ArgumentList "build", '-PbuildVersion="1.18.2"', ...
  ```

- **问题**：工程实际在 `F:\vibe coding工程\mtr-ante`。
  实测 `Test-Path 'F:\mtr-ante\gradlew.bat'` → **False**，
  `Test-Path 'F:\mtr-ante'` → **False** ⇒ 该脚本在当前环境**必然失败**。

  同类：`runc.ps1` / `runs.ps1` 写死 `F:\mc\...` 个人开发路径。

- **建议修法**：改成 `$PSScriptRoot` 相对路径（`Join-Path $PSScriptRoot 'gradlew.bat'`），
  个人路径移到本地配置。

- **严重程度**：低（开发便利性问题，不影响发布物）　**确信度**：确认

---

### 13. 【低】`RootedScriptFileSystem` 的 root 未解引用符号链接

- **位置**：`common/.../scripting/RootedScriptFileSystem.java:36-38`、`:69`

  ```java
  this.root = root.toAbsolutePath().normalize();   // 未 toRealPath()
  ...
  if (!real.startsWith(root)) { throw new AccessDeniedException(...); }   // real 已解引用，root 未解引用
  ```

- **问题**：`real` 是 `toRealPath()` 解引用后的路径，`root` 不是。
  若 root 路径本身含符号链接，**合法路径会被误拒**（AccessDeniedException），
  表现为「沙箱里明明有文件却读不到」。

- **建议修法**：`this.root = root.toAbsolutePath().toRealPath();`（root 存在时；
  不存在则退化为 `toAbsolutePath().normalize()`）。

- **严重程度**：低　**确信度**：确认（代码逻辑层面；实际触发取决于 root 是否含链接）

---

### 14. 【低】`resetRunner()` 不等待旧线程终止

- **位置**：`common/.../scripting/ScriptHolderBase.java:453-456`

  ```java
  public static void resetRunner() {
      SCRIPT_THREAD.shutdownNow();
      SCRIPT_THREAD = Executors.newSingleThreadExecutor();
  }
  ```

- **问题**：`shutdownNow()` 之后立刻换新 executor，旧线程可能仍在运行并持有
  `Context`；`close()`（L458-463）并发 `context.close()` ⇒ 竞态。

- **建议修法**：`shutdownNow()` 后 `awaitTermination(1, TimeUnit.SECONDS)`。

- **严重程度**：低　**确信度**：确认

---

## 三、查过但**未发现**问题的方向

- **mtr-turbo 双端一致性**：fabric / forge 的 16 个对应文件逐一比对，差异全部是
  loader API（`FabricLoader.getConfigDir()` ↔ `FMLPaths.CONFIGDIR`）与注释措辞，
  逻辑无漂移。`MtrUltraStats.dumpJson` 的 `"loader"` 字段双端各自正确
  （fabric 写 `"fabric"`、forge 写 `"forge"`）。
- **mtr-turbo `MtrUltraMixinPlugin`**：ANTE 检测用类路径探测（`Class.forName`）
  + `FabricLoader.isModLoaded` 兜底，异常路径均保守跳过，逻辑可靠。
- **mtr-turbo `RailPositionCacheMixin`**：越界已钳制（`len - STEP`、`Math.min` 封顶、
  `n >= 2`），`WeakHashMap` 弱键 + ThreadLocal 隔离，缓存永真前提（Rail 几何不可变）
  已记录在 `docs/code-review-log.md:28`。
- **mtr-turbo `MtrUltraStats`**：计数器用 `AtomicLong`，`report()` 是 `synchronized`，
  并发安全。`nextLogAt` 是 `volatile`。
- **mtr-ante `RailChunkBase.upload()`**：`isEmpty()` + `poll()` 的 check-then-act
  看似竞态，但 `upload()` 全工程只在 `RailRenderDispatcher.java:192`（渲染线程）
  一处调用 ⇒ **单消费者，无 NPE**。（特意核实后排除，不作为问题上报。）
- **mtr-ante `bufferBuilding` 标志**：曾怀疑 `rebuildBuffer()` 置 `true` 后永不复位，
  核实 `InstancedRailChunk.java:106` / `MeshBuildingRailChunk.java:78` 都会复位
  ⇒ 不成立。
- **mtr-ante `RootedScriptFileSystem` 的越界防护**：符号链接解引用 + 缺失目标的
  最近存在祖先探测，主体逻辑正确（见问题 13 的次要缺陷）。
- **mtr-ante `ScriptHolderBase.inject(String,String)`**：两处调用值均为内部常量，安全。

---

## 四、需要 mtr-next 配合才能处理的项

**无。** 本报告全部 14 项都不依赖 mtr-next 的任何改动，可各自独立修复与验证。

---

## 五、建议处理顺序

| 序 | 项 | 项目 | 严重度 | 修复成本 |
|---|---|---|---|---|
| 1 | #7 CONFIG_INFO 双重转义 | ante | 高 | 低（改用 `putMember`） |
| 2 | #1 `scheduleRebuildTicks=1` 排班冻结 | turbo | 高 | 低（一行判据 + 一个测试） |
| 3 | #2 `java.home=Z:/jdk17` | turbo | 中 | 极低（改一行） |
| 4 | #3 LOD 阈值钳制 | turbo | 中 | 极低 |
| 5 | #8 `boundingBox` 可见性 | ante | 中 | 低（volatile） |
| 6 | #10 `finishCallback` 不执行 | ante | 中 | 极低（finally） |
| 7 | #9 脚本无超时 | ante | 中 | 中 |
| 8 | #11 死代码含全沙箱配置 | ante | 中 | 极低（删除） |
| 9 | #4 相机快照可能过期 | turbo | 中（存疑） | 中（需先确认调用顺序） |
| 10 | #5 / #6 / #12 / #13 / #14 | 双项目 | 低 | 极低 |

> 说明：#1 与 #7 之外，其余均未做运行时验证（当前环境无图形客户端、
> 无 MTR 运行时依赖），修复后建议补离线测试或冒烟验证。
