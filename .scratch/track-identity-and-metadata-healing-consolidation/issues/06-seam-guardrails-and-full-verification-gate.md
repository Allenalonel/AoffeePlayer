# 06: 补充架构接缝防回退守卫与全量交付门禁验证

**What to build:** 在项目架构守卫测试中固化组合包 B 确立的模块边界与规范身份约束，随后执行完整单元测试套件与 Release 打包校验门禁，确保全部工单改动达成零编译告警、零回归且满足二进制体积预算，并在确认达标后整理完结工单状态。

**Blocked by:** 03, 05

**Status:** ready-for-agent

- [ ] 架构接缝校验测试新增自动化断言：验证歌单仓储与本地目录解析器不再包含磁盘封面存活性检查、缩略图写盘或独立自愈任务；验证核心仓储与协调器不再绕开 `TrackIdentity` 散写原始 `content://` 前缀判定。
- [ ] 完整运行 `./gradlew testDebugUnitTest verifyReleasePackaging` 且全部通过。
- [ ] 复核代码符合 `CODING_STANDARDS.md` 并发、主线程安全、600 行单文件预算与黑盒测试表面要求。
