# 02: 收敛 ServerRepository 存储级联注销至 MediaLibraryRepository

**What to build:** 将 `ServerRepositoryImpl` 中删除 WebDAV 服务器与解绑本地 SAF 目录时的多表级联清理收拢为调用 `mediaLibraryRepository.clearStorageData(id)` 与 `clearLocalFolderData(uri)`。彻底移除 `ServerRepositoryImpl` 构造函数对 3 个底层 DAO（`directoryCacheDao`、`mediaTrackIndexDao`、`directorySyncCheckpointDao`）的直接依赖；更新 `ServerRepositoryTest` 与 `ServerCascadeDeletionTest`。

**Blocked by:** 01: 建立 MediaLibraryRepository 领域接口与深层实现

**Status:** ready-for-agent

- [ ] `ServerRepositoryImpl` 引入 `MediaLibraryRepository` 作为级联清理协作者，移除对 `directoryCacheDao`、`mediaTrackIndexDao` 与 `directorySyncCheckpointDao` 的直接构造依赖。
- [ ] `deleteServer(id)` 将目录快照、曲库索引与增量检查点的删除操作统一收拢为调用 `mediaLibraryRepository.clearStorageData(id)`。
- [ ] `removeMountedLocalFolder(uriString)` 将本地挂载目录的缓存与索引清理统一收拢为调用 `mediaLibraryRepository.clearLocalFolderData(uriString)`。
- [ ] 更新 `ServerRepositoryTest` 与 `ServerCascadeDeletionTest`，断言存储注销经由 `MediaLibraryRepository` 统一接缝进行级联清理，全部测试通过。
