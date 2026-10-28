# MineKUmusic

本版本由 **小乐** 开发，基于 **Sorihiko / DangoMusic** 修改，提供个人音乐和自愿加入的全服音乐频道；服务端队列与 Velocity 群组服实现参考 **Coloryr / AllMusic**。项目、插件和发行文件名称为 **MineKUmusic**，客户端内显示为 **MineKU Music**。

本分支交付 Minecraft **26.2 Fabric / NeoForge 客户端**、**Folia 插件**和 **Velocity 插件**，使用 Java 25。上游其他版本的源码仍保留在项目中，不属于本分支已验证的交付范围。

源码仓库：[kuxiaole / MineKUmusic](https://github.com/kuxiaole/MineKUmusic)。问题反馈：[Issues](https://github.com/kuxiaole/MineKUmusic/issues)。

## 来源与署名

| 项目 | 作者 | 在 MineKUmusic 中的作用 |
| --- | --- | --- |
| [DangoMusic](https://github.com/Sorihiko/DangoMusic) | Sorihiko | 直接代码基础：客户端播放器、音频播放、歌词、HUD、界面及加载器适配 |
| [AllMusic](https://github.com/Coloryr/AllMusic) | Coloryr | 服务端实现参考：队列、点歌、服务端管理播放状态、客户端播放和 Velocity 代理接入 |

DangoMusic 原有界面代码还注明排版参考 MuoniumPlayer，此项上游设计署名一并保留。完整来源、区别与开源组件见 [CREDITS.md](CREDITS.md)。客户端的“设置 → 关于”也提供来源说明和项目地址。

## 安装

| 组件 | 文件 | 安装位置 |
| --- | --- | --- |
| Fabric 客户端 | `MineKUmusic-1.6.6+26.2-fabric.jar` | 客户端 `mods`，同时安装对应 Fabric API |
| NeoForge 客户端 | `MineKUmusic-1.6.6+26.2-neoforge.jar` | 客户端 `mods` |
| 独立 Folia 插件 / 群组服经济桥接 | `MineKUmusic-server-1.6.6-folia.jar` | Folia `plugins`，群组服收费时使用经济桥接模式 |
| Velocity 群组服插件 | `MineKUmusic-server-1.6.6-velocity.jar` | Velocity `plugins`，统一管理全服队列 |

请替换旧版客户端和对应插件 JAR，避免新旧版同时加载。部署版本、Cookie 配置、权限和升级兼容说明见 [SERVER-MUSIC.md](SERVER-MUSIC.md)。

## 使用

- 按 `M` 打开播放器，侧栏显示 `MineKU Music`；个人音乐保留原有使用方式。
- 在“全服音乐”中自行加入频道，搜索点歌、查看队列、撤回自己的待播歌曲和投票切歌。
- 聊天中输入 `/music search <关键词>` 可查看彩色搜索结果，每页 10 首，支持点击点歌、上一页、下一页与悬停查看完整歌曲信息。搜索和翻页免费，点击点歌后仍在客户端确认费用。
- 全服点歌先弹窗确认费用；可通过 Vault 对接经济插件，仅点歌收费，搜索、收听、投票均免费。收费默认关闭，启用后默认每首 10 KU币，金额和币名可以修改。
- 切歌需要当前频道成员人数的 15%，向上取整且至少 1 票；聊天支持点击投票，旧投票消息绑定原歌曲。
- 暂停只暂停本人收听；退出频道恢复个人播放。群组切服由 Velocity 维持同一频道。
- “设置 → 关于”注明“本版本由小乐开发”，首先列出本项目开源地址 `https://github.com/kuxiaole/MineKUmusic` 并提供复制按钮，再列出基础项目、参考项目、原作者、许可证与项目地址。
- `/minekumusic` 或 `/minekumusic help` 显示彩色中文帮助，品牌标题只显示一次，每条指令提供执行或填入输入框的按钮；也可使用 `/music`、`/kum` 两个别名。Tab 按权限和输入前缀补全指令、队列页码及可操作的待播条目 ID。管理员使用 `/minekumusic reload` 热重载常用配置、聊天语言和配色，保留播放、队列和交易记录；`/minekumusic help reload` 查看重载范围。

26.2 的歌词与播放器界面需要 OpenGL。音源仅使用账号正常授权返回的完整播放地址；服务器账号 Cookie 与客户端个人登录分开配置。1.6.5 起仅读取 AllMusic / 浏览器导出的 `cookie.json` 数组文件，旧 `netease-cookie.txt` 不再读取。群组服配置在 Velocity 端；详见 [JSON 账号配置](SERVER-MUSIC.md#配置会员账号仅-json)。

## 聊天语言与配色

插件首次启动生成带中文注释的 `messages.properties`，可修改帮助、搜索、翻页按钮、悬浮说明、投票、交易及错误提示。`color.*` 统一调整主题色，支持英文颜色名和 `#RRGGBB`；也可在单条文案中使用颜色标签。修改后执行 `/minekumusic reload`，错误配置会保留原运行设置。

群组服的全服消息在 Velocity 端配置，Folia 桥接后端的本地提示分别配置。示例见 [messages.properties.example](messages.properties.example)，完整说明见 [语言与配色客制化](SERVER-MUSIC.md#聊天语言与配色客制化)。客户端播放器继续保留自身语言切换。

## 点歌收费

在独立 Folia 或 Velocity 的 `config.properties` 中配置：

```properties
request-fee-enabled=true
request-fee-amount=10
currency-name=KU币
```

独立服需要可用于 Folia 的 Vault 接口插件及经济插件。群组服还需要在各 Folia 后端安装本项目的 Folia 插件，设置 `economy-bridge-only=true`，并在代理和后端填写相同的 `economy-bridge-secret`；后端经济插件需共用余额数据。币名是显示文字，不会创建新的货币账户。详见 [收费部署与交易恢复](SERVER-MUSIC.md#点歌收费)。

1.6.6 改为检测实际注册的 Vault Economy 服务，补齐 ServiceIO / XConomy 加载顺序，并区分经济不可用、桥接超时和后端无连接。已有扣款记录在经济接口暂时缺失时仍可核对。收费群组服升级时请同时替换代理与后端插件并完整重启，保留交易文件；具体排查见部署文档。

## 构建

在 `modern26` 目录使用 Java 25：

```powershell
.\gradlew.bat :26.2-fabric:build :26.2-neoforge:build
.\gradlew.bat -p ..\common :core:test
.\gradlew.bat -p ..\server build
```

Linux / macOS 在同一目录使用：

```sh
./gradlew :26.2-fabric:build :26.2-neoforge:build
./gradlew -p ../common :core:test
./gradlew -p ../server build
```

客户端输出在 `modern26/versions/26.2-*/build/libs`，插件输出在 `server/build/libs`。名称带 `dev` 的 JAR 是中间产物。

## 兼容与许可证

项目、插件名称和发行文件使用 `MineKUmusic`，mod 列表与客户端界面显示 `MineKU Music`。为兼容已有客户端设置、资源和权限配置，内部 mod ID `dangomusic`、`dangomusic:channel` 协议、`dangomusic.*` 权限及原代码包名继续保留。Velocity 的内部插件 ID 保持小写 `minekumusic`；主指令为 `/minekumusic`，别名为 `/music` 和 `/kum`，不再注册 `/dgm`。

采用上游的 [GPL-3.0](LICENSE)，保留原作者署名与第三方许可证。`CREDITS.md` 随四种 JAR 和发行包提供；客户端第三方许可证还保存在 JAR 的 `META-INF/licenses/`。
