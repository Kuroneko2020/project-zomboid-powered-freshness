# 冰箱返鲜 0.1.0：给朋友的安装说明

需要：僵尸毁灭工程 42.20.4、ZombieBuddy 2.3.2，以及 PoweredFreshness 0.1.0（工坊订阅或本地安装 ZIP）。

已发布至 [Steam 创意工坊：冰箱返鲜 / Powered Freshness（3800777834）](https://steamcommunity.com/sharedfiles/filedetails/?id=3800777834)。本 GitHub 仓库只提供源码、文档和封面素材，不提供安装包或 GitHub Release；本地 ZIP 可按 README 构建生成，或使用另行收到的安装包。

Steam 已将 ZombieBuddy 列为本 Mod 的必需物品；Java loader 仍需按作者流程手动安装。

1. 先退出游戏。
2. 安装前置 ZombieBuddy 2.3.2。Windows 用户先订阅前置，再按官方说明运行其安装器，接入游戏启动器；只在创意工坊订阅前置还不够。
   - 前置工坊：https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853
   - 官方安装说明：https://github.com/zed-0xff/ZombieBuddy/blob/master/doc/Installation.md
   - Windows 安装器：https://github.com/zed-0xff/ZombieBuddy/releases/tag/windows_installer
   - 运行作者的 `ZombieBuddyInstaller.exe`，选择 `Install or update ZombieBuddy`，再选 `Both` 为常规和备用启动接入 Java loader，或只选实际使用的启动方式。核对修改预览并按提示继续；若安装器在修改 Steam 启动项时要求退出 Steam，再按提示退出。
   - 启动游戏后检查主菜单左上角显示 `ZombieBuddy v2.3.2 loaded`；若没有或版本不同，先完成前置安装。
   - 本成品锁定前置 2.3.2，安装时核对版本。
3. 订阅上述 Powered Freshness，等待 Steam 下载完成，再继续第 5 步。也可选择本地 ZIP：按 Win+R，输入 `%USERPROFILE%\Zomboid\mods` 后回车；如果没有 mods 文件夹，就在 Zomboid 文件夹内创建一个。
4. 仅本地 ZIP 安装需要此步：解压收到的安装 ZIP，把里面整个 `PoweredFreshness` 文件夹复制到上述 mods 文件夹。最终应存在：
   `%USERPROFILE%\Zomboid\mods\PoweredFreshness\42\mod.info`
   `PoweredFreshness` 内应直接包含 `42` 和 `common`，不要多套一层同名文件夹。选择一种安装方式，避免同时保留同名本地版和工坊版。
5. 启动游戏，在 Mod 列表启用 ZombieBuddy 和“冰箱返鲜 / Powered Freshness”。旧存档也需要在该存档的 Mod 设置里启用。
6. 第一次加载或 JAR 更新时，ZombieBuddy 会询问是否允许这个 Java Mod；核对名称为 PoweredFreshness 和 SHA-256 后按正常流程批准。可仅本次会话批准或记住决定；服务器也需要单独完成自己的审批。

联机：房主服务器和每位玩家都需要相同版本的本 Mod 及 ZombieBuddy 2.3.2。使用工坊版时，房主在房间的工坊物品列表加入 ZombieBuddy `3619862853` 和 Powered Freshness `3800777834`，并在 Mod 列表启用 `ZombieBuddy`、`PoweredFreshness`，保留原有条目。使用本地 ZIP 时，每台机器分别复制安装。工坊下载不能代替 Java loader 安装；每位玩家和服务器仍需按前置作者流程单独接入。

独立服务器：工坊安装按上面的两个 Workshop ID 和 Mod ID 配置。若使用本地 ZIP，放到运行服务器账号的 `Zomboid/mods/PoweredFreshness`；如果服务器使用自定义缓存目录，则放到该缓存目录下的 `mods/PoweredFreshness`。两种方式都需在服务器 Mod 列表中加入 ZombieBuddy、PoweredFreshness，并按前置说明接入服务器 Java 启动。房主客户端已经有前置，不等于服务器已经有前置。

不要把你自己的存档、整个 Zomboid 文件夹、默认 Mod 列表或前置批准文件一起发给别人。只需发送工坊入口和本说明，或发送 PoweredFreshness 安装 ZIP 和本说明；前置由对方按官方步骤安装。

使用：易腐食物在通电冰箱或冰柜内按沙盒常温腐败速度返鲜，变质可以回到陈腐，再回到新鲜；断电或取出后恢复原版处理。真实双客户端联机尚未实测，具体验证范围见安装包 README 和 docs/verification.md。

## English

Requires Project Zomboid **42.20.4**, Powered Freshness **0.1.0**, and ZombieBuddy **2.3.2**. Published on [Steam Workshop: Powered Freshness (3800777834)](https://steamcommunity.com/sharedfiles/filedetails/?id=3800777834). This GitHub repository provides source, documentation and cover artwork, with no installation packages or GitHub Releases. Build the source to obtain a local installation ZIP, or use a separately supplied package.

Steam now lists ZombieBuddy as a required item for this mod; its Java loader still requires manual installation following the author's instructions.

Subscribe to Powered Freshness and [ZombieBuddy (3619862853)](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853). ZombieBuddy's Java loader still needs manual setup through the [author's instructions](https://github.com/zed-0xff/ZombieBuddy/blob/master/doc/Installation.md) and [Windows installer](https://github.com/zed-0xff/ZombieBuddy/releases/tag/windows_installer). Subscription alone is insufficient. Alternatively, extract the ZIP's entire `PoweredFreshness` folder into `%USERPROFILE%\Zomboid\mods`; it should directly contain `42` and `common`. Use one installation method to avoid duplicate copies.

On Windows, run `ZombieBuddyInstaller.exe`, select `Install or update ZombieBuddy`, then `Both` for Normal and Alternate Launch or only the mode you use. Review its proposed changes and follow the prompts; close Steam if requested when changing launch options. Start the game and check for `ZombieBuddy v2.3.2 loaded` in the main menu. If missing or a different version is shown, fix the prerequisite installation first. Check the mod name and SHA-256 before approving a new or changed Java JAR; the server needs its own approval too.

Enable both mods, including in existing save settings, and follow ZombieBuddy's first-load Java approval prompt. Every server/host and client needs matching mod and dependency versions and its own Java loader setup. Workshop servers need item IDs `3619862853` and `3800777834`, plus Mod IDs `ZombieBuddy` and `PoweredFreshness`, preserving existing entries. Local ZIP servers need the folder in their own cache `mods` directory. Check server and client loading separately.

Food recovers from rotten to stale to fresh inside powered refrigerators and freezers. Real two-client multiplayer has not been playtested; see [verification.md](verification.md) for completed checks and their limits. The project's code, tests and documentation were generated and reviewed by AI under user direction and are MIT-licensed. Game and third-party software retain their own terms and are not included. 本项目代码、测试和文档由 AI 根据用户要求生成并审查，以 MIT 许可证开放；游戏和第三方框架不随本项目分发。
