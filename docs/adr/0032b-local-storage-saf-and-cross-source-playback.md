# 0032b: 本地存储 SAF 挂载集成与跨源多模回放管道 (Local Storage SAF Integration and Cross-Source Playback Pipeline)

## Status

Accepted (Disambiguated as 0032b alongside 0032a [0032a-androidx-palette](0032a-androidx-palette-and-m3-expressive-dynamic-theming.md); Amended by ADR 0034 and ADR 0035: CUE companion parsing purged; dual-track storage state decoupled)

> ⚠️ **AMENDMENT NOTICE**:
> - **状态**: `Accepted (Amended by ADR 0034 and ADR 0035)`
> - **现行法典**: 技术约束以 `CODING_STANDARDS.md` 为唯一权威终审。
> - **核心修订**: 
>   1. 决策第 3 节中的伴生 CUE 虚拟分轨已被 ADR-0034 彻底废止清除；
>   2. 决策第 5 节中的复合去重键去除了 `virtualTrackId`，统一由 `TrackKey(serverId, remotePath)` 统摄；
>   3. 媒体库浏览与在播凭据由 ADR-0035 执行双轨解耦。

## Context

Aoffee Player 最初设计为面向私有云与家庭 NAS 的高保真 WebDAV 流式播放器。随着用户群体对无损发烧音频的深度需求，大量用户在手机内置存储或外置 MicroSD 卡中存储了大量 Hi-Res 母带、CD/SACD 分轨抓轨（CUE 伴生音频）以及伴生双语歌词（.lrc）。然而既有系统存在以下核心结构性限制：

1. **终端视窗概念狭隘**：
   - 底部导航栏第三项固定为“服务器”，仅支持配置基于 HTTP/HTTPS 的 WebDAV 远程节点，无法引用手机本地已有的音频文件夹。
2. **离线与断网可用性断崖**：
   - 媒体库（`DirectoryBrowser`）严格与远程服务器绑定。在地铁、飞机或无网络覆盖环境下，整个媒体库无法使用，本地无损文件无法被索引与播放。
3. **歌单孤岛（Playlist Silo）**：
   - 自定义歌单（`Playlist`）此前只支持存储远程服务器文件，无法收录本地音频，更无法构建“云端 + 本地”混合播放的统一音乐歌单。
4. **Android 存储规范与权限安全约束**：
   - 遵循现代 Android（Android 10 ~ 14+）平台规范，严禁申请侵犯用户隐私的 `MANAGE_EXTERNAL_STORAGE`（所有文件管理权限），必须采用 Google 官方推荐的 Storage Access Framework（SAF）树状授权方案，并通过持久化 URI 权限（`takePersistableUriPermission`）实现跨设备重启的长效保活。

## Decision

恪守渐进演进式架构（Smooth Evolutionary Architecture）与最小侵入准则，在完整保留原有 WebDAV 纯流式优势的基础上，将本地存储升格为一等公民：

### 1. 统一存储源概念与保留保留标识符 (Unified Storage Domain & `LOCAL_STORAGE_ID`)

- **领域常量与类型投影**：
  - 定义标准领域常量 `const val LOCAL_STORAGE_ID = 0L`；
  - 扩展 `val WebDavServer.isLocal: Boolean get() = id == LOCAL_STORAGE_ID`；
  - 构造单例领域实例工厂 `WebDavServer.createLocalStorage(isDefault)`，URL 协议头标识为 `content://local`；
  - 仓储层 `ServerRepository` 在 `getAllServers()` 中始终置顶发射本地存储节点；在 `deleteServer()` 中设置不可删除防御护栏（Guard）。
- **底栏导航视窗升格**：
  - `AppDestination.SERVER_LIST` 标签由“服务器”升级为“存储”（Storage），保留图标 `AppIcons.Storage`；
  - 存储页面顶部固定常驻不可删除的“本地存储”卡片，清晰展示挂载目录数与活跃指示，点击一键切换为活跃源并联动直达媒体库。

### 2. SAF 目录挂载管理与持久化授权 (SAF Folder Management & Room Persistence)

- **持久化元数据模型**：
  - 新增 `LocalStorageFolderEntity(uriString, displayName, dateAdded)` 与 `LocalStorageFolderDao`；
  - 数据库平滑升级至版本 6（`MIGRATION_5_6`），无损保留既有服务器、歌单与目录缓存数据；
