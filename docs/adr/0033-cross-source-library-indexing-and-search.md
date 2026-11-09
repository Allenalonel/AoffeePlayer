# 0033: 统一跨源曲库快速索引与全局搜索架构 (Unified Cross-Source Library Fast Indexing and Global Search Architecture)

## Status

Accepted (Amended by ADR 0034 and ADR 0035: CUE virtual track indexing purged; write-through directory indexing enforced)

> ⚠️ **AMENDMENT NOTICE**:
> - **状态**: `Accepted (Amended by ADR 0034 and ADR 0035)`
> - **现行法典**: 技术约束以 `CODING_STANDARDS.md` 为唯一权威终审。
> - **核心修订**: 
>   1. 决策第 1、2、4 节中的 CUE 虚拟分轨检索与 `isCue` 字段已在 ADR-0034 中被物理清除，Room v8 已物理抹除 `isCue` 列；
>   2. 媒体库浏览与在播流凭据由 ADR-0035 执行双轨解耦。

## Context

在 Aoffee Player 早期实现中，媒体库的搜索能力主要依赖于对 `directory_cache` 表中存储的 `dataJson` 字段执行 SQL `LIKE %query%` 模糊匹配，再在内存中反序列化完整的 `RemoteDirectory` 对象树。随着本地存储（SAF）一等公民支持（ADR-0032b）以及用户收藏音乐规模增长（数千至数万首无损音轨），这种搜索架构暴露了严重的工程瓶颈：

1. **未访问目录无法检索（Undiscovered Silos）**：
   - 依赖目录访问时写入的缓存，用户从未下钻浏览过的深层远程子文件夹或本地文件夹无法被搜索命中。
2. **高频击键内存与 CPU 抖动（Heap & Serialization Churn）**：
   - 每次搜索输入均反序列化整个目录 JSON，不仅触发大量临时对象分配，而且当库容达数千首时导致 UI 出现微卡顿。
3. **CUE 分轨检索缺失（Missing CUE Virtual Track Discovery）**：
   - 整轨抓轨文件（.flac/.wav + .cue）内部的具体歌曲标题、表演者无法被单独搜索，用户无法直接在搜索结果中点击播放单曲分轨。
4. **全量扫描开销巨大（Lack of Incremental Rescan）**：
   - 每次扫描均重新拉取全量目录，消耗大量带宽与电量，极易触发私有云 NAS 或云端 WebDAV 的频控拦截。

## Decision

恪守渐进演进式架构与 ADR-0002（纯在线流式零持久音频缓存）、ADR-0013（原生 SQLite 与协程，拒绝第三方搜索引擎库）以及 ADR-0032b（本地存储一等公民）规范，建立统一的跨源快照索引与流式检索管道：

### 1. 轻量专用索引模型与 B-Tree 查询矩阵 (Room v7 Schema & Index Matrix)

- **`media_track_index` 表**：
  - 存储最小化字段：`serverId`、`remotePath`、`fileName`、`fileSize`、`lastModified`、`format`、~~`isCue`~~ *(已在 ADR-0034 Room v8 物理抹除)*、`parentPath`、`parsedTitle`、`parsedArtist`；
  - 复合主键 `(serverId, remotePath)`，建立关于 `fileName`、`parsedTitle`、`parsedArtist`、`parentPath`、`remotePath` 的复合索引；
- **`directory_sync_checkpoint` 表**：
  - 记录 `(serverId, path, lastModified, etag, lastScannedMs)`，专供增量扫描跳过判断；
- **防级联外键设计与对称数据清理**：
  - 遵循 `CODING_STANDARDS.md` §4，不设置 SQLite 物理外键约束；在 `ServerRepositoryImpl` 中统一由应用层对删除服务器（`deleteServer`）与解绑本地目录（`removeMountedLocalFolder`）执行事务级联清理，杜绝产生孤儿残留与无效搜索结果。

### 2. 本地 SAF 极速游标扫描与伴生 CUE 自动分轨 (`LocalStorageIndexScanner`)

- **树状游标批处理**：
  - 遍历挂载目录树时采用 `DocumentsContract.buildChildDocumentsUriUsingTree` 查询 ContentProvider 游标，排除系统噪声目录（`.Trash-*`, `.@eaDir`, `Android`, `.thumbnails` 等）；
  - 提取文件名智能启发式解析标题与艺术家（`MediaFilenameParser`），分批（Batch）批量插入 `media_track_index`；
- **~~本地 CUE 无损分轨提取~~ *(Deprecated & Purged by ADR-0034)*`**:
  - *(已在 ADR-0034 彻底物理移除：代码库完全清除 CUE 分轨逻辑，仅维护 1:1 物理真实文件索引)*。

### 3. 并发受限与基于 ETag/时间戳的 WebDAV 增量同步 (`WebDavSyncScanner`)

- **信号量并发阈值控制（Concurrency Limiter）**：
  - 使用 `kotlinx.coroutines.sync.Semaphore(maxConcurrency = 3)` 与无界 Channel 建立并发上限保护，防止压垮用户家庭 NAS 或触发公共网盘 Rate Limit；
- **毫秒级增量跳过（Incremental Subtree Skipping）**：
  - 扫描前比对服务端返回的 `lastModified` 和 `etag` 与本地 `directory_sync_checkpoint`，未变更的子目录直接跳过网络请求与文件重写，使数千曲目复检在 1~2 秒内收敛完成；
  - 在扫描完成后自动修剪已被远端删除的过期曲目，保持索引绝对一致；
- **网络波动弹性（Transient Network Resilience）**：
  - 单个子目录的网络抖动或 HTTP 异常由管道局部隔离并记录，不中断整体扫描，更不抹除已持久化的曲目索引。

### 4. 搜索结果多源融合与源隔离 (`LibrarySearchRepositoryImpl`)

- **~~虚拟轨物理路径解耦与 0 错误回放~~ *(Deprecated by ADR-0034)*`**:
  - *(已在 ADR-0034 彻底物理废除)*；
