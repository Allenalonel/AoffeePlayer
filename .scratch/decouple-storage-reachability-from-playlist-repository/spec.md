# Spec: Decouple Storage Reachability from Playlist Repository

Status: ready-for-agent

## Problem Statement

In the current architecture, server reachability checks, offline dimming, and fault self-healing are coupled directly into `PlaylistRepository`. This causes significant architectural and user-facing friction:

1. **Cross-Seam Reverse Leakage**: When audio playback encounters a transient network outage, 404 Not Found, or authentication failure while playing tracks from directory browsing or global search, the playback session's fault-tolerance logic invokes `PlaylistRepository` to mutate the database. Even when the playing track does not belong to any playlist, meaningless database queries and writes are triggered.
2. **Persistent Dirty State & Pseudo-Deadlock**: Reachability status (`isInactive`, `inactiveReason`) is written directly into the SQLite `playlist_tracks` table. When users encounter temporary network drops (e.g. elevators, tunnels, Wi-Fi handoffs), tracks are stamped as inactive on disk. If the app cold-starts while offline, these tracks remain persistently dimmed, causing a pseudo-deadlock where users cannot easily play their songs even after local network conditions recover.
3. **Bloated Repository Interface**: `PlaylistRepository` is forced to act as a global network probe manager. It holds dependencies on network HTTP clients, server repositories, storage access framework (SAF) content resolvers, and system context, exposing six non-playlist health methods and eight dedicated Room DAO mutation queries.
4. **Scattered Self-Healing Call Sites**: When users verify credentials in Server Management, or when system connectivity changes, unrelated callers must reach into `PlaylistRepository` simply to signal server health.

## Solution

Decouple storage source and audio track reachability into a dedicated, deep `StorageReachabilityTracker` subsystem:

1. **Dedicated Reachability Subsystem**: Extract a single authoritative `StorageReachabilityTracker` deep module responsible for tracking unreachable servers, missing files, and invalid credentials, and coordinating the four-way self-healing triggers.
2. **In-Memory Reactive State Flow**: Maintain reachability and dimming status entirely in a thread-safe, reactive in-memory state flow (`StateFlow<Map<TrackKey, InactiveReason>>`). Eliminate frequent SQLite disk writes during network fluctuations, ensuring zero persistent dirty state upon cold-start.
3. **Clean Directional Seams**:
   - `PlaybackFaultTolerance` (playback engine) reports failures and playback success strictly to `StorageReachabilityTracker`, eliminating any reverse coupling to `PlaylistRepository`.
   - `ServerManagementViewModel` reports successful connection tests directly to `StorageReachabilityTracker`.
   - The application session forwards network recovery broadcasts directly to `StorageReachabilityTracker`.
4. **Streamlined Playlist Repository**: `PlaylistRepository` drops all network clients, SAF resolvers, and health probing methods. It focuses purely on playlist CRUD and track ordering. When returning playlist track entries to the UI, it dynamically combines database entities with `StorageReachabilityTracker`'s reactive flow to decorate tracks with their reachability state.
5. **Zero-Migration Database Compatibility**: Retain the existing `isInactive` and `inactiveReason` columns in the Room entity with default values, but cease mutating them on network events, avoiding risky database migrations.

## User Stories

