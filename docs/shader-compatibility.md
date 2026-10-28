# 光影与 3D 歌词兼容检查

检查日期：2026-10-26。此处记录渲染入口的代码检查结果。

## 各版本入口

| Minecraft / 加载器 | 歌词绘制位置 | 本次结果 |
| --- | --- | --- |
| 1.20.1 Fabric | `GameRenderer.renderLevel` 调用 `LevelRenderer.renderLevel` 返回后 | 从 `WorldRenderEvents.LAST` 迁移，避开世界渲染期间的光影处理 |
| 1.21.1 Fabric | 同上 | 已在前次修改中迁移 |
| 1.20.1 Forge | `RenderLevelStageEvent.Stage.AFTER_LEVEL` | Forge 的 GameRenderer 补丁在 LevelRenderer 返回后派发，保留现有入口 |
| 1.21.1 NeoForge | `RenderLevelStageEvent.Stage.AFTER_LEVEL` | NeoForge 补丁在 LevelRenderer 返回后派发，保留现有入口 |
| 1.21.11 Fabric | GameRenderer 中 LevelRenderer 调用返回后 | 已采用较晚入口，保留 |
| 1.21.11 NeoForge | `RenderLevelStageEvent.AfterLevel` | GameRenderer 中 LevelRenderer 返回后派发，保留 |
| 26.2 / 26.3 Fabric | GameRenderer 中 LevelRenderer.render 调用返回后 | 已采用较晚入口，保留 |
| 26.2 / 26.3 NeoForge | `RenderLevelStageEvent.AfterLevel` | 检查对应 NeoForge GameRenderer 补丁，均在 LevelRenderer 返回后派发，保留 |
| 1.8.9 Forge | `RenderWorldLastEvent`，在 EntityRenderer.renderWorldPass 内、画手之前 | 使用旧版渲染管线，保留现有入口 |

现代 Fabric 的两个入口都使用 `At.Shift.AFTER`，而不是注入 LevelRenderer 的 RETURN：这样无需依赖与 Iris 的 RETURN 注入之间的执行顺序。相机投影此时仍用于世界渲染，尚未切换为手部投影。

Forge / NeoForge 的检查依据为构建缓存中对应版本 userdev JAR 的 GameRenderer 补丁。不能仅凭 `LAST`、`AFTER_LEVEL` 等事件名称判断时机。

1.8.9 的 `LegacyGfx.guarded` 会保存 GL 状态并临时关闭当前着色器，隔离绘制期间的部分状态；旧版客户端继续使用这一入口。

## 构建与检查

- `modern/gradlew build --offline`：全部六个 1.20.1～1.21.11 目标构建通过。
- 检查 1.20.1 / 1.21.1 Fabric 成品 JAR 的 Mixin 元数据及重映射后的调用描述符。

实机回归时，使用同一首有歌词的歌曲，分别验证无光影与开启光影、像素字体与平滑字体、Glow 开与关，以及切换维度和重新加载光影后的显示情况。
