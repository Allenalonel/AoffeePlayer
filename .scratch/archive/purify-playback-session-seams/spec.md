# Spec: 播控会话接缝纯化、双轨彻底切割与起播前音频焦点门控 (Purify Playback Session Seams)

Status: `completed`  
ADR: [`docs/adr/0041-purify-playback-session-seams-and-audio-focus-gating.md`](../../../docs/adr/0041-purify-playback-session-seams-and-audio-focus-gating.md)

## Problem Statement

当前 Aoffee Player 的播控垂直链路存在三项直接影响用户听歌体验的真实故障与维护阻塞：
1. **生产音频焦点门控缺失**：底层媒体引擎关闭了原生音频焦点管理，而宿主虽然绑定了焦点监听器并负责释放焦点，整个生产路径却从未在起播前向系统申请 `Audio Focus`，导致与其他发声应用并发时无法正常抢占或仲裁系统音频通道。
2. **浏览或搜索行为篡改甚至清空在播队列**：当用户正在收听某台服务器上的目录队列时，若在媒体库切换 `Browsing Storage` 或在全局搜索中点击另一个服务器的目录结果，会话层会将浏览源强行覆写至 `Playback Server` 与引擎凭据上下文，轻则导致来源标签与下一首拉流凭据错位、重则直接停止并清空当前播放队列。
3. **跨层反向渗透与内部状态机纠缠**：应用级 `Playback Session` 反向依赖了底层流播放包的硬件锁与异常实现，且硬件锁只申请不释放；同时会话内部 6 个模块通过共享可变状态流与双向闭包互相直改状态，造成冷启动门控、选服与容错边界分散，单测被迫依赖重型桩对象。

## Solution

参考主流成熟 Android 播放器（Auxio 的宿主焦点门控与纯状态机分层、Symfonium 的浏览/在播彻底正交与边界错误归一化），完成播控接缝的全面纯化：
- **起播前零破音音频焦点门控与对称硬件锁**：`PlaybackSessionHost` 成为硬件锁与 `AudioFocusHandler` 的唯一持有者；通过底层引擎暴露的极窄起播前门控回调在真正激活解码渲染前申请系统 `Audio Focus`，系统拒批时直接保持暂停不发声，并完整保留来电中用户手动暂停不自动续播的精细语义。
- **彻底物理分离 `Browsing Storage` 与 `Playback Server`**：从 `Playback Session` 对外接口与会话状态中彻底移除浏览源字段和切换方法；浏览切换与搜索跳转不再触碰播控会话；目录点播时由调用方显式传入目标 `Storage Source`；仅当正在播放的远程服务器被彻底删除时会话才自动停止清理，删其余源或浏览切换对播放零干扰。
- **强类型领域错误边界与纯决策协作者**：`AudioPlayerEngine` 在出口边界将 HTTP 404/401/403、网络超时与不支持 Range 续传一次性归一化为强类型 `PlaybackErrorKind`；会话内部队列与容错协作者纯化为无副作用的决策模块，由主会话作为唯一状态提交与引擎调用主干。

## User Stories

