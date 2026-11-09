# 0036: 折叠媒体库浏览与索引检索为单一深模块 (Consolidate Media Browsing and Index Search into Deep MediaLibraryRepository)

## Status

Accepted (Supersedes ADR-0024; Amends ADR-0033 and ADR-0035)

## Context

随着 Aoffee Player 演进至本地与远程双源混编体系，媒体库的浏览与全库检索能力逐步成为用户交互的核心枢纽。然而，在现有实现中，媒体库领域层存在显著的人为接缝割裂与浅层架构摩擦：

1. **仓储接缝人为分裂与双重内存缓存 (Split Repositories & Dual LRU Caches)**:
   - 系统被人为划分为 `DirectoryRepository`（负责目录层级浏览与 SWR 缓存）与 `LibrarySearchRepository`（负责全库检索、扫描调度与搜索历史）；
   - 两者各自维护独立的 `RemoteDirectory` 内存 LRU 缓存（前者容量 50，后者容量 100 用于反序列化 `directory_cache.dataJson`），存在重复占用内存与一致性竞态隐患；
   - `DirectoryRepositoryImpl` 在浏览发现音轨时必须执行写穿透更新索引（`syncDirectoryToMediaIndex`），而全库检索又由另一个仓储发起，曲库索引的生产端与消费端分属两处。
2. **检索冗余与反序列化抖动 (Search Churn & JSON Fallback)**:
   - 尽管 ADR-0033 引入了专用的 `media_track_index` B-Tree 数据表，但在 `LibrarySearchRepositoryImpl` 中依然同时查询 `directory_cache` 并对所有命中的 `dataJson` 执行耗时的 JSON 反序列化及内存去重，造成高频击键时的内存分配与 CPU 抖动。
3. **表现层双重注入与编排心智 (ViewModel General Contractor Smell)**:
   - `DirectoryBrowserViewModel` 被迫同时注入 `DirectoryRepository` 与 `LibrarySearchRepository` 两个句柄，分别监听目录内容、同步进度流、检索结果与历史记录，并在切源与刷新时手动在两个仓储间协调状态。
4. **存储注销级联依赖严重外溢 (Storage Invalidation Leakage)**:
   - 当在 `ServerRepositoryImpl` 中删除服务器（`deleteServer`）或解绑本地目录（`removeMountedLocalFolder`）时，仓储被迫直接注入并协调 4 个底层的 DAO（`directoryCacheDao`、`trackMetadataDao`、`mediaTrackIndexDao`、`directorySyncCheckpointDao`），导致 `ServerRepositoryImpl` 构造参数高达 9 个，持久层内部结构严重泄漏。

## Decision

基于深模块（Deep Module）设计准则、接口即测试表面与零伪碎片化铁律，将目录浏览、曲库索引、增量扫描与全库检索彻底折叠为单一深层媒体库仓储模块：

### 1. 确立单一深模块接口 `MediaLibraryRepository` (Unified Deep Seam)
- 物理废除原 `DirectoryRepository` 与 `LibrarySearchRepository` 接口；
- 在 `domain.repository` 包中建立统一的权威媒体库仓储接口 `MediaLibraryRepository`，涵盖：
  - **SWR 响应式目录浏览**: `observeDirectoryContent(server, path, forceRefresh): Flow<DirectoryContent>`, `getCachedDirectory(server, path): RemoteDirectory?`；
  - **跨源与单源 B-Tree 检索**: `search(query: String, serverId: Long?): Flow<LibrarySearchResult>`；
  - **增量扫描调度与进度流**: `observeSyncState(serverId: Long?): Flow<LibrarySyncState>`, `syncServer(server: WebDavServer, force: Boolean): LibrarySyncResult`；
  - **搜索历史管理**: `getSearchHistory(): List<String>`, `recordSearchQuery(query: String): List<String>`, `clearSearchHistory()`；
  - **统一存储注销级联清理接缝**: `clearStorageData(serverId: Long)`, `clearLocalFolderData(uriString: String)`。

### 2. 单一权威内存 LRU 缓存与纯 B-Tree 检索净化 (Unified L1 Cache & Clean B-Tree)
- 在 `MediaLibraryRepositoryImpl` 内部将双重 `RemoteDirectory` LRU 缓存合并为单一权威的 L1 内存缓存（容量锁定为 100 目录）；
- 检索管道全面对齐 `media_track_index` 复合 B-Tree 查询，彻底移除对 `directory_cache.dataJson` 的二次 LIKE 模糊反序列化，实现击键 0ms 纯索引响应。

### 3. 内化增量扫描器与搜索历史为私有协作者 (Internal Seams)
- `WebDavSyncScanner` 与 `LocalStorageIndexScanner` 作为内部协作者由 `MediaLibraryRepositoryImpl` 统筹调度，不再对外部依赖注入容器暴露；
- 搜索历史存储内化为私有协作者，外部调用端仅面向极窄的接口方法。

### 4. 收敛调用端依赖与级联清理接缝 (Pruned Dependencies)
- `DirectoryBrowserViewModel` 依赖由 4 个精简为 3 个（`serverRepository`, `mediaLibraryRepository`, `musicPlayerAppSession`），单点订阅统一的媒体库流；
- `ServerRepositoryImpl` 删除服务器或解绑目录时直接调用 `mediaLibraryRepository.clearStorageData(serverId)` 与 `clearLocalFolderData(uriString)`，构造函数剥离 3 个低层 DAO 依赖。

### 5. 统一测试表面 (Consolidated Test Surface)
- 将 `DirectoryRepositoryTest` 与 `LibrarySearchRepositoryTest` 整合为单一权威黑盒测试套件 `MediaLibraryRepositoryTest`，直接面向 `MediaLibraryRepository` 验证 SWR 目录浏览、写穿透索引、增量跳过与 B-Tree 检索。

## Consequences

### Positive
- **高局部性（High Locality）**: 目录树快照、写穿透索引、增量扫描跳过、B-Tree 检索与搜索历史全流程完全内聚于单一仓储模块中。
- **高杠杆（High Leverage）**: 表现层 `DirectoryBrowserViewModel` 仅面向单一媒体库接缝，彻底消除上层业务对多仓储的手动编排。
- **杜绝内存重复**: 双重 LRU 内存缓存合二为一，消除多源并发操作下的缓存不一致隐患。
- **消除构造依赖膨胀**: `ServerRepositoryImpl` 构造参数大幅精简，存储级联注销在仓储内部闭环。
- **测试表面对齐**: 表现层单测与端到端集成测试仅需注入/模拟单一媒体库接口。

### Superseded and Amended ADRs
- **Supersedes ADR-0024**: 将原 ADR-0024 中拆分目录富化与搜索历史至两个分离仓储的浅层设计彻底终结，升格为一体化深模块。
- **Amends ADR-0033 and ADR-0035**: 强化了跨源曲库 B-Tree 索引的单一权威地位，并闭环了存储注销在仓储层面的统一定点级联清理接缝。
