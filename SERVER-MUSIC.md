# MineKUmusic 全服音乐

Minecraft 26.2 的 Fabric / NeoForge 客户端与 Folia / Velocity 插件。所有组件使用 Java 25。项目、插件和发行文件名称为 `MineKUmusic`，客户端内显示为 `MineKU Music`。需要替换原 DangoMusic，不要同时安装两个相同 mod ID 的版本。

本版本由 **小乐** 开发，基于 [Sorihiko / DangoMusic](https://github.com/Sorihiko/DangoMusic) 修改，服务端队列、点歌与代理接入参考 [Coloryr / AllMusic](https://github.com/Coloryr/AllMusic)。原有界面的 MuoniumPlayer 设计署名与第三方许可证也予以保留，详见 [CREDITS.md](CREDITS.md) 和客户端“设置 → 关于”。

本次构建固定使用 Folia API `26.2.build.7-beta`、Velocity API `4.3.0`；Fabric Loader `0.19.5`、Fabric API `0.161.0+26.2`、NeoForge `26.2.0.93`。Fabric 客户端还需要安装对应的 Fabric API。

## 安装

### 独立 Folia

1. 将 `MineKUmusic-server-1.6.0-folia.jar` 放入 Folia 26.2 的 `plugins` 目录。
2. 全新安装首次启动后，在 `plugins/MineKUmusic/config.properties` 调整频道名称和点歌规则，重启生效。升级安装按下文说明读取旧目录。
3. 玩家安装对应加载器的 26.2 客户端，在播放器左侧打开“全服音乐”，自行加入频道。

### Velocity 群组服

1. 将 `MineKUmusic-server-1.6.0-velocity.jar` 放入 Velocity 4.3.0 的 `plugins` 目录。
2. 使用代理生成的插件数据目录中的 `config.properties` 配置全服频道。
3. 免费模式下后端不用安装音乐插件。开启收费时，后端安装 Folia 插件并设为经济桥接模式，步骤见下文。不要同时部署独立 Folia 音乐实例；队列、权限和播放时间全部由代理管理。
4. 保持客户端到同一代理的连接即可跨后端续播。切换到另一个代理地址属于重新连接。

不需要额外开放音乐插件端口，不依赖 Firebase、Redis 或 AllMusic。玩家没有安装 MineKU Music 也可以正常进入服务器，但无法收听频道。

### 从旧版升级

- 关闭游戏及插件所在服务器，替换旧客户端和插件 JAR，不要同时保留新旧插件。客户端文件为 `MineKUmusic-1.6.0+26.2-fabric.jar` 或 `MineKUmusic-1.6.0+26.2-neoforge.jar`。收费功能需要同时升级客户端和插件；旧客户端在收费服务器会收到升级提示，不会直接扣款。
- 两种插件显示名称均为 `MineKUmusic`。Folia 全新安装使用 `plugins/MineKUmusic`；Velocity 内部插件 ID 保持小写 `minekumusic`，全新安装使用 `plugins/minekumusic`。
- 若新目录没有 `config.properties`，Folia 按 `plugins/minekumusic`、`plugins/DangoMusic` 的顺序查找旧配置，Velocity 则查找 `plugins/dangomusic-server`；找到后继续使用对应旧目录。Cookie、队列、频道名称和 `server-id` 会一并保留，启动日志会显示实际读取目录。Windows 上仅大小写不同的目录通常视为同一目录。
- 若新目录已有 `config.properties`，则以新目录为准。需要迁移时，停服后完整复制旧目录内容到新目录，保留 `server-id`。
- 客户端 mod ID、`config/dangomusic`、`dangomusic:channel` 和 `dangomusic.*` 权限节点继续沿用。新命令别名 `/minekumusic` 与原 `/dgm` 均可使用。

## 音源

点歌费用由游戏内经济插件提供，与网易云账号是否具备歌曲播放权限相互独立。

服务端使用网易云的搜索、歌曲详情、歌词和播放地址接口，支持独立的网易云账号 Cookie。配置有会员播放权限的账号后，会携带该账号的 Cookie 请求会员歌曲的完整播放地址；是否可播仍以账号实际权限和音源返回为准。未配置 Cookie 时使用匿名模式。

只接受接口正常返回的完整 MP3；无可用地址、仅试听或其他编码格式会明确失败。Cookie 不发送给玩家，不写入队列或协议消息，也不会被加入音频下载请求；客户端直接使用音源返回的播放地址下载音频。玩家自己的网易云登录仍只用于个人音乐。

### 配置会员账号

1. 更新插件并启动一次，会在实际插件数据目录生成 `netease-cookie.txt`。全新 Folia 安装路径为 `plugins/MineKUmusic/netease-cookie.txt`，Velocity 为 `plugins/minekumusic/netease-cookie.txt`；升级安装可能继续使用旧目录，以启动日志为准。
2. 在文件注释下填入该网易云账号的完整 Cookie 请求头，或单独的 `MUSIC_U=实际值`。文件使用 UTF-8，整段不加引号；不需要把 Cookie 填进客户端。
3. 保存后，下一次搜索、点歌或播放地址刷新会重新读取，无需重启。已经播放中的歌曲继续使用现有地址。

也兼容 AllMusic 使用的 `cookie.json` 数组格式：放在同一个插件数据目录，保留各项的 `name`、`value`、`domain`、`path` 字段。以下是占位示例，不是真实凭据：

```json
[
  {
    "name": "MUSIC_U",
    "value": "YOUR_SESSION_VALUE",
    "domain": ".music.163.com",
    "path": "/",
    "hostOnly": false,
    "httpOnly": true
  }
]
```

优先级：`netease-cookie.txt` 中的有效内容优先；文件为空或只有注释时读取 `cookie.json`。两者都为空时匿名访问。登录失效、缺少播放权限或仅返回试听时会提示失败，不自动改用其他来源。凭据只保存在插件目录，交付包只包含空白模板。

## 使用

- 初次默认不加入，加入选择按 `server-id` 保存在客户端 `config/dangomusic/server-music.properties`。
- 加入时保存个人播放队列和状态，退出或真正断线时恢复。点个人歌曲会先弹出切换确认。
- 全服页支持搜索点歌、队列分页和撤回自己的待播歌曲；原有搜索和歌单旁的 `+` 也可点到全服。
- 全服页、歌单中的全服点歌按钮及 `/dgm request` 都先显示确认弹窗，列出本次费用和币名。确认有效期 60 秒；取消或过期不扣款。命令点歌会在离开聊天界面后打开播放器确认。
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

1. Velocity 安装 `MineKUmusic-server-1.6.0-velocity.jar`，配置费用、币名和 `economy-bridge-secret`。密钥至少 32 个字符，使用随机生成的值。
2. 在每个允许点歌的 Folia 后端安装 `MineKUmusic-server-1.6.0-folia.jar`、Vault 接口插件和经济插件。后端设置如下：

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
- Vault 扣款和本插件文件之间没有共同的数据库事务。若进程恰好在扣款执行中退出，插件会标记为待管理员核对，不自动重复扣款。管理员应先查询经济插件交易记录，然后在**原扣款 Folia 后端控制台**执行 `dgm payment <交易ID> paid`（确认已经扣款）或 `dgm payment <交易ID> cancel`（确认未扣款）。该命令只处理不确定交易，不能覆盖已确定的结果。代理随后会继续入队或结束该请求。

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

配置文件使用 UTF-8。`config.properties` 调整后完整重启插件所在的 Folia 或 Velocity；Cookie 文件例外，下一次解析即生效。`queue.json` 自动保存队列和进度，重启时重新解析播放地址并从保存进度继续，不补算停机时间；不保存音频地址、Cookie 和投票。

## 命令与权限

| 命令 | 用途 | 权限 |
| --- | --- | --- |
| `/dgm queue [页码]` | 查看当前歌曲和队列条目 ID | `dangomusic.use` |
| `/dgm search <关键词>` | 搜索歌曲 ID | `dangomusic.use` |
| `/dgm request <歌曲ID>` | 点歌，需要已加入频道 | `dangomusic.request` |
| `/dgm withdraw <条目ID>` | 撤回自己的待播歌曲 | `dangomusic.use` |
| `/dgm vote` | 投票切歌，需要已加入频道 | `dangomusic.vote` |
| `/dgm skip` | 直接切歌 | `dangomusic.admin` |
| `/dgm pause` / `/dgm resume` | 暂停或恢复整个频道 | `dangomusic.admin` |
| `/dgm remove <条目ID>` | 删除任意待播歌曲 | `dangomusic.admin` |
| `/dgm clear` | 停止当前歌曲并清空队列 | `dangomusic.admin` |
| `/dgm payment <交易ID> paid` / `cancel` | 原扣款 Folia 后端核对中断交易 | `dangomusic.admin` |

以上命令也可将 `/dgm` 替换为 `/minekumusic`。普通功能默认允许，管理权限在 Folia 默认给 OP，在 Velocity 需由代理端权限插件分配。控制台可以管理队列。

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

- 1.6.0 收费版本通过 67 项自动化测试（服务端 53 项、客户端 14 项）；新增覆盖旧配置补齐中文注释、特殊字符与原配置值保留、再次启动后配置稳定。计费权限、统一入队入口与代理付款回执经过源码检查，尚未进行实际经济插件联调。
- 四个 JAR 均构建成功；共享协议类、加载器描述文件和许可证已核对。
- 收费测试使用隔离的合成余额与曲目，覆盖搜索免费、取消、费用确认、余额不足、重复提交、歌曲不可播放、命令点歌、离线与跨后端交易归属、重启核对、交易记录复用及人工核对。客户端测试覆盖确认、取消、过期、切服、旧版免费服务器及延迟回执。
- 本轮改名覆盖客户端侧栏、主页、游戏按键分类、mod 列表、插件名称、聊天前缀及四种发行文件。设置中的“关于”注明“本版本由小乐开发”，列出来源、上游作者、许可证和项目地址；四种加载器或插件描述信息也包含小乐署名。四种 JAR 均附带来源说明。
- Cookie 测试使用隔离的合成账号数据，覆盖请求携带登录信息、AllMusic JSON 导入、配置更新、登录失效、无播放权限和试听拒绝；未使用真实会员账号完成会员曲目播放联调。
- 此前的网易云真实接口抽样（本轮未重复）：搜索返回 20 条；无可用完整 MP3 的歌曲被拒绝；另一首返回完整 MP3 地址，音频 HTTP 状态为 200，类型为 `audio/mpeg`，并用 JLayer 成功连续解码 100 帧。
- 尚未启动实际 Minecraft 客户端或 Folia / Velocity 服务器，未实机验证新关于页、中文输入法候选窗、聊天点击、动画观感、游戏内音频输出及跨服连续性，也未测量 750 毫秒同步指标。输入法修复已核对实际 26.2 客户端的 `TextInputManager` 与界面生命周期，并通过两种加载器构建。

保留 DangoMusic 原项目的 GPL-3.0 许可证和署名。AllMusic 为实现参考，本版本使用独立的 `dangomusic:channel` 协议。四种 JAR 和发行包均附带 `CREDITS.md` 与 `LICENSE`。
