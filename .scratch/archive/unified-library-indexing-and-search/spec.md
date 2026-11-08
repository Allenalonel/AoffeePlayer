# Unified Cross-Source Library Indexing and Search

Status: completed

## Problem Statement

From the user's perspective, the current search functionality within the application is severely limited and counter-intuitive:
1. **Unvisited Folders are Invisible**: Search only finds tracks located within directories that the user has already manually navigated into during the current or previous app sessions. If a user connects a WebDAV NAS with 1,000 albums or mounts a local storage folder with thousands of songs, typing in the search bar returns zero results until every single subfolder has been clicked and cached.
2. **Search Performance Degrades Under Large Caches**: Search queries are executed by scanning raw directory JSON blobs (`dataJson LIKE '%query%'`) and repeatedly deserializing full directory objects on every keystroke, causing noticeable UI stutter, garbage collection pauses, and lag during typing.
3. **No Incremental Synchronization**: There is no dedicated library scanning or indexing mechanism to discover newly added music on remote WebDAV servers or local storage without manually refreshing each specific directory.
4. **No Cross-Source Unified Search Visibility**: Users cannot easily perform a global search across all their configured music sources (local storage and multiple WebDAV servers) to find a song when they do not remember which server or folder hosts it.

## Solution

The solution introduces an automated, decoupled, and incremental **Cross-Source Library Synchronization and Indexing Engine** integrated into the unified repository layer, alongside a high-performance **SQLite B-Tree Search Index**:

1. **Instant First-Class Discovery**:
   - When a WebDAV server is connected or a local storage directory is mounted, the application can perform a swift, non-blocking background index traversal that indexes all audio files, directories, and CUE sheets across the entire storage tree.
   - Once indexed, users can immediately search and find any song or album in their entire collection, even if they have never navigated into that folder.

2. **Unified Cross-Source Search**:
   - Search operates seamlessly across both Local Storage (Storage Access Framework) and remote WebDAV servers, using a unified media index schema.
   - Users can search within the currently active storage source by default, or seamlessly expand their search to query across all configured storage sources simultaneously.

3. **Incremental Synchronization (Fast Rescan)**:
   - Re-scanning an existing library checks directory modification timestamps and ETags against previously recorded timestamps. Unchanged directories and their subtrees are skipped entirely, completing routine refresh passes in seconds.

4. **Two-Tier Progressive Indexing**:
   - **Tier 1 (Fast File Indexing)**: Indexes file paths, file names, sizes, formats, and CUE sheets in seconds. Basic artist and title information is extracted instantly from file naming patterns (e.g., `Artist - Title.flac`), providing 90%+ search accuracy with zero heavy network overhead.
   - **Tier 2 (On-Demand Metadata Enrichment)**: Heavy ID3 tags and embedded album covers are extracted lazily via existing SWR HTTP Range requests only when tracks are viewed, searched, or played, strictly preventing network congestion and battery drain.

5. **Smooth Material 3 Expressive UI Integration**:
   - The media library top app bar displays a subtle sync status indicator when background indexing is active (e.g., "Indexing 340 tracks...").
   - Silent, zero-friction indexing automatically triggers upon source mount, server connection, server switch, or list pull-to-refresh without requiring manual overflow menu navigation.
   - Search results display matching audio tracks and directories with instant playback (Ad-hoc Queue Insertion) and direct navigation.
   - The search interface features a high-contrast Segmented Pill slider for unambiguous switching between the current active source and global library search.

## User Stories

