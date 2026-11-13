# Spec: TrackIdentity 规范化、伴生清单两阶段收拢与跨源封面批量自愈 (TrackIdentity & Metadata Healing Consolidation)

Status: `completed`  
ADR: [`docs/adr/0042-track-identity-companion-manifest-and-metadata-healing-consolidation.md`](../../docs/adr/0042-track-identity-companion-manifest-and-metadata-healing-consolidation.md)

## Problem Statement

当前 Aoffee Player 在跨源曲目身份识别、本地目录解绑清理、伴生资源提取以及歌单封面自愈方面存在多处影响使用体验与数据可靠性的问题：
1. **解绑单个本地目录误删全库索引**：用户挂载了多个本地音乐文件夹时，只要解绑其中任意一个，整个 `Local Storage` 下的目录缓存、检索索引、同步检查点与 `Browsing Cursor` 都会被连带清空，导致其余本地文件夹的搜索结果瞬间消失并被迫重新全量扫描。
2. **本地与远程伴生资源行为不一致且易发生碰撞**：本地目录内的同一挂载树下不同专辑子目录可能发生封面错串；当曲目同时存在内嵌歌词与外置同名 `.lrc` 文件时，本地解析错误地让内嵌歌词抢占了外置 `.lrc`；远程 WebDAV 曲目在切歌展示歌词时即使同目录下根本没有 `.lrc` 文件，依然会盲目向服务器发起网络请求造成大量 404 错误。
3. **歌单加载慢且远程封面丢失后无法自愈**：歌单包含大量曲目时每次刷新都会对数据库逐首循环查询；一旦系统清理了图片缓存，本地歌单曲目能后台重新拉取封面，而远程 WebDAV 歌单曲目则完全放弃自愈、长期保持缺失状态。
4. **本地路径身份判定在各处静默分叉**：判断曲目是否属于 `Local Storage` 及生成唯一复合键的逻辑散落在十余个模块中，部分位置已遗漏规范本地目录前缀，存在将本地资源错归至远程服务器配置的风险。

## Solution

依据 `ADR-0042` 与主流成熟播放器（Symfonium、Poweramp、Auxio）的架构实践，将跨源身份、级联清理与元数据/伴生资源管道收拢为内聚的深模块契约：
- **单一规范 `Track Identity`**：以强类型 `TrackIdentity` 取代旧有离散标识与各处散落的前缀字符串判断，统一负责本地存储识别、`LOCAL_STORAGE_ID` 单向钳制与全库物理复合去重键派生。
- **精准不对称 `Storage Cascade Invalidation`**：移除表现层对媒体库的越界全库清理调用；解绑单个 `Mounted Directory` 时严格按树前缀清理目录缓存、检索索引、同步检查点与游标，同时保护跨源歌单的曲目元数据快照；删除远程 `WebDavServer` 时同步定向清理磁盘封面、数据库元数据以及元数据仓储内存中的歌词与封面缓存。
- **两阶段 `Companion Manifest` 与双列歌词存储**：升级 Room 数据库至 v10 拆出独立的伴生歌词路径列，确立「外置伴生 `.lrc` 优先、内嵌歌词永久保底」的双源策略，并在目录清单已确认无 `.lrc` 时跳过播放期盲发请求；把本地与远程的封面/歌词配对、两阶段快显与逻辑目录隔离统一收敛进 `TrackMetadataRepository`。
- **统一批量查询与跨源 `Cover Art Liveness Healing`**：移除歌单仓储内部的逐行查询与本地专属封面检测逻辑，改为调用 `TrackMetadataRepository` 的批量校验与自愈接缝，让远程与本地歌单条目共享零 N+1 查询与连通性门控保护下的自动自愈能力。

## User Stories

