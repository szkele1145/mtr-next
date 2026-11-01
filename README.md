# MTR Next 4to3 Car

**在 YMTR 3.x（MTR 3.6.4.2 分支）里加载并渲染 MTR 4.0 格式的车包。**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green)](https://minecraft.net)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-blue)](https://fabricmc.net)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)
[![Status](https://img.shields.io/badge/Status-Work%20in%20Progress-orange)](#项目状态)

---

## 这是什么

MTR 4.0 换了全新的车包格式（`.bbmodel` + `properties_*.json` + `definition_*.json` + `mtr_custom_resources.json`），而 YMTR 还在用 3.x 的旧格式，导致 4.0 车包**完全无法使用**。

这个模组补上了这层桥：让 3.x 能读 4.0 车包、能把 4.0 车型注册进配车界面、能按编组逐节渲染不同的车。

> 轨道预览功能已拆为独立模组：[**MTR Next Rail**](https://github.com/szkele1145/mtr-next-rail)

## 项目状态

**开发中（Work in Progress）** —— 请先看清楚再下载。

| 功能 | 状态 |
|---|---|
| 4.0 车包解析（bbmodel / properties / definition / 贴图 / 音效） | 可用 |
| 4.0 车型注册进 YMTR 配车界面 | 可用 |
| 自定义编组（加车 / 删车 / 翻转 / 保存 / 持久化） | 可用 |
| 逐节车型（头车 / 中间车 / 尾车各不同） | 可用 |
| **车体部件渲染位置** | **有错位问题**（扶手/挡板漂移、正反贴图重叠），正在修 |
| BVE 声景（`sound.cfg` 全套按键） | 可用 |

**如果你只是想用轨道预览**，请装 [MTR Next Rail](https://github.com/szkele1145/mtr-next-rail)，它是稳定版。

## 功能

### 4.0 车包加载

把 4.0 格式的车包（`.zip`）放进 `resourcepacks/` 并在游戏里启用即可。模组会自动：

- 解析 `mtr_custom_resources.json` 清单
- 读取每个车型的 `models` / `bogie1Models` / `bogie2Models`
- 解析 `.bbmodel` 的组层级、元素几何、贴图 UV
- 把车型注册进 YMTR 的 `TrainClientRegistry`，让它在配车界面可选

### 自定义编组

在原版配车界面点「编组」按钮，可以：

- 从可用车辆列表里挑选，逐节添加到编组
- 每节单独设置方向（正接 / 反接）
- 删除某一节、清空整个编组
- 保存后自动同步到服务端

编组会持久化到 `<存档>/mtr-next-4to3-car-formations.nbt`，重进世界、重启游戏都还在。

### 逐节车型

编组里可以是不同的车（头车 `M_F`、中间车 `M_T`、尾车 `M_B`），渲染时按编组**对应那一节**的车型画，不会整列都用同一种。

### BVE 声景

支持 4.0 车包的 `sound.cfg`，包括电机、刹车、道岔、雨刷等全部按键。

## 兼容性

| 项目 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Fabric Loader | >= 0.15.6 |
| Fabric API | 任意 1.20.1 版本 |
| **MTR** | **YMTR（MTR 3.6.4.2 分支）必需** |
| Java | 17+ |

**必需依赖**：本模组不能独立运行，必须装 MTR（YMTR 版）。不兼容原版 MTR 3.2.x。

## 安装

1. 安装 Fabric Loader 与 Fabric API
2. 安装 MTR（YMTR 3.6.4.2 分支）
3. 把 `mtr-next-4to3-car-*.jar` 放进 `mods/`
4. 启动游戏，把 4.0 车包放进 `resourcepacks/` 并启用

## 从源码构建

```bash
# Windows（JDK 17 必需）
set GRADLE_USER_HOME=C:\gradle-home
gradlew.bat build

# 产物：build/libs/mtr-next-4to3-car-<version>-1.20.1-fabric.jar
```

## 已知问题

- **车体部件错位**：车内扶手、挡板可能漂移到车身之外，正贴图与反贴图会重叠。根因还在定位。
- **复杂模型更明显**：元素多、层级深的车包（如 2418 元素的 17 格长车）错位更严重，极端情况下整车不可见。
- **刷子失效**：部分情况下轨道节点工具无法正常使用，正在排查。

## 致谢

- [MTR](https://github.com/jonafanho/Minecraft-Transit-Railway) —— 车包格式与渲染算法
- [YMTR](https://github.com/) 3.6.4.2 分支 —— 提供 3.x 宿主环境

## 许可

MIT