1. As a music listener connecting a WebDAV server with hundreds of artist folders, I want the player to index all audio files in the background, so that I can search for any song immediately without manually clicking into every folder.
2. As a music listener with songs stored on my phone's local storage, I want the app to scan my mounted SAF directories using fast cursor traversal, so that my entire local music library is indexed in seconds.
3. As a user typing a query in the search bar, I want search results to appear instantaneously (< 30ms) as I type, so that there is no typing lag or UI frame drop.
4. As a user who owns multiple music sources (e.g., phone storage and a home NAS), I want to search across all sources at once, so that I can find a track even if I do not remember where it is stored.
5. As a user focused on my current server, I want to filter search results to only the active storage source by default, so that my search results are clean and relevant to my current browsing context.
6. As a user with slow or metered home broadband, I want the library scan to only fetch directory listings and file names, so that indexing does not consume gigabytes of bandwidth reading ID3 tags from all remote audio files.
7. As a NAS owner with mechanical hard drives, I want the player to limit concurrent WebDAV requests during library scanning, so that the scan does not hammer my disks or trigger server rate limits.
8. As a user with private cloud directories containing system folders (like Synology `@eaDir` or `#recycle`), I want the scanner to automatically ignore non-music system directories, so that garbage files do not pollute my search results.
9. As a user who frequently adds new music to my NAS, I want subsequent library scans to skip unchanged directories using modification timestamps, so that refreshing a 5,000-song library takes only a couple of seconds.
10. As a user who listens to single-file album rips with CUE sheets, I want the scanner to detect `.cue` files and index virtual tracks, so that searching for a song title contained inside a CUE sheet returns the virtual track directly.
11. As a user viewing search results, I want clicking an audio track to insert it as the next song in my current playback queue and start playing immediately, so that my existing queue and browsing session are preserved.
12. As a user viewing search results, I want clicking a matching directory to navigate directly to that folder and update the breadcrumb bar, so that I can browse surrounding albums.
13. As a user who uses search frequently, I want my recent search queries saved in a history list, so that I can repeat searches with a single tap.
14. As a user who wants privacy, I want to be able to remove individual search history items or clear the entire history with one click.
15. As a user browsing the media library, I want to see a subtle, non-intrusive sync progress indicator when a scan is in progress, so that I know new music is actively being indexed.
16. As a user experiencing poor Wi-Fi, I want the scanner to gracefully handle transient network errors during sync without crashing or wiping previously indexed songs, so that my existing index remains safe.
17. As a user who updates music files externally, I want a manual "Sync Library" button in the library view, so that I can force a re-scan whenever I want.
18. As a user whose audio files follow common naming conventions like `Artist - Title.mp3`, I want to be able to find tracks by artist or song name even before full ID3 tags have been downloaded.
19. As a user who unmounts a local storage directory or deletes a WebDAV server configuration, I want the app to automatically purge all associated index entries, so that dead files do not linger in search results.
20. As a user listening to music offline, I want search to work completely offline for all my mounted local storage tracks, so that airplane mode does not break my music search.
21. As a user with large collections (over 10,000 tracks), I want the search index stored in indexed SQLite tables rather than raw JSON strings, so that memory usage remains tiny and searches never trigger out-of-memory errors.
22. As a user in selection mode, I want to select multiple search results and batch-add them to any custom playlist, so that building playlists from search is effortless.

## Implementation Decisions

### 1. Unified Media Index Schema (Room Database Migration)
- A new dedicated table for indexing audio files and CUE sheets across all storage sources:
  - Supports both local storage (`serverId = 0`) and remote WebDAV servers (`serverId > 0`).
  - Indexed fields include `serverId`, `remotePath`, `fileName`, `fileSize`, `lastModified`, `format`, `isCue`, `parentPath`, and heuristic `parsedArtist` / `parsedTitle`.
  - B-Tree compound indexes on `(serverId, fileName)`, `(fileName)`, and `(serverId, remotePath)` guarantee single-digit millisecond query times across tens of thousands of tracks without JSON deserialization.
- A directory sync checkpoint table tracks `(serverId, path, lastModified, etag, lastScannedMs)` to power incremental skip logic.
- Migration preserves all existing server configurations, playlists, cached directory contents, and track metadata snapshots without destructive resets.

### 2. High-Level Domain Seam (`LibrarySearchRepository`)
- Deepen the existing `LibrarySearchRepository` domain interface so that it acts as the cohesive single seam for both searching and library synchronization.
- Interface capabilities include:
  - `search(query: String, serverId: Long?): Flow<LibrarySearchResult>`: reactive query emitting matching directories and tracks across all sources or filtered by server.
  - `observeSyncState(serverId: Long?): Flow<LibrarySyncState>`: exposes sync state (`Idle`, `Scanning(progress, currentPath, tracksFound)`, `Completed(totalTracks)`, `Failed(message)`).
  - `syncServer(server: WebDavServer, force: Boolean): LibrarySyncResult`: suspends while performing a cooperative, cancelable scan.
  - Search history management methods: `getSearchHistory()`, `recordSearchQuery()`, `removeSearchHistory()`, `clearSearchHistory()`.
- UI view models interact solely through this high-level repository seam, avoiding scattered dependencies on low-level sync workers or scanners.

