# 01: 建立 StorageReachabilityTracker 独立深模块与依赖装配

**What to build:**
构建全新的 `StorageReachabilityTracker` 领域深模块，为应用提供统一的存储节点与音轨物理连通状态中枢。维护纯内存响应式状态流（`inactiveTracks` 与 `serverReachability`），消灭网络抖动时对 SQLite 磁盘的高频写入。内部完整封装 WebDAV PROPFIND 网络连通性测试、本地 SAF Document 游标探针、并发防重入互斥机制以及四重闭环自愈调度。在应用级容器 `AppContainer` 与 `WebDavApplication` 中完成单例装配，并构建覆盖状态上报、探针验证与自愈恢复的完整黑盒单元测试套件。

**Blocked by:** None (can start immediately)

**Status:** in-progress

- [ ] 在 `domain.storage` 包下定义 `StorageReachabilityTracker` 窄接口，暴露 `inactiveTracks: StateFlow<Map<TrackKey, InactiveReason>>`、`serverReachability: StateFlow<Map<Long, Boolean>>`、`reportTrackUnavailable`、`reportServerUnavailable`、`reportServerReachable` 与 `triggerReachabilitySelfHealing`。
- [ ] 在 `data.storage` 包下实现 `StorageReachabilityTrackerImpl`，内部封装 `WebDavClient` 的 PROPFIND 连接测试、Android SAF Document 游标探针与状态映射。
- [ ] 实现针对并发探活请求的防重入机制（`AtomicBoolean`），杜绝重复并发网络探针风暴。
- [ ] 在 `AppContainer` 与 `WebDavApplication` 中注册并初始化 `storageReachabilityTracker` 单例。
- [ ] 编写全面的单元测试套件 `StorageReachabilityTrackerTest`，验证单曲与整机故障上报、恢复信道状态流发射以及自愈探针逻辑全部通过。
