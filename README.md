# Realwayfinder

**v1.2.2** · Minecraft **26.2** · Fabric · 纯客户端

在 MCC Island 上按优先级选钓鱼点、扫点、走到站位并抛竿。行走使用编进本 jar 的 Baritone 26.2（提交 `9fadf7cf`，LGPL-3.0）。不要再另装 Baritone。

当前适配进度：**I4 与 Tropical Grotto**。I5–I9 能从导航界面点进去，还没有内置扫点环。

## 依赖

游戏内需要同时安装：

- Fabric Loader `>=0.20.1`
- Fabric API
- Fabric Language Kotlin
- [SpotFilter](https://github.com/KClgame/SpotFilter) `1.9.8`（编译与运行都需要；鱼点数据来自它）
- KCl Utils（`fabric.mod.json` 的运行时依赖）

Java 25。

## 安装

把 `build/libs/realwayfinder-<版本>.jar` 放进 `.minecraft/mods/`，与上面的依赖一起启动。

## 按键

控件分类是 **Realwayfinder**。默认键：

| 键 | 作用 |
| --- | --- |
| V | 开关整个模组 |
| N | 打开或关闭配置 |
| K | 开始扫点，或中断当前扫点 |
| J | 跳过当前扫点点位 |
| M | 重置寻路 |

聊天框打开时这些键不触发。

## 行为

- 岛屿优先级在配置里排。未扫过的岛先扫点，扫过的岛直接钓鱼，钓完的岛跳过。整点只清岛屿的已扫和已完成标记，Grotto 的已扫标记保留。
- 已有内置扫点环：I1（7 个点）、I2（8）、I3（8）、I4（22）。扫完直接开始找鱼，不走回出生点。
- 大厅里用背包指南针进入 Fishing，再按固定箱子槽位左键点岛。槽位：I1=20，I2=21，I3=22，Temperate=24，I4=29，I5=30，I6=31，Tropical=33，I7=38，I8=39，I9=40，Barren=42。
- 岛上的鱼种、Overclock（Hook / Magnet / Rod）和 Grotto 种类在配置里分开设置。
- 行动栏出现 `Enter /hub` 时发送 `/hub`。
- 岩浆和熔岩不走。水面净空不足两格时下沉或绕行。

配置写在 `.minecraft/config/realwayfinder/client.txt` 和 `overclock.txt`。

## 构建

需要 JDK 25。SpotFilter 源码用公开提交 [`bfdac6fe`](https://github.com/KClgame/SpotFilter/commit/bfdac6fefc405598e0bb10b9843ee3c55908b129)（1.9.8）。把编好的 `spotfilter-1.9.8.jar` 放到本仓库的 `libs/`，或先把该提交编到 `spotfilter/build/libs/`。

```
./gradlew build
```

测试：

```
./gradlew test --tests kcl.realwayfinder.nav.RouteTest
```

GitHub Actions 会自行检出并编译上述 SpotFilter 提交，再执行 `./gradlew build`。

## 待办：I5–I9

导航名字和点击槽位已经接上。缺的是每座岛自己的扫点环和出生识别，做完一座再把 `fabric.mod.json` 的适配进度往后标。

岛屿对照：

| 编号 | 名称 | 导航槽位 |
| --- | --- | --- |
| I5 | Coral Shores | 30 |
| I6 | Twisted Swamp | 31 |
| I7 | Ancient Sands | 38 |
| I8 | Blazing Canyon | 39 |
| I9 | Ashen Wastes | 40 |

每座岛：

- [ ] **I5 Coral Shores**：记录扫点环，写入 `BuiltInRoutes`；确认出生点识别；在岛上走完一圈并开始钓鱼。
- [ ] **I6 Twisted Swamp**：同上。
- [ ] **I7 Ancient Sands**：同上。
- [ ] **I8 Blazing Canyon**：同上。这条环要避开岩浆和熔岩。
- [ ] **I9 Ashen Wastes**：同上。

一座岛的环落地后：扫点结束要进入寻路钓鱼，整点重置后未扫的岛会再扫，钓完会去优先级里的下一座。

## 许可

本模组代码为 [CC0-1.0](LICENSE)。

Baritone 源码在 `third_party/baritone`，许可是 LGPL-3.0，见 `third_party/baritone/LICENSE`。打包进 jar 的副本名为 `LICENSE_baritone_LGPL-3.0`。
