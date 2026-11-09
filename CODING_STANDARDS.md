# Coding Standards

本项目架构设计与编码实现的唯一最高法典（超越所有历史 `docs/adr/`）。代码审查严格对标 Matt Pocock 双轴审查体系：
1. **Standards 轴**：严格对照本法典六大章节硬约束与 Fowler 代码坏味道基线（杜绝神秘命名、重复代码、依恋情结、数据泥团、基本类型偏执、发散式变化与中间人等），违背法典者一票否决；
2. **Spec 轴**：严格对照工单验收条件，杜绝需求遗漏与越界重构（Scope Creep）。

机械包接缝与防僵尸类规则由 `PackagingSeamVerificationTest` 自动化持续保证。

---

## 1. Module Depth, Packaging Seams & Test Surface Discipline (模块深度、包接缝与测试表面纪律)

- **Module Depth & Zero Artificial Fragmentation (职责内聚与严禁伪碎片化)**:
  - **高内聚守恒**：对于单一职责的高内聚模块（如 `AsfExtractor` 的解复用、`LrcParser` 的歌词规整、内聚的复杂 Compose 容器），即便达到 400–600+ 行亦应保持在单文件中。
  - **内部接缝解耦**：当单个类累积 2 个以上正交子领域或 6 个以上非相关构造依赖时，提炼包级私有（`internal`）协作者并隐藏在窄公共接口之后。
  - **严禁伪碎片化**：严禁将 150–400 行的内聚组件拆成单调用者的卫星碎片文件（如 `*Collaborators.kt` 或 30 行转发壳），严禁通过伪压缩掩饰模块内部复杂度。
- **Streaming Playback Seam & Zero Reverse Coupling (播放引擎单向隔离铁律)**:
  - `AudioPlayerEngine` 是纯粹的媒体流播放抽象，严禁持有 `MediaSession`、硬件唤醒锁、音频路由钩子或逆向回调，严禁反向依赖数据层；
  - `data.player` 绝对禁止反向引入 `data.service`、`domain.session`、`ui` 或 `androidx.media3.session`；
  - `PlaybackSessionHost` 仅通过公开接口操纵引擎，绝对禁止向下类型转换（如强转 `Media3AudioPlayerEngine`）；
  - 重构至深层接缝后，严禁遗留中间兼容转发壳、工厂别名或僵尸文件，旧接口必须物理清除（由 `PackagingSeamVerificationTest` 自动化检验）。
- **UI Layering Cleanliness (表现层通用组件单向隔离纪律)**:
  `ui.common` 严格保持跨屏幕中立，绝对禁止反向引入任何特定业务屏幕包（`ui.browser`、`ui.playlist`、`ui.player`、`ui.server`；由 `PackagingSeamVerificationTest` 自动化检验）。
- **Binary Thinning & Packaging Budget (打包轻量化与二进制守恒铁律)**:
  - 生产构建严格锁定 64 位 `arm64-v8a` ABI 单架构（剥离 32 位动态库）；
  - 强制开启 R8 全量混淆压缩与资源摇树；排除 `material-icons-extended` 臃肿依赖；
  - 严禁宽泛通配符（如 `-keep class androidx.media3.** { *; }`）；
  - 全量 Release APK 体积 $\le 7.5\text{MB}$，单 `classes.dex` 体积 $\le 4.0\text{MB}$（由 `verifyReleasePackaging` 持续验证）。
- **Black-Box Test Surface & Zero Brittle Assertions (黑盒测试表面与禁止脆弱实现断言)**:
  测试必须面向公共接口与可观察状态/行为（`StateFlow` / `Flow` 发射值与函数返回值）；严禁使用反射（`Class.forName`、`declaredMethods`）测试内部私有门面，严禁在测试中硬编码源码文件行数断言（`readLines().size <= X`）。

---

## 2. Concurrency Model & Media3 Looper Thread-Safety (并发调度模型与 Media3 主 Looper 安全契约)

