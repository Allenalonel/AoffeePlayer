# 03: M3E TopAppBar Sort Action Menu and Presentation Integration

**What to build:** An intuitive, accessible sorting control within the media library's top app bar following Material Design 3 Expressive guidelines. Users can tap a sort icon in the top action area to open a styled dropdown menu, choose their preferred sort field (Name, Last Modified, Size) and toggle direction (Ascending, Descending), with clear checkmark indicators showing the active selection. The control seamlessly fades or collapses when search or multi-selection modes are active.

**Blocked by:** 02: ViewModel Reactive Sorting and WYSIWYG Playback Queue Alignment

**Status:** ready-for-agent

- [ ] A dedicated sort icon button is integrated into `FluidTopAppBar` in `DirectoryBrowserScreen` to the left of the search icon.
- [ ] Tapping the sort button opens a Material 3 `DropdownMenu` containing categorized options for sorting fields (Name, Last Modified, Size) and directions (Ascending, Descending).
- [ ] Active sort field and direction options display distinct visual selection cues (such as checkmark icons or tinted container states).
- [ ] The sort action button gracefully animates out or disables when the floating search capsule expands, and hides during multi-select mode.
- [ ] Tapping any sort option updates the sorting state in the view model and instantly dismisses the dropdown menu.
- [ ] Full project test suite passes (`./gradlew testDebugUnitTest`), and release packaging verification (`PackagingSeamVerificationTest`) confirms zero architectural leaks or binary bloat.