1. 作为听众，当我在存储管理页面解绑某一个不再需要的 `Mounted Directory` 时，我希望仅清除该文件夹的缓存、索引与 `Browsing Cursor`，而我挂载的其他本地音乐文件夹依然能即时检索和秒开浏览。
2. 作为听众，当我解绑一个曾经将曲目加入跨源 `Playlist` 的本地文件夹后，我希望该歌单中对应的 `Inactive Playlist Track` 依然完整保留歌曲标题、艺术家、时长和封面快照，而不是变成空白条目。
3. 作为听众，当我在存储管理页面删除一台远程 `WebDavServer` 后，我希望不仅磁盘缓存与数据库中的目录和元数据被清空，后台内存中残存的该服务器歌词和封面缓存也一并失效，避免重新添加同名地址服务器时串出旧数据。
4. 作为听众，当我在 `Local Storage` 挂载树下浏览多个不同专辑子目录时，我希望专辑 A 的目录共享封面只服务专辑 A，绝对不会串用到同挂载树下的专辑 B 或被无封面子目录误伤。
5. 作为听众，当一个包含十几首曲目的专辑目录下只有一张共用 `cover.jpg` 且音轨自身无内嵌封面时，我希望应用在本地仅存储一份共用的封面缩略图，避免重复解码写盘占用闪存空间。
6. 作为听众，当我首次打开一个包含大量曲目的本地或远程目录时，我希望页面先借助同目录的 `Companion Manifest` 在毫秒级呈现已配对的封面与伴生歌词，随后后台再静默补全每首歌的内嵌标题、艺术家、专辑与内嵌封面。
7. 作为听众，当一首曲目同时具备内嵌歌词和同目录同名 `.lrc` 文件时，无论是本地音频还是远程 WebDAV 音频，我都希望优先展示外置 `.lrc` 歌词；若外置 `.lrc` 读取失败或无效，再自动无缝回退到内嵌歌词，不因为曾发现外置 `.lrc` 而丢失内嵌保底文本。
8. 作为听众，当我在播放一首没有同目录 `.lrc` 文件的远程 WebDAV 歌曲时，如果目录列表已确认不存在同名 `.lrc`，我希望播放器直接读取内嵌歌词或呈现空歌词态，不要每次切歌都向服务器发一次注定 404 的请求。
9. 作为听众，当我在远程服务器上新上传了同名 `.lrc` 文件并在媒体库下拉刷新或重扫目录后，我希望重新识别并记录该伴生歌词路径，下次播放即可立即加载新歌词。
10. 作为听众，当应用缓存目录中的封面缩略图被系统清理后，无论我的跨源 `Playlist` 里收藏的是本地曲目还是远程 WebDAV 曲目，打开歌单时都希望后台自动触发 `Cover Art Liveness Healing` 重新恢复封面并持久化更新快照。
11. 作为听众，当我的跨源 `Playlist` 中包含当前已断网或服务器不可达的 `Inactive Playlist Track` 时，我希望歌单刷新不会触发任何无意义的网络自愈探测风暴，直到存储源恢复可达后再恢复自愈。
12. 作为听众，当我打开包含数百首跨源曲目的大型 `Playlist` 时，我希望歌单界面瞬间完成渲染，不因逐首歌单曲查库产生明显的卡顿或掉帧。
13. 作为听众，当应用从旧版本数据库升级到新版本后，我希望我过去提取保存的曲目元数据、封面路径与歌单关联全部完整保留，不丢失任何历史数据。
14. 作为开发者，我希望全项目只有 `TrackIdentity` 一个权威模块定义本地存储判定、`LOCAL_STORAGE_ID` 钳制与物理复合去重键，杜绝在仓储、协调器或模型间出现漏判 `/local/` 前缀的分叉实现。
15. 作为开发者，我希望 `LocalStorageDirectoryResolver` 只专注于通过 SAF 游标列举目录与文件，不再私自读写缩略图或拼装元数据；同样 `PlaylistRepository` 不再包含磁盘文件存活性检查或自愈协程调度。
16. 作为开发者，我希望在单向依赖有向无环图（DAG）的前提下完成服务器删除时的元数据内存缓存失效，不重新引入仓储层环形依赖。

## Implementation Decisions

### 1. 规范化强类型 `TrackIdentity` 模型
- 在领域模型层建立不可变值类型 `TrackIdentity`（取代原有的 `TrackKey`），内部封装 `serverId` 与 `remotePath` 并在构造阶段自动完成本地存储识别（覆盖 `LOCAL_STORAGE_ID`、`content://`、`file://` 及 `/local/` 规范路径前缀）与 `LOCAL_STORAGE_ID (0L)` 单向钳制。
- `TrackIdentity` 统一对外提供：是否为本地曲目判定、跨源物理复合去重键、标准 `AudioTrack.id` 字符串生成以及按源作用域（单源本地、单源远程、全库）的过滤断言。
- 全面迁移 `AudioTrack`、`TrackMetadata`、`TrackMetadataEntity`、`StorageReachabilityTracker`、`PlaylistRepository`、`MediaLibrarySearchCoordinator`、`PlaybackMetadataCoordinator` 与 `DefaultTrackMetadataResolver` 中的旧 `TrackKey` 与散落前缀判定，统一委托 `TrackIdentity` 处理。