1. 作为听众，当我打开应用点击任意曲目开始播放或从暂停恢复播放时，我希望应用在出声前先向系统申请 `Audio Focus`，以便其他正在播放音视频的应用自动暂停或礼让。
2. 作为听众，当我正在通话或语音会议中误触播放键且系统拒绝授予 `Audio Focus` 时，我希望播放器直接保持暂停且不发出任何短暂破音或发起无效网络拉流。
3. 作为听众，当我在播放音乐期间遇到来电等瞬态焦点丢失时，我希望播放器自动暂停；挂断电话后自动恢复播放，且若我在通话期间自己手动按了暂停则挂断后不再自动续播。
4. 作为听众，当导航播报触发短暂降音提示时，我希望交由系统混音器自动压低音量，不会叠加二次降音也不破坏现有播放状态。
5. 作为听众，当我在流式播放或切歌过程中暂停、出错、结束或退回空闲态时，我希望对应的 CPU 与 Wi-Fi 硬件锁成对释放，不会因锁泄漏增加后台耗电。
6. 作为听众，当我正在后台播放服务器 A 的目录队列时，我希望在媒体库切换到服务器 B 或本地存储浏览目录，完全不会打断当前播放、也不会篡改全屏播放器显示的 `Playback Server` 名称与拉流凭据。
7. 作为听众，当我在全局搜索结果中点击另一个服务器的目录条目查看内容时，我希望只切换媒体库浏览视图，绝不能清空或停止我当前正在播放的目录队列或歌单队列。
8. 作为听众，当我在浏览任意目录点击一首曲目开始播放时，我希望浏览器直接把当前浏览的 `Storage Source` 显式传递给 `Playback Session`，立即按该源建立 `Playback Queue` 并解码播放。
9. 作为听众，当我在存储管理页面删除一台闲置未在播的 WebDAV 服务器时，我希望当前正在播放另一台服务器或本地存储的会话完全不受影响。
10. 作为听众，当我在存储管理页面删除“当前正在播放”的 WebDAV 服务器时，我希望播控会话自动停止播放并清空该已删除源的残余队列，避免继续残留无效状态。
11. 作为听众，当网络音频流遇到文件不存在（404）、认证失效（401/403）或连接超时时，我希望播放器准确识别故障语义、上报连通性追踪器并在多曲队列中平滑跳向下一首可播音轨。
12. 作为听众，当不支持 HTTP Range 断点续传的服务器在播放中途断流时，我希望播放器准确弹出不支持续传的重播确认提示，并在我确认后从头重播该曲。
13. 作为听众，当应用被系统回收后，我通过蓝牙耳机或通知栏按键冷启动恢复播放时，我希望会话等待持久化现场恢复完毕后再可靠执行播放、暂停或切歌指令，不丢失首次按键意图。
14. 作为开发者，我希望 `domain.session` 包完全不依赖 `data` 层任何具体实现或异常类，保持单向清晰的分层接缝。
15. 作为开发者，我希望队列流转与容错决策模块成为不持有共享可变状态流、不直调引擎的纯决策模块，从而不造引擎假对象就能直接验证洗牌、插队、死循环防护与超时策略。
16. 作为开发者，我希望有一套自动化架构检验规则守卫 `domain.session` 与 `Playback Session` 接口纯净度，防止未来改动重新引入浏览状态或底层泄漏。

## Implementation Decisions

### 1. 宿主垄断硬件锁与 Engine 起播前音频焦点窄门控
- **硬件锁单一归属**：彻底移除 `MusicPlayerAppSessionImpl` 对 `StreamingHardwareLockManager` 的持有与申请调用，将硬件锁申请与释放全生命周期统一收拢至 `PlaybackSessionHost`（响应播放与带起播意图的缓冲状态）。
- **起播前窄门控（Pre-Play Gate）**：在 `AudioPlayerEngine`（及其实现 `Media3AudioPlayerEngine`）增加极窄的起播前门控注册接口（例如 `setPrePlayGate(gate: () -> Boolean)`），在真正执行 `player.play()` 或将 `playWhenReady` 置为开启前同步求值。
- **宿主焦点闭环**：`PlaybackSessionHost` 在构造或绑定阶段向引擎挂接 `AudioFocusHandler::requestAudioFocus` 作为起播门控；若焦点申请被系统拒绝，引擎直接阻断底层激活并回落至 `Paused`（或维持 `Idle`），会话状态同步收敛为未起播态，避免瞬间破音与无效网络缓冲。
- **精细焦点语义保留**：继续保留 `AudioFocusHandler` 对 `AUDIOFOCUS_LOSS_TRANSIENT`、`AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`、`AUDIOFOCUS_GAIN`、`AUDIOFOCUS_LOSS` 的状态流转及 `pausedByTransientAudioFocus` 标志仲裁（用户手动暂停或已结束时清除续播标志）。

