# Spec: Local Storage Integration and Multi-Source Playback

Status: completed

## Problem Statement

Aoffee Player is currently architected as a pure-streaming remote WebDAV player. While it excels at streaming Hi-Res audio directly from remote NAS and cloud servers (such as OpenList, Alist, Nextcloud, and private WebDAV servers), users frequently carry high-fidelity music files, ripped SACD/CD CUE sheets, and downloaded tracks directly on their device's internal storage or external SD cards.

From the user's perspective:
1. The bottom navigation bar has a dedicated "服务器" (Servers) destination that only allows configuring remote HTTP/HTTPS WebDAV servers. There is no ability to point the app to songs already stored on the phone.
2. The "媒体库" (Directory Browser) is tightly bound to remote WebDAV servers. If the user is on an airplane, subway, or in an area without network connectivity, the media library becomes unreachable, preventing them from enjoying local music files.
3. The "歌单" (Playlists) feature cannot mix tracks from remote cloud sources and local storage into a unified listening experience. Users cannot create a playlist containing their favorite cloud tracks alongside local lossless recordings.

Mainstream high-fidelity Android players like Salt Player (椒盐音乐) and Symfonium demonstrate that users expect an intuitive, privacy-conscious local storage experience: leveraging Android's modern Storage Access Framework (SAF) to mount custom music folders, browsing them via an elegant hierarchical folder tree, seamlessly reading companion `.cue`分轨 and `.lrc`歌词 without MediaStore noise pollution, and mixing local and cloud tracks inside unified playlists.

## Solution

Introduce first-class **Local Storage** support alongside WebDAV into Aoffee Player through a smooth evolutionary architecture:

1. **Evolve "服务器" to "存储" (Storage Management)**:
   - Rename the third primary navigation bar destination from "服务器" to "存储" (Storage), retaining the iconic storage iconography.
   - Permanently pin a built-in, undeletable **"本地存储" (Local Storage)** node at the top of the storage list, displaying the mounted directory count and status.
   - Provide a "管理目录" (Manage Folders) sheet on the Local Storage card where users can add one or more music directories via the Android SAF system picker (`ACTION_OPEN_DOCUMENT_TREE`) and take persistable URI permissions.
   - Retain the ability to add, edit, test, and activate remote WebDAV servers below the local storage node.
   - Selecting "本地存储" sets it as the active storage node (`Active Storage`) and seamlessly transitions to the Directory Browser.

2. **Hierarchical Local Directory Browsing in "媒体库"**:
   - When Local Storage is active, the Directory Browser's root path (`/`) presents the user's mounted folders as top-level mount point cards (e.g., `📁 内部存储 - Music`, `📁 外置SD卡 - Hi-Res`).
   - Clicking into any mount point enables natural hierarchical folder drill-down, leveraging Android `DocumentsContract` traversal with reactive SWR (Stale-While-Revalidate) caching.
   - Maintain 100% feature parity with WebDAV browsing: automatic detection of companion `.cue` sheets with rich `CueAlbumCard` presentation, embedded and external artwork (`cover.jpg`) thumbnail extraction, companion `.lrc` bilingual lyrics binding, and contextual multi-select.

3. **Hybrid Cross-Source Playlists (WebDAV + Local)**:
   - Allow audio tracks from both WebDAV servers and Local Storage to be added to the same custom playlist.
   - In the database, local tracks are identified by a reserved permanent system ID (`LOCAL_STORAGE_ID = 0L`), with their persistent document URI stored in `remotePath`.
   - When playing local tracks, the playback engine utilizes ExoPlayer's native `DefaultDataSource` for direct, zero-copy local I/O, achieving instantaneous playback with zero network buffering latency.
   - Honor the Symmetric Fault Lifecycle: if a local file is deleted externally or an SD card is unmounted, the track is gracefully grayed out (`FILE_NOT_FOUND`); when the file or SD card is restored, reachability self-healing automatically clears the fault state.

## User Stories

### Storage Management ("存储"视窗)
1. As a music lover, I want to see "存储" as the third tab on the bottom navigation bar instead of "服务器", so that the terminology naturally reflects both local and cloud music sources.
2. As a user opening the Storage screen, I want to see a permanently pinned "本地存储" card at the top, so that I know my device storage is readily available.
3. As a user, I do not want the "本地存储" card to have a delete option, so that I cannot accidentally delete the system local storage node.
4. As a user tapping on "本地存储", I want it to become the active storage source and immediately take me to the "媒体库" to browse my songs.
5. As a privacy-conscious Android user, I want the app to use Android's Storage Access Framework (SAF) folder picker when adding local music, so that I don't have to grant dangerous all-files-access permissions.
6. As an audiophile with music in multiple places, I want to mount multiple local folders (e.g., internal `/Music` and an external MicroSD card folder), so that all my music collections are accessible.
7. As a user managing local folders, I want to see a list of all currently mounted folders with their display names, so that I know exactly what directories the app has access to.
8. As a user, I want to remove a mounted folder from the local storage configuration, so that folders I no longer want to index are excluded.
9. As a WebDAV user, I want to continue adding, editing, and deleting my WebDAV servers (like OpenList or home NAS) below the local storage card, so that my existing remote workflow is completely preserved.
10. As an existing user upgrading the app, I want all my previously saved WebDAV server configurations to remain intact without data loss, so that the upgrade is frictionless.

