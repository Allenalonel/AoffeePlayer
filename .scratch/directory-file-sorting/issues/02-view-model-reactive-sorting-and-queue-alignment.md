# 02: ViewModel Reactive Sorting and WYSIWYG Playback Queue Alignment

**What to build:** Reactive directory sorting and exact visual-to-audio queue alignment in the directory browser view model. When directories are loaded or refreshed from cache/network, or when the user changes their sorting preference, the directory items in the view model are reordered dynamically in memory without triggering database writes or network queries. When the user taps an audio track or performs batch actions, the audio tracks dispatched to the playback session strictly mirror the visual ordering currently presented on screen.

**Blocked by:** 01: Domain Sort Engine and Media Library Sort Preference Persistence

**Status:** ready-for-agent

- [ ] `DirectoryBrowserUiState` holds the active `DirectorySortOrder`, initialized from the persisted preference in `MediaLibraryRepository`.
- [ ] `DirectoryBrowserViewModel` exposes a public method `onSortOrderChanged(newOrder: DirectorySortOrder)` that updates UI state, persists the preference, and immediately resorts `currentDirectory` in memory.
- [ ] Directory content loaded from `MediaLibraryRepository.observeDirectoryContent()` is automatically sorted according to the current sort order before being emitted in UI state.
- [ ] Switching sort orders operates purely in memory with zero redundant network requests or SQLite database invalidation.
- [ ] When the user clicks an audio track (`onAudioTrackClicked`), the `RemoteDirectory` passed to `MusicPlayerAppSession.playDirectoryTrack()` contains the files in the exact order visible on screen, guaranteeing that "next track" playback strictly honors the user's sort preference.
- [ ] In multi-select mode, `selectAllAudioFiles()`, `getSelectedAudioTracks()`, and `playSelectedNext()` maintain the sequence established by the active sort order.
- [ ] Unit tests in `DirectoryBrowserViewModelTest` verify sort order loading, dynamic in-memory reordering, persistence triggering, and exact playback queue alignment upon track selection.
