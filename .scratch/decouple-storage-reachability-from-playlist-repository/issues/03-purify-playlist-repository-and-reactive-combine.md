# 03: 净化歌单仓储并以响应式组合重构歌单详情状态

**What to build:**
收缩并净化歌单仓储。从 `PlaylistRepository` 中彻底剥离 `WebDavClient`、`ServerRepository`、SAF 游标探针与 Context，删除 6 个非歌单连通性公共方法。在 `getTracksForPlaylistFlow()` 内部通过响应式 `combine` 动态复合 `StorageReachabilityTracker` 的内存失联流并组装 `PlaylistTrackEntry`。在 `PlaylistDetailViewModel` 中接入 Tracker 进行初次加载探活与点击置灰曲目探活，保持用户端交互体验完整如初。重构 `PlaylistRepositoryTest` 与 `PlaylistDetailViewModelTest`。

**Blocked by:** 01: 建立 StorageReachabilityTracker 独立深模块与依赖装配, 02: 迁移播控容错、会话宿主与服务器管理至 Tracker

**Status:** in-progress

- [ ] 从 `PlaylistRepository` 接口与 `PlaylistRepositoryImpl` 实现中物理删除 `markTrackInactive`、`markServerInactive`、`clearServerUnreachableStatus`、`getInactiveTracksFlow`、`checkTrackReachability` 与 `triggerReachabilitySelfHealing` 6 个连通性方法。
- [ ] 从 `PlaylistRepositoryImpl` 构造函数中剥离 `webDavClient`、`serverRepository`、`localUriReachabilityChecker` 与 `context`，注入 `storageReachabilityTracker: StorageReachabilityTracker?`。
- [ ] 在 `PlaylistRepository.getTracksForPlaylistFlow()` 中通过 `combine(storageReachabilityTracker.inactiveTracks)` 动态装配 `PlaylistTrackEntry.isInactive` 与 `inactiveReason`。
- [ ] 在挂起函数 `getTracksForPlaylist()` 中读取 `storageReachabilityTracker` 内存快照完成状态组装。
- [ ] `PlaylistDetailViewModel` 引入 `StorageReachabilityTracker`，在初始化与点击置灰曲目时通过 Tracker 触发 `triggerReachabilitySelfHealing()`。
- [ ] 重构 `PlaylistRepositoryTest`，删除所有 WebDAV/SAF 网络探活相关的复杂 Mock，聚焦歌单 CRUD 与响应式流复合；更新 `PlaylistDetailViewModelTest` 确保全绿通过。
