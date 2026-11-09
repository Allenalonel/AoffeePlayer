# 04: 物理清除旧仓储接口并闭环端到端验证门禁

**What to build:** 物理彻底删除旧的 `DirectoryRepository.kt`、`DirectoryRepositoryImpl.kt`、`LibrarySearchRepository.kt`、`LibrarySearchRepositoryImpl.kt` 及其原单测文件；从 `AppContainer` 与 `AppViewModelFactory` 中抹除旧接口引用；更新端到端测试（`EndToEndPlaylistAndSearchPipelineTest`、`EndToEndCacheResilienceIntegrationTest` 等）与 `PackagingSeamVerificationTest`；运行全量测试门禁 `./gradlew testDebugUnitTest`，确证零僵尸类残留、无非法依赖穿透且 100% 单测通过。

**Blocked by:** 03: 表现层接入 MediaLibraryRepository 并精简 ViewModel 依赖

**Status:** ready-for-agent

- [ ] 物理删除 `DirectoryRepository.kt`、`DirectoryRepositoryImpl.kt`、`LibrarySearchRepository.kt`、`LibrarySearchRepositoryImpl.kt`。
- [ ] 物理删除已整合的旧测试文件 `DirectoryRepositoryTest.kt` 与 `LibrarySearchRepositoryTest.kt`。
- [ ] 从 `AppContainer` 与 `AppViewModelFactory` 中彻底抹除旧接口属性与遗留别名。
- [ ] 更新 `EndToEndPlaylistAndSearchPipelineTest`、`EndToEndCacheResilienceIntegrationTest` 与 `EndToEndPlaybackPipelineIntegrationTest`，对齐新接缝。
- [ ] 更新并通过 `PackagingSeamVerificationTest`，确证无僵尸类残留，各包单向接缝合规。
- [ ] 执行全工程单元测试 `./gradlew testDebugUnitTest`，确保 100% 绿色通行，无构建警告。