### Media Library ("媒体库"视窗)
11. As a listener with Local Storage active, I want the root directory of the media library to display my mounted folders as clean directory cards, so that I can choose which music collection to explore.
12. As a listener tapping a mounted folder, I want to drill down into its subfolders just like a file manager, so that I can navigate my curated folder hierarchy.
13. As a listener drilling down into local folders, I want to see breadcrumb chips at the top, so that I can jump back to any parent directory or the root with a single tap.
14. As an album listener, I want local folders containing `.cue` files and long audio files (e.g., single FLAC/WAV image) to render as virtual CUE album cards, so that I can browse and play individual tracks.
15. As a listener, I want local audio tracks to display extracted embedded cover art or companion `cover.jpg`/`folder.jpg` in the directory list, so that the UI is visually rich.
16. As a listener, I want local audio tracks to display their audio format badges (e.g., FLAC, MP3, WAV, Hi-Res), so that I know the audio quality of my files.
17. As an audiophile, I want local directory contents to be cached via SWR (Stale-While-Revalidate), so that entering previously visited folders loads instantly even on slow SD cards.
18. As a user, I want pull-to-refresh in local directories to rescan the folder for newly added or deleted files, so that my library reflects external changes.
19. As a user, I want contextual multi-select to work on local audio tracks, so that I can batch add local songs to playlists or batch enqueue them to play next.
20. As a user searching in the media library, I want search queries to match local files and directories, so that I can quickly find songs stored on my device.

### Hybrid Playlists ("歌单"视窗)
21. As a curator, I want to add songs from my local storage into any playlist, so that I can organize local tracks into themed collections.
22. As a curator, I want a single playlist to hold both WebDAV cloud songs and local device songs, so that I don't have to keep separate playlists for different storage locations.
23. As a listener playing a mixed playlist, I want the player to seamlessly transition from a remote WebDAV track to a local storage track without errors or audio glitches.
24. As a listener playing a mixed playlist, I want the player to transition from a local track to a remote WebDAV track, automatically loading the WebDAV credentials and streaming pipeline.
25. As a listener, I want local songs in a playlist to show their cached cover art, title, artist, album, and duration, matching the visual quality of WebDAV tracks.
26. As a user who removed an SD card, I want playlist tracks from the missing SD card to show a dimmed inactive state with an explanatory message, rather than crashing or skipping violently.
27. As a user who reinserted the SD card, I want the dimmed tracks to automatically self-heal and become playable again, without manual re-adding.
28. As a user reordering a mixed playlist, I want custom drag-and-drop reordering to work identically across local and WebDAV tracks.
29. As a user batch-adding tracks to a playlist, I want duplicate detection to prevent adding the same local song twice to the same playlist.

### Playback Experience & Engine
30. As a listener playing a local track, I want playback to start instantly without network buffering spinners, so that the local experience feels native and snappy.
31. As a listener seeking within a local track, I want timeline seeking to be instantaneous, so that jumping to my favorite part of a song has zero delay.
32. As a listener with local `.lrc` files in the same folder as the music, I want the full-screen player to display synced bilingual rolling lyrics, just like it does for WebDAV tracks.
33. As a listener playing local tracks, I want the Material 3 Expressive dynamic palette to extract colors from local album covers, so that the entire app theme responds to local music.
34. As a listener playing local tracks, I want lock screen controls, system notification media controls, and Bluetooth metadata to display local song details accurately.
35. As a listener with offline local music, I want playback to work flawlessly with Airplane Mode enabled or without an internet connection.

## Implementation Decisions

### 1. Storage Domain & Data Layer Evolution (Smooth Evolutionary Architecture)
- **Unified Storage Source Concept**:
  - Evolve the mental model from `WebDavServer` to `StorageSource` while preserving 100% backward compatibility with Room database tables and DAO queries.
  - Define a canonical domain constant: `const val LOCAL_STORAGE_ID = 0L`.
  - Introduce an extension property on the domain model: `val WebDavServer.isLocal: Boolean get() = id == LOCAL_STORAGE_ID`.
  - For `LOCAL_STORAGE_ID`, synthesize a singleton domain instance:
    ```kotlin
    WebDavServer(
        id = LOCAL_STORAGE_ID,
        name = "本地存储",
        url = "content://local",
        isDefault = false
    )
    ```
