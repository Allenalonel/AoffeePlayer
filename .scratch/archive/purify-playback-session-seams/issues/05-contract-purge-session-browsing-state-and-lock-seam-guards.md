# 05: 物理删除 Session 遗留浏览态并锁定静态架构红线（Contract）

**What to build:** 完成 Expand–Contract 的最终收缩步骤并加固静态架构守卫。从 `Playback Session` 公开接口、会话状态模型及会话测试桩中彻底物理删除 `browsingServer`、`setActiveServer` 及其重载与旧版无服务器参数的目录点播转发桥接；并在静态包接缝校验测试中补充自动化断言，永久禁止 `domain.session` 反向依赖 `data` 层任何实现或异常，以及禁止播控会话与状态模型残留任何浏览源成员。

**Blocked by:** 01 (Audio Focus 窄门控与宿主硬件锁), 02 (PlaybackErrorKind 归一映射), 04 (全量调用方迁移)

**Status:** completed

- [x] `Playback Session` 接口、会话状态模型及假对象中 `browsingServer` 与 `setActiveServer` 及其旧目录点播桥接已物理清零
- [x] 静态包接缝校验测试明确校验 `domain.session` 零 `data.*` 导入且会话接口/状态模型零浏览态字段或方法
- [x] 静态守卫测试及全量核心会话单测编译并通过
