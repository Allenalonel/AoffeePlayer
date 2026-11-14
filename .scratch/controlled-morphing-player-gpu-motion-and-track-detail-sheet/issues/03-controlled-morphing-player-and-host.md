# 03: 宿主控制权收拢与受控单体流体播放器 (Controlled Morphing Container & Host Coordinator)

**What to build:**
`MainActivity` 升格为全局唯一的 Scaffold Coordinator，独占维持播放器展开进度 `playerExpansionProgress`（驱动底栏位移与主内容缩放）与全局唯一的 `PlaybackQueueBottomSheet` 抽屉；`MorphingPlayerContainer` 纯化为受控单体组件，剥离冗余的 `internalProgress`；`FullPlayerView` 退化为纯展示插槽，彻底移除内部重复的抽屉副本与手势；确立清晰的返回键消费链路（队列抽屉优先关闭 -> 全屏播放器折叠至 Mini -> 顶层导航退出），彻底终结多层抽屉冲突与返回键状态竞态。

**Blocked by:** None (can start immediately).

**Status:** completed

## Acceptance criteria

- [x] `MainActivity` 独占维持 `isPlayerExpanded: Boolean` 与 `playerExpansionProgress: Float`，单向向底栏传递位移并在顶层单点渲染 `HoistedPlaybackQueueSheet`。
- [x] 顶层 `BackHandler` 严格按优先级协调：`isPlaybackQueueSheetVisible`（优先关闭抽屉）-> `isPlayerExpanded`（折叠至迷你胶囊）-> 顶层导航退出。
- [x] `MorphingPlayerContainer` 彻底剥离内部闲置的 `internalProgress: animateFloatAsState`，只接收外部传入的 `progress: Float`；内聚处理下拉折叠阻尼与滑落手势。
- [x] `FullPlayerView` 彻底移除内部的 `showQueueSheet` 状态与第二套 `HoistedPlaybackQueueSheet`；点击队列按钮仅向外派发 `onOpenQueue` 回调；彻底移除内部重复的 `downwardSwipeModifier` 与重复包裹的主题组件。
- [x] 针对性单元测试通过：`./gradlew testDebugUnitTest --tests "*PlayerSheetExpansionTest*" --tests "*FullPlayerPresentationTest*" --tests "*MiniPlayerPresentationTest*"`。