1. As an audiophile listener streaming music from a remote WebDAV server, I want directory browsing and playback errors not to touch or alter my personal playlists, so that my saved playlists remain untouched when exploring new files.
2. As a listener experiencing a transient network hiccup, I want my playlist tracks not to be permanently stuck in an inactive gray state upon app restart, so that my music remains accessible as soon as connectivity returns.
3. As a listener with a cross-source playlist containing both local SAF files and remote WebDAV tracks, I want disconnected servers to visually indicate unavailability only while actually unreachable, so that I can clearly understand why a track cannot be played.
4. As a listener tapping a dimmed track from an offline server in my playlist, I want the player to show an informative explanation ("所属服务器无法连接") and immediately attempt a background probe, so that if the server has come back online, the track instantly unlocks and plays.
5. As a listener tapping a dimmed track whose physical file was deleted or renamed, I want the player to explain that the file is missing ("音频文件已被重命名或删除"), so that I know to clean up or replace that track.
6. As a listener playing through a playlist with an unreachable track, I want the playback queue manager to safely skip over the unreachable track without deadlocking, so that playback continues uninterrupted for the remaining available tracks.
7. As a listener who updates server credentials or host settings in Server Management, I want testing the connection successfully to immediately clear the offline badge across all playlist tracks referencing that server, so that I do not have to restart the app or edit playlists.
8. As a listener whose device transitions from airplane mode back to Wi-Fi, I want the app to automatically detect network recovery and re-probe unreachable servers in the background, so that my playlists automatically return to full health without manual intervention.
9. As a listener successfully playing a track that was previously considered unreachable, I want the successful playback start (sound output) to feed back into the reachability tracker and automatically awaken all tracks on that server, so that verified connectivity is immediately respected.
10. As a listener browsing an active playlist, I want sorting (by custom drag-and-drop, date added, title, or artist) to function seamlessly regardless of whether tracks are currently active or dimmed, so that my organizational preferences are preserved.
11. As a listener managing local device storage on Android, I want unmounted or revoked SAF directory tracks in playlists to be diagnosed as unreachable through SAF Document URI reachability probes, so that I can re-mount or clean up disconnected storage folders.
12. As a listener with multiple WebDAV servers, I want an outage on one specific NAS to dim only tracks belonging to that specific server, without affecting tracks from my other servers or local storage.
13. As an app user on low-end hardware, I want network error events and server recoveries to update in-memory state flows rather than triggering repeated SQLite database transactions, so that the UI stays fluid and flash storage write wear is minimized.
14. As a developer maintaining the codebase, I want `PlaybackFaultTolerance` to have zero imports of or dependencies on `PlaylistRepository`, so that the playback pipeline remains completely isolated from playlist data models.
15. As a developer, I want `PlaylistRepository` to contain zero WebDAV client calls, zero HTTP Range probes, zero SAF content resolver queries, and zero background ping loops, so that the playlist repository is focused strictly on playlist data management.
16. As a developer writing tests for `PlaylistRepository`, I want to test playlist CRUD and ordering without having to mock OkHttp clients, network exceptions, or ContentResolvers, so that tests run fast, reliably, and without brittle setup.
17. As a developer writing tests for `PlaybackFaultTolerance`, I want to verify error handling and skip behavior using a simple in-memory `StorageReachabilityTracker` stub rather than a database repository, so that playback tests stay lightweight and main-safe.
18. As a future maintainer, I want the `StorageReachabilityTracker` interface to be screen-neutral and storage-source-centric, so that directory browsing and library search can consume the same reachability state in the future with zero architectural redesign.

## Implementation Decisions

### 1. Storage Reachability Tracker Interface Contract
A new deep interface will be established in the domain layer to manage all reachability state transitions and self-healing triggers:

```kotlin
interface StorageReachabilityTracker {
    val inactiveTracks: StateFlow<Map<TrackKey, InactiveReason>>
    val serverReachability: StateFlow<Map<Long, Boolean>>

    fun reportTrackUnavailable(serverId: Long, remotePath: String, reason: InactiveReason)
    fun reportServerUnavailable(serverId: Long, reason: InactiveReason)
    fun reportServerReachable(serverId: Long)

    suspend fun triggerReachabilitySelfHealing()
}
```

### 2. Implementation Encapsulation Behind the Seam
The concrete implementation will encapsulate:
- WebDAV PROPFIND testing via the existing WebDAV client;
- SAF Document URI reachability probing via ContentResolver;
- Internal re-entrancy prevention (`AtomicBoolean` / `Mutex`) to avoid concurrent probe storms;
- Thread-safe mutable state flows for active track and server health mappings.

### 3. Removal of Reachability from Playlist Repository
The following members and parameters will be physically eliminated from `PlaylistRepository` and `PlaylistRepositoryImpl`:
- Parameters: `webDavClient`, `serverRepository`, `localUriReachabilityChecker`, `context`;
- Methods: `markTrackInactive`, `markServerInactive`, `clearServerUnreachableStatus`, `getInactiveTracksFlow`, `checkTrackReachability`, `triggerReachabilitySelfHealing`;
- DAO queries: Obsolete `isInactive` and `inactiveReason` SQL update queries in `PlaylistDao`.

