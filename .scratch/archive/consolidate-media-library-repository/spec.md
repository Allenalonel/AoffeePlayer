# Consolidate Media Browsing and Index Search into Deep MediaLibraryRepository

Status: completed

## Problem Statement

From the user's perspective, browsing music folders and searching for tracks across the media library should feel instant, coherent, and seamless. However, due to an artificial architectural separation between directory browsing and global library searching:
1. **Redundant Memory Overhead & Cache Inconsistencies**: The application maintains two separate in-memory caches for directory information. Visited folders cached during directory browsing are not directly synchronized with search cache state, occasionally creating subtle delays, memory duplication, or state divergence between the directory explorer and search results.
2. **Search Stutter on Large Libraries**: When searching across large WebDAV shares or mounted device storage folders, the search engine still falls back to scanning full JSON directory blobs (`directory_cache.dataJson`) and deserializing track lists on the fly. This CPU and memory allocation churn causes keyboard input stutter and micro-stalls on mid-range and battery-saving devices.
3. **Fragile Server Deletion & Folder Unmounting**: Removing a WebDAV server or unmounting a local SAF folder requires manual coordination across multiple low-level database tables and caches. If an operation is interrupted or partially executed, orphan search records or stale cached folders can linger in the media library.
4. **Maintenance and Evolution Friction**: The Directory Browser UI layer (`DirectoryBrowserViewModel`) is forced to orchestrate two parallel repositories (one for folder trees and another for search/sync), making bug fixes, synchronization indicators, and future UI enhancements significantly more complex and error-prone.

## Solution

The solution collapses directory browsing, incremental background indexing, and high-speed B-Tree search into a single, rock-solid, deep **Media Library Subsystem**:
1. **Unified Media Library Interface (`MediaLibraryRepository`)**:
   A single deep repository serves the entire media library. Directory browsing, B-Tree searching, background sync tracking, search query history, and storage invalidation are accessed through one cohesive domain seam.
2. **Zero-Lag Pure B-Tree Search**:
   Search queries strictly execute over the indexed `media_track_index` B-Tree table with sub-millisecond SQLite queries. The sluggish fallback of scanning and deserializing raw directory JSON blobs is completely eliminated, guaranteeing instant 60/120fps keystroke response even with tens of thousands of tracks.
3. **Consolidated Single-Authority In-Memory Cache**:
   A single, authoritative 100-entry LRU memory cache serves both directory navigation and instant query resolution, halving memory overhead and preventing cache drift.
4. **Automatic Write-Through Indexing on Browsing**:
   Whenever a user browses into a folder, the directory content is displayed instantly (0ms from cache) and refreshed via SWR. Any audio tracks found are atomically written through into the search index in the same operation, ensuring browsed folders are immediately searchable without waiting for full background scans.
5. **Atomic Cascading Cleanup on Server/Folder Removal**:
   When a user unmounts a local storage folder or deletes a WebDAV server, the Media Library Subsystem provides a clean cascading invalidation method that wipes directory cache snapshots, B-Tree index rows, sync checkpoints, and memory caches in a single atomic database transaction.
6. **Lean State Presentation**:
   `DirectoryBrowserViewModel` is relieved of multi-repository orchestration. Constructor dependencies drop, synchronization state streams naturally, and user interactions remain smooth and responsive.

## User Stories

1. As a listener browsing my music library, I want folder contents to load instantly from cache, so that navigation between albums and folders is fluid and lag-free.
2. As a listener typing in the search bar, I want song results to appear in real-time as I type, so that I can quickly find any track without keyboard lag or frame drops.
3. As a listener searching for a song in a newly explored folder, I want that folder's contents to be instantly searchable, so that I don't have to wait for a full background library scan.
4. As a listener switching between "Current Storage" and "All Storage" in the search pill, I want results to filter instantly, so that I can easily isolate tracks on my device from my remote NAS.
5. As a listener deleting an unused WebDAV server, I want all of its cached folders and search results to disappear immediately and cleanly, so that ghost tracks never clutter my library search.
6. As a listener unmounting a local device folder, I want its index entries and cached snapshots to be completely erased in a single operation, so that unmounted folders leave zero leftover data.
7. As a listener who frequently searches for the same artists or albums, I want my recent search history to be preserved and easily clearable, so that I can repeat searches with a single tap.
8. As a listener on a metered or slow cellular connection, I want directory browsing to serve cached snapshots first (SWR pattern), so that I can browse my collection even when connectivity is intermittent.
9. As a listener with tens of thousands of songs on my home server, I want background sync to report progress quietly in the top bar without locking up the folder browser or interrupting audio playback.
10. As a listener browsing a deeply nested folder hierarchy, I want breadcrumb navigation to update smoothly without redundant network requests for already-cached parent folders.
11. As a user refreshing a directory via pull-to-refresh, I want the refresh to update both the directory listing and the underlying search index simultaneously, ensuring my library stays 100% consistent with the remote server.
12. As a user reviewing track information in search results, I want high-fidelity metadata (artist, album, duration, format badge, cover art) to match the latest tag data retrieved from the server.
13. As a developer maintaining the codebase, I want `DirectoryBrowserViewModel` to depend on a single `MediaLibraryRepository` interface, so that testing and extending the browser screen is simple and straightforward.
14. As a developer writing automated tests, I want a single authoritative test suite for the media library repository, so that I can verify browsing, indexing, and searching through a unified black-box interface.
15. As an Android system resource manager, I want the media library to maintain a single unified memory cache and honor memory trim callbacks, so that the player consumes minimal RAM when running in the background.