### 2. 存储注销前缀级联与单向定向失效
- **消除表现层冗余越界清理**：从存储管理 ViewModel 中移除删除服务器或解绑本地目录后对 `MediaLibraryRepository` 的直接级联调用，确保所有 `Storage Cascade Invalidation` 统一由 `ServerRepository` 单向触发。
- **本地目录树前缀精准清理**：为目录缓存持久化 DAO 补充按 `serverId` 与路径前缀删除的能力。当解绑单个 `Mounted Directory` 时，媒体库仓储仅清除该挂载根路径前缀对应的 `directory_cache`、`media_track_index`、`directory_sync_checkpoint`、L1 内存缓存与关联的 `Browsing Cursor`，同时主动失效根级汇总目录快照以保证根目录列表即时刷新；刻意保留 `track_metadata` 与缩略图快照，防止跨源歌单中的失效条目丢失展示信息。
- **删除远程服务器定向清理**：在保持 `ADR-0037` 单向依赖拓扑的前提下，扩展 `StorageCascadeInvalidator` 或引入独立的服务器元数据失效回调能力，使删除远程 `WebDavServer` 时同步清理磁盘封面、Room `track_metadata` 记录以及 `TrackMetadataRepository` 内存中的歌词缓存与目录封面缓存。

### 3. Room 数据库升级（v9 → v10）与伴生清单两阶段收拢
- **数据库 Schema 演进**：将 `AppDatabase` 升至版本 10 并编写向后兼容的增量迁移脚本，为 `track_metadata` 表新增可空列 `companionLyricsPath`；领域模型 `TrackMetadata` 同步拆分承载 `companionLyricsPath`（外置伴生 `.lrc` 的本地 URI、文件路径或远程路径）与 `lyrics`（内嵌歌词正文永驻保底）。
- **剥离本地解析器冗余职责**：精简 `LocalStorageDirectoryResolver`，使其仅负责通过 SAF 游标查询目录结构并输出 `RemoteDirectory`（含全部子文件与伴生文件清单），彻底移除其内部直接写入 `CoverArtStorage` 与组装 `metadataMap` 的重复路径。
- **两阶段富化与单份共享封面**：由 `TrackMetadataRepository` 统一统筹目录浏览富化：
  - 第一阶段（毫秒级快显）：在 `directoryFiles` 清单中直接配对同目录同名 `.lrc` 写入 `companionLyricsPath`，并按规则配对单曲同名封面或目录共享封面生成**单份**共享缩略图（以封面文件物理标识而非每首歌路径为存储键），快速填充缺失元数据；
  - 第二阶段（后台并发补全）：对音轨执行受限并发 Range/流式提取内嵌标签与内嵌封面，执行字段级无损合并（内嵌封面覆盖目录共享封面，内嵌歌词写入保底字段而绝不覆盖 `companionLyricsPath`）。
- **修复 SAF 逻辑目录作用域碰撞**：目录富化与解析器接口显式传入逻辑目录路径（`directoryPath`）作为目录封面缓存与清单查找作用域，彻底禁止解析器通过对 SAF `content://` URI 按斜杠切分来猜测父目录。
- **修复歌词优先级与零 404 负向缓存**：`TrackMetadataRepository.resolveLyrics` 严格按「读取并解析 `companionLyricsPath`（外置伴生优先）→ 失败或为空时回退提取/读取内嵌 `lyrics`」执行；若目标曲目所在目录已完成伴生清单核验且未发现同名 `.lrc`（例如在目录已入库元数据中明确记录了目录伴生核验状态），播放期直接跳过向 WebDAV 盲发 `GET *.lrc`，仅在目录刷新或重扫时重新探测。

### 4. `TrackMetadataRepository` 统一批量查询与跨源封面存活性自愈
- 在 `TrackMetadataRepository` 接口新增批量查询与存活性自愈契约：接收一批规范 `TrackIdentity` 以及允许发起后台自愈的可达性筛选集合，单次通过 Room `IN` 批量查询读取所有缓存元数据，在内部完成磁盘缩略图物理存活性校验。
- 对于磁盘封面失效或缺失且通过可用性筛选的曲目（同时覆盖 `Local Storage` 与远程 `WebDavServer`），由 `TrackMetadataRepository` 内部使用既有并发信号量与防重入任务表统一调度后台重新提取，并支持将自愈后的新封面路径异步回调或批量同步至调用方。
- 彻底删除 `PlaylistRepositoryImpl` 内部的逐首循环 `getCachedMetadata` 调用、`isCoverDeadOnDisk` 磁盘检查及 `triggerBackgroundCoverHealing` 本地专属后台任务；歌单仓储在组装歌单条目流时，利用 `StorageReachabilityTracker` 提供的可达性状态过滤出非 `Inactive Playlist Track` 集合，一次性委托 `TrackMetadataRepository` 完成批量元数据对齐与跨源封面自愈触发，并批量更新失效快照。

### 5. 架构边界与防回退守卫
- 在 `PackagingSeamVerificationTest` 中增加静态约束校验：验证歌单仓储与本地目录解析器不再引用封面磁盘校验或直接生成缩略图，验证核心数据与领域仓储层不再出现散落的原始 `startsWith("content://")` 前缀判断。