- **Local Storage Configuration Persistence**:
  - Create a dedicated lightweight storage model: `data class LocalStorageFolder(val uriString: String, val displayName: String, val dateAdded: Long)`.
  - Store mounted SAF folder URIs in Android Jetpack DataStore (`local_storage_folders.pb` or JSON preferences) or a lightweight Room table.
  - Provide a dedicated repository seam or deepen `ServerRepository` to manage mounted local folders:
    - `fun getMountedLocalFolders(): Flow<List<LocalStorageFolder>>`
    - `suspend fun addMountedLocalFolder(uri: Uri, displayName: String)`
    - `suspend fun removeMountedLocalFolder(uriString: String)`
- **ServerRepository Deepening**:
  - `getAllServers()` emits a combined list: the permanent `LOCAL_STORAGE_ID` node first, followed by configured WebDAV servers.
  - `deleteServer(id: Long)` explicitly guards against deleting `LOCAL_STORAGE_ID` (no-op or error).
  - `setActiveServer(id: Long)` seamlessly supports switching between `LOCAL_STORAGE_ID` and remote WebDAV server IDs.

### 2. Primary Navigation & Storage UI Presentation
- **AppDestination Enum Update**:
  - Update `AppDestination.SERVER_LIST` label from `"服务器"` to `"存储"`.
  - In `MainActivity.kt`, render the bottom bar item as `"存储"` with the existing `AppIcons.Storage` icon.
- **Storage Management Screen (`ServerListScreen.kt`)**:
  - Display the pinned **"本地存储" (Local Storage) Card** at the top of the list:
    - Visuals: Phone/device storage icon, "本地存储" title, subtitle showing "已挂载 X 个音乐目录" (or "未添加音乐目录，点击管理").
    - Action: Tapping the card activates local storage and navigates to `AppDestination.DIRECTORY_BROWSER`.
    - Secondary Action: "管理目录" (Manage Folders) button opening the folder configuration sheet.
  - **Local Folder Management Sheet / Dialog**:
    - Lists all currently mounted SAF directories with their display names.
    - Provides a prominent "+ 添加音乐目录" button that launches the system SAF tree picker (`Intent.ACTION_OPEN_DOCUMENT_TREE`).
    - When a tree URI is returned, calls `context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)` to guarantee permanent access across reboots, then registers the folder in the repository.
    - Allows removing an existing folder entry (which releases persistable URI permission).

### 3. Directory Repository & Local Folder Traversal
- **DirectoryRepository Routing**:
  - When `server.isLocal` is true:
    - If `path == "/"` (root): Synthesize and emit a `RemoteDirectory` whose subdirectories correspond to the user's mounted SAF folder entries (e.g., `path = "/local/<folder_uri_hash>"`, `name = folder.displayName`).
    - If `path` is within a mounted directory: Delegate to `LocalStorageDirectoryResolver`.
- **LocalStorageDirectoryResolver**:
  - Uses `DocumentsContract` and `ContentResolver.query` on `DocumentsContract.buildChildDocumentsUriUsingTree` to efficiently enumerate files and subdirectories without the severe overhead of legacy `DocumentFile.listFiles()`.
  - Filters audio files using existing supported extensions (`.mp3`, `.flac`, `.wav`, `.wma`, `.m4a`, `.aac`, `.ogg`).
  - Identifies companion `.cue` files and invokes `CueParser` to construct `VirtualTrack` and `CueAlbumCard` entries.
  - Identifies companion `.lrc` files to attach lyrics sources.
  - Emits `DirectoryContent` into the existing SWR cache (`DirectoryCacheDao`), enabling offline caching and instant subsequent loads.

### 4. Metadata & Artwork Extraction for Local Tracks
- **TrackMetadataRepository Integration**:
  - For local tracks, open a `ParcelFileDescriptor` or `InputStream` via `ContentResolver.openInputStream(uri)`.
  - Feed the stream into existing parsers (`FlacParser`, `Id3v2Parser`, `WavParser`, `AsfParser`) to extract embedded title, artist, album, duration, and embedded front cover images.
  - Save extracted artwork thumbnails to `CoverArtStorage` under a dedicated local directory namespace (`server_0/`), ensuring immediate display and full palette dynamic theming.
  - Fall back to external companion images (`cover.jpg`, `folder.jpg`) located in the same local directory.