## Implementation Decisions

### 1. Unified Deep Module Interface (`MediaLibraryRepository`)
- Physically retire `DirectoryRepository` and `LibrarySearchRepository` interfaces.
- Create a unified `MediaLibraryRepository` interface in `domain.repository`:

```kotlin
package com.webdav.player.domain.repository

import com.webdav.player.domain.model.DirectoryContent
import com.webdav.player.domain.model.LibrarySearchResult
import com.webdav.player.domain.model.LibrarySyncResult
import com.webdav.player.domain.model.LibrarySyncState
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.WebDavServer
import kotlinx.coroutines.flow.Flow

interface MediaLibraryRepository {
    // SWR Directory Browsing
    fun observeDirectoryContent(
        server: WebDavServer,
        path: String,
        forceRefresh: Boolean = false,
    ): Flow<DirectoryContent>

    suspend fun getCachedDirectory(
        server: WebDavServer,
        path: String,
    ): RemoteDirectory?

    // Multi-source & Single-source B-Tree Search
    fun search(
        query: String,
        serverId: Long?,
    ): Flow<LibrarySearchResult>

    // Incremental Background Sync & Progress
    fun observeSyncState(serverId: Long? = null): Flow<LibrarySyncState>

    suspend fun syncServer(
        server: WebDavServer,
        force: Boolean = false,
    ): LibrarySyncResult

    suspend fun rebuildIndex(server: WebDavServer): LibrarySyncResult

    // Search History Management
    fun getSearchHistory(): List<String>
    fun recordSearchQuery(query: String): List<String>
    fun removeSearchHistory(query: String): List<String>
    fun clearSearchHistory()

    // Unified Storage Deletion Cascade Invalidation
    suspend fun clearStorageData(serverId: Long)
    suspend fun clearLocalFolderData(uriString: String)
    suspend fun clearAllCaches()
    fun clearMemoryCache()
}
```

### 2. Single Consolidated L1 In-Memory Cache
- `MediaLibraryRepositoryImpl` consolidates the two prior LRU caches into a single `LinkedHashMap<String, RemoteDirectory>` with capacity clamped at 100 entries.
- Guarded by internal thread-safe synchronization (`synchronized(cacheLock)`).
- Responds to system `onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)` by clearing the L1 memory cache.

### 3. Elimination of JSON Scanning & Fallback in Search
- Full library search strictly queries `media_track_index` via `MediaTrackIndexDao.search` (composite B-Tree index) and `directory_sync_checkpoint` via `DirectorySyncCheckpointDao.searchCheckpoints`.
- Eliminates the legacy fallback that executed `LIKE %query%` over `directory_cache.dataJson` and deserialized JSON blobs during typing.
- Enforces strict source isolation:
  - Local queries (`serverId == LOCAL_STORAGE_ID` or `server.isLocal`) only match `content://` and local paths;
  - Remote queries (`serverId != null && serverId != LOCAL_STORAGE_ID`) strictly exclude `content://` and local paths;
  - Global queries (`serverId == null`) deduplicate tracks using physical canonical keys (`local:${remotePath}` vs `${serverId}:${remotePath}`).

### 4. Internalization of Scanners and Search History as Private Collaborators
- `WebDavSyncScanner` and `LocalStorageIndexScanner` remain focused scanning engines, but become `internal` private collaborators of `MediaLibraryRepositoryImpl`. They are no longer exposed on `AppContainer` or external DI surfaces.
- SharedPreferences-based search history persistence is encapsulated inside `MediaLibraryRepositoryImpl` (or an internal collaborator `InternalSearchHistoryStore`), presenting only clean methods to callers.

### 5. Storage Invalidation Cascade Consolidation
- `clearStorageData(serverId: Long)`:
  - Removes L1 memory cache entries for `serverId`;
  - Deletes Room `directory_cache` entries for `serverId`;
  - Deletes `media_track_index` rows for `serverId`;
  - Deletes `directory_sync_checkpoint` records for `serverId`.