- **Dispatcher Tripartite Separation & Repository Main-Safety (调度器三权分立与主线程安全契约)**:
  - **职责三权分立**：I/O 阻塞型网络与磁盘读写严格使用 `Dispatchers.IO`；计算密集型（音频重采样、LRC 规整、Palette 色彩提取、Native 交互）使用 `Dispatchers.Default`；UI 状态与 Media3 播放器操作严格使用 `Dispatchers.Main.immediate`。
  - **Main-Safety 契约**：所有仓储层（Repository）与用例层暴露给外部调用的公共 `suspend` 函数必须自带调度器切换逻辑（`withContext(ioDispatcher)`），调用方在主线程直接调用无需额外切换，默认保证主线程安全。
  - **CancellationException 防吞法则**：在循环扫描、网络重试与流式读取中的 `try-catch` 块若捕获宽泛异常，必须显式重抛协程取消异常（`if (e is CancellationException) throw e`），严禁吞噬取消信号破坏结构化并发。
- **Media3 Application Looper Unidirectional Dispatch (Media3 主 Looper 线程单向派发铁律)**:
  底层 `Player` 实例（`ExoPlayer`）的方法调用（如 `prepare`、`play`、`pause`、`seekTo`、`setMediaSource`）必须严格收敛运行在引擎创建时的应用主 Looper 线程（`applicationLooper` / `Dispatchers.Main.immediate`）；严禁在后台协程（`Dispatchers.IO` 或 `Dispatchers.Default`）中跨线程直调播放器实例，跨协程交互必须通过 `runOnApplicationLooper` 派发执行。
- **Lifecycle-Aware StateFlow Collection (UI 状态流生命周期安全收集铁律)**:
  Compose UI 表现层消费 ViewModel 或应用会话层暴露的 `StateFlow` 时，必须强制使用 `collectAsStateWithLifecycle()`（默认处于 `Lifecycle.State.STARTED` 时收集）；严禁在表现层直接调用裸 `collectAsState()`，确保退入后台或处于 Backstack 时立即挂起消费管道，杜绝后台重组与电量隐性消耗。

---

## 3. Cross-Source Storage Identity, SAF & Incremental Indexing (跨源身份隔离、SAF 本地存储与增量索引体系)

- **Decoupling Browsing Storage and Playback Server Context (浏览源与在播源双轨解耦法则 - ADR-0035)**:
  `browsingServer`（媒体库浏览源）与 `activeServer`（在播流凭据上下文）必须彻底解耦：
  - 媒体库目录浏览、面包屑与存储管理仅由 `browsingServer`（对齐 `ServerRepository.getActiveServer()`）驱动；
  - 播放器时间线、预热、MediaSource 凭据与全屏播放器来源标签仅由在播音轨物理所属源 `activeServer` 驱动；
  - 跨源歌单或跨源搜索切歌时，仅动态切换底层 `activeServer`，**严禁覆写或重置用户的媒体库浏览源与停留目录**。
- **Local Storage Identity Clamping (本地存储身份单向钳制法则)**:
  播放管线（`MusicPlayerAppSessionImpl`）、元数据协调器（`PlaybackMetadataCoordinator`、`TrackMetadataRepositoryImpl`）及持久层（`TrackMetadataEntity.fromDomain`）必须对任何 `content://`、`file://` 以及 `/local/*` 规范化本地音轨将 `serverId` 强制钳制为 `LOCAL_STORAGE_ID` (`0L`)，**严禁借用或污染远程 WebDAV 服务器 ID**。
- **Strict Source Isolation & Physical Composite Deduplication (严格源过滤与物理复合键去重法则)**:
  - **单源隔离**：单源检索（`serverId != null`）时，WebDAV 源必须剔除所有本地文件（`content://`、`isLocal`），本地源必须剔除所有远程文件。
  - **全库复合去重**：全库检索（`serverId == null`）跨越 `media_track_index`、`directory_cache`、`track_metadata` 时，必须按物理唯一规范键 `TrackKey(serverId, remotePath)`（本地 `local:${remotePath}`，远程 `${serverId}:${remotePath}`）严格去重。
