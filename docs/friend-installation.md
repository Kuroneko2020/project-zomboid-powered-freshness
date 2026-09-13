# 冰箱返鲜 0.1.0：给朋友的安装说明

需要：僵尸毁灭工程 42.20.4、ZombieBuddy 2.3.2，以及 PoweredFreshness 安装 ZIP。本 GitHub 仓库只提供源码，可按 README 构建生成安装 ZIP，或使用另行收到的安装包。

1. 先退出游戏。
2. 安装前置 ZombieBuddy 2.3.2。Windows 用户先订阅前置，再按官方说明运行其安装器，接入游戏启动器；只在创意工坊订阅前置还不够。
   - 前置工坊：https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853
   - 官方安装说明：https://github.com/zed-0xff/ZombieBuddy/blob/master/doc/Installation.md
   - Windows 安装器：https://github.com/zed-0xff/ZombieBuddy/releases/tag/windows_installer
   - 本成品锁定前置 2.3.2，安装时核对版本。
3. 按 Win+R，输入 `%USERPROFILE%\Zomboid\mods` 后回车；如果没有 mods 文件夹，就在 Zomboid 文件夹内创建一个。
4. 解压收到的安装 ZIP，把里面整个 `PoweredFreshness` 文件夹复制到上述 mods 文件夹。最终应存在：
   `%USERPROFILE%\Zomboid\mods\PoweredFreshness\42\mod.info`
   `PoweredFreshness` 内应直接包含 `42` 和 `common`，不要多套一层同名文件夹。
5. 启动游戏，在 Mod 列表启用 ZombieBuddy 和“冰箱返鲜 / Powered Freshness”。旧存档也需要在该存档的 Mod 设置里启用。
6. 第一次加载时，ZombieBuddy 会询问是否允许这个 Java Mod；核对名称为 PoweredFreshness 后选 Yes。随后可选择记住批准。

联机：房主和每位玩家都需要相同版本的安装包及前置，房主还要在该房间的 Mod 设置里启用它们。这个安装包没有创意工坊 ID，不会随加入房间自动下载。

独立服务器：放到运行服务器账号的 `Zomboid/mods/PoweredFreshness`；如果服务器使用自定义缓存目录，则放到该缓存目录下的 `mods/PoweredFreshness`。还需在服务器 Mod 列表中加入 ZombieBuddy、PoweredFreshness，并按前置说明接入服务器 Java 启动。房主客户端已经有前置，不等于服务器已经有前置。

不要把你自己的存档、整个 Zomboid 文件夹、默认 Mod 列表或前置批准文件一起发给别人。只需发送 PoweredFreshness 安装 ZIP 和本说明；前置由对方按官方步骤安装。

使用：易腐食物在通电冰箱或冰柜内按沙盒常温腐败速度返鲜，变质可以回到陈腐，再回到新鲜；断电或取出后恢复原版处理。真实双客户端联机尚未实测，具体验证范围见安装包 README 和 docs/verification.md。
