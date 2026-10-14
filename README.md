# MTR Next — 把 MTR 4.x 特性带回 YMTR 3.x

> 独立 mixin 附属（**不是** MTR/YMTR 的分支）。目标 YMTR 3.6.x / MTR 3.2.x（MC 1.20.1 Fabric）。
> 定位：4.x 特性的**回移层**——轨道预览 + 4.0 格式车包的全链路支持。

## 功能矩阵（0.9.0）

| 功能 | 状态 | 说明 |
|---|---|---|
| **轨道预览** | ✅ 曲线精确 | 手持已选节点的轨道物品时实时看到将生成的轨道形状——用 YMTR 同源 Rail 贝塞尔采样（64 段），**预览=实际** |
| **4.0 车包解析** | ✅ 三级 schema | mtr_custom_resources.json vehicles 段：几何/转向架/连接面（gangway/barrier）/多模型层/legacy 字段 |
| **自定义编组** | ✅ 全链路 | 车厂侧线"编组"按钮 → 可视化编辑屏（头连头方向翻转/连接面校验/车色图标）→ 保存写入 Siding 发车配置 |
| **车辆渲染** | ✅ bbmodel 桥 | BlockBench 盒体 → MC ModelPart，车包贴图，走 YMTR 原生列车渲染管线 |
| **连接面渲染** | ✅ 程序化 | gangway（贯通道风挡）/barrier（端墙）贴图带从 4.0 properties_*.json 渲染 |
| **BVE 声音** | ✅ 实时速度 | sound.cfg 七段解析 + 速度-档位调速表（csv 线性插值），motor 音随加减速实时变调 |
| **编组持久化** | ✅ sidecar NBT | 不侵入 YMTR 存档——卸载本 mod 世界照常打开 |

**双版本车包并存承诺**：4.0 格式（vehicles 段特征）由 MTR Next 接管；
**3.2 格式零侵入**——YMTR 原样处理。无 4.0 车包时界面零变化（编组按钮不出现）。

## 已验证

- 13 轮真实服务器冒烟（YMTR 共存）：全部 `Done` 零 mixin 报错
- **三方实测**：YMTR + MTR Turbo + MTR Next 同装 `Done (10.166s)` 零冲突
- 格式实证：ERIDU-DG1000A 标准车包全解构（mtr-turbo 仓库 docs/mtr4-pack-format.md）
- 全部挂钩点 javap 字节码核验（intermediary 名/全签名/字段链/速度字段）

## 使用

1. 装 YMTR 3.6.x（或 MTR 3.2.x）+ 本 mod
2. 放入任意 **4.0 格式车包**（含 `assets/mtr/mtr_custom_resources.json` vehicles 段，
   如 ERIDU-DG1000A）
3. 车厂侧线界面出现"编组 [MTR-Next]"按钮 → 可视化编组（加车/头连头翻转/删除）
4. 手持轨道节点选了第一节点后，看任意方块出**精确曲线预览**
5. 保存编组 → 该侧线按你的编组发车，4.0 车体+声音全部呈现

## 构建与开发

```
gradlew build     # 产物 build/libs/mtr-next-<ver>-1.20.1-fabric.jar
gradlew runServer # 冒烟
```

## 许可

MIT
