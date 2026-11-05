# 04: 歌单曲目复合对账与去重自愈流水线去重

**What to build:** 在 `PlaylistRepositoryImpl` 中提炼统一的私有对账流水线 `reconcilePlaylistTracks`，彻底消除 `getTracksForPlaylistFlow` (响应式流) 与 `getTracksForPlaylist` (一次性快照) 之间近 80 行高度重复的代码。集中处理复合键去重（`serverId:remotePath:virtualTrackId`）与孤儿数据异步删除、`Symmetric Fault Lifecycle` 对称故障置灰与在线状态合并，以及缺失缩略图时的后台封面异步自愈提取，杜绝未来故障自愈逻辑演进时的双份拷贝分化风险。

**Blocked by:** None (can start immediately)

**Status: resolved**

## Acceptance criteria

- [x] 在 `PlaylistRepositoryImpl` 中提炼私有方法 `reconcilePlaylistTracks(entities: List<PlaylistTrackEntity>, inactiveMap: Map<TrackKey, InactiveReason>, servers: List<WebDavServer>?): List<PlaylistTrackEntry>`。
- [x] 将内存复合键去重、异步删除冗余持久化条目、服务器不可达状态合并（`InactiveReason.SERVER_UNREACHABLE`）、以及缺失封面自愈调度统一收拢在 `reconcilePlaylistTracks` 内部。
- [x] 将 `getTracksForPlaylistFlow` 与 `getTracksForPlaylist` 重构为直接复用 `reconcilePlaylistTracks`，消除约 75~80 行冗余重复代码。
- [x] 确保已有的 `PlaylistRepositoryTest` 完整覆盖 Flow 订阅与快照获取，且行为表现 100% 对齐。
- [x] 运行 `./gradlew testDebugUnitTest --tests "*PlaylistRepositoryTest*"` 且测试全部通过。

## Answer

已通过 `ticket-04` 工作树子代理完成实现并合入集成分支：

1. 在 `PlaylistRepositoryImpl` 中提取私有方法 `reconcilePlaylistTracks`，集中处理复合键去重、异步删除多余持久化行、离线状态合并与后台封面自愈触发；
2. 重构 `getTracksForPlaylistFlow` 和 `getTracksForPlaylist` 复用统一流水线，消除了 75+ 行重复代码；
3. `PlaylistRepositoryTest` 完整覆盖响应式流与快照查询，所有 29 个单测用例全部通过。

