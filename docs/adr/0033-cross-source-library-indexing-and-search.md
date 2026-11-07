# 0033: 统一跨源曲库快速索引与全局搜索架构 (Unified Cross-Source Library Fast Indexing and Global Search Architecture)

## Status

Accepted

## Context

在 Aoffee Player 早期实现中，媒体库的搜索能力主要依赖于对 `directory_cache` 表中存储的 `dataJson` 字段执行 SQL `LIKE %query%` 模糊匹配，再在内存中反序列化完整的 `RemoteDirectory` 对象树。随着本地存储（SAF）一等公民支持（ADR-0032）以及用户收藏音乐规模增长（数千至数万首无损音轨），这种搜索架构暴露了严重的工程瓶颈：

1. **未访问目录无法检索（Undiscovered Silos）**：
   - 依赖目录访问时写入的缓存，用户从未下钻浏览过的深层远程子文件夹或本地文件夹无法被搜索命中。
2. **高频击键内存与 CPU 抖动（Heap & Serialization Churn）**：
   - 每次搜索输入均反序列化整个目录 JSON，不仅触发大量临时对象分配，而且当库容达数千首时导致 UI 出现微卡顿。
3. **CUE 分轨检索缺失（Missing CUE Virtual Track Discovery）**：
   - 整轨抓轨文件（.flac/.wav + .cue）内部的具体歌曲标题、表演者无法被单独搜索，用户无法直接在搜索结果中点击播放单曲分轨。
4. **全量扫描开销巨大（Lack of Incremental Rescan）**：
   - 每次扫描均重新拉取全量目录，消耗大量带宽与电量，极易触发私有云 NAS 或云端 WebDAV 的频控拦截。

## Decision

恪守渐进演进式架构与 ADR-0002（纯在线流式零持久音频缓存）、ADR-0013（原生 SQLite 与协程，拒绝第三方搜索引擎库）以及 ADR-0032（本地存储一等公民）规范，建立统一的跨源快照索引与流式检索管道：

### 1. 轻量专用索引模型与 B-Tree 查询矩阵 (Room v7 Schema & Index Matrix)

- **`media_track_index` 表**：
  - 存储最小化字段：`serverId`、`remotePath`、`fileName`、`fileSize`、`lastModified`、`format`、`isCue`、`parentPath`、`parsedTitle`、`parsedArtist`；
  - 复合主键 `(serverId, remotePath)`，建立关于 `fileName`、`parsedTitle`、`parsedArtist`、`parentPath`、`remotePath` 的复合索引；
- **`directory_sync_checkpoint` 表**：
  - 记录 `(serverId, path, lastModified, etag, lastScannedMs)`，专供增量扫描跳过判断；
- **防级联外键设计与对称数据清理**：
  - 遵循 `CODING_STANDARDS.md` §4，不设置 SQLite 物理外键约束；在 `ServerRepositoryImpl` 中统一由应用层对删除服务器（`deleteServer`）与解绑本地目录（`removeMountedLocalFolder`）执行事务级联清理，杜绝产生孤儿残留与无效搜索结果。

### 2. 本地 SAF 极速游标扫描与伴生 CUE 自动分轨 (`LocalStorageIndexScanner`)

- **树状游标批处理**：
  - 遍历挂载目录树时采用 `DocumentsContract.buildChildDocumentsUriUsingTree` 查询 ContentProvider 游标，排除系统噪声目录（`.Trash-*`, `.@eaDir`, `Android`, `.thumbnails` 等）；
  - 提取文件名智能启发式解析标题与艺术家（`MediaFilenameParser`），分批（Batch）批量插入 `media_track_index`；
- **本地 CUE 无损分轨提取**：
  - 自动识别同级 `.cue` 文件并通过零依赖的 `CueParser` 解析出各虚拟分轨，生成带有 `#cue_X` 虚拟标识的索引项，确保本地整轨镜像即刻可搜。

### 3. 并发受限与基于 ETag/时间戳的 WebDAV 增量同步 (`WebDavSyncScanner`)

