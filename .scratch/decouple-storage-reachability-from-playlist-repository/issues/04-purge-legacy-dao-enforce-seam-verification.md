# 04: 物理清除废弃 DAO 查询、建立接缝防线并完成全链路验证

**What to build:**
完成最后的架构清理与全链路硬性接缝防线建立。清理 `PlaylistDao` 中遗留的 8 个直接读写 `isInactive` / `inactiveReason` 的冗余 SQL 方法，保持 Room 实体字段静默兼容以实现零数据库迁移风险。在 `PackagingSeamVerificationTest` 中追加自动化架构断言：确证播控容错包零引入歌单仓储、歌单仓储零引入网络客户端与外部探针。运行全量单元测试与安装包校验门禁（`./gradlew testDebugUnitTest verifyReleasePackaging`），确保零回归且安装包体积达标。

**Blocked by:** 03: 净化歌单仓储并以响应式组合重构歌单详情状态

**Status:** ready-for-agent

- [ ] 从 `PlaylistDao` 中物理删除废弃的 8 个 `isInactive` 更新与查询方法（`updateTrackInactiveStatus`、`markServerInactiveStatus`、`clearServerInactiveStatus`、`getDistinctServerIdsForInactiveServers` 等）。
- [ ] 确保 `PlaylistTrackEntity` 保留现存 `isInactive = false` 与 `inactiveReason = null` 列定义，无需变更 Room 数据库版本。
- [ ] 在 `PackagingSeamVerificationTest` 中增加针对逆向依赖的静态检查：断言 `PlaybackFaultTolerance.kt` 严禁反向 `import com.webdav.player.domain.repository.PlaylistRepository`；断言 `PlaylistRepositoryImpl.kt` 严禁引入 `WebDavClient`。
- [ ] 运行 `./gradlew testDebugUnitTest` 验证全量单元测试 100% 绿色通过。
- [ ] 运行 `./gradlew verifyReleasePackaging` 验证 Release 构建与单架构 ABI 预算达标。