- **Write-Through & Dual-Source Bounded Checkpoint Skipping (目录写穿透与双源增量检查点跳过法则 - ADR-0033)**:
  - 浏览目录写入 `directory_cache` 时必须同步写穿透 upsert `media_track_index` 并差集清理同目录已删文件；
  - WebDAV 远程目录扫描协程必须受 `kotlinx.coroutines.sync.Semaphore(maxConcurrency = 3)` 严格限制；
  - WebDAV 扫描（比对服务端 `(lastModified, etag)`）与本地 SAF 扫描（比对目录修改时间戳）统一基于 `directory_sync_checkpoint` 表，对未变更子树执行毫秒级增量跳过。
- **SAF Lifecycle Management & Cursor Batch Traversal (SAF 目录权限生命周期与游标批处理性能铁律 - ADR-0032b)**:
  - **长效权限对称维护**：挂载本地目录调用 `ContentResolver.takePersistableUriPermission`；解除挂载时必须成对执行 `releasePersistableUriPermission` 并级联清除缓存与索引。
  - **目录遍历过滤黑名单**：扫描本地 SAF 游标时严禁使用低效易假死的 `DocumentFile.listFiles()`，强制采用 `DocumentsContract.buildChildDocumentsUriUsingTree` 查询 ContentResolver 游标并分批批量组装；
  - **系统级杂质与缩略图目录黑名单标准集**：统一静默过滤系统级杂质目录：`.@eadir`、`#recycle`、`lost.dir`、`.git`、`.thumbnails`、`.cache`、`android`、`__macosx`、`.trash-*`。

---

## 4. Streaming Audio Pipeline, Hardware Defense & Demuxing (流式音频管道、硬件防假死与解复用规范)

- **Pure Ephemeral Streaming & Bounded Range Probing (纯流式零音频落盘与受限切片探针铁律 - ADR-0002, ADR-0003)**:
  远程音频流严格采用纯内存缓冲回放，**严禁将远程音频数据缓存落盘至本地持久化闪存**（流式 `OkHttpClient` 严禁配置磁盘 `Cache`）；后台远程元数据提取（`DefaultTrackMetadataResolver`）必须通过受限 HTTP Range 头部/尾部字节窗口按需探测，**严禁为提取标签或封面而全量下载整首音频文件**。
- **Canonical Sample Rate Resampling (标准采样率升采样防御)**:
  `CanonicalResamplingAudioProcessor` 仅将采样率 `< 44,100 Hz` 的 `16-bit PCM` 流统一升采样至 `44,100 Hz` 双声道 PCM（防厂商 HAL/DSP 路由切换时钟错位假死）；`>= 44,100 Hz` 及 `24-bit` / `32-bit Float` 流必须保持 100% 零损耗直通（Passthrough）。
- **Stream-Only Demuxing & 32-Bit Timestamp Underflow Safeguard (Native 流式单向解复用与时间戳防下溢防线 - ADR-0012)**:
  在 Native C++ `ffmpeg_demuxer.cc` 创建 `AVIOContext` 时**显式将流式 `seek` 回调置为 `nullptr`** 锁定单向流；Native 层强制拦截大于 `0x80000000LL` 的下溢时间戳并截断归零，杜绝 ASF/WMA 前卷减法下溢导致的 1193 小时伪进度与开播静音。
- **Audiophile Specs Resolution & 4-Level Quality Badges (发烧级规格解析与四级音质徽章契约)**:
  全屏播放器与详情弹窗必须基于音频解码容器通过 `AudioQuality.resolveAudiophileSpecs()` 动态解析真实物理规格（采样率 44.1kHz~384kHz、位深 16-bit~32-bit、比特率及 Hi-Res 状态），严禁静态占位符伪造；列表行以药丸徽章（`AudioQualityBadgePill`）形式动态呈现四级音质（`LOSSLESS`、`HIGH_QUALITY`、`STANDARD`、`COMPRESSED`），色彩统一跟随动态 `MaterialTheme.colorScheme.primary`。
