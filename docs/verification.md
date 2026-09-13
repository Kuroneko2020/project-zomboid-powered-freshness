# 验证范围 / Verification

目标：Project Zomboid 42.20.4、ZombieBuddy 2.3.2。使用 JDK 25 编译，项目字节码为 Java 17；游戏本身运行在 Java 25。

## 如何复现

准备自己合法取得的游戏和前置，在源码根目录运行：

```powershell
./scripts/Build.ps1 -GameDirectory '你的游戏目录'
```

`projectzomboid.jar` 和 `ZombieBuddy.jar` 仅在本地用于编译与验证。可通过 `PZ_GAME_DIR`、`ZOMBIEBUDDY_JAR` 指定依赖位置。脚本默认执行以下 12 个测试入口，任何失败都会终止本次构建。

| 测试入口 | 验证内容 |
| --- | --- |
| CoreTests | 五档速率、归零、顺序供断电、重叠电源、冻结返鲜、非法值与顺序保护 |
| AgeJournalTest | 未决历史保留、已决结果原子提交、跨容器顺序、异常数据处理 |
| PowerTrackerTests --game | 发电机实际停机小时记账、卸载与物理移除区别、重复回调、保存恢复、游戏字段契约 |
| ProtocolTest | 会话、序号、消息字段及有符号物品 ID |
| FairOutboxTest | 有界队列的公平性，固定遍历不会使尾部物品永久收不到更新 |
| NetworkPayloadTest | 使用游戏实际 Kahlua 表解析消息 |
| LuaBridgeTest | 使用游戏 Kahlua 编译器编译 Lua 桥 |
| HooksTest | 原版字段写入保留、年龄快照、读档恢复与转移边界 |
| GameTransformerTest | 七个目标游戏类的精确转换，缺少目标或重复转换时拒绝 |
| InstrumentationTest | 独立 JVM 对真实游戏类进行首次加载与重新转换，开启 -Xverify:all |
| BootstrapTest | 实际 ZombieBuddy Main 调用入口、版本检查、Lua 全局表与重复启动 |
| FoodIntegrationTest | 实际 Food.updateAge：五档冰箱/冰柜速率、冻结、断电、归零、转移、单机/客户端/服务器分支、阶段恢复与首次发现 |

## 已执行的验证

0.1.0 开发验证中，上述入口已通过。另使用独立缓存、配置和回环网络启动过真实 GameServer，确认 Java Mod 安装、Lua 暴露和服务器世界启动回调均成功，随后正常关闭服务器。相关原始环境日志保留在开发环境，没有随本源码仓库分发。

Food 集成测试只替换 Lua 脚本加载的初始化边界，以免读取外部游戏脚本和存档；沙盒值显式设置，Food 实例绕过资源构造。实际年龄、冻结、供电判断和阶段判定仍执行游戏实现。无返鲜转换的负对照确认失败，能识别补丁未加载的情况。

## 未验证的范围

- 两个真实客户端联网后的完整取放、重连、卸载区块与断电流程。
- 真实存档中的发电机随机损坏、第三方电源兼容和长期运行性能。
- 用户实机验收。服务器成功启动不等于多人完整验收。

## English

Build with JDK 25 and locally supplied Project Zomboid 42.20.4 / ZombieBuddy 2.3.2 JARs. The 12 automated suites cover the timing model, protocol, real game-class transformation, the actual framework bootstrap/Lua global, and controlled calls to real Food.updateAge. An isolated real GameServer also loaded the Mod and entered its authoritative world-start callback. Original environment logs are retained privately, not shipped here.

The Food fixture replaces only script-loading initialization and bypasses resource constructors. These results do not constitute a two-client multiplayer playtest, native generator runtime certification, performance benchmark or user acceptance. No release assets or third-party binaries are distributed in this repository.
