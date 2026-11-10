# Architectural Decision Records (ADRs)

本文档归档了 Aoffee Player 架构演进全生命周期的 40 篇架构决策记录。

> ⚠️ **CONSUMPTION RULE & PRECEDENCE**:
> 1. **最高权威法典**：所有架构约束与实现不变量以根目录 **`CODING_STANDARDS.md`** 为唯一最高裁决（详见 `AGENTS.md`）；
> 2. **跳过已废弃项（Superseded）**：标为 `Superseded` 的 ADR（如 ADR-0014、0021 已被 ADR-0034 废止）仅作历史演进存根，**严禁作为当前设计依据**；
> 3. **遵守追溯修订（Amended）**：标为 `Amended` 的 ADR 已被后置架构追溯更新（核心追溯：`ADR-0034` 彻底物理清除 CUE 虚拟分轨，`ADR-0035` 彻底解耦浏览源与在播源双轨，`ADR-0025` 废止 SQLite 硬外键），正文中已废弃条款以删除线 `~~...~~` 提示；
> 4. **编号递增规则**：历史存在 `0032a`（Palette 动态取色）与 `0032b`（SAF 本地存储）并列，后续新增 ADR 统一从 **`0037`** 起严格按四位数字单调递增。

---

## ADR 全生命周期状态矩阵 (Index Matrix)

