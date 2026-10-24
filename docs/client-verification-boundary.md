# 4.0 车包加载路径审查（0.31 发现的关键盲区）

## 发现

四方实测时把 ERIDU 车包放进了 `run/resourcepacks/eridu.zip`——但
**服务端不会自动启用 resourcepacks 目录里的包**：
1. 服务端 resourcepacks 是给客户端推送用的（server.properties 的
   resource-pack=），服务器自身资源解析不含它
2. 服务端 ResourceManager 解析的是 **datapacks**（world/datapacks）
   与 mod 自带资源

## 结论：车包在服务端测试场景从未被加载过！

四方实测的"MTR-Next initializing"只验证了 mod 启动——
`CustomResourcesReloadMixin` 挂的是 **客户端**（mixins.json 的
client 段），而 runServer 无头模式根本没有客户端资源重载。
所以：
- 车包解析（VehiclePackLoader.reload）从未在测试中执行过
- TrainTypeBridge 注册、编组按钮条件（has4xVehicles）也无从触发
- 0.29/0.30 的渲染重写更无验证机会（需要客户端 + 启用的资源包）

## 正确的验证路径（需客户端）

1. Fabric 客户端（runClient）启动
2. 选项→资源包→启用 ERIDU 车包
3. 进世界 → 车厂侧线看"编组 [MTR-Next]"按钮是否出现
4. 打开编组屏 → 3D 预览直接显示 per-face UV + winding 修复效果

**这一步需要你（用户）在有图形环境的客户端执行**——headless
的服务端测试对整条 4.0 车包链路是零覆盖。

## 已做与能做的（服务端可验证的部分）

- mod 加载/mixin 应用：39 轮冒烟全绿 ✓
- 三方/四方 mod 共存：零冲突 ✓
- 4.0 解析器的纯逻辑（json 结构）：ERIDU 样本静态解析可另测

## 行动

记为已知验证边界；客户端视觉验证清单留给用户：
1. 编组按钮出现？
2. 编组屏 3D 预览车体贴图正确？（0.29/0.30 的核心验证点）
3. 门动画平滑？
4. 发车后 4.0 车体+声音全呈现？
