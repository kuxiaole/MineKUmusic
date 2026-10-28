# MineKUmusic 全服音乐

Minecraft 26.2 的 Fabric / NeoForge 客户端与 Folia / Velocity 插件。所有组件使用 Java 25。项目、插件和发行文件名称为 `MineKUmusic`，客户端内显示为 `MineKU Music`。需要替换原 DangoMusic，不要同时安装两个相同 mod ID 的版本。

本版本由 **小乐** 开发，基于 [Sorihiko / DangoMusic](https://github.com/Sorihiko/DangoMusic) 修改，服务端队列、点歌与代理接入参考 [Coloryr / AllMusic](https://github.com/Coloryr/AllMusic)。原有界面的 MuoniumPlayer 设计署名与第三方许可证也予以保留，详见 [CREDITS.md](CREDITS.md) 和客户端“设置 → 关于”。

本次构建固定使用 Folia API `26.2.build.7-beta`、Velocity API `4.3.0`；Fabric Loader `0.19.5`、Fabric API `0.161.0+26.2`、NeoForge `26.2.0.93`。Fabric 客户端还需要安装对应的 Fabric API。

## 安装

### 独立 Folia

1. 将 `MineKUmusic-server-1.6.5-folia.jar` 放入 Folia 26.2 的 `plugins` 目录。
2. 全新安装首次启动后，在 `plugins/MineKUmusic/config.properties` 调整频道名称和点歌规则，使用 `/minekumusic reload` 生效。升级安装按下文说明读取旧目录。
3. 玩家安装对应加载器的 26.2 客户端，在播放器左侧打开“全服音乐”，自行加入频道。

### Velocity 群组服

1. 将 `MineKUmusic-server-1.6.5-velocity.jar` 放入 Velocity 4.3.0 的 `plugins` 目录。
2. 使用代理生成的插件数据目录中的 `config.properties` 配置全服频道。
3. 免费模式下后端不用安装音乐插件。开启收费时，后端安装 Folia 插件并设为经济桥接模式，步骤见下文。不要同时部署独立 Folia 音乐实例；队列、权限和播放时间全部由代理管理。
4. 保持客户端到同一代理的连接即可跨后端续播。切换到另一个代理地址属于重新连接。

不需要额外开放音乐插件端口，不依赖 Firebase、Redis 或 AllMusic。玩家没有安装 MineKU Music 也可以正常进入服务器，但无法收听频道。

### 从旧版升级

- 关闭游戏及插件所在服务器，替换旧客户端和插件 JAR，不要同时保留新旧插件。客户端文件为 `MineKUmusic-1.6.5+26.2-fabric.jar` 或 `MineKUmusic-1.6.5+26.2-neoforge.jar`。1.6.0～1.6.4 客户端可继续配合 1.6.5 插件使用；从更早版本升级收费功能时需要同时更新客户端和插件，旧客户端在收费服务器会收到升级提示，不会直接扣款。
- 两种插件显示名称均为 `MineKUmusic`。Folia 全新安装使用 `plugins/MineKUmusic`；Velocity 内部插件 ID 保持小写 `minekumusic`，全新安装使用 `plugins/minekumusic`。
- 若新目录没有 `config.properties`，Folia 按 `plugins/minekumusic`、`plugins/DangoMusic` 的顺序查找旧配置，Velocity 则查找 `plugins/dangomusic-server`；找到后继续使用对应旧目录。Cookie、队列、频道名称和 `server-id` 会一并保留，启动日志会显示实际读取目录。Windows 上仅大小写不同的目录通常视为同一目录。
- 若新目录已有 `config.properties`，则以新目录为准。需要迁移时，停服后完整复制旧目录内容到新目录，保留 `server-id`。
- 客户端 mod ID、`config/dangomusic`、`dangomusic:channel` 和 `dangomusic.*` 权限节点继续沿用。主指令统一为 `/minekumusic`，只提供 `/music` 和 `/kum` 两个别名，不再注册 `/dgm`；旧的聊天投票消息需等待新版插件重新发送。

## 音源

点歌费用由游戏内经济插件提供，与网易云账号是否具备歌曲播放权限相互独立。

服务端使用网易云的搜索、歌曲详情、歌词和播放地址接口，支持独立的网易云账号 Cookie。配置有会员播放权限的账号后，会携带该账号的 Cookie 请求会员歌曲的完整播放地址；是否可播仍以账号实际权限和音源返回为准。未配置 Cookie 时使用匿名模式。

只接受接口正常返回的完整 MP3；无可用地址、仅试听或其他编码格式会明确失败。Cookie 不发送给玩家，不写入队列或协议消息，也不会被加入音频下载请求；客户端直接使用音源返回的播放地址下载音频。玩家自己的网易云登录仍只用于个人音乐。

### 配置会员账号（仅 JSON）

从 **1.6.5** 开始，服务端只读取 AllMusic / 浏览器导出的 **`cookie.json` 对象数组**，不再读取 `netease-cookie.txt` 或分号分隔的 Cookie 文本。

1. 替换插件 JAR 并完整重启一次。首次启动在实际插件数据目录生成内容为 `[]` 的 `cookie.json`；已有文件不会被覆盖。全新独立 Folia 的路径为 `plugins/MineKUmusic/cookie.json`，Velocity 为 `plugins/minekumusic/cookie.json`。旧版升级可能继续使用旧目录，以启动日志显示的位置为准。
2. 将导出的 JSON 文件原样保存为上述 `cookie.json`。最外层必须是数组 `[...]`，每项有字符串类型的 `name` 和 `value`；保留 `domain`、`path` 以及浏览器附带的其他字段即可，无需手动拼接为 `name=value`。
3. 群组服把文件放在 **Velocity 插件数据目录**，音乐音源请求由代理处理；Folia 经济桥接后端不需要配置账号。
4. 保存后下一次搜索、点歌或播放地址刷新会重新读取，无需再重启或执行 reload。已经播放中的歌曲继续使用现有地址。

以下是结构示例，所有登录值均为占位文本，不是真实凭据：

```json
[
  {
    "domain": ".music.163.com",
    "hostOnly": false,
    "httpOnly": true,
    "name": "MUSIC_U",
    "path": "/",
    "sameSite": "no_restriction",
    "secure": true,
    "session": true,
    "storeId": "0",
    "value": "YOUR_SESSION_VALUE"
  }
]
```

JSON 使用 UTF-8，可带 BOM。`name`、`value` 必须为字符串；`domain`、`path` 可省略，分别默认网易云域名和 `/`。浏览器导出的 `expirationDate`、`hostOnly`、`httpOnly`、`sameSite`、`secure`、`session`、`storeId` 等附加字段可原样保留。插件保留值里的逗号、等号等原始内容，不再将 JSON 转换后按 TXT 格式重新解析。

文件不存在、为空或为 `[]` 时匿名访问。登录失效、账号无播放权限或仅返回试听时仍明确返回失败。旧版 `netease-cookie.txt` 即使存在也不读取、不覆盖 JSON；从 TXT 配置升级时须重新导出 JSON，不能只修改扩展名。发行包里的 `cookie.json.example` 是空数组模板，不包含账号。真实 Cookie 只放在服务器插件数据目录，不放入公开仓库或分发包。

## 使用

- 初次默认不加入，加入选择按 `server-id` 保存在客户端 `config/dangomusic/server-music.properties`。
- 加入时保存个人播放队列和状态，退出或真正断线时恢复。点个人歌曲会先弹出切换确认。
- 全服页支持搜索点歌、队列分页和撤回自己的待播歌曲；原有搜索和歌单旁的 `+` 也可点到全服。
- 全服页、歌单中的全服点歌按钮及 `/minekumusic request` 都先显示确认弹窗，列出本次费用和币名。确认有效期 60 秒；取消或过期不扣款。命令点歌会在离开聊天界面后打开播放器确认。
- 暂停只暂停本人收听，恢复时跟上全服进度；下一首按钮和快捷键用于投票切歌。个人拖动、上一首、随机和循环在频道模式不可用。
- 队列先进先出。默认每人最多 3 首待播、成功点歌间隔 30 秒；当前歌曲不占待播名额。
- 切歌门槛为当前已加入频道人数的 15%，向上取整且至少 1 票。例如 1–6 人需 1 票、7–13 人需 2 票、14–20 人需 3 票；不计入仅在线但未加入频道的玩家。每首歌每人一票，退出移除其投票，换歌清空投票。
- 有新投票且尚未达标时，频道成员会在聊天中收到票数和“点击投票切歌”按钮；达到门槛立即切歌并公告。退出或离线使门槛降低时也会重新判断。旧聊天按钮绑定原歌曲，换歌后点击不会误投下一首。
- 26.2 客户端打开播放器时启用原版文本输入，关闭后释放，修复搜索时中文输入法被强制关闭的问题。“全服队列 / 搜索点歌”支持选中背景滑动、悬停渐变和内容交叉淡入淡出；设置里的“减少频道动画”可关闭滑动。
- 无人收听时继续播放，空队列保持安静。离线不会删除已经点入的歌曲。

## 点歌收费

只对成功提交的全服点歌收取固定费用。搜索、查看队列、加入收听、投票、个人播放及管理员操作不收费。收费默认关闭；设置 `request-fee-enabled=true` 后启用，默认每首 10 KU币。金额支持最多两位小数，范围为 0 到 1000000，且不能超过经济插件支持的小数精度。币名支持 1 至 32 个字符，只用于界面显示，不会新建一套余额。

### 服务端决定扣款与入队

客户端确认弹窗仅展示费用并发送确认编号。玩家身份取当前连接，曲目、金额、有效期和确认所属玩家保存在服务端；客户端传入的金额、余额、付款状态不作为计费依据。界面点歌和命令点歌共用这一流程，启用收费后，旧版直接点歌入口会提示升级。只有服务端取得成功扣款结果才允许收费点歌入队，缺少 Vault、桥接不可用或付款结果不确定时均不会转成免费点歌。

群组服的付款通道由 Velocity 拦截，只接收原后端签名验证通过、且与待处理交易完全匹配的回执；该通道不会转发给玩家。客户端不含桥接密钥，也没有标记付款成功的权限。开源代码不提供免单能力，但部署时仍须保护服务端密钥、经济插件和管理权限。

### 独立 Folia

1. 安装适用于当前 Folia 的 Vault 接口插件，以及已注册 Vault Economy 服务的经济插件。
2. 在 MineKUmusic 实际数据目录的 `config.properties` 中设置：

```properties
request-fee-enabled=true
request-fee-amount=10
currency-name=KU币
economy-bridge-only=false
```

3. 重启 Folia。扣款在该玩家的 Folia 实体调度器中执行，交易文件写入异步执行。经济插件自身也必须支持 Folia。

### Velocity 群组服

1. Velocity 安装 `MineKUmusic-server-1.6.5-velocity.jar`，配置费用、币名和 `economy-bridge-secret`。密钥至少 32 个字符，使用随机生成的值。
2. 在每个允许点歌的 Folia 后端安装 `MineKUmusic-server-1.6.5-folia.jar`、Vault 接口插件和经济插件。后端设置如下：

```properties
economy-bridge-only=true
economy-bridge-secret=
request-fee-enabled=false
```

3. 在上面的空白密钥处填入与代理完全相同的随机值，然后重启代理和后端。经济桥接模式不会创建音乐队列，也不会向客户端提供另一个全服音乐频道；费用只取代理的配置。
4. 后端经济插件必须共用余额存储或已有的群组经济同步方案。本插件不会同步各服务器独立的余额。

代理把付款交给点歌时所在后端处理，后续核对继续查同一个后端的同一笔交易。切服不会把扣款转到另一个后端重新执行。桥接消息带 HMAC 校验；密钥仅存在代理和后端配置文件中，不发送给客户端。

### 计费规则与恢复

- 玩家确认后，服务端先检查权限、频道订阅、队列上限、重复歌曲、冷却及音源可用性，然后保存交易并扣款。上述检查失败、余额不足或明确扣款失败时，不加入队列、不收取费用。
- 费用购买的是一次点歌入队。扣款确认成功后，即使玩家已经离线，歌曲仍会入队；撤回、投票切歌、管理员删除，以及之后播放时音源变化导致的失败不自动退款。
- `queue.json` 保存尚待核对的点歌，原扣款 Folia 后端的 `payments.json` 保存交易结果。回执延迟或重启后会核对原交易，已完成的扣款不会再次执行；核对期间暂停接受新的点歌，搜索和当前播放仍可继续。不要删除有未完成交易的文件，也不要在核对完成前更换后端名称或密钥。
- 未送达的扣款首次执行期限为两分钟；如果后端确认没有执行过且期限已过，则不再扣款。已记录的结果保留 30 天；有待人工核对的记录持续保留，交易文件最多保留 50000 条，达到上限会停止新的扣款并提示维护。
- Vault 扣款和本插件文件之间没有共同的数据库事务。若进程恰好在扣款执行中退出，插件会标记为待管理员核对，不自动重复扣款。管理员应先查询经济插件交易记录，然后在**原扣款 Folia 后端控制台**执行 `minekumusic payment <交易ID> paid`（确认已经扣款）或 `minekumusic payment <交易ID> cancel`（确认未扣款）。该命令只处理不确定交易，不能覆盖已确定的结果。代理随后会继续入队或结束该请求。

配置示例见仓库根目录的 `config.properties.example`，每项都带中文用途、取值范围和独立服 / 群组服说明。插件生成的 `config.properties` 使用同一份注释模板；旧版升级后启动一次，会自动补齐缺少的选项和中文注释，保留已有配置值，无需删除旧配置。首次将 `server-id` 留空会自动生成，生成后应保留。不要把真实密钥、账号 Cookie、`payments.json` 或运行中的配置文件提交到公开仓库。

## 配置

| 配置 | 默认 | 含义 |
| --- | --- | --- |
| `server-id` | 自动生成 UUID | 服务器或群组的固定标识，保留它以维持玩家选择 |
| `channel-name` | `MineKU Music` | 界面显示的频道名称；已有配置不覆盖 |
| `player-queue-limit` | `3` | 每位玩家的待播上限 |
| `queue-limit` | `100` | 整个待播队列上限 |
| `request-cooldown-seconds` | `30` | 成功点歌之间的最短间隔 |
| `sync-seconds` | `2` | 服务端推送进度的间隔 |
| `request-fee-enabled` | `false` | 是否对全服点歌收费 |
| `request-fee-amount` | `10` | 每首点歌费用；0 表示免费 |
| `currency-name` | `KU币` | 确认弹窗与付款提示中的货币名称 |
| `economy-bridge-only` | `false` | Folia 后端仅提供经济桥接，队列由 Velocity 管理 |
| `economy-bridge-secret` | 空 | 代理与桥接后端共享的随机密钥，至少 32 个字符 |

配置文件使用 UTF-8。常用配置调整后执行 `/minekumusic reload`，频道标识、桥接模式和密钥变更则需完整重启，具体范围见下文；Cookie 文件下一次解析即生效。`queue.json` 自动保存队列和进度，重启时重新解析播放地址并从保存进度继续，不补算停机时间；不保存音频地址、Cookie 和投票。

## 命令与权限

| 命令 | 用途 | 权限 |
| --- | --- | --- |
| `/minekumusic` / `/minekumusic help [子命令]` | 中文帮助，仅列出当前身份和服务器模式可用的指令 | 所有人可查看帮助 |
| `/minekumusic queue [页码]` | 查看当前歌曲和队列条目 ID | `dangomusic.use` |
| `/minekumusic search <关键词>` | 免费搜索，每页 10 首，点击结果点歌或翻页 | `dangomusic.use` |
| `/minekumusic searchpage <页码>` | 查看最近一次聊天搜索的指定页；也可点击翻页按钮 | `dangomusic.use` |
| `/minekumusic request <歌曲ID>` | 点歌，需要已加入频道 | `dangomusic.request` |
| `/minekumusic withdraw <条目ID>` | 撤回自己的待播歌曲 | `dangomusic.use` |
| `/minekumusic vote` | 投票切歌，需要已加入频道 | `dangomusic.vote` |
| `/minekumusic skip` | 直接切歌 | `dangomusic.admin` |
| `/minekumusic pause` / `/minekumusic resume` | 暂停或恢复整个频道 | `dangomusic.admin` |
| `/minekumusic remove <条目ID>` | 删除任意待播歌曲 | `dangomusic.admin` |
| `/minekumusic clear` | 停止当前歌曲并清空队列 | `dangomusic.admin` |
| `/minekumusic reload` | 热重载常用配置，保留队列及交易状态 | `dangomusic.admin` |
| `/minekumusic payment <交易ID> paid` / `cancel` | 原扣款 Folia 后端核对中断交易 | `dangomusic.admin` |

以上命令也可将 `/minekumusic` 替换为 `/music` 或 `/kum`，两者使用相同的权限、Tab 补全和处理逻辑。`/dgm` 已移除。普通功能默认允许，管理权限在 Folia 默认给 OP，在 Velocity 需由代理端权限插件分配。控制台可以管理队列。

Tab 补全按权限、大小写不敏感的前缀及参数位置过滤：`help` 补全可用子命令，`queue` 补全实际页码，`withdraw` 只补全自己的待播条目 ID，管理员的 `remove` 补全全部待播条目 ID，`vote` 可补全当前播放实例，`payment` 的最后一个参数补全 `paid` / `cancel`。搜索关键词和歌曲 ID 需按搜索结果输入，不会误补成在线玩家姓名。经济桥接后端只显示 `help`、`reload`、`payment`；Velocity 不处理后端的交易核对命令。

### 聊天点击界面

- 参考 AllMusic 的聊天交互：紫色品牌标题、金黄色指令和歌曲信息、青色点击按钮。`help` 仅在顶部显示一次品牌，正文不重复附加 `[MineKUmusic]`。`help`、`queue`、`vote` 的按钮直接执行；需要参数或管理频道的指令先填入聊天输入框，由玩家补全并提交。控制台帮助使用纯文字。
- `/music search <关键词>` 每页显示 10 首，歌曲旁的 `[点歌]` 执行对应歌曲 ID 的点歌指令，悬停可查看完整歌名、歌手及歌曲 ID。无点歌权限或控制台身份不会显示点歌按钮。
- `[上一页]`、`[下一页]` 直接翻页，不可用的按钮显示为灰色；`[重新搜索]` 将搜索指令填入输入框。搜索与翻页均免费，点击点歌仍需已加入频道，并在客户端弹窗确认后才会扣款。
- 每位玩家只保留最近一次聊天搜索的关键词和页码，10 分钟无操作后失效，离线时清理。翻页按钮绑定该次搜索，重新搜索后点击旧翻页按钮会提示失效；旧歌曲按钮始终对应当时显示的歌曲 ID。网络请求失败可重试，不改动队列。
- Folia 与 Velocity 使用相同的消息组件；Folia 在玩家实体调度器中一次发送整组消息，网络搜索继续异步执行。客户端播放器内的搜索仍每页 20 首。

### 聊天语言与配色客制化

1.6.4 起，插件的聊天文案保存在实际数据目录的 **`messages.properties`**，与 `config.properties` 放在一起；首次启动自动生成，模板见仓库根目录的 `messages.properties.example`。文件使用 **UTF-8**，每项都有中文用途和占位符说明。可以翻译成其他语言，也可保留中文并改成自己的服务器风格。

- 独立 Folia 修改 `plugins/MineKUmusic/messages.properties`；旧版升级使用启动日志显示的实际数据目录。
- 群组服的帮助、搜索、队列、投票和点歌结果由 **Velocity** 发送，因此修改代理端数据目录的文件，并在代理执行 `/minekumusic reload`。
- Folia 经济桥接后端的本地帮助、交易核对和重载提示使用自己的语言文件；需要统一外观时复制同一份配置到各后端，并分别重载。不会自动跨服覆盖文件。
- 语言文件控制插件聊天展示。客户端播放器仍使用自己的语言切换和文案；音源、经济插件提供的未知错误详情会按原文插入。按钮指令、权限、15% 投票规则和收费确认逻辑不随文字修改而改变。

默认采用紫色品牌、金色重点、黄色正文、青色按钮、灰色说明、深灰禁用项、绿色成功提示和红色错误。以下为可直接修改的示例，替换已有同名项即可，不要追加重复键：

```properties
# 八种主题色支持 Minecraft 英文颜色名或 #RRGGBB；这里示范柔和的紫金青配色。
color.brand=#C084FC
color.heading=#FBBF24
color.primary=#FDE68A
color.action=#67E8F9
color.muted=#A1A1AA
color.disabled=#71717A
color.success=#86EFAC
color.error=#F87171
# 普通提示的前缀；留空可以隐藏，不会给帮助每行添加前缀。
chat.prefix=<brand>[KU音乐] </brand>
# 搜索点歌按钮文字；仍然走服务端报价和客户端费用确认。
search.request=点这首歌
# 可调整占位符顺序，动态值保持为普通文字。
search.heading=<heading>第 {page} 页</heading><muted> · 搜索：</muted><primary>{query}</primary>
# 成功支付后的提示；金额来自实际扣款，币名来自 config.properties。
payment.paid=<success>点歌成功，已支付 {amount} {currency}。</success>
```

| 配置项 | 用途 |
| --- | --- |
| `color.*` | 全局主题色，供 `<brand>`、`<heading>`、`<primary>` 等标签引用 |
| `chat.prefix` / `chat.title` | 普通提示前缀和帮助 / 搜索标题外观 |
| `button.*` | 可用按钮、禁用按钮和分隔符外观 |
| `help.*` / `command.<子命令>.description` / `.arguments` | 帮助内容、说明、参数显示文字与悬浮提示 |
| `search.*` | 搜索结果、完整歌曲悬浮信息、点歌和翻页按钮 |
| `queue.*` / `vote.*` / `admin.*` | 队列、投票及管理员操作通知 |
| `reload.*` / `payment.*` / `audio.*` / `result.*` | 重载、交易、播放及常见业务结果 |

格式采用 MiniMessage 的颜色和基础样式子集：`<red>文字</red>`、`<#C084FC>文字</#C084FC>`、`<bold>粗体</bold>`、`<italic>斜体</italic>`、`<underlined>下划线</underlined>`，`<newline>` 换行。颜色和样式必须闭合；不接受 `&` 颜色码、`reset`、`click` 或 `hover` 标签。悬浮文字使用对应的 `*-hover` 配置，点击动作由程序绑定。

占位符写作 `{query}`、`{page}` 等，只能使用该项中文注释列出的名称，可重排、重复或省略。帮助的 `.arguments` 为普通文本；更改参数提示不会改变真实指令语法。歌曲名、搜索词、玩家名等动态内容不会作为格式解析。币名在 `config.properties` 的 `currency-name` 修改。

保存后执行 **`/minekumusic reload`**（或 `/music reload`、`/kum reload`），无需重启，之后发送的新消息立即使用新设置；聊天历史中的旧消息不会被重绘。颜色、标签、占位符或配置校验失败时，**运行中的配置和语言都保持原样**，提示会指出错误项，修正后重新执行即可。已有配置缺少新键时自动补齐默认值和中文注释，保留自定义值；普通重载保留语言文件中的自写注释和排版，升级补齐键时重新按中文模板整理。

### 配置热重载

1. 保存实际数据目录中的 `config.properties` 和 / 或 `messages.properties`，由管理员或控制台执行 `/minekumusic reload`（控制台省略 `/`）。群组服的频道和费用配置在 Velocity 重载。
2. 可热重载聊天语言、主题颜色、按钮文字、悬浮说明、频道名称、每人及全局队列上限、点歌冷却、同步间隔、收费开关、价格和币名。队列、当前歌曲、播放进度、暂停状态、订阅、投票和交易记录保留；降低队列上限不会删除已入队歌曲。
3. 调整收费开关、价格或币名后，尚未确认的旧报价失效，玩家需重新点歌；已经确认的请求按确认时的金额和币名继续处理，未决扣款仍核对原交易。
4. `server-id`、`economy-bridge-only`、`economy-bridge-secret` 变更必须完整重启。若它们同时被修改，热重载会拒绝整次变更，并指出需重启的配置项；不会部分应用其他修改。密钥变更前应先处理完未决交易，再协调代理和后端。
5. 配置或语言文件格式、颜色、占位符错误时，整次重载失败并继续使用运行中的原配置和语言；修正文件后可以再次重载。连续重复执行时会提示正在处理。重载在异步线程读取文件，Folia 玩家线程不会等待磁盘操作。
6. Folia 经济桥接后端也支持 `help`、Tab 和 `reload`，但它不拥有音乐队列，修改费用应在代理端完成。安装或更新插件 JAR、Vault 及经济插件仍需完整重启，`/minekumusic reload` 不会重新加载 JAR。

## 构建与验证

在 `modern26` 目录使用 Java 25：

```powershell
.\gradlew.bat :26.2-fabric:build :26.2-neoforge:build
.\gradlew.bat -p ..\common :core:test
.\gradlew.bat -p ..\server build
```

客户端产物位于 `modern26/versions/26.2-*/build/libs`，插件产物位于 `server/build/libs`。不要分发名称带 `dev` 的客户端中间产物。

自动化测试使用明确标注的合成曲目和模拟连接，覆盖队列规则、完整歌词分片、多客户端自愿订阅、重复请求、重新握手及重启恢复；新增覆盖 15% 取整边界、成员变动、重复投票、聊天通知接收范围和旧投票失效。客户端测试覆盖首次不自动加入、退出恢复队列、个人播放确认、播放控制路由和投票携带当前播放实例。

游戏内验收仍应在两个实际客户端、独立 Folia 和“Velocity + 两个 Folia 后端”上进行，检查音频输出、歌词、切服连续性、界面布局及缓冲完成后的 750 毫秒同步目标。构建和模拟测试不能替代这些实际验证。

### 本次验证记录

- 1.6.5 共 91 项测试通过（服务端 77 项、客户端 14 项），四种 JAR 构建成功。账号配置只读取 `cookie.json`，覆盖完整浏览器导出字段、UTF-8 BOM、含逗号和等号的值、空值、已有文件保留、旧 TXT 被忽略、JSON 热更新及格式错误提示；本地模拟音源确认导出值原样进入请求头。用户提供的 JSON 已完成本地解析和请求头构建检查，未发起账号网络请求；未进行实际歌曲播放联调。两种插件已核对包含空数组 JSON 模板，且不再打包 TXT 模板。
- 1.6.4 服务端 75 项测试通过；客户端 14 项测试检查通过（客户端代码未变，复用 Gradle 已通过结果），共 89 项，四种 JAR 构建成功。新增验证中文注释和自定义值保留、缺项补齐、主题色、按钮标签及悬浮文字、动态占位符、独立服务器主题、格式错误保留文件、配置与语言一起重载、任一校验失败时保留两份旧运行设置。收费确认和搜索点击原有测试继续通过；已核对两种插件内的语言模板且未重复打包 Adventure。尚未在实际 Folia / Velocity 上验证配色观感与鼠标交互。
- 1.6.3 共 82 项测试通过（服务端 68 项、客户端 14 项），四种 JAR 构建成功。新增检查彩色帮助与点击动作、10 首分页及音源请求偏移、旧翻页失效、离线清理、空结果、失败重试和权限过滤；收费测试通过实际生成的聊天按钮指令验证先确认再扣款。尚未在实际 Folia / Velocity 服务器上检查聊天显示和鼠标操作。
- 1.6.2 将主指令改为 `/minekumusic`，仅注册 `/music`、`/kum` 两个别名；帮助、配置注释、搜索提示和聊天投票点击均使用主指令。服务端 62 项测试重新运行通过，客户端 14 项测试检查通过（代码未变，复用 Gradle 已通过结果），四个 JAR 均构建成功；已核对 Folia 成品命令描述及 Velocity 成品的注册与注销名称。尚未在实际服务器上验证别名与 Tab 补全。
- 1.6.1 版本通过 76 项自动化测试（服务端 62 项、客户端 14 项）；本次新增指令帮助、补全规则、重载保留播放及队列、降低上限后恢复队列、无效配置回退、需重启选项及重载期间原价交易延续的验证。此前已覆盖中文配置注释、特殊字符和原配置值保留。尚未进行实际经济插件联调。
- 四个 JAR 均构建成功；共享协议类、加载器描述文件和许可证已核对。
- 收费测试使用隔离的合成余额与曲目，覆盖搜索免费、取消、费用确认、余额不足、重复提交、歌曲不可播放、命令点歌、离线与跨后端交易归属、重启核对、交易记录复用及人工核对。客户端测试覆盖确认、取消、过期、切服、旧版免费服务器及延迟回执。
- 本轮改名覆盖客户端侧栏、主页、游戏按键分类、mod 列表、插件名称、聊天前缀及四种发行文件。设置中的“关于”注明“本版本由小乐开发”，列出来源、上游作者、许可证和项目地址；四种加载器或插件描述信息也包含小乐署名。四种 JAR 均附带来源说明。
- Cookie 测试使用隔离的合成账号数据，覆盖请求携带登录信息、AllMusic JSON 导入、配置更新、登录失效、无播放权限和试听拒绝；未使用真实会员账号完成会员曲目播放联调。
- 此前的网易云真实接口抽样（本轮未重复）：搜索返回 20 条；无可用完整 MP3 的歌曲被拒绝；另一首返回完整 MP3 地址，音频 HTTP 状态为 200，类型为 `audio/mpeg`，并用 JLayer 成功连续解码 100 帧。
- 尚未启动实际 Minecraft 客户端或 Folia / Velocity 服务器，未实机验证新关于页、中文输入法候选窗、聊天点击、动画观感、游戏内音频输出及跨服连续性，也未测量 750 毫秒同步指标。输入法修复已核对实际 26.2 客户端的 `TextInputManager` 与界面生命周期，并通过两种加载器构建。

保留 DangoMusic 原项目的 GPL-3.0 许可证和署名。AllMusic 为实现参考，本版本使用独立的 `dangomusic:channel` 协议。四种 JAR 和发行包均附带 `CREDITS.md` 与 `LICENSE`。