| 编号 | 决策标题 | 当前状态 | 追溯说明 / 取代来源 |
| :--- | :--- | :---: | :--- |
| **0001** | [Use AndroidX Media3 with FFmpeg Extension](0001-use-androidx-media3-with-ffmpeg-extension.md) | `Accepted` | 奠定 Media3 + FFmpeg 核心播放管线基石 |
| **0002** | [Pure Online Streaming Without Persistent Disk Cache](0002-pure-online-streaming-without-persistent-disk-cache.md) | `Accepted` | 确立纯内存流式回放、零音频持久落盘铁律 |
| **0003** | [On-Demand Incremental Metadata Extraction via HTTP Range](0003-on-demand-incremental-metadata-extraction-via-http-range.md) | `Accepted` | 受限 HTTP Range 探测标签与封面 |
| **0004** | [Configurable Self-Signed SSL Trust](0004-configurable-self-signed-ssl-trust.md) | `Accepted` | 单服务器自签名 SSL 信任隔离原则 |
| **0005** | [Target Android 10 (API 29) Minimum SDK](0005-target-android-10-api-29-minimum-sdk.md) | `Accepted` | 最低 SDK 版本锁定为 API 29 |
| **0006** | [Dual-Source Lyrics Resolution Strategy](0006-dual-source-lyrics-resolution.md) | `Accepted` | 伴生 LRC 与内嵌歌词双源回退解析 |
| **0007** | [Scaffold-Hoisted Navigation and Floating Miniplayer](0007-scaffold-hoisted-navigation-and-floating-miniplayer.md) | **`Superseded`** | **已被 ADR-0028 与 ADR-0031 胶囊流体形变取代** |
| **0008** | [Persistent Directory Cache with SWR and Complete Session Resume](0008-persistent-directory-cache-with-swr-and-session-resume.md) | `Amended` | 由 ADR-0025 废止硬外键，ADR-0035 解耦双轨源 |
| **0009** | [Dual-Source Artwork Pipeline and Industrial LRC Normalization](0009-dual-source-artwork-and-bilingual-lrc-normalization.md) | `Accepted` | 伴生封面透传与工业级 LRC 规整四律 |
| **0010** | [Synchronous Service Elevation and Dynamic Authenticated MediaSource](0010-synchronous-service-elevation-and-dynamic-authenticated-mediasource.md) | `Amended` | 首帧前台提升；适配器由 ADR-0025 收敛合并 |
| **0011** | [Deepen Track Metadata Repository and Internalize Resolver](0011-deepen-track-metadata-repository-and-internalize-resolver.md) | **`Superseded`** | **已被 ADR-0025 统一播放引擎与仓储层架构取代** |
| **0012** | [Stream-Only Demuxing Pipeline and Timestamp Underflow Safeguard](0012-stream-only-demuxing-and-timestamp-underflow-protection.md) | `Accepted` | Native 单向流解复用与 32 位时间戳防下溢 |
| **0013** | [64-Bit Only Native ABI and R8 Binary Thinning](0013-64bit-only-abi-and-r8-binary-thinning.md) | `Accepted` | 64 位 ABI 独占与 APK $\le 7.5\text{MB}$ 预算 |
| **0014** | [CUE Virtual Track In-Stream Mapping and Folder-Bounded Gapless Streaming](0014-cue-virtual-track-and-folder-ring-gapless-streaming.md) | **`Superseded`** | **已被 ADR-0034 彻底全盘废除与物理清除** |
| **0015** | [Session-Managed Queue Progression and Fault-Tolerant Streaming Pipeline](0015-session-managed-queue-and-fault-tolerant-streaming-pipeline.md) | `Amended` | CUE 调度模型已被 ADR-0034 清除 |
| **0016** | [Deepen Audio Player Engine to Stream Rendering Pipeline and Unify Session Intent](0016-deepen-player-engine-to-stream-rendering-pipeline.md) | `Amended` | CUE 时间轴已被 ADR-0034 清除 |
| **0017** | [Resilient Background Playback, Deferred Demotion, Dual Hardware Locks](0017-resilient-background-playback-and-battery-optimization-whitelist.md) | `Amended` | CUE 时间轴已被 ADR-0034 清除 |
| **0018** | [Unify System Playback Intent Routing Through App Session](0018-unify-system-playback-intent-routing-through-app-session.md) | `Amended` | 冷启动意图门控由 ADR-0035 统一规范 |
| **0019** | [Streamline Playback Pipeline and Consolidate Session Host](0019-streamline-playback-pipeline-and-consolidate-session-host.md) | `Amended` | 凭据统一由 ADR-0035 队列管理器解析 |
| **0020** | [跨服务器本地歌单与增量曲库即时搜索](0020-cross-server-playlists-and-incremental-library-search.md) | `Amended` | CUE 由 ADR-0034 废除，搜索由 ADR-0033 升级 |
| **0021** | [深层 CUE 解析器下沉与消除网络客户端泄漏](0021-deepen-cue-sheet-resolver-and-purge-network-leakage.md) | **`Superseded`** | **已被 ADR-0034 彻底全盘废除与物理清除** |
| **0022** | [歌单仓储加深与下沉连通性及封面自愈](0022-deepen-playlist-repository-with-reachability-and-artwork-self-healing.md) | `Amended` | 歌单自愈探活下沉；连通性管理由 ADR-0038 解耦至 StorageReachabilityTracker |
| **0023** | [播放会话内部接缝化与解构上帝类](0023-internal-seams-and-session-decomposition.md) | `Amended` | CUE 由 ADR-0034 清除，双轨源由 ADR-0035 解耦 |
| **0024** | [下沉目录富化与搜索历史至仓储层并轻量化视图模型](0024-deepen-directory-and-search-repositories-and-slim-viewmodel.md) | **`Superseded`** | **已被 ADR-0036 彻底取代并升格为 MediaLibraryRepository** |
| **0025** | [深化播放引擎与收敛仓储层](0025-deepen-player-engine-and-consolidate-repositories.md) | `Amended` | 废除硬外键；CUE 骨架由 ADR-0034 清除 |
| **0026** | [显式洗牌播放队列与原序镜像](0026-explicit-shuffled-playback-queue-and-order-mirroring.md) | `Amended` | CUE 洗牌由 ADR-0034 清除；确立原序镜像不变量 |
| **0027** | [批量添加到歌单与上下文多选模式](0027-batch-playlist-addition-and-contextual-selection.md) | `Accepted` | 歌单批量入库原子去重与上下文多选底栏 |
| **0028** | [Material 3 Expressive 界面重构、全屏流体播放器与双行规范](0028-material-3-expressive-elevation-and-dynamic-theming.md) | `Accepted` | M3E 界面重构与 AudioTrackRow 双行规范 |
| **0029** | [品牌更名为 Aoffee Player 及自适应图标规范](0029-rebranding-to-aoffee-player-and-adaptive-launcher-icon-specification.md) | `Accepted` | 正式更名为 Aoffee Player，包名维持 com.webdav.player |
| **0030** | [全链路服务器故障自愈与多触发恢复网络](0030-full-lifecycle-server-fault-self-healing-and-multi-trigger-recovery.md) | `Amended` | 级联删除由 ADR-0035 闭环；持久化置灰由 ADR-0038 迁移为内存响应式流 |
| **0031** | [Material 3 Expressive 动效系统与流体胶囊形变架构](0031-material-3-expressive-motion-and-capsule-morphing.md) | `Accepted` | 统一 MotionTokens 与 GPU 矩阵形变过渡 |
| **0032a** | [AndroidX Palette 官方多色提取、深邃暗调舞台与感知色调](0032a-androidx-palette-and-m3-expressive-dynamic-theming.md) | `Accepted` | 三层感知色调模型与 Monochrome Safeguard |
| **0032b** | [本地存储 SAF 挂载集成与跨源多模回放管道](0032b-local-storage-saf-and-cross-source-playback.md) | `Amended` | CUE 由 ADR-0034 清除；确立 LOCAL_STORAGE_ID 与 SAF 架构 |
| **0033** | [统一跨源曲库快速索引与全局搜索架构](0033-cross-source-library-indexing-and-search.md) | `Amended` | CUE 检索由 ADR-0034 清除；确立 media_track_index 表 |
| **0034** | [彻底废弃与物理清除 CUE 虚拟分轨支持](0034-deprecate-and-purge-cue-sheet-support.md) | `Accepted` | 物理清除 4,500+ 行 CUE 模块，回归 1:1 真实文件 |
| **0035** | [跨源会话双轨解耦与对称存储生命周期](0035-cross-source-session-decoupling-and-symmetric-storage-lifecycle.md) | `Accepted` | browsingServer 与 activeServer 双轨彻底解耦 |
| **0036** | [折叠媒体库浏览与索引检索为单一深模块](0036-consolidate-media-library-repository.md) | `Accepted` | 折叠 Directory 与 Search 仓储，确立 MediaLibraryRepository 深模块 |
| **0037** | [存储生命周期级联失效与单向仓储接缝](0037-storage-lifecycle-cascade-and-unidirectional-repository-seams.md) | `Accepted` | 提炼 StorageCascadeInvalidator 窄接口，斩断仓储层环形接缝 |
| **0038** | [从歌单仓储解耦存储源连通性追踪器与内存响应式自愈](0038-decouple-storage-reachability-from-playlist-repository.md) | `Accepted` | 提炼 StorageReachabilityTracker 深模块，解耦播控/歌单与内存状态流 |
| **0039** | [从播控会话剥离浏览游标并收敛至媒体库深模块](0039-purge-browsing-cursor-from-playback-session.md) | `Accepted` | 消除目录切换 DataStore 刷盘风暴，彻底落实 ADR-0035 终局解耦 |
