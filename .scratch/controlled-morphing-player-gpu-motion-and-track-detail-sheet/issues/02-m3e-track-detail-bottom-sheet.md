# 02: 统一沉浸式曲目详情抽屉 TrackDetailBottomSheet 与清理浅碎片 (M3E Detail Sheet & Satellite Cleanup)

**What to build:**
在 `ui.common` 建立遵循 Material 3 Expressive 的统一曲目详情抽屉 `TrackDetailBottomSheet`，无论在媒体库目录列表、搜索结果还是自定义歌单，均通过统一抽屉呈现完整的发烧级音频规格（采样率、位深、比特率、Hi-Res 动态徽章）与规范的存储卷标路径（如 `[内部存储] Music/01.flac`）。强制落实 `ModalBottomSheetProperties(isFocusable = false, shouldDismissOnBackPress = true)` 与优雅收起动效，彻底杜绝 Android 14+ 软键盘闪退鬼影（Zero IME Ghost）；彻底物理删除媒体库与歌单两处重复旧 Dialog，并物理删除单行浅文件 `TabAccessibilityGuard.kt`。

**Blocked by:** None (can start immediately).

**Status:** done

## Acceptance criteria

- [x] 在 `ui.common` 中建立统一的 `TrackDetailBottomSheet`，优雅排版封面缩略图、主标题、艺术家、专辑名称、发烧规格药丸（`AudioQualityBadgePill`）、物理存储卷标格式化路径（基于 `TrackPathFormatter`）、文件大小与精确时长。
- [x] 强制落实焦点隔离不变量：抽屉配置 `properties = ModalBottomSheetProperties(isFocusable = false, shouldDismissOnBackPress = true)`，关闭时内聚通过 `sheetState.hide()` 执行位移退出动画后回调卸载，杜绝 IME 软键盘误弹窗与闪退鬼影。
- [x] 媒体库长按/菜单「详细信息」（`AudioTrackItemRow`）与歌单「曲目详情」（`PlaylistTrackRow` / `PlaylistDetailScreen`）统一迁移至新抽屉。
- [x] 彻底物理删除 `PlaylistTrackDetailDialog.kt` 与其对应测试（若存在），彻底删除 `AudioTrackItemRow.kt` 内部的 `FileInfoDialog`。
- [x] 彻底物理删除单行包装浅文件 `TabAccessibilityGuard.kt`，将其修饰符内联沉淀至 `MainActivity.kt` 导航栏调用处。
- [x] 针对性单元测试通过：`./gradlew testDebugUnitTest --tests "*TrackDetailBottomSheetTest*" --tests "*DirectoryBrowserPresentationTest*" --tests "*PlaylistTrackRowTest*"`。
