# 04: Directory Browser and Playlist UI Normalization

**What to build:** The directory browser and playlist interfaces provide a clean, unified presentation free of CUE cards and virtual track badges. Monolithic audio files appear as standard audio items ready for instant playback, `.cue` files appear as inert non-audio items, and track detail dialogs display only authentic audio properties.

**Blocked by:** 02: Playback Engine and Session Stream Consolidation, 03: Repositories and Sync Scanners Purification

**Status:** ready-for-agent

- [ ] In `DirectoryBrowserScreen`, remove `CueAlbumCard` item rendering, expand/collapse interaction handlers, and file filtering logic so companion audio files appear directly in the main file list.
- [ ] In `AudioTrackItemRow` and related file views, render `.cue` files with standard non-playable file styling (half opacity, non-clickable for playback) while companion audio files display standard quality badges, duration, and click-to-play handlers.
- [ ] In `DirectoryBrowserViewModel` and `DirectoryBrowserUiState`, remove `cueAlbums` state field, `isCueAlbumActive()`, `isVirtualTrackActive()`, `toggleCueAlbumExpanded()`, and `playCueAlbum()`.
- [ ] In `SearchResultsViews`, remove `CueTagBadge` and any UI badges indicating virtual CUE tracks.
- [ ] In `PlaylistTrackDetailDialog`, remove the info rows displaying CUE virtual track identifiers and CUE file paths.
- [ ] In `PlaylistListViewModel` and `PlaylistDetailViewModel`, remove `cuePath` parameters from playlist addition workflows.
- [ ] Update and pass UI tests (`DirectoryBrowserViewModelTest`, `DirectoryBrowserPresentationTest`, `AudioTrackRowTest`, `SearchTrackItemTest`).