## Testing Decisions

### 测试原则
- 仅通过各模块最高层公共接口与可观察输出（返回值、`Flow`/`StateFlow` 发射结果、数据库查询结果、HTTP 请求日志）编写黑盒验证测试；
- 严禁通过反射访问内部缓存或对源文件行数做脆弱断言；使用内存 Room 数据库与可控 Fake 网络/文件适配器保证测试快速且确定性运行。

### 拟验证的核心接缝与覆盖矩阵
1. **`TrackIdentity` 纯领域接缝**：
   - 验证 `content://`、`file://`、`/local/` 与远程路径在构造时准确分类并强制将本地路径 `serverId` 钳制为 `LOCAL_STORAGE_ID`；验证跨模块去重键与 `AudioTrack.id` 格式完全一致。
2. **`ServerManagementViewModel` / `ServerRepository` ↔ `MediaLibraryRepository` 接缝（`ServerCascadeDeletionTest`、`ServerManagementViewModelTest`）**：
   - 验证挂载两个本地目录后解绑其中一个时，仅被解绑目录的缓存、索引、检查点与 `Browsing Cursor` 被清除，兄弟本地目录与 `track_metadata` 快照完全保留；
   - 验证删除远程 `WebDavServer` 后，除了数据库与封面磁盘文件外，`TrackMetadataRepository` 内存缓存也定向失效，不再向后续同地址新建服务器返回旧歌词或旧封面。
3. **`MediaLibraryRepository` ↔ `TrackMetadataRepository` 接缝（`TrackMetadataRepositoryTest`、`MediaLibraryRepositoryTest`）**：
   - 验证本地与远程目录均遵循「伴生清单快显 → 内嵌提取字段级补全」，同目录多首歌曲共用一张 `cover.jpg` 仅生成单份缩略图；
   - 验证同一 SAF 挂载树下两个不同子目录各自的共享封面互不污染；
   - 验证同时存在外置 `.lrc` 与内嵌歌词时严格优先外置 `.lrc`，外置读取失败时无缝回退内嵌歌词，且目录已核验无 `.lrc` 时播放期发出的 WebDAV `GET *.lrc` 请求次数为 `0`。
4. **`PlaylistRepository` ↔ `TrackMetadataRepository` 接缝（`PlaylistRepositoryTest`）**：
   - 验证跨源歌单同时包含本地和远程曲目且缓存封面被删除时，两者均能触发后台自愈并刷新歌单封面；
   - 验证处于不可达/置灰状态的 `Inactive Playlist Track` 不会触发网络封面自愈请求；
   - 验证多曲目歌单刷新使用批量元数据查询而不发生逐行循环查库。
5. **迁移与架构守卫接缝（`DatabaseMigrationTest`、`PackagingSeamVerificationTest`）**：
   - 验证 Room `v9 -> v10` 迁移完整保留既有 `track_metadata` 数据并正确初始化 `companionLyricsPath`；
   - 验证静态包边界与反散落前缀判定约束。

### 参考先例
- `TrackMetadataRepositoryTest`（基于内存 Room 与 Fake `WebDavClient` 的元数据/歌词黑盒验证）；
- `PlaylistRepositoryTest`（歌单对账与封面自愈流式行为测试）；
- `ServerCascadeDeletionTest` & `DatabaseMigrationTest`（存储级联对称性与 Room 迁移回归验证）；
- `PackagingSeamVerificationTest`（架构不变量静态门禁）。

## Out of Scope

- **组合包 C 范畴**：`Morphing Player` UI 宿主控制权合并、`graphicsLayer` 绘制阶段动画改造、`TabAccessibilityGuard` / 重复详情弹窗整理，以及 `WebDavClient` 测试 Fake 全局抽取去重。
- 音轨歌词界面渲染样式与双语 LRC 解析算法本身（`LrcParser` 既有行为保持不变）。
- 改变跨源歌单的离线置灰触发规则或 `StorageReachabilityTracker` 四重自愈协议。

## Further Notes

- 严格遵守 `CODING_STANDARDS.md` 第 1 节（模块深度与零伪碎片化、600 行单文件预算、黑盒测试表面）、第 2 节（主线程安全与 `CancellationException` 防吞）、第 3 节（跨源身份钳制与 SAF 游标遍历规范）及第 5 节（无硬 SQLite 外键与级联清理对称性）。
- 关联领域文档：`GLOSSARY.md`（`Track Identity`、`Companion Manifest`、`Storage Cascade Invalidation`、`Cover Art Liveness Healing`）与 `docs/adr/0042-track-identity-companion-manifest-and-metadata-healing-consolidation.md`。
