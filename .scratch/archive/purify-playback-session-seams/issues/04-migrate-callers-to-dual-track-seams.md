# 04: 迁移全量调用方与测试套件至双轨正交接口（Migrate）

**What to build:** 将所有 UI 调用方、会话测试桩及全工程单元测试/集成测试套件从旧的 `setActiveServer`、`browsingServer` 与无服务器参数目录点播接口迁移至纯正交的双轨交互模式。存储管理与媒体库界面不再向 `Playback Session` 同步浏览源选择；目录点播全部改为显式传递目标 `Storage Source`；测试套件中依靠浏览源设置来拼装播放上下文的用例改为直接构造对应 `Playback Queue` 或通过显式点播入口建立在播上下文。

**Blocked by:** 03 (双轨正交化 Expand)

**Status:** completed

- [x] UI 层（含主界面与媒体库浏览）零残留对 `Playback Session` 浏览源设置或读取调用
- [x] 会话测试桩及全部单元测试、恢复测试、端到端集成测试完成向显式目录点播与纯双轨断言的迁移，旧浏览接口在生产与测试代码中调用数归零
- [x] 全量受影响测试集保持绿灯编译并通过