### 4. Dynamic Presentation Assembly
`PlaylistRepository.getTracksForPlaylistFlow()` will query `PlaylistDao.getTracksForPlaylistFlow()`, and `combine` with `StorageReachabilityTracker.inactiveTracks` (and server reachability) to emit fully decorated `PlaylistTrackEntry` instances.
`PlaylistRepository.getTracksForPlaylist()` (suspend) will perform an in-memory merge of the current reachability snapshot.

### 5. Dependency Decoupling at Call Sites
- `PlaybackFaultTolerance`: Replaces `playlistRepository` with `storageReachabilityTracker`. Reports 404, credential, and connection failures directly to the tracker. Calls `reportServerReachable` upon playback start.
- `ServerManagementViewModel`: Replaces `playlistRepository` with `storageReachabilityTracker`. Calls `reportServerReachable(serverId)` when connection test succeeds.
- `MusicPlayerAppSessionImpl`: Calls `storageReachabilityTracker.triggerReachabilitySelfHealing()` on network recovery.
- `PlaylistDetailViewModel`: Observes the decorated track list from `PlaylistRepository`. Triggers self-healing on initial load and when clicking dimmed tracks via `StorageReachabilityTracker`.

### 6. Packaging Seam Invariant Enforcement
Update `PackagingSeamVerificationTest` to verify:
- `PlaybackFaultTolerance.kt` contains zero references or imports of `PlaylistRepository`;
- `PlaylistRepository.kt` and `PlaylistRepositoryImpl.kt` contain zero references or imports of `WebDavClient`, `serverRepository`, or reachability probing logic.

## Testing Decisions

### 1. Test Philosophy & Seam Placement
- Tests must be strictly black-box against public interfaces and observable state (`StateFlow` emissions, returned values), avoiding introspection of internal variables.
- Existing seams must be preserved: UI ViewModels test their state flows; `PlaylistRepository` tests its CRUD and combined track streams; `StorageReachabilityTracker` tests its fault reporting, self-healing, and state emissions.

### 2. Modules to Test
- `StorageReachabilityTrackerTest`: Verify that `reportServerUnavailable` marks all tracks under that server, `reportTrackUnavailable` marks a single track, `reportServerReachable` clears the server status, and `triggerReachabilitySelfHealing` executes PROPFIND and SAF probes with re-entrancy protection.
- `PlaylistRepositoryTest`: Verify playlist creation, track addition with deduplication, track reordering, track removal, and that `getTracksForPlaylistFlow` properly combines with `StorageReachabilityTracker` to emit active and inactive tracks.
- `PlaybackFaultToleranceTest`: Verify that 404, auth error, and server unavailable errors invoke `StorageReachabilityTracker` and execute appropriate queue skipping without touching any playlist.
- `ServerManagementViewModelTest`: Verify that successful connection tests notify `StorageReachabilityTracker`.
- `PlaylistDetailViewModelTest`: Verify that dimmed tracks display expected error messages and trigger self-healing.
- `PackagingSeamVerificationTest`: Verify no reverse imports between playback fault tolerance and playlist repositories.

### 3. Prior Art
- `MediaLibraryRepositoryTest`: Prior art for testing consolidated repository streams and memory cache behavior.
- `PlaybackFaultToleranceTest`: Prior art for testing error handling and skip progression.
- `PackagingSeamVerificationTest`: Prior art for architectural seam and reverse-import verification.

## Out of Scope

- Adding offline/dimmed badges to Directory Browser or Global Search UI (the tracker interface is cross-screen neutral, but presentation changes in this effort are scoped to `PlaylistDetail`).
- Altering the SQLite database schema (columns `isInactive` and `inactiveReason` in `playlist_tracks` remain present with default values to avoid database migration risks).
- Introducing periodic background polling services (reachability checks remain strictly event-driven: on-demand click, playback success, test connection, and system network recovery).

## Further Notes

- Aligns directly with **ADR-0038** and **CODING_STANDARDS.md** §1 (Module Depth & Zero Reverse Coupling) and §5 (Symmetric Fault Lifecycle & Multi-Trigger Self-Healing).
- Supersedes the legacy coupled design established in ADR-0022 and ADR-0030.