### 2. 引擎边界强类型领域错误映射（`PlaybackErrorKind`）
- 在领域模型层引入枚举 `PlaybackErrorKind`，包含 `FILE_NOT_FOUND`、`AUTH_INVALID`、`SERVER_UNREACHABLE`、`UNSUPPORTED_RANGE`、`UNKNOWN`，并作为属性携带在 `PlaybackState.Error` 上（保留默认参数兼容既有调用与测试）。
- `Media3AudioPlayerEngine` 在其错误捕获边界一次性解析底层异常因果链（包含 HTTP 404/401/403、连接/域名/超时异常、`UnsupportedRangeReconnectionException` 等），构造携带精确 `PlaybackErrorKind` 的 `PlaybackState.Error`。
- `PlaybackFaultTolerance` 移除对底层具体异常类的引入与宽泛字符串 `contains` 猜测，统一基于 `PlaybackState.Error.kind` 分派 404、认证失效、不可达与不支持 Range 的处理逻辑。

### 3. 从 `Playback Session` 物理切除浏览源状态与 `setActiveServer`
- **接口与状态净化**：从 `MusicPlayerAppSession` 公共接口中彻底删除 `setActiveServer` 及其重载；从 `PlayerSessionState` 领域模型中彻底删除 `browsingServer` 字段。
- **显式目录点播参数**：`MusicPlayerAppSession.playDirectoryTrack` 修改为显式接收目标 `WebDavServer` 参数，队列构建与元数据富化直接使用该显式服务器，不再从会话状态回溯猜测浏览源。
- **浏览层直连数据源**：`DirectoryBrowserViewModel` 统一只订阅 `ServerRepository.getActiveServer()` 作为 `Browsing Storage` 来源；在全局搜索结果点击目录时，仅切换 `ServerRepository` 浏览源并加载目录，严禁调用 `Playback Session` 任何方法；`MainActivity` 切换服务器卡片时同理不再通知 `Playback Session`。
- **在播源被删被动停止**：`PlaybackTimelineCoordinator` 彻底移除对 `ServerRepository.getActiveServer()` 的监听；改为在会话观察层轻量比对当前在播 `activeServer`（排除不可删除的本地存储）是否仍存在于服务器列表中，仅当在播服务器被删除时自动调用 `stop()`，其余存储变更或切换浏览源零干扰。

### 4. 纯化会话内部协作者与单一状态提交主干
- **纯化队列与容错协作者**：遵循 ADR-0023 单文件 600 行上限，将 `PlaybackQueueManager` 与 `PlaybackFaultTolerance` 从直接持有 `MutableStateFlow`、直接调用 `AudioPlayerEngine` 与异步闭包网中解放出来：
  - `PlaybackQueueManager` 专职负责纯队列运算、洗牌镜像、死循环防抖记录与跨源 `resolveServerForTrack` 解析，返回明确的队列更新结果或构建请求；同步清除历史遗留的无用参数死重载。
  - `PlaybackFaultTolerance` 专职负责基于 `PlaybackErrorKind` 的错误判定、跳过/报错/Range 弹窗决策以及缓冲超时看门狗调度回调，不再在 7 处位置直接越权改写主会话 `MutableStateFlow` 或直调 `playerEngine.pause()`。
- **瘦身时间轴协调器**：将 `PlaybackTimelineCoordinator` 的构造依赖从 17 个（含 7 个反向闭包回调）收窄为仅订阅引擎状态流、音轨自然切换流与进度流，并将事件路由回单一会话主干处理。
- **统一冷启动门控与状态提交**：`MusicPlayerAppSessionImpl` 将 `isRestored` 等待门控收敛为单一内部恢复等待助手（消除 `play`、`togglePlayPause`、`skipToNext`、`skipToPrevious` 重复样板），并作为 `_sessionState` 提交、引擎调用与持久化刷盘的唯一权威主干。

### 5. 架构接缝自动化守卫
- 在 `PackagingSeamVerificationTest` 中补充静态依赖断言：
  - `domain.session` 包下所有源码严禁 `import` `com.webdav.player.data.*`；
  - `MusicPlayerAppSession` 与 `PlayerSessionState` 严禁存在 `browsingServer` 或 `setActiveServer` 成员。

