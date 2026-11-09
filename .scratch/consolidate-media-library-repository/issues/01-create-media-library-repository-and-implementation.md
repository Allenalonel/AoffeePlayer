# 01: 建立 MediaLibraryRepository 领域接口与深层实现

**What to build:** 定义统一的 `MediaLibraryRepository` 领域接口与实现类 `MediaLibraryRepositoryImpl`。合并单一权威 L1 LRU 内存缓存（容量 100 目录）；实现 SWR 响应式目录浏览与写穿透索引维护；实现基于 `media_track_index` 复合 B-Tree 的毫秒级检索（彻底移除针对 `dataJson` 的模糊文本匹配及反序列化）；内化扫描调度与搜索历史；建立统一级联注销接口 `clearStorageData(serverId)` 与 `clearLocalFolderData(uriString)`；建立权威测试套件 `MediaLibraryRepositoryTest`。此阶段保留旧接口以保证全工程构建不中断。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 在 `domain.repository` 包中建立 `MediaLibraryRepository` 接口，定义 SWR 目录浏览、B-Tree 检索、扫描调度、搜索历史与级联清理公共契约。
- [ ] 在 `data.repository` 包中实现 `MediaLibraryRepositoryImpl`，整合单一权威 L1 LRU 内存缓存（容量 100 目录），在内存警告时响应清理。
- [ ] 目录浏览在远程或本地差异更新时，原子写穿透 upsert 至 `media_track_index`，并自动清理已删除文件。
- [ ] 检索管道全面对齐 `media_track_index` 复合 B-Tree 索引与 `directory_sync_checkpoint`，彻底废除对 `directory_cache.dataJson` 的模糊匹配反序列化。
- [ ] 将 `WebDavSyncScanner` 与 `LocalStorageIndexScanner` 作为内部协作者由实现类内部统筹调度，内化 SharedPreferences 搜索历史存储。
- [ ] 编写并执行全新的权威测试套件 `MediaLibraryRepositoryTest`，全面覆盖浏览、写穿透、检索、历史与级联清理且 100% 通过。
- [ ] 临时保留既有 `DirectoryRepository` 与 `LibrarySearchRepository` 接口及实现，确保外部依赖模块编译不受影响。
