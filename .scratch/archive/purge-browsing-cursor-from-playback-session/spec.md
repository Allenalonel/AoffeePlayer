# Spec: Purge Browsing Cursor from Playback Session into Media Library

Status: `ready-for-agent`

## Problem Statement

When the user navigates through music directories in the media library, entering folders or returning up breadcrumbs incurs hidden background flash storage wear and unnecessary battery drain. Even though the user is merely browsing files and has not initiated new playback, the background playback session orchestrator serializes the entire audio playback queue (which may contain hundreds of tracks, metadata, and shuffle mirrors) and flushes it to disk on every single folder transition.

Furthermore, upon application cold start, directory browsing experiences an artificial delay because the browser view model unnecessarily suspends and waits for the audio playback session and background playback engine to finish restoring before reading the initial folder. Lastly, directory browsing navigation remains tightly coupled to audio playback internals, creating fragile test doubles and violating the dual-track isolation established between browsing storage and playback servers.

## Solution

From the user's perspective, browsing through folders becomes instantaneous and completely zero-overhead. Directory navigation never triggers playback session disk saving. The directory browser renders immediately on cold start without waiting for the audio playback engine to restore.

When the application is closed and reopened, or when switching between remote WebDAV servers and local device storage, the media library effortlessly and accurately remembers the exact folder where the user left off for each storage source. When a storage source is deleted or a mounted local folder is unmounted, its remembered browsing position is cleanly and automatically purged without leaving orphan data.

## User Stories

1. As a listener browsing music, I want directory transitions to be instantaneous, so that navigating my music collection never causes micro-stutters or high disk I/O.
2. As a listener browsing folders, I want folder navigation to be completely isolated from audio playback saving, so that clicking folders does not trigger unnecessary flash storage writes.
3. As a listener opening the app, I want the media library to render my last visited folder immediately on cold start, so that I don't have to wait for the audio playback engine to finish background initialization.
4. As a listener switching between multiple WebDAV servers, I want the app to remember the last visited folder on each server independently, so that switching servers restores my exact browsing location on that server.
5. As a listener switching between local device storage and remote WebDAV servers, I want my local folder browsing position to be remembered independently from remote server positions, so that I can switch sources without losing my place.
6. As a listener launching the app for the very first time on a server, I want to start at the root directory by default, so that I have a clean entry point into my storage hierarchy.
7. As a listener who deletes a WebDAV server in storage management, I want its remembered browsing location to be permanently removed, so that stale paths do not linger in application storage.
8. As a listener who unmounts a local device folder, I want any browsing cursor pointing inside that unmounted directory tree to safely reset to the root directory, so that I never get trapped in an inaccessible folder.
9. As a listener playing music from a folder, I want the in-progress streaming playback to continue uninterrupted while I navigate to other folders, so that browsing files never interferes with ongoing audio rendering.
10. As a listener listening to a cross-source playlist, I want to explore directories in the media library without affecting my playlist playback queue or triggering playlist re-saves, so that playlist playback and folder exploration remain completely decoupled.
11. As a listener performing global library search, I want search queries and search results to be independent of my folder browsing location, so that searching does not alter my remembered directory cursor.
12. As a listener who kills the app from recent tasks while browsing, I want both my playback state (current track, progress, queue) and my directory browsing state (last visited folder) to restore independently and reliably upon relaunch, so that I experience seamless continuity across process death.
13. As a listener whose active playback server differs from my browsing storage source, I want my browsing directory cursor to belong to the browsing storage source, so that in-flight playback credential transitions never overwrite where I am browsing.
14. As a developer maintaining the codebase, I want the playback session interface to contain strictly playback and queue methods, so that the audio engine facade is cohesive and deep.
15. As a developer writing tests for directory browsing, I want directory browser tests to run without instantiating or mocking the playback session, so that tests are fast, robust, and decoupled.
16. As a developer maintaining storage lifecycle, I want browsing cursor invalidation to be handled inside the media library's cascade invalidation seam, so that storage cleanup invariants are enforced in one place.

## Implementation Decisions

### 1. Media Library Repository Deep Seam Expansion
- The `MediaLibraryRepository` domain interface will be expanded with two dedicated browsing cursor methods:
  - `fun getLastBrowsingPath(serverId: Long): String`
  - `fun setLastBrowsingPath(serverId: Long, path: String)`
- The repository implementation will encapsulate all path validation, normalization, and persistence details behind this seam.
- Callers and tests interact only through this narrow interface without knowing whether storage is backed by memory or disk.

### 2. Internal Browsing Cursor Store Collaborator
- An internal, package-private collaborator (`InternalBrowsingCursorStore`) will be introduced inside the data layer, encapsulated within `MediaLibraryRepositoryImpl`.
- The store will maintain an in-memory thread-safe map (`ConcurrentHashMap<Long, String>`) for zero-latency retrieval during UI rendering.
- Updates will asynchronously write to a dedicated, lightweight key-value preference store (`media_library_preferences`) completely isolated from audio playback data.
- Writing a path update takes less than 0.2ms and does not serialize or touch any audio queue models.

### 3. Storage Cascade Invalidation Integration
- The browsing cursor cleanup will integrate seamlessly into the existing `StorageCascadeInvalidator` implementation within `MediaLibraryRepositoryImpl`:
  - `clearStorageData(serverId: Long)`: Removes the browsing cursor associated with the deleted server ID.
  - `clearLocalFolderData(uriString: String)`: Checks the current local storage cursor; if the cursor starts with or resides within the unmounted local directory tree, resets the local browsing cursor safely back to root (`"/"`).