- **低内存流式元数据融合（Metadata Harmonization）**：
  - 查询时不全量加载 `track_metadata` 表，通过 `searchMetadataFlow(query, serverId)` 仅精确拉取命中关键词的 ID3 缓存；
  - 内存中动态融合专辑封面缩略图路径、高精度持续时长与正式专辑名。
- **严格源隔离准入与物理复合去重（Strict Source Isolation & Composite Deduplication）**：
  - 单源搜索（`serverId != null`）时，对曲目索引、目录快照及元数据实施严格防御式过滤：当目标源为 WebDAV 时，坚决剔除任何 `content://` 或本地音轨；当目标源为本地时，坚决剔除远程音轨，杜绝源间穿透；
  - 全库跨源搜索（`serverId == null`）时，建立规范物理键：本地音轨统一映射为 `local:${remotePath}`，远程音轨映射为 `${serverId}:${remotePath}`，彻底根除跨表重叠导致的单曲重复渲染。
- **本地存储身份钳制与历史数据自愈（Local ServerId Clamping & Self-Healing）**：
  - 播放会话（`MusicPlayerAppSessionImpl`）与元数据协调器（`PlaybackMetadataCoordinator`）播放本地曲目时，始终锁定为 `WebDavServer.createLocalStorage()`；
  - 实体构造（`TrackMetadataEntity.fromDomain`）强制将所有 `content://` 路径的 `serverId` 钳制为 `LOCAL_STORAGE_ID = 0L`，严禁借壳继承当前活跃 WebDAV 节点的 ID；
  - 应用启动时自动触发 `TrackMetadataDao.cleanupCorruptLocalMetadata()`，物理清除历史遗留的错标数据。

### 5. 响应式 UI 同步状态流与一体化分段胶囊 (`DirectoryBrowserUiState` & Screen)

- **非阻塞进度呈现**：
  - 仓储层暴露 `Flow<LibrarySyncState>`（`Idle`、`Scanning`、`Completed`、`Failed`）；
  - 媒体库顶部栏实时展示低侵入的 Material 3 `LinearProgressIndicator` 与已索引曲目计数，完成后平滑淡出，不阻断前台浏览与播放交互；
- **一体化滑块分段胶囊（Segmented Pill Capsule）**：
  - 摒弃视觉权重轻、状态反差模糊的传统 `FilterChip`，重构为双槽圆角胶囊滑轨（`SearchScopeSegmentedPill`）；
  - 选中的栏位由**实体高亮的主题色药丸滑块（配合 2dp 物理阴影与中等弹性物理动效）**覆盖，文字高亮加粗，并配备直观语义图标（当前源展示设备/云端图标，全库展示全局图标）；未选中的栏位为低沉次要文字，彻底解决用户“无法一眼看清当前在哪个栏位”的视觉痛点；
- **全自动静默索引与手势归口（Zero-Friction Silent Auto-Indexing）**：
  - 移除顶部栏右上角突兀且违背搜索逻辑的 `⋮` 二级“同步媒体库”按钮，恢复清爽纯粹的单搜索图标；
  - 索引构建全面转入自动化：添加 WebDAV 服务器、挂载本地 SAF 目录、应用启动切源或进入搜索时，由系统在后台协程静默触发增量同步；
  - 手动刷新体验自然归口至列表顶部的**下拉刷新（Pull-to-Refresh）**，在刷新目录的同时顺带复检曲库增量快照。
- **即时回放与面包屑穿梭（Ad-hoc Queue Insertion & Drill-down）**：
  - 点击曲目结果采用 `playNextAndAdvance(track)` 插入至下一首并立即起播，保留用户既有播放队列；
  - 点击目录结果平滑收起搜索并自动定位下钻至目标路径，同步更新面包屑导航。

## Consequences

### Positive
- **搜索性能质的飞跃**：从全 JSON 扫描反序列化演进为原生 SQLite 复合 B-Tree 索引检索，击键响应时间压降至毫秒级，且内存开销几乎为零；
- **全格式覆盖**：所有常见音频格式（FLAC/WAV/MP3/APE/WMA/OGG/M4A/AAC等）实现一站式检索命中（CUE 虚拟分轨已在 ADR-0034 清除）；
- **纯流式与零数据沉降守则（ADR-0002）**：仅持久化曲目结构元数据与检查点，坚决不向本地磁盘写入持久音频字节数据；
- **极简依赖（ADR-0013）**：100% 采用 Android Jetpack Room 与 Kotlin Coroutines，无第三方搜索引擎或高复杂组件引入。

### Compliance & Limits
- 数据库 Schema 演进（Room v7 引入索引用表 `MIGRATION_6_7`，随后由 ADR-0034 Room v8 `MIGRATION_7_8` 彻底物理抹除 `isCue` 字段），单元测试 `DatabaseMigrationTest` 确保全版本平滑无损迁移；
- 远程扫描并发严格限制在 3 个以内，避免并发过高引发宿主网络拥塞。