### 3. Decoupled Storage Synchronization Providers
- The sync engine delegates traversal to specialized storage providers behind an internal interface:
  - **Local Storage Provider**: Uses direct ContentResolver queries with column projection on `DocumentsContract.buildChildDocumentsUriUsingTree`, bypassing expensive reflection and wrapper allocations.
  - **WebDAV Remote Provider**: Performs recursive Breadth-First Search (BFS) directory listing using existing `WebDavClient` with a strict concurrency limiter (maximum 3 concurrent requests) to eliminate connection exhaustion and server rate limiting.
  - **Blacklist Filter**: Automatically skips known non-music system paths (e.g., `.@eaDir`, `#recycle`, `.Trash-*`, `LOST.DIR`, `.git`, `.thumbnails`).
  - **Incremental Skip**: Before traversing a directory's children, the provider queries the server's directory modification time/ETag; if unchanged from the previous sync checkpoint, the directory's child traversal is bypassed.

### 4. Two-Tier Indexing and Metadata Harmonization
- Indexing never triggers full audio file downloads or bulk HTTP Range metadata extraction.
- Filename heuristics decompose patterns such as `Artist - Title.ext` or `TrackNumber - Title.ext` to populate initial search keys.
- When search results are returned, existing `TrackMetadataDao` entries are merged in-memory to augment tracks with rich ID3 tags (embedded artwork, album name, exact duration) if already cached.
- Unextracted tracks undergo lazy SWR enrichment via existing metadata pipelines when viewed or queued for playback.

### 5. CUE Sheet Virtual Track Integration
- `.cue` files are identified during directory traversal and indexed with their associated parent audio files.
- The search repository leverages `CueSheetResolver` to surface individual virtual tracks in search results alongside standalone audio tracks.

### 6. Cascade Cleanup and Lifecycle Defense
- Deleting a WebDAV server or removing a mounted local storage URI triggers atomic deletion of all associated rows in the media index and directory sync checkpoint tables.
- Server unreachable states gracefully set sync state to failed without purging existing valid index records.

## Testing Decisions

### What Makes a Good Test
- Tests must verify external domain behavior rather than internal SQL query strings or private helper methods.
- A good test exercises the complete flow: feeding a mock WebDAV server or local directory structure into the sync engine, initiating a synchronization, querying search keywords through the repository, and asserting that expected tracks and directories are emitted with correct metadata and server IDs.
- Tests must verify incremental synchronization: modifying a directory timestamp causes re-indexing, while unmodified directories produce zero additional network requests.
- Tests must verify fault resilience: mid-sync connection drops leave previously indexed items intact and report appropriate error states.

### Modules to Be Tested
- `LibrarySearchRepositoryTest`: Tests end-to-end sync, incremental re-scan skips, CUE virtual track indexing, multi-source vs. single-source search filtering, case-insensitivity, and search history operations.
- `DirectoryBrowserViewModelTest`: Tests that search query changes trigger repository search, sync state emissions update UI progress indicators, and directory/track clicks perform expected navigation and playback calls.
- `AppDatabaseMigrationTest`: Tests Room database migration to ensure existing user data (servers, playlists, cache) remains intact while new index tables and indexes are created.

### Prior Art
- `app/src/test/java/com/webdav/player/EndToEndPlaylistAndSearchPipelineTest.kt`
- `app/src/test/java/com/webdav/player/data/repository/LibrarySearchRepositoryTest.kt`
- `app/src/test/java/com/webdav/player/data/repository/DirectoryRepositoryTest.kt`

## Out of Scope

- Implementing WebDAV RFC 5323 `SEARCH` protocol (due to lack of real-world server support across Nextcloud, Alist, Synology, Apache/Nginx).
- Pre-emptively extracting ID3 tags and album artwork for all thousands of tracks during initial scan (violates bandwidth, battery, and instant-scan constraints).
- Background scheduled periodic sync workers via WorkManager over cellular data (sync runs on user action, source mount, or explicit app refresh to respect user data plans).
- Audio waveform pre-calculation or acoustic fingerprinting.

## Further Notes

- Strictly complies with ADR-0002 (Pure Online Streaming Without Persistent Disk Cache) — only lightweight metadata index rows are persisted, never audio bytes.
- Strictly complies with ADR-0013 (64-bit ABI and R8 Binary Thinning) — introduces zero third-party search libraries; relies purely on native Android SQLite and Kotlin Coroutines.
- Complies with ADR-0032 (Local Storage SAF and Cross-Source Playback) by treating `LOCAL_STORAGE_ID = 0L` as a first-class citizen alongside remote servers.
- Architectural design decisions will be formally recorded in `docs/adr/0033-cross-source-library-indexing-and-search.md`.
