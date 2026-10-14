# MTR Next — 把 MTR 4.x 特性带回 YMTR 3.x

> 独立 mixin 附属（**不是** MTR/YMTR 的分支）。目标 YMTR 3.6.x / MTR 3.2.x（MC 1.20.1 Fabric）。
> 定位：4.x 特性的**回移层**——轨道预览 + 4.0 格式车包的全链路支持。

## 功能矩阵（0.21.0）

| 功能 | 状态 | 说明 |
|---|---|---|
| **轨道预览** | ✅ 曲线精确 | 与正式轨道同源贝塞尔（64 段采样）——**预览=实际** |
| **4.0 车包解析** | ✅ 三级 schema | vehicles / properties / definition 全字段 |
| **自定义编组** | ✅ 完整编辑器 | 头连头翻转、Flip All、Clear All、连接面校验反馈、选中车详情条、双列表分页、车色图标 |
| **编组→发车** | ✅ 桥接闭环 | 保存即写入 Siding 车辆配置 |
| **车辆渲染** | ✅ bbmodel 桥 | BlockBench → MC ModelPart，YMTR 原生渲染管线 |
| **门动画** | ✅ 平滑插值 | `door` 命名部件识别，开 0.5/s 关 0.8/s 渐进 |
| **连接面** | ✅ 三向贴图 | gangway（贯通道）/barrier（端墙）侧/顶/底程序化渲染 |
| **BVE 声音** | ✅ **十段全通** | Motor/Brake/Run/Flange/Noise/Door Open/Door Close（真实关门沿检测）/Joint/Release/Shoe |
| **实时速度** | ✅ | Train.speed 反射（缓存）驱动全部调速表 |
| **编组持久化** | ✅ sidecar NBT | 卸载 mod 世界照常打开 |

**双版本车包并存**：4.0 格式（vehicles 段特征）由 MTR Next 接管；
**3.2 格式零侵入**——YMTR 原样处理。无 4.0 车包时界面零变化。

## 已验证

- 28 轮真实服务器冒烟 + 三方实测（YMTR + MTR Turbo + MTR Next 零冲突）
- 格式实证：ERIDU-DG1000A 参考包全解构（十段声音字段逐一对照）
- 全部挂钩 javap 字节码核验；限制记录→解除限制的完整闭环（Door Close 案例）

## 使用

1. 装 YMTR 3.6.x（或 MTR 3.2.x）+ 本 mod
2. 放入 4.0 格式车包（含 `mtr_custom_resources.json` vehicles 段）
3. 车厂侧线"编组 [MTR-Next]"按钮 → 可视化编组
4. 手持轨道节点选点 → 精确曲线预览
5. 保存 → 该侧线按你的编组发车；4.0 车体/连接面/十段声音全部呈现

## 许可

MIT