- **First-Frame Foreground Elevation & Dual-Cache Memory Trimming (首帧前台提升与双内存缓存协同裁剪 - ADR-0010, ADR-0017)**:
  `WebDavMediaService` 创建首帧必须同步提升前台服务并展示通知（满足 Android 5 秒契约，解耦网络缓冲）；服务显式声明 `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` 规范；暂停时执行宽容窗口延迟降级，防短暂停顿被系统查杀；在 `WebDavApplication` 与 `MainActivity` 中监听 `ComponentCallbacks2.onTrimMemory(level)`，收到 `TRIM_MEMORY_UI_HIDDEN` 时主动同时清空 `ThumbnailMemoryCache` 与 `ArtworkColorExtractor` 的 LRU 内存缓存，彻底释放前台展示开销。
- **Hardware Locks, Audio Focus & Becoming Noisy Protection (双硬件锁、焦点仲裁与拔耳机防护)**:
  流式播放/缓冲期同时持有 CPU `WakeLock` 与高性能 `WifiLock`（`StreamingHardwareLockManager`），暂停即放；`AudioFocusHandler` 将瞬态焦点丢失映射至会话层门控（仅恢复因瞬态焦点中断的回放，Ducking 交由系统混音器 `setWillPauseWhenDucked = false`）；拦截 `ACTION_AUDIO_BECOMING_NOISY` 在耳机拔出或蓝牙断连时立即调用 `appSession.pause()`。
- **Single-Server Self-Signed SSL Isolation (单服务器自签名 SSL 信任隔离原则 - ADR-0004)**:
  自签名证书信任开关（`allowSelfSigned`）在 WebDAV 服务器配置中独立持久化，默认关闭；宽松的 `TrustManager` 与 `HostnameVerifier` 仅作用于该服务器派生的专属 `OkHttpClient`，**严禁提升或污染全局网络客户端与其他服务器连接**。
- **Non-Disruptive Metadata Enrichment & Premature EOF Guard (零中断富化与流式容错自愈)**:
  - 后台富化当前在播曲目元数据时仅响应式更新 Session 与 MediaSession 状态，**严禁重建底层播放器时间线（Timeline）**。
  - `ResilientWebDavDataSource` 遇读超时按字节偏移发起 HTTP Range 透明重连；结合底层传输字节数与上层时长双重比对执行伪 EOF 熔断（Premature EOF Guard），杜绝网络闪断引发提前跳曲。

---

## 5. Fault Lifecycle Symmetry, Cache Elasticity & Queue Invariants (故障生命周期对称、缓存弹性与队列不变量)

- **No Hard SQLite Foreign Keys on Cache Tables**:
  `directory_cache` 与 `track_metadata` 严禁声明指向 `webdav_servers(id)` 的硬外键级联删除，保障跨源歌单与元数据缓存的独立读写弹性。
- **Symmetric Multi-Tier Cache Invalidation on Storage Deletion (存储注销多级缓存级联清理对称律)**:
  - 解绑本地目录（`removeMountedLocalFolder`）时，必须同步清理 `media_track_index`、`directory_sync_checkpoint`、Room L2 `directory_cache`（`LOCAL_STORAGE_ID` 快照）及 `MediaLibraryRepositoryImpl` L1 内存缓存。
  - 删除 WebDAV 服务器（`deleteServer`）时，除清理数据库表、缩略图文件与连接池外，必须同步失效 `MediaLibraryRepository` L1 内存缓存；若被删节点为默认源，自动升格剩余远程节点为默认源。
- **Symmetric Fault Lifecycle & Multi-Trigger Self-Healing (故障对称闭环与四重自愈原则)**:
  凡写入置灰或降级标记（`markTrackInactive`、`markServerInactive`）的接缝，必须在同领域内具备四重自动自愈信道（PROPFIND 探活、测试连接成功、播放解码出声反哺、系统网络恢复广播），严禁只写不销。