- **原生 SAF 树状选择与权限持久化**：
  - “管理目录”弹层支持调起系统 `ACTION_OPEN_DOCUMENT_TREE` 原生文件选择器；
  - 选择后通过 `ContentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` 锁定长效授权；
  - 移除目录时主动调用 `releasePersistableUriPermission` 释放权限并清理数据。

### 3. 本地目录树游标遍历与 SWR 缓存注入 (`LocalStorageDirectoryResolver`)

- **高效游标枚举**：
  - 采用 `DocumentsContract.buildChildDocumentsUriUsingTree` 查询系统 ContentProvider 游标，坚决避开高开销的 `DocumentFile.listFiles()` 反射开销；
  - 媒体库根路径 `/` 渲染所有挂载文件夹卡片（`/local/<encodedTreeUri>/`），子路径支持任意深度下钻导航；
  - 媒体库面包屑条（`DirectoryBreadcrumbStrip`）智能解码并展示挂载文件夹与子目录名；
- **伴生资源关联与封面/歌词匹配**：
  - ~~自动枚举同级 `.cue` 文件并通过 `CueParser` 解析出虚拟分轨与 CUE 专辑卡片~~ *(已在 ADR-0034 彻底物理移除)*；
  - 自动按同文件名匹配同级伴生 `.lrc` 歌词与封面图（`cover.jpg`, `folder.jpg` 等）；
  - 将探测结果序列化为 JSON 注入已有的 Room `DirectoryCacheDao`，二次访问享受 0ms 瞬间 SWR 缓存加载。

### 4. ExoPlayer 本地直放管道与硬件锁解耦 (Local Playback Pipeline & Hardware Locks)

- **MediaSource 零拷贝直接装载**：
  - 在 `WebDavMediaSourceBuilder` 中识别 `server.isLocal` 或 `content://` / `file://` URI，使用 `DefaultDataSource.Factory(context)` 创建 `ProgressiveMediaSource`，旁路 OkHttp 网络栈与 Digest 认证；
  - 沿用成熟的音频解码器工厂，无缝兼容 FLAC、WAV、MP3 与软解 WMA 格式。
- **动态硬件锁与看门狗旁路**：
  - 播放本地曲目时，自动释放或旁路网络专有的 `WifiLock` 与弱网超时看门狗定时器，防止误报网络故障；
  - 保留 CPU `WakeLock` 与前台通知栏服务，确保黑屏与后台切歌稳定持续出声；
  - 切回 WebDAV 曲目时，无缝重新加锁与恢复网络看门狗。

### 5. 跨源混编歌单与对称失效自愈 (Hybrid Playlists & Symmetric Self-Healing)

- **混编持久化与复合去重**：
  - `PlaylistDao` 与 `PlaylistRepositoryImpl` 统一支持存储 `serverId = LOCAL_STORAGE_ID` 与本地 Document URI；
  - 批量收录音轨时，基于规范物理复合键 `TrackKey(serverId, remotePath)` 执行原子去重（CUE 分轨维度已在 ADR-0034 废止）；
- **对称失效与自愈网络（Symmetric Fault Lifecycle）**：
  - 本地曲目探活通过 `ContentResolver.query` 或打开文件描述符校验；
  - 若 SD 卡拔出或文件被删除，音轨优雅置灰显示 `FILE_NOT_FOUND`，切歌时静默平滑跳过；
  - 当 SD 卡重新插入或文件恢复后，`triggerReachabilitySelfHealing` 自动探活并清除置灰，恢复高光可播状态。

## Consequences

### Positive
- **双引擎兼备**：保留纯流式零磁盘占用特性的同时，赋予播放器工业级本地音乐体验。
- **隐私合规**：严格基于 SAF 最小授权，无安全敏感权限风险。
- **零缓冲秒开**：本地音频走系统内核文件描述符直传，跳过网络握手，起播与 Seek 达到原生毫秒级响应。
- **跨源无缝**：用户可以在同一个歌单内随心混合私有云与本地歌曲，切歌体验平滑连贯。

### Compliance & Limits
- 支持 Android 10 (API 29) 至 Android 14+ (API 34)；
- 严禁引入任何额外第三方存储依赖，打包体积、64-bit ABI 瘦身与 R8 混淆门禁保持 100% 达标。
