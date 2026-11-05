# Spec: Architecture Deepening and Seam Purification (Post-v3.3)

Status: ready-for-agent

## Problem Statement

Following the successful rollout of the Material 3 Expressive motion system and single living view architecture in v3.3 (ADR-0031), four distinct architectural frictions have surfaced in the codebase's most frequently modified regions:

1. **Dead Math Surface & CPU Layout Inefficiencies in Morphing Player**:
   During the migration from transitional mock clones to the Single Living View architecture (`90dca8d`), seven geometry interpolation functions in the `MorphingPlayerMath` interface lost all production callers. However, they remain exposed as public functions, and unit tests continue asserting on these zombie calculations rather than the real container behavior. Simultaneously, the container surface continues executing per-frame CPU layout re-measurements (`Modifier.height()` and `Modifier.padding()`) rather than anchoring smoothly into the GPU draw phase.
2. **Contextual Selection State Machine & Session Leakage in Directory Browser**:
   The `DirectoryBrowserScreen` presentation layer currently juggles dual independent selection sets (directory paths and search track IDs), four back-button handlers, and selection state toggling. Crucially, batch queue actions leak across the ViewModel seam: the UI layer accesses the underlying playback session directly (`viewModel.musicPlayerAppSession?.playNext()`) and manually reverses tracks in a client-side loop. This bypasses the ViewModel, leaving batch dispatch logic untested by unit suites.
3. **Fragmented Subtitle Resolution & Fallback Drift Across Audio Track Rows**:
   Five distinct list implementations (Directory Browser items, CUE album tracks, Playlist rows, Library Search results, and Queue sheet rows) format track subtitles and missing-metadata fallbacks independently. This leads to subtle user-facing discrepancies (e.g., displaying `"未知"` in directory lists versus `"未知艺术家"` in search and queue rows), while scattering the 72dp Track Row Height Parity rule across multiple callers.
4. **Duplicated Reconciliation & Self-Healing Pipeline in Playlist Repository**:
   The reactive Flow (`getTracksForPlaylistFlow`) and one-shot snapshot (`getTracksForPlaylist`) in the Playlist repository contain two nearly identical 80-line blocks. Both independently perform composite key deduplication (`serverId:remotePath:virtualTrackId`), query-level cleanup of zombie database rows, Symmetric Fault Lifecycle state merging, and background cover self-healing. Any future bug fix or self-healing adjustment must be manually mirrored across both blocks, introducing a high risk of divergence.

## Solution

Deepen the four affected modules, collapse shallow interfaces, and consolidate fragmented logic behind clean seams:

1. **Deepen Capsule-to-Fullscreen Morphing Container**:
   - Prune the seven uncalled functions from the `MorphingPlayerMath` public interface.
   - Internalize live morphing thresholds (`SecondaryControlsFadeStartProgress` and `MiniControlsFadeEndProgress`) into the container implementation.
   - Ensure container sizing and corner shaping respect the Draw Phase Animation Rule, eliminating unnecessary CPU measurement passes while strictly preserving 100% of existing UI visual properties.
   - Refactor expansion unit tests to verify observable container states, downward collapse gestures, and live expansion progress instead of orphaned mathematical formulas.
2. **Deepen Contextual Selection & Batch Queue Dispatch in Directory Browser**:
   - Pull contextual selection state, select-all toggling, and clear actions into the `DirectoryBrowserViewModel` interface.
   - Encapsulate batch "Play Next" and batch "Add to Playlist" dispatch within the ViewModel, making the application session reference private.
   - Slim down `DirectoryBrowserScreen` to observe selection state reactively and delegate selection events directly to the ViewModel.
3. **Unify Audio Track Row Subtitles & Height Parity Policy**:
   - Deepen `AudioTrackRowDefaults` by introducing a single canonical subtitle formatting function that accepts domain metadata models and guarantees the standard 72dp double-line height parity.
   - Standardize fallback text to `"未知"` across all views when artist or album tags are missing.
   - Simplify callers (`AudioTrackItemRow`, `PlaylistTrackRow`, `SearchResultsViews`, `QueueTrackItemRow`) so they pass structured metadata to the unified interface rather than pre-formatting ad-hoc strings.
4. **Consolidate Playlist Track Reconciliation Pipeline**:
   - Extract a single private reconciliation pipeline function (`reconcileTracks`) in `PlaylistRepositoryImpl`.
   - Route both `getTracksForPlaylistFlow` and `getTracksForPlaylist` through this single source of truth for composite key deduplication, offline reachability merging, and background cover art self-healing.

## User Stories