- **Diagnostic Aggregation Parity & Granularity Alignment (诊断聚合对等与粒度对齐原则)**:
  表现层将多个故障枚举（`InactiveReason.SERVER_UNREACHABLE`、`SERVER_CREDENTIAL_INVALID`、`FILE_NOT_FOUND`）聚合展示时，领域自愈层必须以相同广度覆盖；服务器级连接/认证故障与恢复必须按 `serverId` 批量同步，严禁仅更新单曲。
- **Atomic Playlist Deduplication & Batch Invariant (歌单批量入库原子去重法则 - ADR-0027)**:
  `PlaylistRepository.addTracksToPlaylist` 必须在单一 Room 事务内基于 `TrackKey(serverId, remotePath)` 物理复合差异排重，仅对全新曲目分配自增 `sortOrder` 并返回插入记录的自增 ID 集合 `List<Long>`（由上层 ViewModel 计算 `addedCount` 与 `skippedCount` 回调），严禁主线程多次往返。
- **Unidirectional Session Routing & Session-Managed Queue (播控单向信道与队列集中托管法则)**:
  - 所有播控入口（UI `PlaybackActions`、系统媒体按键与通知栏 `WebDavMediaSessionCallback`、`PlaybackSessionHost`）必须统一向 `MusicPlayerAppSession` 派发指令，**严禁绕过会话层直调 `AudioPlayerEngine`**。
  - 队列流转与跨曲仲裁由会话层集中管控，底层引擎仅作单流/双流预热渲染容器，严禁自主跨曲跳转；目录回放（`QueueOrigin.DIRECTORY`）严格以当前目录曲目集合为闭环边界，严禁跨父级或兄弟目录越界。
- **Explicit Shuffle & Order Mirroring Invariant (显式洗牌队列与原序镜像不变量 - ADR-0026)**:
  `PlaybackQueue` 必须同时维护打乱序列 `tracks` 与原序镜像 `originalTracks`：
  - 开启随机模式时强制将当前曲目置顶（`currentIndex = 0`），退出随机时利用 `originalTracks` 瞬时恢复原序且无损重寻址当前音轨；
  - 切歌与回溯为指针的线性算术移动（`currentIndex ± 1`），**严禁任何 `randomOrNull()` 动态随机跳曲**；
  - 队列末尾切歌触发确定性的整轮重洗牌（Reshuffle）；“下一首播放”（Play Next）必须向 `tracks` 与 `originalTracks` 双向同步插队。
- **Cold-Start Restoration Gating (冷启动播控恢复门控法则)**:
  进程被杀后经外部广播（蓝牙按键、通知栏 Play）唤醒时，若 `restoreSession()` 尚未完成且队列为空，外部播控指令（`play()`、`togglePlayPause()`、`skipToNext()`、`skipToPrevious()`）必须在协程内挂起等待恢复完成后执行，严禁因竞态丢弃指令。

---

## 6. All-Weather Visual Stage, Color Purity & Fluid Motion (全天候视觉舞台、色彩纯净度防护与流体微动效)

- **Single Living Entity & Slot Architecture (单一真实常驻实体与插槽架构法则)**:
  `MorphingPlayerContainer` 中核心元素（封面、播放键、队列键）仅在根容器拥有唯一常驻实例；`MiniPlayer` 与 `FullPlayerView` 退化为纯几何测量插槽（`Spacer` 占位），严禁重复渲染重影（Double Rendering）或在动画终点通过 `if (isMorphing)` 分支偷换视图（Zero End-State Swaps）。
- **Draw Phase Animation Rule (GPU 绘制阶段变换原则)**:
  60/120fps 形变严禁逐帧修改 `Modifier.size()` / `Modifier.offset()` 触发 CPU 重测重排；必须以固定尺寸为基底，在 `Modifier.graphicsLayer` 内通过 GPU 矩阵变换（`scaleX/Y`、`translationX/Y`、`shape`、`clip`）完成过渡。