- `clearLocalFolderData(uriString: String)`:
  - Clears memory cache, Room directory cache, track index, and checkpoints prefixed with the normalized local root path or raw URI for `LOCAL_STORAGE_ID`.
- `ServerRepositoryImpl`:
  - `deleteServer(id)` and `removeMountedLocalFolder(uri)` call `mediaLibraryRepository.clearStorageData(id)` and `mediaLibraryRepository.clearLocalFolderData(uri)`, eliminating direct dependencies on `directoryCacheDao`, `mediaTrackIndexDao`, and `directorySyncCheckpointDao`. Constructor arguments for `ServerRepositoryImpl` drop from 9 to 6.

### 6. Dependency Injection & ViewModel Streamlining
- `DirectoryBrowserViewModel`:
  - Drops `librarySearchRepository` parameter;
  - Replaces `directoryRepository` with `mediaLibraryRepository: MediaLibraryRepository`;
  - Constructor dependencies reduced from 4 to 3.
- `AppContainer`:
  - Exposes `val mediaLibraryRepository: MediaLibraryRepository`;
  - Removes `directoryRepository` and `librarySearchRepository`.
- `WebDavApplication`:
  - Wires `MediaLibraryRepositoryImpl` with `WebDavClient`, `AppDatabase`, `CoverArtStorage`, `LocalStorageDirectoryResolver`, and `TrackMetadataRepository`.

### 7. Governance & Architectural Records
- Aligned with ADR-0036 (`docs/adr/0036-consolidate-media-library-repository.md`).
- ADR-0024 marked as `Superseded`.
- `GLOSSARY.md` updated with `Media Library` definition.

## Testing Decisions

### What Makes a Good Test
- Tests must verify external observable behavior through the `MediaLibraryRepository` seam (StateFlow/Flow emissions, returned search results, cached directory lookups, history mutations).
- Tests must never assert internal private fields, reflection, or raw JSON serializer internals.
- Tests must execute deterministically using coroutine test dispatchers (`runTest`).

### Modules to Be Tested
1. **Media Library Repository (`MediaLibraryRepositoryTest`)**:
   - Consolidated black-box test suite replacing `DirectoryRepositoryTest` and `LibrarySearchRepositoryTest`:
     - SWR directory observation (cache hit 0ms emission, network update, metadata enrichment).
     - Write-through indexing (verifying discovered tracks in directory browsing are automatically upserted into `mediaTrackIndexDao`).
     - B-Tree search with source isolation (WebDAV source filtering, Local storage filtering, and global deduplicated search).
     - Incremental sync scheduling and progress state emissions.
     - Search history LRU limit (max 20), blank query trimming, deduplication, and removal.
     - Atomic storage deletion cascading (verifying cache and index clearing for servers and local folders).
2. **Server Repository (`ServerRepositoryTest`)**:
   - Verify server deletion and mounted local folder removal calls `mediaLibraryRepository.clearStorageData` and `clearLocalFolderData`.
3. **Directory Browser ViewModel (`DirectoryBrowserViewModelTest`)**:
   - Verify UI state transitions using a fake or mock `MediaLibraryRepository`.
4. **Packaging and Seam Verification (`PackagingSeamVerificationTest`)**:
   - Verify zero zombie classes, no references to retired `DirectoryRepository` / `LibrarySearchRepository`, and strict single-seam architecture.
5. **End-to-End Integration Pipeline (`EndToEndPlaylistAndSearchPipelineTest`)**:
   - Verify that folder navigation, indexing, and playlist additions work seamlessly across the consolidated media library.

### Prior Art for the Tests
- Existing `DirectoryRepositoryTest.kt` and `LibrarySearchRepositoryTest.kt` provide established test patterns and mock behaviors that will be merged into `MediaLibraryRepositoryTest.kt`.
- `LocalStorageIndexScannerTest.kt` and `WebDavSyncScannerTest.kt` continue to serve as targeted algorithm unit tests for internal scanning logic.

## Out of Scope

- Modifying the underlying SQLite schema or Room migration: `media_track_index`, `directory_cache`, and `directory_sync_checkpoint` table structures in Room v8 remain completely unchanged.
- Changes to UI design or visual components: `DirectoryBrowserScreen`, `FloatingSearchPillCapsule`, and `SearchResultsViews` UI rendering remain visually identical.
- Modifying audio playback engine or session coordinators: `MusicPlayerAppSession` and playback pipelines are untouched.

## Further Notes

- Net code reduction: Eliminates ~400+ lines of duplicated caching, JSON decoding, and multi-repository boilerplate.
- Strict compliance with `CODING_STANDARDS.md`: Maintains the 600-line single-file budget, Dispatcher three-way separation, and zero zombie classes.
