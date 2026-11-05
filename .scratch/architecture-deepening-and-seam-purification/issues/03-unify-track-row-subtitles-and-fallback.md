# 03: 音轨列表行副标题规范与兜底策略统一

**What to build:** 统一全 App 音轨列表项（媒体库 `AudioTrackItemRow`、歌单 `PlaylistTrackRow`、搜索 `SearchResultsViews`、队列抽屉 `PlaybackQueueBottomSheet`）的副标题格式化与缺失标签兜底策略。在 `AudioTrackRowDefaults` 中提供统一的 `formatTrackSubtitle(artist, album, duration, extra)` 接口，严格对齐 72dp 双行高度（Track Row Height Parity），当艺术家与专辑信息缺失时统一安全回退显示 `"未知"`（杜绝各页面自行拼接时 `"未知"` 与 `"未知艺术家"` 的文案漂移与逻辑分化）。

**Blocked by:** None (can start immediately)

**Status: resolved**

## Acceptance criteria

- [x] 在 `AudioTrackRowDefaults` 中升级/提供 `formatTrackSubtitle(artist: String?, album: String? = null, durationText: String? = null, extra: String? = null): String`，确保在无任何标签信息时统一返回 `"未知"`，且单行不换行。
- [x] 简化 `AudioTrackItemRow.kt`：移除其私有的 `resolveAudioSubtitle` 方法，统一调用 `AudioTrackRowDefaults.formatTrackSubtitle`。
- [x] 简化 `PlaylistTrackRow.kt`：移除条目内部 ad-hoc 的 `?: "未知"` 回退逻辑，直接消费统一格式化器。
- [x] 统一 `SearchResultsViews.kt` 的 `resolveSearchTrackSubtitle` 与 `PlaybackQueueBottomSheet.kt` 的 `resolveQueueTrackSubtitle`，消除向 `"未知艺术家"` 的散落漂移，与媒体库/歌单保持完全一致。
- [x] 在 `AudioTrackRowTest` 中补充针对各类元数据缺失组合（无艺术家、无专辑、空字符串等）的副标题格式化单测，验证各视窗条目高度严格对齐 72dp 且不塌陷。
- [x] 运行 `./gradlew testDebugUnitTest --tests "*AudioTrackRowTest*"` 且测试全部通过。

## Answer

已通过 `ticket-03` 工作树子代理完成实现并合入集成分支：

1. 在 `AudioTrackRowDefaults` 中实现统一的 `formatTrackSubtitle`，缺失艺术家和专辑信息时统一安全兜底返回 `"未知"`，保障 72dp 行高平齐规范；
2. 重构媒体库 `AudioTrackItemRow`、歌单 `PlaylistTrackRow`、搜索 `SearchResultsViews` 和队列 `PlaybackQueueBottomSheet`，消除各处对 `"未知艺术家"` 的文案漂移与重复拼接逻辑；
3. 更新与新增针对副标题兜底及行高平齐的单元测试，全部通过。

