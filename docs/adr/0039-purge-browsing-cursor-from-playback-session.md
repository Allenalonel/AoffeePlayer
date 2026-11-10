# 0039: 从播控会话剥离浏览游标并收敛至媒体库深模块 (Purge Browsing Cursor from Playback Session into Media Library)

## Status

Accepted

## Context

在 [ADR-0035](0035-cross-source-session-decoupling-and-symmetric-storage-lifecycle.md) 中，系统确立了 `browsingServer`（媒体库浏览源）与 `activeServer`（在播流凭据上下文）彻底解耦的双轨原则，终结了播放切歌覆写浏览源与停靠界面的混乱状态。并在 [ADR-0036](0036-consolidate-media-library-repository.md) 中将目录层级 SWR 快照、B-Tree 索引与搜索历史统一收敛至 `MediaLibraryRepository` 单一深模块。

然而，在近期的全项目架构审查中，发现历史架构演进遗留了一处极其严重的**跨接缝逆向渗透与隐性磁盘 I/O 陷阱**：

1. **浏览操作引发隐性磁盘 I/O 风暴（Hidden Disk I/O Storm）**：
   在现存实现中，各存储源的目录浏览停留路径（`currentDirectoryPath` 与 `serverLastDirectories`）依然寄生在播控会话数据泥团 `PlaybackSessionData` 与 `PlayerSessionState` 内部。每当用户在 `DirectoryBrowserScreen` 中点击一个子目录或点击面包屑返回时，`DirectoryBrowserViewModel` 都会调用 `musicPlayerAppSession.setCurrentDirectoryPath(path)`。这不仅无意义地更新了全局播放会话状态流，更直接触发 `SessionPersistenceCoordinator` 将包含整条播放队列（成百上千首音轨的元数据、打乱镜像等）全量序列化并写入 DataStore 磁盘！用户纯粹在浏览目录，后台却在频繁刷盘持久化播放器现场快照。
2. **数据泥团与职责倒错（Data Clump & Inverted Responsibility）**：
   `MusicPlayerAppSession` 与 `PlaybackSessionData` 的唯一正交职责应是统筹流媒体解码、播放队列流转、硬件锁与系统宿主意图。强塞 `getLastDirectoryForServer(serverId)` 与 `setCurrentDirectoryPath(path)` 属于典型的职责错位，使得媒体库浏览逻辑严重反向侵蚀播控会话。
3. **启动期异步竞争与测试表面污染（Startup Race & Brittle Test Surface）**：
   `DirectoryBrowserViewModel` 在初始化恢复当前源上次访问目录时，必须异步挂起等待 `musicPlayerAppSession.isRestored.first { it }`，导致毫无依赖关系的媒体库目录渲染必须在冷启动时串行等待底层播控会话恢复；与此同时，任何针对目录切换与面包屑导航的单元测试，都被迫 Mock 或引入复杂的 `MusicPlayerAppSession` 假对象。

## Decision

依据 `/codebase-design` 深度模块与高局部性（Locality）原则，对媒体库浏览游标与播控会话实施物理级剥离与深化重构：

### 1. 提炼领域概念 `Browsing Cursor` 并扩充 `MediaLibraryRepository` 深模块接缝
在 `GLOSSARY.md` 确立 **Browsing Cursor**（浏览游标）标准领域概念，并在 `MediaLibraryRepository` 深度接口中扩充游标读写契约：
```kotlin
interface MediaLibraryRepository : StorageCascadeInvalidator {
    // Browsing Cursor Retention
    fun getLastBrowsingPath(serverId: Long): String
    fun setLastBrowsingPath(serverId: Long, path: String)
    ...
}
```
该接缝对外呈现极小认知负担，对内完全收敛各源历史浏览深度的状态维护。

### 2. 内化包级私有轻量持久化协作者 `InternalBrowsingCursorStore`
在 `data.repository` 提炼包级私有协作者 `InternalBrowsingCursorStore`，隐藏在 `MediaLibraryRepositoryImpl` 之后：
- 采用私有轻量键值存储（SharedPreferences 或独立 DataStore），辅以内存 `ConcurrentHashMap` 实现 0ms 瞬间寻址；
- 目录路径变更仅触发极简键值异步写入（`< 0.2ms`），彻底断绝播放队列大对象的磁盘刷盘；
- **对称纳入级联清理契约（Storage Cascade Invalidation）**：
  - 当触发 `clearStorageData(serverId)` 时，内部同步抹除该存储源的浏览游标；
  - 当触发 `clearLocalFolderData(uriString)` 时，若本地存储当前游标处于被解绑目录子树内，自动安全重置回根目录 `"/"`。

### 3. 物理级净化播控会话层 `MusicPlayerAppSession` 与持久层
- **彻底拔除会话方法**：从 `MusicPlayerAppSession` 接口及其实现类中彻底物理删除 `getLastDirectoryForServer` 与 `setCurrentDirectoryPath`；
- **消除数据泥团**：从 `PlayerSessionState` 领域实体中物理移除 `currentDirectoryPath` 字段；从 `PlaybackSessionData` 中物理移除 `currentDirectoryPath` 与 `serverLastDirectories`；
- **清理 DataStore 键**：从 `DataStorePlaybackSessionStore` 中彻底删除 `KEY_CURRENT_DIRECTORY_PATH`，播放队列持久化彻底专职于纯回放现场。

### 4. 表现层 `DirectoryBrowserViewModel` 启动极速化与单向解耦
- **零等待冷启动**：ViewModel 启动或切换存储源时，直接同步向 `mediaLibraryRepository.getLastBrowsingPath(serverId)` 查询历史路径并秒开目录渲染，**彻底移除对 `musicPlayerAppSession.isRestored` 的异步挂起依赖**；
- **零播控联动**：目录加载成功仅调用 `mediaLibraryRepository.setLastBrowsingPath(serverId, path)`，彻底阻断对播控会话状态与底层播放器的任何干扰。

## Consequences

- **Locality（高局部性）**：浏览游标与目录 SWR 快照、B-Tree 索引、增量检查点及存储级联清理 100% 聚焦在 `MediaLibraryRepository` 内部，排查或演进浏览状态无需跨越会话层；
- **Leverage（高杠杆与性能飞跃）**：完全消除了普通文件浏览对 DataStore 磁盘 I/O 的高频触发，大幅减轻闪存写入与电池功耗；媒体库列表在应用冷启动时达到秒级渲染；
- **Zero Reverse Coupling（零逆向依赖）**：媒体库目录导航与状态记忆彻底斩断对 `MusicPlayerAppSession` 的反向依赖，完全落实 ADR-0035 双轨解耦的终局形态；
- **Test Surface Shrinkage（测试表面收缩）**：`DirectoryBrowserViewModelTest` 不再需要为路径恢复而 Mock 播放会话，`MusicPlayerAppSessionTest` 也彻底去除了与目录路径相关的冗余断言，单测更加稳健轻量。