1. As a listener, I want smooth, stutter-free container expansion when opening the full-screen player from the mini-player capsule, so that the transition feels fluid and natural at 120Hz.
2. As a listener, I want the full-screen player expansion to consume minimal CPU and battery resources, so that my device runs cool during frequent player interactions.
3. As a developer maintaining the player motion system, I want the morphing math interface to only expose functions actually used by the container, so that I don't waste time maintaining dead code.
4. As a developer writing tests, I want player expansion tests to verify real container behavior and gesture thresholds, so that test passes guarantee actual user experience stability.
5. As an audiophile browsing large music directories, I want to enter multi-select mode and select tracks across folders without UI glitches or lost selections, so that managing batches of songs is predictable.
6. As an audiophile searching for music, I want search multi-select actions to behave identically to directory multi-select actions, so that my muscle memory works across the entire app.
7. As an audiophile selecting multiple tracks, I want tapping "Play Next" to insert the entire batch into the upcoming playback queue in the exact natural order I selected, so that track sequence is preserved.
8. As a developer maintaining the directory browser, I want multi-select state and batch queueing logic to live inside the ViewModel, so that I can unit-test selection logic without instantiating Compose UI nodes.
9. As a developer, I want the UI screen to never access the underlying playback session directly, so that architectural boundaries and unidirectional data flow are maintained.
10. As a listener browsing tracks in directory lists, I want tracks without artist tags to show `"未知"` in their subtitle, so that row height remains a uniform 72dp without collapsing.
11. As a listener browsing search results, I want tracks without artist tags to consistently show `"未知"` rather than inconsistent fallbacks, so that typography matches the directory browser.
12. As a listener viewing the playback queue, I want the current track's progress and missing-tag fallbacks to follow the same formatting rules as the rest of the application, so that the design feels cohesive.
13. As a developer creating audio track list items, I want a single canonical subtitle formatter, so that I don't have to re-invent null-checking and string concatenation in every new screen.
14. As a music curator organizing playlists, I want playlist tracks to load with identical deduplication and cover-healing rules whether I view them on screen or stream them sequentially, so that offline track states never desynchronize.
15. As a developer maintaining the playlist repository, I want track deduplication, reachability fault merging, and cover self-healing to live in a single reconciliation function, so that fixing a fault recovery bug in one place automatically fixes it everywhere.
16. As an Android user, I want all static layouts, padding, margins, colors, and typography to remain visually identical before and after these refactorings, so that zero visual regressions occur.

## Implementation Decisions

### 1. Capsule-to-Fullscreen Morphing Container Deepening
- **Pruning the Math Interface**: Remove unused functions from `MorphingPlayerMath` (`artworkSize`, `artworkCornerRadius`, `artworkRotation`, `playButtonSize`, `playButtonCornerRadius`, `queueButtonSize`, `secondaryControlsAlpha`, `miniControlsAlpha`). Retain only live mathematical helpers (`containerHeight`, `containerHorizontalMargin`, `containerBottomMargin`, `containerCornerRadius`).
- **Internalizing Thresholds**: Move secondary control fade-in and mini-control fade-out threshold constants directly into `MorphingPlayerDefaults` as package-private tokens.
- **Draw Phase Alignment**: Maintain fixed-size layout constraints for the full player view while driving visual bounds, clipping shape, and vertical translation via `graphicsLayer` Draw phase transformations to eliminate CPU measure & layout passes.

### 2. Contextual Selection & Batch Queue Deepening
- **ViewModel Selection Interface**: Expand `DirectoryBrowserViewModel` with dedicated selection state and operations:
  - State: `selectedFilePaths: StateFlow<Set<String>>`, `isSelectionMode: StateFlow<Boolean>`.
  - Operations: `toggleFileSelection(path: String)`, `selectAllAudioFiles()`, `clearSelection()`, `playSelectedNext()`, `getSelectedAudioTracks(): List<AudioTrack>`.
- **Session Encapsulation**: Change `musicPlayerAppSession` in `DirectoryBrowserViewModel` from `val` (publicly accessible) to `private val`. Batch queue operations (`playNext`) must be dispatched internally by the ViewModel.
- **Natural Order Insertion**: When enqueueing multiple selected tracks via `playSelectedNext()`, the ViewModel must ensure tracks are queued so that they play in the exact forward order selected by the user.
- **Screen Simplification**: In `DirectoryBrowserScreen`, remove local `remember { mutableStateOf(setOf<String>()) }` state holders for directory files. Bind the top-bar multi-select controls and floating bottom pill bar directly to the ViewModel's state and methods.

### 3. Track Row Subtitle & Height Parity Consolidation
- **Canonical Formatter**: In `AudioTrackRowDefaults`, provide a single authoritative formatting function:
  ```kotlin
  fun formatTrackSubtitle(
      artist: String?,
      album: String? = null,
      durationText: String? = null,
      extra: String? = null,
  ): String
  ```
  If all metadata fields are null or blank, it must return `"未知"` to guarantee that `ListItem` never collapses to single-line 56dp height, strictly satisfying the 72dp Track Row Height Parity rule.
