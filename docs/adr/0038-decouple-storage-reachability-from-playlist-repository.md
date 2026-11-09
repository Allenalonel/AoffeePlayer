# 0038: 从歌单仓储解耦存储源连通性追踪器与内存响应式自愈 (Decouple Storage Reachability from Playlist Repository)

## Status

Accepted

## Context

在先前引入跨服务器歌单（ADR-0020）及全链路自愈网络（ADR-0022、ADR-0030）的过程中，由于当时仅歌单承载跨源音轨，系统将服务器不可达（`SERVER_UNREACHABLE`）、凭据失效（`SERVER_CREDENTIAL_INVALID`）与音轨文件缺失（`FILE_NOT_FOUND`）的探测、标记与自愈调度逻辑全部塞入了 `PlaylistRepositoryImpl`。

随着曲库全局搜索（ADR-0033）、双轨解耦（ADR-0035）与媒体库单一深模块（ADR-0036）的演进，这种将网络健康状态与特定业务仓储绑定的架构设计暴露出三大严重的**架构摩擦与坏味道**：

1. **跨接缝逆向渗透（Cross-Seam Leakage）**：
   `PlaybackFaultTolerance` 属于播控核心会话层（`domain.session`）。当用户在媒体库目录浏览（`QueueOrigin.DIRECTORY`）或全局搜索（`QueueOrigin.SEARCH`）中播放某首歌曲遭遇 404 或网络中断时，容错看门狗竟然违背接缝反向调用 `PlaylistRepository.markTrackInactive` 与 `markServerInactive`。这不仅在物理上将非歌单流式播放与歌单数据模型强行绑定，且若该曲目未被任何歌单收藏，底层 Room 执行的是毫无意义的 0 匹配 SQL UPDATE。
2. **依恋情结与职责膨胀（Feature Envy & Bloated Seam）**：
   `PlaylistRepository` 沦为事实上的“全域网络探活代理中心”，被迫注入 `WebDavClient`、`ServerRepository`、SAF 游标探针以及 Android `Context`，背负了 6 个非歌单连通性公共方法以及 8 个 Room DAO 更新查询。与此同时，`ServerManagementViewModel`（仅为通知测试连接成功）与 `MusicPlayerAppSessionImpl`（仅为转发系统网络连通广播）也被迫依赖了 `PlaylistRepository`。
3. **磁盘持久化脏状态与伪死锁（Persistent Dirty State & Deadlock）**：
   旧实现将瞬态网络故障写入本地 Room `playlist_tracks` 表的持久化列 `isInactive` 中。当用户处于弱网（如电梯）或服务器临时维护时，大量曲目被落盘为“置灰”；当离线冷启动应用时，即使本地局域网已恢复，歌单曲目依然残留在持久化的置灰状态中，必须等待低效的全量探活逐一唤醒，违背了纯流式轻量响应的架构原则。

## Decision

恪守 `/codebase-design` 深度模块原则与状态单向隔离铁律，重构连通性子系统与歌单仓储：

### 1. 建立独立深模块接缝 `StorageReachabilityTracker`
在 `domain.storage`（或 `domain.repository`）提炼统一管理存储节点与音轨连通性的专职深模块：
```kotlin
interface StorageReachabilityTracker {
    val inactiveTracks: StateFlow<Map<TrackKey, InactiveReason>>
    val serverReachability: StateFlow<Map<Long, Boolean>>

    fun reportTrackUnavailable(serverId: Long, remotePath: String, reason: InactiveReason)
    fun reportServerUnavailable(serverId: Long, reason: InactiveReason)
    fun reportServerReachable(serverId: Long)

    suspend fun triggerReachabilitySelfHealing()
}
```
该模块对外呈现极窄的报告与探活接缝，对内完全封装：
- WebDAV PROPFIND 网络连通性探针（`WebDavClient.testConnection`）；
- SAF `content://` 与 `file://` Document 游标探针；
- 四重自愈网络（播放成功反哺、测试连接成功、网络恢复广播、歌单加载/点击探活）的防重入与并发调度。

### 2. 迁移为纯内存响应式状态流（In-Memory Reactive State）
- 彻底废除向 Room `playlist_tracks` 频繁执行 SQL `UPDATE ... SET isInactive = 1` 的磁盘 I/O 行为；
- 存储源与音轨的不可达状态完全由 `StorageReachabilityTracker` 维护在轻量线程安全的内存映射中；
- 应用冷启动时状态天然清白，零磁盘脏状态残留，彻底消除离线启动导致的持久化置灰伪死锁。

### 3. 彻底斩断外部调用方的逆向依赖
重塑单向依赖拓扑：
- **`PlaybackFaultTolerance`**：移除 `PlaylistRepository` 依赖，替换为 `StorageReachabilityTracker`。流式播放遇到 404 或服务端宕机仅向 Tracker 上报状态，出声成功时向 Tracker 反哺连通性，**彻底阻断播控向歌单的任何反向浸润**；
- **`ServerManagementViewModel`**：移除 `PlaylistRepository` 依赖，测试连接成功直接调用 `StorageReachabilityTracker.reportServerReachable(serverId)`；
- **`MusicPlayerAppSessionImpl`**：网络恢复广播仅通知 `StorageReachabilityTracker.triggerReachabilitySelfHealing()`。

### 4. 歌单仓储 `PlaylistRepository` 纯净化与瘦身
- 移除 `webDavClient`、`serverRepository`、`localUriReachabilityChecker`、`context` 依赖；
- 移除 `markTrackInactive`、`markServerInactive`、`clearServerUnreachableStatus`、`getInactiveTracksFlow`、`checkTrackReachability`、`triggerReachabilitySelfHealing` 6 个连通性方法；
- `PlaylistRepository.getTracksForPlaylistFlow(playlistId)` 内部通过 `combine(storageReachabilityTracker.inactiveTracks)` 动态装配 `PlaylistTrackEntry.isInactive` 与 `inactiveReason`；
- 接口收敛为纯粹的歌单创建、查询、删除、曲目添加、移除、排序及封面富化。

### 5. 数据库平滑兼容与零迁移成本
- Room 实体 `PlaylistTrackEntity` 中保留现有 `isInactive` 与 `inactiveReason` 列定义（默认值分别为 `false` 与 `null`）；
- 废止针对这二者的全量 DAO 更新方法，无需升级 Room 数据库版本，以 0 风险实现架构演进。

## Consequences

- **Locality**: 连通性故障感知、PROPFIND 探活与自愈调度完全收敛在 `StorageReachabilityTracker` 内部，修复与探活策略演进彻底与歌单解耦；
- **Leverage**: 单一健康中枢同时服务播放容错、服务器管理、系统广播与歌单，并天然支持未来媒体库目录浏览与搜索结果零成本展示离线置灰；
- **Zero Reverse Coupling**: 播控引擎与服务器管理完全剥离对 `PlaylistRepository` 的调用，彻底对齐架构法典中的单向隔离铁律；
- **High Performance**: 瞬态网络波动不再触发 SQLite 频繁刷盘，读写性能大幅提升且避免冷启动脏数据；
- **Test Surface Shrinkage**: `PlaylistRepositoryTest` 摆脱繁重的 WebDAV/SAF 探针 Mock，单测聚焦于纯粹的歌单数据不变量。