### 5. Playback Pipeline & ExoPlayer MediaSource Construction
- **Media3AudioPlayerEngine & WebDavMediaSourceBuilder**:
  - When loading an `AudioTrack`:
    - If `track.serverId == LOCAL_STORAGE_ID`:
      - The `streamUrl` resolves to the local `content://` or `file://` URI string.
      - `WebDavMediaSourceBuilder.createMediaSource()` recognizes the local scheme and builds a `ProgressiveMediaSource` using `DefaultDataSource.Factory(context)`.
      - Bypasses network-specific OkHttp clients, Digest authenticators, and SSL trusting sockets.
    - If `track.serverId != LOCAL_STORAGE_ID`:
      - Retains the existing `ResilientWebDavDataSource` with authenticated OkHttp and self-healing reconnection.
- **Session Queue Progression & Seamless Switching**:
  - In `MusicPlayerAppSessionImpl`, `loadTrackIntoEngine` checks `track.serverId`.
  - When transitioning between a WebDAV track and a local track:
    - Safely releases network hardware locks (WifiLock) when playing local tracks, and re-engages them when playing WebDAV tracks.
    - Zero disruption to `MediaSessionService`, notification controls, or audio focus.

### 6. Hybrid Playlist Storage & Reachability
- **PlaylistTrackEntity & PlaylistTrackEntry**:
  - Naturally accept `serverId = LOCAL_STORAGE_ID` and `remotePath = localUriString`.
  - `toAudioTrack()` reconstructs the `AudioTrack` with `serverId = 0L`.
- **Reachability Validation**:
  - For local tracks in playlists:
    - Test availability by querying `ContentResolver.query` or attempting to open the URI.
    - If missing or unmounted: mark `isInactive = true, inactiveReason = InactiveReason.FILE_NOT_FOUND`.
    - When user remounts the directory or reinserts SD card: `triggerReachabilitySelfHealing()` tests local URIs and clears inactive status symmetrically.

## Testing Decisions

### What Makes a Good Test
- Tests must verify **observable system behavior at public seams**, not internal private helper methods or reflection.
- Database tests must verify real Room DAO persistence and queries with in-memory SQLite.
- Repository tests must verify reactive Flow emissions (e.g., SWR cache hits followed by enriched updates).
- Session tests must verify that playing, skipping, and queue management dispatch correct states across mixed-source playlists.

### Seams to Test
1. **`ServerRepository` Seam**:
   - Verify `getAllServers()` always emits the synthetic Local Storage node alongside stored WebDAV servers.
   - Verify `deleteServer(LOCAL_STORAGE_ID)` cannot delete local storage.
   - Verify mounting and unmounting SAF folders persists and updates emissions correctly.
2. **`DirectoryRepository` Local Traversal Seam**:
   - Verify root path `/` on local storage emits mounted folders as directory content.
   - Verify child directory traversal parses audio tracks and pairs companion `.cue` and `.lrc` files.
   - Verify SWR caching stores local directory snapshots in Room.
3. **`PlaylistRepository` Mixed-Source Seam**:
   - Verify playlists can store a mix of WebDAV tracks (`serverId = 1L`) and Local tracks (`serverId = 0L`).
   - Verify batch adding local tracks respects deduplication.
   - Verify reachability validation marks deleted local tracks as `FILE_NOT_FOUND` and self-heals when restored.
4. **`MusicPlayerAppSession` & Engine Seam**:
   - Verify playing a local track loads the local URI into the player engine without network exceptions.
   - Verify skipping between a WebDAV track and a local track smoothly updates session state, current track, and queue progression.

### Prior Art in Codebase
- `ServerRepositoryTest.kt`: Testing server lifecycle and active server selection.
- `DirectoryRepositoryTest.kt`: Testing SWR directory observation, cache invalidation, and CUE album enrichment.
- `PlaylistRepositoryTest.kt`: Testing cross-server playlist storage, deduplication, and reachability self-healing.
- `MusicPlayerAppSessionTest.kt`: Testing queue progression, playback intents, and virtual CUE timelines.

## Out of Scope

- Direct `MANAGE_EXTERNAL_STORAGE` (All Files Access) permission request. Aoffee Player strictly follows modern Android SAF guidelines.
- Cloud syncing of local tracks to WebDAV (upload/sync engine).
- In-place ID3 tag writing or audio file editing.
- Automatic full-storage background crawler scraping files outside user-mounted folders.

## Further Notes

- **Target SDK & Min SDK Compliance**: The implementation supports Android 10 (API 29) through Android 14+ (API 34). Using SAF `ACTION_OPEN_DOCUMENT_TREE` with `takePersistableUriPermission` ensures complete compatibility across all target versions without requiring runtime storage permission dialogs on Android 13+.
- **Packaging Seam Verification**: The new feature introduces no new third-party dependencies, adhering strictly to R8 binary size budgets and 64-bit ABI restrictions.
