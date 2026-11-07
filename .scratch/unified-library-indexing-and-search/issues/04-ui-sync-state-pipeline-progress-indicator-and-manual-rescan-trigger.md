# 04: UI Sync State Pipeline, Progress Indicator & Manual Rescan Trigger

**What to build:**
A responsive, non-intrusive UI experience for library synchronization and search. The media library top bar observes sync operations, displaying real-time progress indicators (e.g., "Indexing 340 tracks...") that smoothly fade out upon completion. Users can manually trigger a library re-scan at any time from the overflow menu. When a server or local storage folder is removed, its associated index entries are automatically purged to prevent dead search results. Search results provide instant playback via Ad-hoc Queue Insertion and directory drill-down navigation.

**Blocked by:** 02: WebDAV Remote Incremental Sync Engine & Concurrency-Limited Traversal, 03: CUE Sheet Virtual Track Indexing & Cross-Source Global Search Harmonization

**Status:** ready-for-agent

- [ ] `LibrarySearchRepository` exposes reactive synchronization status (`LibrarySyncState`: Idle, Scanning with progress and count, Completed, Failed).
- [ ] `DirectoryBrowserViewModel` projects sync state into UI state without blocking browsing or search interactions.
- [ ] Top bar / library header displays a subtle Material 3 progress indicator with track counts during active scans.
- [ ] Overflow menu provides an explicit "Sync Library" action to trigger on-demand synchronization.
- [ ] Deleting a WebDAV server or removing a mounted local storage directory cascades atomic deletion of all its indexed tracks and checkpoints.
- [ ] Clicking a search result track inserts it as the next item in the playback queue and begins playback immediately without wiping the existing queue.
- [ ] Clicking a search result directory navigates directly to that folder and updates the breadcrumbs.
- [ ] Robolectric / UI tests verify sync progress state transitions, manual trigger invocation, and cascade index deletion on source removal.
