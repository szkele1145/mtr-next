# MTR Next — 把 MTR 4.x 特性带回 YMTR 3.x

> 独立 mixin 附属（**不是** MTR/YMTR 的分支）。目标 YMTR 3.6.x / MTR 3.2.x（MC 1.20.1 Fabric）。
> 定位：4.x 特性的**回移层**——轨道预览 + 4.0 格式车包的自定义编组。

## 当前状态（0.1.0 — 早期预览版）

| 功能 | 状态 |
|---|---|
| **轨道预览**（手持已选节点的轨道物品时，实时看到将要生成的轨道形状） | ✅ 直线段折线版（青色半透明、穿墙可见，语义对齐 4.x）；曲线一致预览在 0.2 |
| **4.0 车包解析**（mtr_custom_resources.json 的 vehicles 段） | ✅ 完整三级 schema：车定义/连接面（gangway+barrier）/多模型层/legacy 字段 |
| **自定义编组**（车厂侧线界面"编组"按钮 → 可视化编组编辑屏） | ✅ 数据层+UI：可用车列表/编组序列/头连头方向翻转/连接面校验 |
| **bbmodel 渲染桥** | 🔄 解析器完成（BlockBench 盒体→MC ModelPart），车辆渲染器组装在 0.2 |
| 编组持久化 | ✅ sidecar NBT（不侵入 YMTR 存档——卸载本 mod 世界照常打开） |

**双版本车包并存承诺**：4.0 格式（vehicles 段特征）由 MTR Next 接管；
**3.2 格式车包零侵入**——YMTR 原样处理，MTR Next 不碰。无 4.0 车包时
界面零变化（编组按钮不出现）。

## 已验证

- 5 轮真实服务器冒烟（YMTR 3.6.4.2 共存）：全部 `Done` 零 mixin 报错
- 格式实证：ERIDU-DG1000A 标准车包全解构（见 mtr-turbo 仓库 docs）
- 挂钩点全部 javap 字节码核验（intermediary 名/全签名/字段链）

## 使用

1. 装 YMTR 3.6.x（或 MTR 3.2.x）+ 本 mod
2. 放入任意 **4.0 格式车包**（含 `assets/mtr/mtr_custom_resources.json` vehicles 段）
3. 车厂侧线界面出现"编组 [MTR-Next]"按钮 → 编组编辑
4. 手持轨道节点选了第一节点后，看任意方块出预览线

## 构建与开发

```
gradlew build     # 产物 build/libs/mtr-next-<ver>-1.20.1-fabric.jar
gradlew runServer # 冒烟
```

## 许可

MIT
