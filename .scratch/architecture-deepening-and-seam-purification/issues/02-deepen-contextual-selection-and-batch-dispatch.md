# 02: 媒体库上下文多选与批量排队状态内聚至 ViewModel

**What to build:** 深化 `DirectoryBrowserViewModel`，将目前在 `DirectoryBrowserScreen.kt` UI 层独立维护的文件多选状态机（`selectedFilePaths`）、全选/反选与清空动作、以及直接穿透会话层的 `playNext` 循环入队逻辑彻底收拢至 ViewModel 内部。使 `DirectoryBrowserScreen` 仅响应式消费 ViewModel 暴露的多选状态流，杜绝表现层直接调用 `viewModel.musicPlayerAppSession?.playNext(track)`，同时在 ViewModel 中提供正序批量插入下一首播放的能力与对应单元测试。

**Blocked by:** None (can start immediately)

**Status: claimed**

## Acceptance criteria

- [ ] 在 `DirectoryBrowserViewModel` 中封装多选状态流与操作方法：`selectedFilePaths: StateFlow<Set<String>>`、`isSelectionMode: StateFlow<Boolean>`、`toggleFileSelection(path: String)`、`selectAllAudioFiles()`、`clearSelection()`、`playSelectedNext()` 及 `getSelectedAudioTracks(): List<AudioTrack>`。
- [ ] 将 `DirectoryBrowserViewModel.musicPlayerAppSession` 访问级别收紧为 `private`，切断 UI 表现层绕过 ViewModel 直接调度底层会话的违规接缝。
- [ ] 在 ViewModel 内部实现 `playSelectedNext()`，将选定音频文件以用户所选的正向顺序原子添加至当前播放队列的下一首，调度完成后自动清除多选状态。
- [ ] 精简 `DirectoryBrowserScreen.kt`：移除其本地维护的多选状态与直接会话穿透，改为绑定 ViewModel 的多选状态流与事件接口。
- [ ] 在 `DirectoryBrowserViewModelTest` 中补充针对多选切换、全选过滤（只选音频忽略文件夹）、清空多选以及正序批量入队的单元测试，保证无 UI 依赖下的高密度单测覆盖。
- [ ] 运行 `./gradlew testDebugUnitTest --tests "*DirectoryBrowserViewModelTest*"` 且测试全部通过。