## Testing Decisions

### 测试原则
- 测试必须面向最高层公共或模块接缝的可观察输出（`StateFlow` 状态发射、引擎调用效果、焦点请求/拒批行为），不测试私有字段或实现细节；
- 纯决策协作者通过纯函数输入输出直接验证，不引入 `AudioPlayerEngine` 或 `MutableStateFlow` 桩。

### 测试接缝与覆盖范围
1. **`MusicPlayerAppSession` 接缝（`MusicPlayerAppSessionTest`、`EndToEndPlaybackPipelineIntegrationTest`）**：
   - 验证浏览源切换、搜索跳转及删除非在播服务器时，当前在播 `activeServer`、播放状态与队列 100% 不受干扰；
   - 验证删除当前在播远程服务器时自动触发 `stop()`；
   - 验证 `playDirectoryTrack` 显式传入目标服务器构建队列，且冷启动未恢复前触发播放/切歌意图会在恢复后可靠执行；
   - 验证依据 `PlaybackErrorKind` 准确触发跳过、置错与不支持 Range 重播弹窗。
2. **`PlaybackSessionHost` 接缝（`PlaybackSessionHostTest`、`EndToEndSystemFocusAndRouteRegressionTest`）**：
   - 验证起播前门控真实触发 `AudioFocusHandler.requestAudioFocus()`，系统拒批时底层引擎不启动播放、系统批准后正常出声；
   - 验证来电瞬态抢占、来电期间手动暂停阻断自动续播、永久失焦停播与硬件锁对称申请/释放。
3. **`Media3AudioPlayerEngine` 接缝（`Media3AudioPlayerEngineTest`）**：
   - 验证门控拒绝时底层播放器不进入播放状态；
   - 验证 404/401/连接超时/`UnsupportedRangeReconnectionException` 在引擎出口精确映射为对应 `PlaybackErrorKind`。
4. **纯决策模块接缝（`PlaybackQueueManagerTest`、`PlaybackFaultToleranceTest`）**：
   - 脱离 `AudioPlayerEngine` 与共享流，直接验证队列洗牌/循环/防死循环跳跃及故障决策返回值。
5. **`DirectoryBrowserViewModel` 接缝（`DirectoryBrowserViewModelTest`）**：
   - 验证浏览源直接来自 `ServerRepository`，搜索点击目录不调用 `MusicPlayerAppSession`，点播曲目透传浏览源服务器。
6. **架构红线接缝（`PackagingSeamVerificationTest`）**：
   - 自动化验证 `domain.session` 零 `data.*` 引入及浏览状态零残留。

### 参考先例
- `MusicPlayerAppSessionTest`（会话黑盒行为测试与 `FakeAudioPlayerEngine`）；
- `PlaybackSessionHostTest`（宿主焦点与硬件锁生命周期验证）；
- `PackagingSeamVerificationTest`（包边界与接口洁净度静态断言）。

## Out of Scope

- **组合包 B 范畴**：`Track Identity` 值模型抽取、`ServerManagementViewModel` 越界级联清理修复、伴生清单与 `Cover Art Liveness Healing` 折叠至 `TrackMetadataRepository`。
- **组合包 C 范畴**：`MorphingPlayerContainer` / `FullPlayerView` UI 层合并及 `graphicsLayer` 绘制阶段改造、`WebDavClient` 端口补全与测试 Fake 全局去重。
- 修改 Room 数据库 Schema 或 DataStore 存储格式。

## Further Notes

- 严格遵守 `CODING_STANDARDS.md` 第 1 节（单文件高内聚与 600 行上限、黑盒测试表面）、第 2 节（主线程安全与 Media3 Looper 派发）和第 3 节（浏览源与在播源双轨解耦）。
- 关联领域演进记录：`GLOSSARY.md`（`Audio Focus` 与 `Playback Session`）、`docs/adr/0041-purify-playback-session-seams-and-audio-focus-gating.md`。