- **信号量并发阈值控制（Concurrency Limiter）**：
  - 使用 `kotlinx.coroutines.sync.Semaphore(maxConcurrency = 3)` 与无界 Channel 建立并发上限保护，防止压垮用户家庭 NAS 或触发公共网盘 Rate Limit；
- **毫秒级增量跳过（Incremental Subtree Skipping）**：
  - 扫描前比对服务端返回的 `lastModified` 和 `etag` 与本地 `directory_sync_checkpoint`，未变更的子目录直接跳过网络请求与文件重写，使数千曲目复检在 1~2 秒内收敛完成；
  - 在扫描完成后自动修剪已被远端删除的过期曲目，保持索引绝对一致；
- **网络波动弹性（Transient Network Resilience）**：
  - 单个子目录的网络抖动或 HTTP 异常由管道局部隔离并记录，不中断整体扫描，更不抹除已持久化的曲目索引。

### 4. 虚拟分轨回放解耦与搜索元数据融合 (`LibrarySearchRepositoryImpl`)

- **虚拟轨物理路径解耦与 0 错误回放**：
  - 索引表记录 `id = "$serverId:$parentPath#cue_$trackNumber"` 以满足数据库主键唯一性；
  - 当转换为播放器消费的 `AudioTrack` 时，`remotePath` 严格保持为母带物理文件的远程路径（如 `/Music/Album.flac`），`cuePath` 指向伴生 CUE 文件。起播时底层 `AudioPlayerEngine` 正确直连物理文件并配合 CUE 时间轴精确 Seek，杜绝向服务器请求包含 `#cue_` 的畸形 URL 引发 HTTP 404；
- **低内存流式元数据融合（Metadata Harmonization）**：
  - 查询时不全量加载 `track_metadata` 表，通过 `searchMetadataFlow(query, serverId)` 仅精确拉取命中关键词的 ID3 缓存；
  - 内存中动态融合专辑封面缩略图路径、高精度持续时长与正式专辑名。

### 5. 响应式 UI 同步状态流与双模范围切换 (`DirectoryBrowserUiState` & Screen)

- **非阻塞进度呈现**：
  - 仓储层暴露 `Flow<LibrarySyncState>`（`Idle`、`Scanning`、`Completed`、`Failed`）；
  - 媒体库顶部栏实时展示低侵入的 Material 3 `LinearProgressIndicator` 与已索引曲目计数，完成后平滑淡出，不阻断前台浏览与播放交互；
- **活跃源优先与一键全库检索（Scoped vs. Global Search）**：
  - 默认聚焦当前活跃源（本地存储 `serverId = 0L` 或当前活跃 WebDAV 节点），快速降低搜索结果噪音；
  - 提供 Material 3 `FilterChip` 支持一键切换“当前源”与“全库搜索”；
- **即时回放与面包屑穿梭（Ad-hoc Queue Insertion & Drill-down）**：
  - 点击曲目结果采用 `playNextAndAdvance(track)` 插入至下一首并立即起播，保留用户既有播放队列；
  - 点击目录结果平滑收起搜索并自动定位下钻至目标路径，同步更新面包屑导航。

## Consequences

### Positive
- **搜索性能质的飞跃**：从全 JSON 扫描反序列化演进为原生 SQLite 复合 B-Tree 索引检索，击键响应时间压降至毫秒级，且内存开销几乎为零；
- **全格式与整轨覆盖**：CUE 虚拟分轨与所有常见音频格式（FLAC/WAV/MP3/APE/WMA等）实现一站式检索命中；
- **纯流式与零数据沉降守则（ADR-0002）**：仅持久化曲目结构元数据与检查点，坚决不向本地磁盘写入持久音频字节数据；
- **极简依赖（ADR-0013）**：100% 采用 Android Jetpack Room 与 Kotlin Coroutines，无第三方搜索引擎或高复杂组件引入。

### Compliance & Limits
- 数据库版本锁定为 v7（`MIGRATION_6_7`），单元测试 `DatabaseMigrationTest` 确保从 v1 到 v7 的平滑无损迁移；
- 远程扫描并发严格限制在 3 个以内，避免并发过高引发宿主网络拥塞。
