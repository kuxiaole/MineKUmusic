# 项目来源与署名

`MineKUmusic`（客户端显示为 `MineKU Music`）是基于 DangoMusic 修改的版本，**本版本由小乐开发**。产品改名保留了上游作者署名、源代码包名和许可证；以下区分本版本开发者、直接代码基础、实现参考与上游设计参考。

## 本版本开发者

- 开发者：**小乐**。
- 负责本版本的全服音乐功能、Folia / Velocity 插件接入、客户端调整及 MineKUmusic 品牌版本。
- 此署名指本修改版本的开发工作；DangoMusic 的原作者为 Sorihiko，AllMusic 的原作者为 Coloryr。

## DangoMusic：直接代码基础

- 作者：**Sorihiko**。
- 项目：[Sorihiko / DangoMusic](https://github.com/Sorihiko/DangoMusic)。
- 许可证：GPL-3.0，完整文本保留在本项目的 `LICENSE`。
- 本项目以用户提供的 DangoMusic 源码为基础，继承客户端播放器、网易云个人音乐、音频解码与播放、歌词、HUD、字体、界面及加载器适配。
- 本分支在其基础上加入全服音乐频道、独立 Folia / Velocity 服务端、订阅与个人播放恢复、点歌队列、投票和同步，并修改了品牌名称及设置中的关于页。
- 1.6.0 加入 Vault 点歌收费、客户端费用确认、Velocity 后端经济桥接与交易恢复。
- 1.6.1 统一插件指令帮助和 Tab 补全，支持保留频道及交易状态的配置热重载，并补齐逐项中文配置说明。
- 1.6.2 将主指令统一为 `/minekumusic`，提供 `/music` 和 `/kum` 两个别名，移除 `/dgm`，同步帮助、聊天点击指令和配置文档。
- 1.6.3 参考 AllMusic 的聊天交互，加入彩色搜索结果、点击点歌、翻页和帮助按钮；帮助只显示一次品牌标题，点歌继续使用本项目的费用确认流程。

- 1.6.4 将插件聊天文案、帮助、搜索按钮、悬浮说明、投票和业务提示整理为带中文注释的可自定义语言文件，加入统一主题色与配置、语言同时热重载。客户端通信和收费确认流程保持兼容。

- 1.6.5 将服务端账号配置统一为 AllMusic / 浏览器导出的 `cookie.json` 数组，移除 TXT 读取路径，修复导出值含逗号时被错误拒绝的问题，保留账号播放权限检查。

## AllMusic：服务端实现参考

- 作者：**Coloryr**。
- 项目：[Coloryr / AllMusic](https://github.com/Coloryr/AllMusic)。
- 参考仓库许可证：GPL-3.0。
- 参考其“服务端管理队列和播放状态，客户端实际播放音频”的实现方式，以及点歌和 Velocity 群组服接入设计。服务端兼容其 `cookie.json` 配置格式。
- 聊天交互参考其 `CommandHelp.java`、`MusicSearch.java` 及消息配色：命令填入或执行按钮、逐项歌曲选择、上一页和下一页。本项目在共享服务端核心中实现对应组件，沿用自己的权限、歌曲 ID、费用确认和聊天搜索生命周期。
- MineKUmusic 的队列核心、客户端通信与插件适配位于本项目中，使用 `dangomusic:channel` 协议；部署时使用配套 MineKU Music 客户端，不兼容 AllMusic 客户端协议。

## MuoniumPlayer：上游设计参考

DangoMusic 原有 `MusicScreen.kt` 注释注明播放器排版参考 MuoniumPlayer。本项目沿用该界面基础，因此保留这项上游设计署名。此处记录的是 DangoMusic 原有的设计来源，不将 MuoniumPlayer 列为本轮直接整合的代码项目。

## 开源组件

| 组件 | 用途 | 许可证 |
| --- | --- | --- |
| Inter | 客户端字体 | SIL Open Font License 1.1 |
| JLayer | MP3 解码 | LGPL-2.1 |
| ZXing | 登录二维码 | Apache-2.0 |
| Kotlin 标准库 | 客户端运行时 | Apache-2.0 |
| Gson | JSON 数据处理 | Apache-2.0 |

保留已有第三方声明。客户端 JAR 中的 `META-INF/licenses/` 保存上游已附带的许可证文本；所有客户端和插件 JAR 均包含本文件及项目 `LICENSE`。Fabric、NeoForge、Folia 和 Velocity 是运行平台，各自项目与许可证归其维护者。

收费功能通过 [MilkBowl / VaultAPI](https://github.com/MilkBowl/VaultAPI) 的经济接口与服务器经济插件交互。VaultAPI 使用 LGPL-3.0-or-later，仅作为编译期依赖，接口和经济实现由服务器提供，不打包进本项目 JAR。

## 品牌与兼容标识

项目、插件和发行文件名称为 `MineKUmusic`，客户端内显示为 `MineKU Music`。`me.sorihiko.dango` 代码包、内部 mod ID、资源路径、协议及权限节点继续保留原名称，用于来源追溯和已有安装的兼容，并不改变上述署名归属。Velocity 内部插件 ID 和主指令使用小写 `minekumusic`，命令别名为 `/music` 和 `/kum`。
