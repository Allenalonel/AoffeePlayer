# 05: 领域模型文档（ADR & Glossary）与端到端集成发布验收

**What to build:**
从工程质量与领域认知视角出发，将本次架构决策与技术成果完整沉淀为代码库的长效规范资产。在根目录 `GLOSSARY.md` 中增补新领域概念（`Storage Source`、`Active Storage`、`Local Storage`、`Mounted Directory`），在 `docs/adr/` 中记录正式架构决策文档 `0032-local-storage-saf-and-cross-source-playback.md`。执行全量 Gradle 单元测试套件（`testDebugUnitTest`）与发布包边界验证（`verifyReleasePackaging`），确保 R8 混淆、64位 ABI 瘦身与架构规范 100% 达标。最后在连接的真机或模拟器上执行两阶段 UI 像素级审查与无崩溃冒烟自检，确保整套本地存储与多源播放链路达到生产级发布标准。

**Blocked by:** 04: 跨源混编歌单、切歌过渡与对称失效自愈

**Status:** ready-for-agent

- [ ] 在根目录 `GLOSSARY.md` 中增补 `Storage Source`、`Active Storage`、`Local Storage` 与 `Mounted Directory` 等词汇的标准定义与避免用词。
- [ ] 撰写并落盘 `docs/adr/0032-local-storage-saf-and-cross-source-playback.md`，记录 SAF 选型、0L 本地保留 ID 设计、SWR 本地目录快照与跨源歌单自愈架构。
- [ ] 运行 `./gradlew testDebugUnitTest`，确保整个项目包括原有测试与新增测试全部绿色通过（0 失败）。
- [ ] 运行 `./gradlew verifyReleasePackaging`，确保 APK 预算、R8 混淆规则与 64 位单一 ABI 限制通过集成门禁。
- [ ] 通过 ADB 截屏执行像素级自检（审查存储底栏、管理弹窗、媒体库挂载点卡片），动态触发切歌并检查 `adb logcat -d -b crash` 保证 0 崩溃。
- [ ] 将 `.scratch/local-storage-support/` 状态与 `.scratch/README.md` 索引更新为最终结项状态。
