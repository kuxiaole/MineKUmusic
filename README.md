# MineKUmusic

本版本由 **小乐** 开发，基于 **Sorihiko / DangoMusic** 修改，提供个人音乐和自愿加入的全服音乐频道；服务端队列与 Velocity 群组服实现参考 **Coloryr / AllMusic**。项目、插件和发行文件名称为 **MineKUmusic**，客户端内显示为 **MineKU Music**。

本分支交付 Minecraft **26.1.2 / 26.2 / 26.3 Fabric / NeoForge 客户端**、**Paper / Folia 26.1.2 / 26.2 / 26.3 通用服务器插件**和 **Velocity 插件**，使用 Java 25。上游其他版本的源码仍保留在项目中，不属于本分支的交付范围。

源码仓库：[kuxiaole / MineKUmusic](https://github.com/kuxiaole/MineKUmusic)。问题反馈：[Issues](https://github.com/kuxiaole/MineKUmusic/issues)。

玩家安装、点歌、收费、投票和常见问题见 [玩家使用说明](全服音乐玩家使用说明.md)。

## 1.7.2：26.1.2 全服音乐适配

- 启用上游 26.1.2 Fabric / NeoForge 构建，复用 MineKU 的全服通信、费用确认、投票、扫码登录和个人播放恢复。
- 补齐中文输入生命周期、品牌与作者信息，并按 26.1.2 的 Gui 渲染入口验证 HUD 和节拍动画。
- Paper / Folia 插件最低版本调整为 26.1.2，发行插件使用 Folia API `26.1.2.build.8-stable` 编译；继续交付 26.2 / 26.3 客户端和 Velocity 插件。
- 包含 1.7.1 的歌词翻译、配色、节拍动画和渲染更新；上游基线仍为 `6d902b8`。

完整范围与验证结果见 [1.7.2 更新说明](docs/CHANGELOG-MineKUmusic-1.7.2.md)。

## 1.7.1 上游同步更新

同步上游 DangoMusic `6d902b84d6d4b8241d79024723cb0e9e7847c685`（2026-10-29，上游 1.7.0），增量基线为 `42397a1c8b78138175cfba4781d35cc9ff8aca94`。MineKUmusic 使用独立版本号 **1.7.1**。

- 接入 26.2 / 26.3 的 Vulkan 离屏绘制、HUD 暗角修复和渲染优化。
- 个人音乐新增歌词翻译、歌词与翻译自定义颜色、仅夜间发光，以及按节拍摇动的生物动画。
- 同步列表 / 封面墙切换动画、按键映射修复、中日韩字体兼容和音量条修复。
- 保留全服队列、收费确认、投票、管理员扫码登录、中文输入和 MineKU 署名。全服频道继续由 Paper / Folia / Velocity 管理。

已有 1.7.0 服务器插件可继续配合 1.7.1 客户端使用。上游 Firebase 成员控制不接入全服频道；1.7.1 当时仅保留 26.1.2 上游源码，已在 1.7.2 启用对应发行目标。详细范围和验证限制见 [1.7.1 更新说明](docs/CHANGELOG-MineKUmusic-1.7.1.md)。

## 1.7.0 多音源更新

同步 DangoMusic 提交 `42397a1c8b78138175cfba4781d35cc9ff8aca94` 的个人音乐改进：网易云、QQ 音乐、酷狗音源，扫码登录、歌单、推荐、逐字歌词，以及界面动画、主菜单播放器和渲染调整。保留 MineKU Music 品牌、小乐署名、中文界面和本项目的全服音乐流程。

- 个人音乐可选择网易云、QQ 音乐或酷狗；全服音乐支持网易云和 QQ 音乐，搜索音源按服务器或群组记忆。
- 管理员在游戏中执行 `/music login netease` 或 `/music login qq`，客户端弹出对应音源的二维码，用对应手机应用扫码并确认。
- 全服账号分别保存在 `netease-cookie.json` 和 `qq-cookie.json`，均使用浏览器 Cookie JSON 数组；旧 `cookie.json` 仅在网易云新文件不存在时复制迁移，保留旧文件。
- 群组服账号和队列由 Velocity 管理，Paper / Folia 经济桥接后端只负责支付；搜索和扫码不会扣点歌费用。
- 保留更新提醒，只查询 MineKUmusic 自己的 GitHub 正式发布，不自动下载或安装。

扫码、QQ 全服点歌和音源选择需要客户端与管理频道的插件同时升级至 1.7.0。保留配置、队列、交易记录和 `server-id`，替换 JAR 后完整重启。

## 来源与署名

| 项目 | 作者 | 在 MineKUmusic 中的作用 |
| --- | --- | --- |
| [DangoMusic](https://github.com/Sorihiko/DangoMusic) | Sorihiko | 直接代码基础：客户端播放器、音频播放、歌词、HUD、界面及加载器适配 |
| [AllMusic](https://github.com/Coloryr/AllMusic) | Coloryr | 服务端实现参考：队列、点歌、服务端管理播放状态、客户端播放和 Velocity 代理接入 |

DangoMusic 原有界面代码还注明排版参考 MuoniumPlayer，此项上游设计署名一并保留。完整来源、区别与开源组件见 [CREDITS.md](CREDITS.md)。客户端的“设置 → 关于”也提供来源说明和项目地址。

## 安装

| 组件 | 文件 | 安装位置 |
| --- | --- | --- |
| 26.3 Fabric 客户端 | `MineKUmusic-1.7.2+26.3-fabric.jar` | 26.3 客户端 `mods`，同时安装对应 Fabric API |
| 26.3 NeoForge 客户端 | `MineKUmusic-1.7.2+26.3-neoforge.jar` | 26.3 客户端 `mods` |
| 26.2 Fabric 客户端 | `MineKUmusic-1.7.2+26.2-fabric.jar` | 26.2 客户端 `mods`，同时安装对应 Fabric API |
| 26.2 NeoForge 客户端 | `MineKUmusic-1.7.2+26.2-neoforge.jar` | 26.2 客户端 `mods` |
| 26.1.2 Fabric 客户端 | `MineKUmusic-1.7.2+26.1.2-fabric.jar` | 26.1.2 客户端 `mods`，同时安装对应 Fabric API |
| 26.1.2 NeoForge 客户端 | `MineKUmusic-1.7.2+26.1.2-neoforge.jar` | 26.1.2 客户端 `mods` |
| 独立 Paper / Folia 插件 / 群组服经济桥接 | `MineKUmusic-server-1.7.2.jar` | Paper / Folia 26.1.2 / 26.2 / 26.3 的 `plugins`，群组服收费时使用经济桥接模式 |
| Velocity 群组服插件 | `MineKUmusic-server-1.7.2-velocity.jar` | Velocity `plugins`，统一管理全服队列 |

请替换旧版客户端和对应插件 JAR，避免新旧版同时加载。部署版本、Cookie 配置、权限和升级兼容说明见 [SERVER-MUSIC.md](SERVER-MUSIC.md)。

1.6.10 接入 26.3 Fabric / NeoForge 的全服频道通信，保留 26.2 客户端；Folia 默认编译 API 更新为 `26.3.build.5-alpha`，同一个插件继续以 26.2 为最低 API 版本。26.3 沿用点歌收费、聊天点击加入、个人播放恢复和 Velocity 群组服功能。1.6.10 当时的播放器与歌词仅支持 OpenGL；1.7.1 新增 Vulkan 离屏绘制。

1.6.9 新增点歌成功后的全服聊天公告，显示点歌人、歌曲和可点击的“加入全服音乐”按钮。支持独立 Paper / Folia 与 Velocity 群组服；点击加入免费，并沿用个人播放保存与退出恢复流程。聊天按钮加入需要客户端和管理频道的插件同时更新至 1.6.9，旧客户端仍可从“全服音乐”页面手动加入。

1.6.8 修复 Java 25 的界面 C2 编译兼容规则加载：改用游戏工作目录中的 ASCII 临时文件名，避免 Windows 绝对路径的编码和空格问题；确认 JVM 接受全部规则后才记录成功，并在加载完成后清理临时文件。仅需更新客户端，已部署的 1.6.6 / 1.6.7 插件可继续使用。

1.6.7 修复中文界面的按钮翻译遗漏：点歌确认与切回个人播放弹窗统一显示“取消”，全服点歌页显示“搜索”，同时统一设置和登录提示的中文用语。此次界面修复只需更新客户端，已部署的 1.6.6 插件可继续使用。

## 使用

- 按 `M` 打开播放器，侧栏显示 `MineKU Music`；个人音乐保留原有使用方式。
- 在“全服音乐”中自行加入频道，搜索点歌、查看队列、撤回自己的待播歌曲和投票切歌。
- 有人成功点歌时，所有在线玩家都会收到聊天公告，可点击“加入全服音乐”或输入 `/music join` 免费收听；收到公告不会自动加入。群组服由 Velocity 统一公告。
- 聊天中输入 `/music search <关键词>` 可查看彩色搜索结果，每页 10 首，支持点击点歌、上一页、下一页与悬停查看完整歌曲信息。搜索和翻页免费，点击点歌后仍在客户端确认费用。
- 全服点歌先弹窗确认费用；可通过 Vault 对接经济插件，仅点歌收费，搜索、收听、投票均免费。收费默认关闭，启用后默认每首 10 KU币，金额和币名可以修改。
- 切歌需要当前频道成员人数的 15%，向上取整且至少 1 票；聊天支持点击投票，旧投票消息绑定原歌曲。
- 暂停只暂停本人收听；退出频道恢复个人播放。群组切服由 Velocity 维持同一频道。
- “设置 → 关于”注明“本版本由小乐开发”，首先列出本项目开源地址 `https://github.com/kuxiaole/MineKUmusic` 并提供复制按钮，再列出基础项目、参考项目、原作者、许可证与项目地址。
- `/minekumusic` 或 `/minekumusic help` 显示彩色中文帮助，品牌标题只显示一次，每条指令提供执行或填入输入框的按钮；也可使用 `/music`、`/kum` 两个别名。Tab 按权限和输入前缀补全指令、队列页码及可操作的待播条目 ID。管理员使用 `/minekumusic reload` 热重载常用配置、聊天语言和配色，保留播放、队列和交易记录；`/minekumusic help reload` 查看重载范围。

26.1.2 使用 OpenGL；26.2 / 26.3 可使用 OpenGL 或 Vulkan；Vulkan 通过离屏 OpenGL 环境绘制，需要驱动支持创建该环境，3D 歌词会覆盖手部画面。全服频道仅使用音源正常授权返回的完整 MP3；服务器账号与客户端个人登录分开配置。1.7.0 分别读取 `netease-cookie.json` 和 `qq-cookie.json`，旧 `netease-cookie.txt` 不再读取。群组服配置在 Velocity 端；详见 [JSON 账号配置](SERVER-MUSIC.md#配置会员账号仅-json)。

## 聊天语言与配色

插件首次启动生成带中文注释的 `messages.properties`，可修改帮助、搜索、翻页按钮、悬浮说明、投票、点歌公告、加入按钮、交易及错误提示。`request.announcement` 和 `join.*` 控制点歌公告与加入提示；`color.*` 统一调整主题色，支持英文颜色名和 `#RRGGBB`；也可在单条文案中使用颜色标签。修改后执行 `/minekumusic reload`，错误配置会保留原运行设置。

群组服的全服消息在 Velocity 端配置，Paper / Folia 桥接后端的本地提示分别配置。示例见 [messages.properties.example](messages.properties.example)，完整说明见 [语言与配色客制化](SERVER-MUSIC.md#聊天语言与配色客制化)。客户端播放器继续保留自身语言切换。

## 点歌收费

在独立 Paper / Folia 或 Velocity 的 `config.properties` 中配置：

```properties
request-fee-enabled=true
request-fee-amount=10
currency-name=KU币
```

独立 Paper / Folia 服需要 Vault 兼容接口及经济插件。群组服还需要在各 Paper / Folia 后端安装本项目的服务器插件，设置 `economy-bridge-only=true`，并在代理和后端填写相同的 `economy-bridge-secret`；后端经济插件需共用余额数据。币名是显示文字，不会创建新的货币账户。详见 [收费部署与交易恢复](SERVER-MUSIC.md#点歌收费)。

1.6.6 改为检测实际注册的 Vault Economy 服务，补齐 ServiceIO / XConomy 加载顺序，并区分经济不可用、桥接超时和后端无连接。已有扣款记录在经济接口暂时缺失时仍可核对。收费群组服升级时请同时替换代理与后端插件并完整重启，保留交易文件；具体排查见部署文档。

## 构建

在 `modern26` 目录使用 Java 25：

```powershell
.\gradlew.bat :26.1.2-fabric:build :26.1.2-neoforge:build
.\gradlew.bat :26.2-fabric:build :26.2-neoforge:build
.\gradlew.bat :26.3-fabric:build :26.3-neoforge:build
.\gradlew.bat -p ..\common :sources:test :core:test
.\gradlew.bat -p ..\server build
```

Linux / macOS 在同一目录使用：

```sh
./gradlew :26.1.2-fabric:build :26.1.2-neoforge:build
./gradlew :26.2-fabric:build :26.2-neoforge:build
./gradlew :26.3-fabric:build :26.3-neoforge:build
./gradlew -p ../common :sources:test :core:test
./gradlew -p ../server build
```

客户端输出在 `modern26/versions/<游戏版本>-<加载器>/build/libs`，插件输出在 `server/build/libs`。名称带 `dev` 的 JAR 是中间产物。

## 兼容与许可证

项目、插件名称和发行文件使用 `MineKUmusic`，mod 列表与客户端界面显示 `MineKU Music`。为兼容已有客户端设置、资源和权限配置，内部 mod ID `dangomusic`、`dangomusic:channel` 协议、`dangomusic.*` 权限及原代码包名继续保留。Velocity 的内部插件 ID 保持小写 `minekumusic`；主指令为 `/minekumusic`，别名为 `/music` 和 `/kum`，不再注册 `/dgm`。

采用上游的 [GPL-3.0](LICENSE)，保留原作者署名与第三方许可证。`CREDITS.md` 随所有客户端和插件 JAR 及发行包提供；客户端第三方许可证还保存在 JAR 的 `META-INF/licenses/`。
