# 0041: 播控会话接缝纯化、双轨彻底切割与起播前音频焦点门控 (Purify Playback Session Seams, Excise Browsing State, and Gate Pre-Play Audio Focus)

## Status

Accepted (Amends ADR-0023 and ADR-0035)

> 参照主流成熟 Android 播放器（Auxio 的 Host 焦点与纯状态机分层、Symfonium 的浏览/在播彻底正交与边界错误归一化）确立组合包 A 终局架构共识。

## Context

在 ADR-0023、ADR-0035 与 ADR-0039 演进之后，播控垂直切片（`PlaybackSessionHost` ↔ `MusicPlayerAppSessionImpl` ↔ `AudioPlayerEngine`）仍残留四类结构性摩擦与真实运行缺陷：

1. **生产音频焦点门控断路**：`Media3AudioPlayerEngine` 显式关闭了 ExoPlayer 原生焦点管理（`handleAudioFocus = false`），`PlaybackSessionHost` 仅接入了事件绑定与 `abandonAudioFocus()`，全生产路径从未调用 `AudioFocusHandler.requestAudioFocus()`（仅被单元测试手工触发而掩盖）。
2. **`domain.session` 反向穿透 `data.player`**：`MusicPlayerAppSessionImpl` 直接持有并只申请不释放 `StreamingHardwareLockManager`，`PlaybackFaultTolerance` 直接依赖 `UnsupportedRangeReconnectionException` 并以脆弱字符串 `contains` 猜测 404/401/超时故障。
3. **`browsingServer` 与 `setActiveServer` 残留覆写双轨**：`MusicPlayerAppSession` 与 `PlayerSessionState` 仍暴露 `browsingServer` 与 `setActiveServer()`，`PlaybackTimelineCoordinator` 监听浏览源并反向覆写 `Playback Server` 与引擎凭据，`DirectoryBrowserViewModel` 在搜索点击目录时调用 `setActiveServer()` 误清正在播放的队列。
4. **会话内部 6 模块双向纠缠**：`PlaybackTimelineCoordinator` 承担 17 参（含 7 闭包回调），`PlaybackFaultTolerance` 与 `PlaybackQueueManager` 跨模块直改共享 `MutableStateFlow` 并直接调 `playerEngine`，且冷启动门控与 `resolveServerForTrack` 散落多处。

## Decision

### 1. Host 托管 `AudioFocusHandler` 与 Engine 起播前窄门控（Pre-Play Gate）
- **`PlaybackSessionHost` 作为唯一平台适配器**：独占 `StreamingHardwareLockManager`（申请与释放对称闭环）与 `AudioFocusHandler`，彻底移除 `MusicPlayerAppSessionImpl` 中的硬件锁字段。
- **Engine 起播前窄门控**：在 `AudioPlayerEngine` 出口侧建立极窄的可选门控回调（如 `onBeforePlay: () -> Boolean`），由 `PlaybackSessionHost` 接入 `audioFocusHandler::requestAudioFocus`；在 `Media3AudioPlayerEngine` 真正激活 `playWhenReady = true` / `player.play()` 前拦截求焦，拒批时直接阻断起播并保持 `Paused` / `Idle`，杜绝瞬间破音与无效网络缓冲，同时维持 `domain.session` 对 `data.service` 与 `data.player` 的零反向依赖。

### 2. 引擎出口统一映射强类型领域错误（`PlaybackErrorKind`）
- 在领域模型层定义 `PlaybackErrorKind`（`FILE_NOT_FOUND`、`AUTH_INVALID`、`SERVER_UNREACHABLE`、`UNSUPPORTED_RANGE`、`UNKNOWN`），挂载于 `PlaybackState.Error`。
- 由 `Media3AudioPlayerEngine` 在错误出口一次性把 HTTP 404/401/403、网络超时与 `UnsupportedRangeReconnectionException` 映射为对应领域枚举；`PlaybackFaultTolerance` 仅消费强类型 `PlaybackErrorKind`，彻底移除对 `data.player` 异常类的反向引用及脆弱字符串匹配。

### 3. 从 `Playback Session` 彻底切除浏览源状态与 `setActiveServer`
- 从 `MusicPlayerAppSession` 接口与 `PlayerSessionState` 中彻底移除 `browsingServer` 与 `setActiveServer(...)`；
- `DirectoryBrowserViewModel` 直接以 `ServerRepository.getActiveServer()` 作为 `Browsing Storage` 唯一源；
- `MusicPlayerAppSession.playDirectoryTrack` 显式接收 `server: WebDavServer` 参数，由浏览层点播时直传目标服务器；
- `PlaybackTimelineCoordinator` 移除对 `ServerRepository.getActiveServer()` 的监听；`MusicPlayerAppSessionImpl` 仅轻量监听服务器列表，**仅当当前在播服务器被删除时**自动触发 `stop()` 并清理该源队列，删其余源或切换浏览源对在播会话零干扰。

### 4. 会话内部纯决策协作者与单一状态提交主干
- 恪守 ADR-0023 单文件 600 行上限，将 `PlaybackQueueManager` 与 `PlaybackFaultTolerance` 纯化为无副作用的纯状态/决策模块（不持有 `MutableStateFlow`，不直接调用 `AudioPlayerEngine`），返回显式状态变换与动作指令；
- 瘦身 `PlaybackTimelineCoordinator`，消除 7 闭包回调网；冷启动 `isRestored` 等待门控与 `resolveServerForTrack` 统一收敛至 `MusicPlayerAppSessionImpl` 单一调度主干；
- 在 `PackagingSeamVerificationTest` 中补充架构铁律校验：严禁 `domain.session` 反向 `import` `data.*`。

## Consequences

- **真实音频焦点与对称锁生命周期**：`AudioFocusHandler.requestAudioFocus()` 在生产路径 100% 闭环生效且零破音，保留来电手动暂停不自动续播的精细语义；硬件锁集中由宿主对称申请与释放。
- **彻底落实双轨正交**：媒体库浏览与全局搜索导航在接口层面物理隔离于播控会话，彻底消除搜索跳转误清队列与跨源凭据污染。
- **高局部性与可测性**：`PlaybackQueueManager` 与 `PlaybackFaultTolerance` 成为可独立无 Mock 单测的纯领域决策模块；`domain.session` 达成严格单向隔离。
- **Amends ADR-0023 & ADR-0035**：将 ADR-0023 的内部协作者重构为纯决策单向返回模型，并清理 ADR-0035 遗留在 `MusicPlayerAppSession` 中的 `browsingServer` 与 `setActiveServer` 过渡态接口。