- **All-Weather Immersive Stage & Universal Perceptual Tonal Model (全天候暗调舞台与三层感知色调模型 - ADR-0032a)**:
  - 主界面保持深浅双模自适应；全屏播放器（`FullPlayerView`）强制升格为独立暗调流体舞台，前景文字与图标锁定高亮纯白（`#FFFFFF`，对比度 $\ge 11:1$），终结浅色模式白底白字反差失效。
  - 三层感知色调停靠点：Top Stop（高光发散，优先仲裁 `lightVibrantSwatch`，明度 $L \in [0.58f, 0.72f]$，饱和度 $S \ge 0.50f$）；Mid Stop（漫射层，明度归一化在 Tone 32~45，色阶断点延展至 0.48f）；Bottom Stop（深邃暗彩基底，色相锁定封面基调色，明度 $L = 0.085f$，Tone 8~9）。
- **Track-Driven Dynamic Theme Purity & Hex Prohibition (音轨驱动动态换肤、全域 Hex 禁令与黑白封面灰阶防线)**:
  - 全域动态换肤以在播音轨封面（`ArtworkColorExtractor` 基于 `androidx.palette`、HSL 与 CIE L\*a\*b\* 感知彩度提取）为唯一色彩源；UI 组件严禁硬编码孤立 Hex 色值（如 `Color(0x...)`），除主题种子定义文件 `Color.kt` 与色板提取回退实现 `ArtworkColorExtractor.kt` 外，全域强制绑定 `MaterialTheme.colorScheme` 语义 Token（由 `PackagingSeamVerificationTest` 自动化检验）。
  - 当封面彩度低于阈值（CIE L\*a\*b\* `Chroma < 8` 或 HSL `Saturation < 0.12`）或缺失封面时，强制冻结彩色注入并落入纯正中性石墨灰阶（Pure Neutral Slate `#121318`），杜绝黑白封面被误染成杂色。
- **Ambient Fluid Lyrics Viewport & Center Anchoring (沉浸式流体歌词舞台与居中锚定)**:
  歌词列表强制设置 `contentPadding = PaddingValues(vertical = viewportHeight / 2)`，确保当前行精准锚定在屏幕垂直物理居中线；内边距计算必须附带 `coerceAtLeast(0.dp)` 边界保护；在播行放大至 22sp 粗体配 3.5dp Primary 圆角指示条；未唱行对数渐隐。
- **Local Companion Manifest & Industrial LRC Normalization (伴生清单、无损富化与 LRC 规整四律 - ADR-0006, ADR-0009)**:
  - 解析本地目录伴生封面与歌词必须直接透传真实 `content://` Document URI；合并元数据执行字段级保底合并，严禁整项覆盖抹除；`WebDavNotificationProvider` 维护当前封面 Bitmap 的 URI 级内存缓存，0 磁盘 I/O 复用。
  - 封面解析“内嵌优先，外置伴生保底”；歌词解析“外置同名伴生优先，内嵌保底”。
  - LRC 规整四律：1) 展开行首多时间戳；2) 强制清洗剔除行内/词级（Karaoke / Word-level）时间戳；3) 相邻行时间戳相差 $< 300\text{ms}$ 且文本相同时强制去重；4) 相差 $< 300\text{ms}$ 的两行自动合并为单行 `LyricLine(mainText, translation)` 双语对齐模型。
- **Dual-Line Track Row Parity, Motion Tokens & Focus Timing Gating (全域双行列表、动效令牌与焦点时序门禁 - ADR-0028, ADR-0031)**:
  - 全域音轨行（`AudioTrackRow`）固定保持双行高度一致；缺失艺术家/专辑时副标题强制回退显示 `"未知"`，严禁隐藏副标题退化为单行高度。
  - 动效弹簧参数统一定义在 `MotionTokens.kt`（`SpatialSpring` / `EffectsSpring` / `FastSpatialSpring`），严禁散落硬编码魔法 Tween；FAB 浮动按钮维持 $24\text{dp}$ 动态呼吸净空。
  - `FloatingSearchPillCapsule` 的软键盘聚焦 `requestFocus()` 必须严格门控至展开动画进度 $\ge 0.35f$ 挂载完成后触发，杜绝提前抢焦引发系统级崩溃。
