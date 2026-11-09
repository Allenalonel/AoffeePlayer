# 02: 迁移播控容错、会话宿主与服务器管理至 Tracker

**What to build:**
彻底斩断播控层与服务器管理对歌单仓储的逆向调用。将 `PlaybackFaultTolerance` 中的歌单仓储依赖替换为 `StorageReachabilityTracker`；当目录或搜索流式播放发生 404、认证失败或网络宕机时，仅向 Tracker 上报状态并执行安全跳曲，绝不触碰任何歌单；当物理音频出声进入播放状态时，向 Tracker 反哺连通性。同时，将 `ServerManagementViewModel`（测试连接成功）与 `MusicPlayerAppSessionImpl`（系统网络恢复广播）全面切换为调用 Tracker。更新相关单元测试，确证播放异常时歌单数据零修改。

**Blocked by:** 01: 建立 StorageReachabilityTracker 独立深模块与依赖装配

**Status:** ready-for-agent

- [ ] `PlaybackFaultTolerance` 移除 `playlistRepository` 依赖，引入 `storageReachabilityTracker: StorageReachabilityTracker?`。
- [ ] 在 `PlaybackFaultTolerance.handle404Failure` 中调用 `storageReachabilityTracker.reportTrackUnavailable(..., FILE_NOT_FOUND)`，移除对歌单仓储的修改。
- [ ] 在 `PlaybackFaultTolerance.handleAuthFailure` 与 `handleServerUnavailable` 中调用 `storageReachabilityTracker.reportServerUnavailable(...)`。
- [ ] 在 `PlaybackFaultTolerance.onPlayingStarted` 中调用 `storageReachabilityTracker.reportServerReachable(currentTrack.serverId)`，保留出声反哺闭环。
- [ ] `ServerManagementViewModel` 移除 `PlaylistRepository` 依赖，引入 `StorageReachabilityTracker`，在测试连接成功时调用 `reportServerReachable(serverId)`。
- [ ] `MusicPlayerAppSessionImpl` 在监听到底层网络状态恢复为 `NetworkStatus.Available` 时，派发 `storageReachabilityTracker.triggerReachabilitySelfHealing()`。
- [ ] 更新 `PlaybackFaultToleranceTest`、`ServerManagementViewModelTest` 及相关的播放器集成测试，验证测试全部绿色通过。