- **Call-Site Simplification**:
  - `AudioTrackItemRow`: Delete local `resolveAudioSubtitle` and delegate to `AudioTrackRowDefaults.formatTrackSubtitle`.
  - `PlaylistTrackRow`: Remove ad-hoc `?: "未知"` fallback logic and use `AudioTrackRowDefaults.formatTrackSubtitle`.
  - `SearchResultsViews`: Standardize `resolveSearchTrackSubtitle` to use the same canonical fallback instead of diverging to `"未知艺术家"`.
  - `PlaybackQueueBottomSheet`: Unify `resolveQueueTrackSubtitle` to match the canonical formatting standard while preserving active elapsed/duration indicators.

### 4. Playlist Track Reconciliation Pipeline Consolidation
- **Unified Pipeline Seam**: In `PlaylistRepositoryImpl`, extract:
  ```kotlin
  private suspend fun reconcilePlaylistTracks(
      entities: List<PlaylistTrackEntity>,
      inactiveMap: Map<TrackKey, InactiveReason>,
      servers: List<WebDavServer>?,
  ): List<PlaylistTrackEntry>
  ```
- **Unified Responsibilities**:
  1. Composite key deduplication (`"${serverId}:${remotePath}:${virtualTrackId ?: ""}"`) and asynchronous deletion of redundant database IDs via `coroutineScope.launch { playlistDao.deleteTracksByIds(...) }`.
  2. Inactive status reconciliation incorporating manual reasons, persistent entity reasons, and server offline status (`InactiveReason.SERVER_UNREACHABLE`).
  3. Cover thumbnail cache reconciliation and background cover self-healing dispatch (`triggerBackgroundCoverHealing`).
- **Call-Site Delegation**: Both `getTracksForPlaylistFlow` (inside its `combine` lambda) and `getTracksForPlaylist` (suspend one-shot) delegate directly to `reconcilePlaylistTracks`, eliminating ~80 lines of duplicate code.

## Testing Decisions

### Seam Strategy
Tests must exercise external behavior and state transitions across well-defined seams rather than probing internal implementation details:

1. **Player Morphing & Expansion Seam (`PlayerSheetExpansionTest`)**:
   - Verify that mini-player click and swipe-up gestures transition the coordinator state to expanded.
   - Verify that collapse chevrons and downward drag gestures collapse the sheet.
   - Verify that live geometry interpolation functions (`containerHeight`, `containerMargins`, `containerCornerRadius`) produce expected clamped values across progress `0.0f` to `1.0f`.
   - Remove obsolete tests verifying pruned math functions (`artworkSize`, `playButtonSize`, `queueButtonSize`, etc.).
2. **Directory Selection & Batch Dispatch Seam (`DirectoryBrowserViewModelTest`)**:
   - Verify toggling file selection updates `selectedFilePaths` and `isSelectionMode`.
   - Verify `selectAllAudioFiles` selects only audio files, ignoring subdirectories and non-audio files.
   - Verify `playSelectedNext` collects selected audio tracks, dispatches them in correct order to the playback session, and clears the active selection.
3. **Track Subtitle Formatting Seam (`AudioTrackRowTest`)**:
   - Verify `formatTrackSubtitle` formats artist, album, duration, and extra strings separated by `·`.
   - Verify that when artist and album are absent, the formatter strictly returns `"未知"`, preventing subtitle collapse.
   - Verify row height parity across idle, active, and missing-metadata track rows.
4. **Playlist Repository Reconciliation Seam (`PlaylistRepositoryTest`)**:
   - Verify that `getTracksForPlaylistFlow` and `getTracksForPlaylist` return identical track lists with consistent deduplication and inactive status flags.
   - Verify that duplicate entries in the database are purged asynchronously without disrupting caller output.
   - Verify that missing cover thumbnails trigger background healing consistently in both flow and snapshot queries.
5. **Release Packaging & Resource Seam (`PackagingSeamVerificationTest`)**:
   - Verify that binary thinning rules, 64-bit ABI restrictions, and R8 shrinking invariants remain 100% compliant.
   - Verify that total release APK size remains within the 7.5 MB budget.

## Out of Scope

- Introducing new third-party animation, state-management, or dependency injection libraries.
- Changing any visual layout dimensions, color schemes, font weights, paddings, or elevation tokens.
- Modifying ExoPlayer streaming pipelines, HTTP Range reconnection mechanisms, or WebDAV network protocols.
- Adding new user-facing features or screens.

## Further Notes

- All changes must strictly uphold the project's zero UI visual mutation contract.
- Each of the four tickets can be verified independently with targeted unit tests before running the final packaging gate.
