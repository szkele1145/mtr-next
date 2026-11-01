# 发布产物说明（release/）

本目录存放**实际对外发布的 JAR**，与 `build/libs/` 分离。

## 为什么分开

当前 `build/libs/mtr-next-1.0.0-1.20.1-fabric.jar` 是由 **GPT-6 提供的已验证修复版二进制**
融合而来（原始产物：`mtr-next-4to3-car-0.70.0-renderfix.10-1.20.1-fabric.jar`）。

而本仓库的 `fabric/src/main/java` 源码**早于**该修复版，尚未包含修复内容。
因此：

> ⚠️ **执行 `gradle build` 会用旧源码覆盖融合产物，导致已修复的渲染问题回归。**

在源码同步完成之前，**不要执行 `gradle build`**，或执行后立即用本目录的 JAR 覆盖回去。

## 已完成的元数据改写

融合时仅改动元数据，**103 个 `.class` 文件全部字节级未改动**（sha256 逐一比对，差异数 = 0）：

| 字段 | 值 |
|---|---|
| `id` | `mtr-next`（原 `mtr-next-4to3-car`） |
| `version` | `1.0.0`（原 `0.70.0-renderfix.10`） |
| `name` | `MTR Next` |
| mixins 配置文件 | `mtr-next.mixins.json`（原 `mtr-next-4to3-car.mixins.json`） |
| refmap 文件 | `mtr-next.refmap.json`（原 `mtr-next-4to3-car.refmap.json`） |
| `mixins[].refmap` 字段 | 已同步指向新文件名 |
| `contact.homepage` / `contact.sources` | `https://github.com/szkele1145/mtr-next` |

**refmap 内容逐字节未改**（仅文件名变更）——refmap 内部存的是 mixin 注入点到目标类的映射，
与 `.class` 内的 mixin 目标绑定，改内容会导致 mixin 静默失效或启动崩溃。

## 产物校验

```
文件大小 : 361762 bytes
条目数   : 120（108 文件 + 12 目录条目）
sha256   : f0ea4183b8964f3db5b7d80bcaf8d8dd2e704f4c3b5ac01c7da61d9171cb8123
```

## 后续动作

等 GPT-6 提供对应源码后：

1. 把源码同步进 `fabric/src/main/java`
2. 用 `gradle clean jar` 重新构建
3. 用构建产物覆盖 `release/mtr-next-1.0.0-1.20.1-fabric.jar`
4. 比对新旧 class，确认修复点都在
5. 重新发 GitHub Release + Modrinth 版本
