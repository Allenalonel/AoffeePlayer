# 04: 歌单曲目复合对账与去重自愈流水线去重

**What to build:** 在 `PlaylistRepositoryImpl` 中提炼统一的私有对账流水线 `reconcilePlaylistTracks`，彻底消除 `getTracksForPlaylistFlow` (响应式流) 与 `getTracksForPlaylist` (一次性快照) 之间近 80 行高度重复的代码。集中处理复合键去重（`serverId:remotePath:virtualTrackId`）与孤儿数据异步删除、`Symmetric Fault Lifecycle` 对称故障置灰与在线状态合并，以及缺失缩略图时的后台封面异步自愈提取，杜绝未来故障自愈逻辑演进时的双份拷贝分化风险。

**Blocked by:** None (can start immediately)

**Status: claimed**

## Acceptance criteria

- [ ] 在 `PlaylistRepositoryImpl` 中提炼私有方法 `reconcilePlaylistTracks(entities: List<PlaylistTrackEntity>, inactiveMap: Map<TrackKey, InactiveReason>, servers: List<WebDavServer>?): List<PlaylistTrackEntry>`。
- [ ] 将内存复合键去重、异步删除冗余持久化条目、服务器不可达状态合并（`InactiveReason.SERVER_UNREACHABLE`）、以及缺失封面自愈调度统一收拢在 `reconcilePlaylistTracks` 内部。
- [ ] 将 `getTracksForPlaylistFlow` 与 `getTracksForPlaylist` 重构为直接复用 `reconcilePlaylistTracks`，消除约 75~80 行冗余重复代码。
- [ ] 确保已有的 `PlaylistRepositoryTest` 完整覆盖 Flow 订阅与快照获取，且行为表现 100% 对齐。
- [ ] 运行 `./gradlew testDebugUnitTest --tests "*PlaylistRepositoryTest*"` 且测试全部通过。
