# 资料与技术依据 / Research

## 一手资料

- [Project Zomboid Food API](https://projectzomboid.com/modding/zombie/inventory/types/Food.html)
- [Project Zomboid ItemContainer API](https://projectzomboid.com/modding/zombie/inventory/ItemContainer.html)
- [ZombieBuddy 开发说明](https://github.com/zed-0xff/ZombieBuddy/blob/master/doc/ModdingGuide.md)
- [ZombieBuddy 安装说明](https://github.com/zed-0xff/ZombieBuddy/blob/master/doc/Installation.md)

版本相关接入同时对照了 42.20.4 游戏类与 2.3.2 前置的实际结构；本仓库不分发它们的二进制或反编译副本。

## 关键结论

- 只反转原版最终腐败增量会使完全冻结食物无法返鲜，因此用未受冷藏影响的游戏小时数计算恢复量。
- 原版物品属性同步不包含所需的 Age/lastAged 组合，需要单独的服务器权威快照。
- 稳定食品可能退出原版活动更新列表，因此需要追踪世界冰箱并定时更新。
- 部分容器转移路径直接写容器字段；读档恢复与实际转移必须分别处理。
- 发电机补算的停机时间必须依据原版实际燃料和随机损坏结果，不能再生成一套随机预测。
- 42.20.4 的 Core 版本字符串与 build 号分别存储，因此同时检查两者。结构不匹配时停用补丁。
- 原版跨供断电区间的累计腐败值包含冷藏平均效果，不能把该平均数再次用于断电区间。
- ZombieBuddy 2.3.2 的同名 Lua 别名存在删除原表的行为，本项目使用默认名称，并直接检查 Lua 全局表。

## English

The implementation is based on official Food/ItemContainer APIs, ZombieBuddy documentation and the actual target-version class contracts. Frozen recovery requires the raw game-time interval; container restoration must not be treated as a gameplay transfer; native generator accounting determines confirmed power intervals. Game and framework binaries remain local compile-only inputs.