### 4. Purification of Playback Session and Domain Models
- The domain playback facade (`MusicPlayerAppSession`) will be physically cleansed of non-playback browsing methods:
  - Remove `fun getLastDirectoryForServer(serverId: Long): String`
  - Remove `fun setCurrentDirectoryPath(path: String)`
- The reactive domain state (`PlayerSessionState`) will have the `currentDirectoryPath` property permanently removed.
- The persistence snapshot model (`PlaybackSessionData`) will have `currentDirectoryPath` and `serverLastDirectories` permanently removed.
- The session persistence coordinator (`SessionPersistenceCoordinator`) will cease observing, holding, or serializing directory paths.

### 5. DataStore Playback Session Store Cleanup
- In `DataStorePlaybackSessionStore`, the legacy preference key `KEY_CURRENT_DIRECTORY_PATH` and its reading/writing logic will be completely removed.
- Session persistence will exclusively serialize audio tracks, queue origin, shuffle mirrors, playback index, playback mode, and progress.
- Directory browsing actions will produce zero calls to the session store.

### 6. Directory Browser View Model Decoupling & Instant Startup
- `DirectoryBrowserViewModel` will retrieve the initial browsing path directly and synchronously from `mediaLibraryRepository.getLastBrowsingPath(serverId)`.
- The view model will completely eliminate the asynchronous suspension awaiting `musicPlayerAppSession.isRestored`.
- Folder navigation in the view model will notify `mediaLibraryRepository.setLastBrowsingPath(serverId, normalizedPath)` and will not interact with `MusicPlayerAppSession`.
- `DirectoryBrowserViewModel` will retain `MusicPlayerAppSession` solely for playback commands (`playDirectoryTrack`, `playNext`, transport controls) and active track indicator observation.

## Testing Decisions

### Test Quality Philosophy
- Tests must strictly verify external domain behavior across public seams, never internal implementation details or private storage keys.
- Verification must ensure zero cross-seam leakage: directory navigation must produce measurable state updates on the media library seam while producing zero calls and zero mutations on the playback session persistence seam.

### Modules and Seams to Test

1. **Media Library Repository Seam (`MediaLibraryRepositoryTest`)**:
   - Verify that `getLastBrowsingPath` returns root (`"/"`) by default for unvisited servers.
   - Verify that `setLastBrowsingPath` updates the cursor and subsequent calls return the updated path.
   - Verify that different `serverId`s maintain isolated browsing cursors.
   - Verify that calling `clearStorageData(serverId)` purges the browsing cursor for that server.
   - Verify that calling `clearLocalFolderData(uriString)` safely resets the local storage cursor if it resides within the unmounted directory tree.

2. **Directory Browser View Model Seam (`DirectoryBrowserViewModelTest`)**:
   - Verify that view model initialization reads the initial path from `MediaLibraryRepository` without depending on session restoration.
   - Verify that switching browsing servers reads the target server's saved cursor from `MediaLibraryRepository`.
   - Verify that navigating into a subfolder calls `setLastBrowsingPath` on `MediaLibraryRepository`.
   - Verify that directory navigation tests run cleanly without stubbing or mocking directory methods on `MusicPlayerAppSession`.

3. **Playback Session & Persistence Seam (`SessionPersistenceCoordinatorTest`, `DataStorePlaybackSessionStoreTest`, `MusicPlayerAppSessionTest`)**:
   - Verify that `PlaybackSessionData` serialization and deserialization function correctly without directory fields.
   - Verify that `SessionPersistenceCoordinator` flushes session data only upon track changes, queue mutations, or progress intervals—never on directory navigation.
   - Verify process restart simulation tests resume queue and playback accurately without directory baggage.

4. **Packaging Seam Invariants (`PackagingSeamVerificationTest`)**:
   - Verify that `MusicPlayerAppSession` exposes zero directory navigation methods.
   - Verify that `PlaybackSessionData` and `PlayerSessionState` contain zero directory path fields.

### Prior Art
- `MediaLibraryRepositoryTest`: Existing B-Tree search, SWR directory caching, and cascade invalidation test fixtures.
- `DirectoryBrowserViewModelTest`: Existing view model flow collection and server transition tests.
- `SessionPersistenceCoordinatorTest`: Existing DataStore session save and restore test doubles.
- `PackagingSeamVerificationTest`: Automated architectural AST and reflection invariants.

## Out of Scope

- Search history persistence and indexing (already consolidated into `MediaLibraryRepository`).
- Playback queue internal data structure changes (explicit shuffle queue and order mirroring remain intact).
- UI component visual redesigns (breadcrumbs and track rows remain visually identical).
- Modifying Room database schemas (this feature uses lightweight key-value storage and requires zero SQLite migrations).

## Further Notes

- Fully conforms to `CODING_STANDARDS.md` Section 1 (Module Depth & Zero Artificial Fragmentation) and Section 3 (Cross-Source Storage Identity & Decoupling Browsing Storage from Playback Server Context).
- Completes the architectural vision outlined in `ADR-0035` and recorded in `ADR-0039`.
- Ready for ticket breakdown and test-driven implementation.
