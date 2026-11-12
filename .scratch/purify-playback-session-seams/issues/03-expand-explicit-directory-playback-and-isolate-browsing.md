# 03: 双轨正交化（Expand）：显式目录点播传参、切断浏览覆写与在播源被删自动停止

**What to build:** 在保留旧会话接口桥接以确保现有百余处测试继续绿灯编译的前提下，落地 `Browsing Storage` 与 `Playback Server` 的完全正交运行行为。为 `Playback Session` 新增显式传入目标 `Storage Source` 的目录点播接口（旧无服务器参数重载暂保留为兼容桥接）；媒体库浏览视图模型改为直接订阅存储仓储作为 `Browsing Storage` 唯一数据源，全局搜索点击目录仅切换浏览源而严禁触碰或清空 `Playback Session`；移除时间轴协调器对存储仓储活跃浏览源的监听覆写，改为仅当正在播放的远程服务器从服务器列表中被彻底删除时由会话自动停止并清空残余队列。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `Playback Session` 提供显式传入目标 `Storage Source` 的目录点播方法，旧无参桥接暂时保留转发以免破坏未迁移调用方
- [ ] 媒体库浏览视图模型直接从存储仓储获取 `Browsing Storage`，全局搜索点击目录及顶层切换浏览源时不再覆写 `Playback Server` 或清空正在播放的目录/歌单队列
- [ ] 删除闲置非在播服务器时正在播放的会话零干扰继续；删除“当前正在播放”的远程服务器时播控会话自动停止并清理残余队列
- [ ] 新增覆盖“切换浏览源/搜索跳转不中断播放”与“仅在播源被删时停播”的测试并验证通过
