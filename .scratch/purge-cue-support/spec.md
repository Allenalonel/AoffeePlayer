# Purge CUE Sheet Support and Consolidate Stream Playback

Status: completed

## Problem Statement

From the user's perspective, CUE sheet support in the player has become an obsolete and burdensome legacy feature that introduces friction and architectural instability:
1. **Unnecessary Format Overhead**: Modern music libraries streamed via WebDAV or mounted from local device storage are almost entirely organized as individual track files (`.flac`, `.mp3`, `.m4a`, `.ogg`). Monolithic CD rip images with `.cue` sheets are rarely used by listeners today.
2. **Recurrent Architectural & Review Friction**: CUE virtual track synthesis penetrates deeply into every layer of the app. It requires custom AST lexical parsing, heuristic companion audio file association, synthetic URI rewriting (`#cue_N`), timeline arithmetic spoofing in the media engine, and cold-start session recovery hacks. This generates persistent complexity warnings and structural debt during architectural reviews.
3. **Playback & Timeline Inconsistencies**: Because virtual tracks share a single physical audio stream, operations such as high-frequency progress polling, seeking, gapless transitions, and cold-start state restoration suffer from timing jitter, occasional timeline drift, and network re-buffering edge cases.
4. **Confusing Directory UI**: When a directory contains a `.cue` file, the player intercepts the companion audio file and nests it into an expandable card. Users who just want to play the whole album or audio file directly find this card-based nesting distracting and unintuitive.

## Solution

The solution completely deprecates and physically purges all CUE sheet handling, virtual track synthesis, and timeline offsets across the entire stack, restoring the player engine and repository layer to 100% 1:1 physical audio file streaming:

1. **Clean Directory Browsing**:
   - Monolithic audio files (`.flac`, `.wav`, `.ape`) previously hijacked by CUE associations are directly listed as standard audio tracks with their native format badges, artwork, and full-stream playback capabilities.
   - Any `.cue` files present in remote WebDAV directories or mounted local folders are treated as inert, non-playable auxiliary/unsupported documents (displayed with an inactive style and disabled playback clicks), matching user expectations for non-audio auxiliary files.
   - The expandable card component for CUE albums is removed, simplifying the directory list into a clean, unified flat structure of folders and tracks.

2. **Unified 1:1 Playback Engine**:
   - All tracks in the active playback queue, shuffled order, and session state correspond 1:1 to genuine physical audio streams.
   - The playback timeline coordinator and queue manager are stripped of synthetic URI rewriting, start-offset arithmetic, and virtual track duration spoofing. ExoPlayer timeline positions and durations reflect native stream values directly.

3. **Safe Database Migration (Room v7 to v8)**:
   - A robust migration eliminates legacy CUE fields and cleans virtual track references without data loss for regular tracks.
   - Any historical playlist entries that point to synthetic `#cue_` virtual tracks are automatically purged during migration to prevent unplayable orphan items. All normal tracks and custom playlists are preserved intact.
   - The media track search index is migrated to drop CUE flags and virtual rows, ensuring instantaneous single-digit millisecond search queries without synthetic path deduplication logic.

4. **Simplified Search & Synchronization**:
   - WebDAV synchronization and Local Storage (SAF) scanners ignore `.cue` files during audio discovery and cease background CUE AST parsing.
   - Library search returns genuine physical files directly without intermediate CUE album resolution or text cache querying.

5. **Elimination of Architectural Debt**:
   - All CUE parsing, indexing, and resolution packages are physically removed, deleting ~3,500+ lines of high-maintenance code and resolving all related architectural review concerns permanently.

## User Stories

1. As a listener browsing a music folder containing a monolithic `.flac` audio file and an old `.cue` sheet, I want to see the `.flac` file as a regular playable track, so that I can click and listen to the whole album immediately without dealing with expandable sub-track cards.
2. As a listener browsing files, I want `.cue` files to appear as non-playable auxiliary files, so that I know the file exists on my server or device without accidentally attempting to play it as audio.
3. As a listener using library search, I want search queries to match only genuine audio tracks and folders, so that search results are fast, predictable, and free from duplicate or unplayable virtual items.
4. As a user upgrading the app with existing custom playlists, I want my normal songs to remain completely intact while broken legacy CUE virtual tracks are cleanly purged, so that my playlists never encounter playback crashes or orphan rows.
5. As a listener playing music, I want playback progress, duration, and seeking to reflect the exact physical audio stream without offset arithmetic, so that seek operations are instant, seamless, and free from timeline jitter.
6. As a listener using shuffle mode, I want the queue to contain only authentic physical audio items, so that next/previous track transitions and background playback transitions never trigger synthetic track offset recalculations.
7. As a listener resuming playback after app restart or system process termination, I want the player to restore the last played track directly from its physical path without attempting to download and parse companion `.cue` sheets over the network.
8. As a user scanning a large WebDAV music library, I want synchronization to ignore `.cue` files and only index audio formats, so that initial library scanning finishes faster and consumes minimal network bandwidth.
9. As a user mounting local storage via Android Storage Access Framework (SAF), I want folder scanning to index only genuine audio files without reading local `.cue` file streams into memory.
10. As a listener viewing song details in a playlist, I want the track info dialog to display clean metadata without obsolete CUE path or virtual track identifier labels.
11. As a user reviewing directory contents, I want consistent loading times and zero skeleton placeholder flickering that was previously required while awaiting CUE sheet discovery.
12. As a developer and maintainer, I want the codebase to be free of CUE parsing, matching heuristics, and timeline translation, so that future architectural reviews and code deepening passes are not burdened by obsolete format requirements.
13. As a developer writing playback unit tests, I want playback session and queue tests to operate purely on clean audio track fixtures without configuring mock CUE web responses or resolvers.
14. As an audio listener streaming high-resolution lossless audio, I want the media pipeline to avoid synthetic ExoPlayer clipping or stream restarting, ensuring smooth and gapless playback.
15. As a maintainer, I want the project glossary and architecture decision records to accurately reflect the retirement of virtual tracks, maintaining high domain clarity.

## Implementation Decisions

### 1. Database Schema Migration (Room v7 to v8)
- Bump Room database version from 7 to 8 with a non-destructive migration (`MIGRATION_7_8`).
- Android 10 (API 29) uses SQLite 3.28, which does not support `ALTER TABLE DROP COLUMN`. Schema updates use the standard SQLite table recreation pattern:
  - Create temporary replacement tables without CUE columns (`cuePath` and `virtualTrackId` in `playlist_tracks`; `isCue` in `media_track_index`).
  - Copy existing valid rows into the replacement tables:
    - In `playlist_tracks`, filter out rows where `remotePath LIKE '%#cue_%'` or `virtualTrackId IS NOT NULL`.
    - In `media_track_index`, copy only entries where `isCue = 0` and `remotePath NOT LIKE '%#cue_%'`.
  - Drop the old tables and rename the replacement tables.
  - Re-establish all primary keys, foreign key cascade constraints, and B-Tree indexes.
- In `track_metadata`, simplify any queries that historically stripped `#cue_` substrings from remote paths.
- In `directory_cache`, remove query logic specifically filtering or querying `.cue` patterns.
- In `DataStorePlaybackSessionStore`, remove the preference key for persisting CUE paths.

#### Prototype Migration Schema
```sql
-- Room MIGRATION_7_8 summary
CREATE TABLE `playlist_tracks_new` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `playlistId` INTEGER NOT NULL,
    `serverId` INTEGER NOT NULL,
    `remotePath` TEXT NOT NULL,
    `sortOrder` INTEGER NOT NULL,
    `title` TEXT NOT NULL,
    `artist` TEXT,
    `album` TEXT,
    `durationMs` INTEGER NOT NULL,
    `size` INTEGER NOT NULL,
    `format` TEXT NOT NULL,
    `coverThumbnailPath` TEXT,
    `isInactive` INTEGER NOT NULL DEFAULT 0,
    `inactiveReason` TEXT,
    FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
);
INSERT INTO `playlist_tracks_new` SELECT id, playlistId, serverId, remotePath, sortOrder, title, artist, album, durationMs, size, format, coverThumbnailPath, isInactive, inactiveReason FROM `playlist_tracks` WHERE `remotePath` NOT LIKE '%#cue_%' AND `virtualTrackId` IS NULL;
DROP TABLE `playlist_tracks`;
ALTER TABLE `playlist_tracks_new` RENAME TO `playlist_tracks`;

CREATE TABLE `media_track_index_new` (
    `serverId` INTEGER NOT NULL,
    `remotePath` TEXT NOT NULL,
    `fileName` TEXT NOT NULL,
    `fileSize` INTEGER NOT NULL,
    `lastModified` INTEGER NOT NULL,
    `format` TEXT NOT NULL,
    `parentPath` TEXT NOT NULL,
    `parsedTitle` TEXT NOT NULL,
    `parsedArtist` TEXT,
    PRIMARY KEY(`serverId`, `remotePath`)
);
INSERT INTO `media_track_index_new` SELECT serverId, remotePath, fileName, fileSize, lastModified, format, parentPath, parsedTitle, parsedArtist FROM `media_track_index` WHERE `isCue` = 0 AND `remotePath` NOT LIKE '%#cue_%';
DROP TABLE `media_track_index`;
ALTER TABLE `media_track_index_new` RENAME TO `media_track_index`;
```

### 2. Complete Physical Purge of CUE Data and Domain Modules
- Physically delete the entire CUE parsing and indexing package (`data.cue`) and CUE domain package (`domain.cue`).
- Delete domain models `CueAlbumItem` and `VirtualTrack`.
- Remove CUE resolver bindings from the dependency injection container (`AppContainer`).
- Clean domain models:
  - In `AudioTrack`: remove `cuePath` and `isVirtualTrack`.
  - In `PlaylistTrackEntry`: remove `cuePath`, `virtualTrackId`, and `isVirtualTrack`.
  - In `PlaybackSessionData`: remove `cuePath`.
  - In `DirectoryContent`: remove `cueAlbums`.
  - In `RemoteFile`: remove `isCue`.
  - In `RemoteFileType`: remove `Cue` type; classify `.cue` files as standard unsupported/document files.
  - In `AudioQuality`: remove CUE badge mappings.

### 3. Streamlining the Playback Session & Engine Pipeline
- Deepen and simplify `MusicPlayerAppSession`:
  - Remove `playVirtualTrack` interface method and all `cuePath` parameters from playback requests.
  - The queue manager operates exclusively on 1:1 physical `AudioTrack` entities; eliminate directory CUE mapping and `#cue_` synthetic ID parsing.
- Deepen and simplify `PlaybackTimelineCoordinator`:
  - Remove `activeCuePath`, `applyCueContextAndGetStartMs()`, and all virtual timeline start offset math.
  - Track start positions are determined strictly by user seek intent or zero for new tracks.
- Deepen and simplify `SessionPersistenceCoordinator`:
  - Cold session restoration operates purely from persisted track metadata without secondary network requests to fetch `.cue` files.

### 4. Repository & Scanning Simplification
- In `DirectoryRepository`:
  - Remove CUE resolution steps from directory observation flows.
  - Emit directory contents containing only directories and standard files. Companion audio files are returned in the standard file list.
- In `LibrarySearchRepository`:
  - Remove CUE text cache querying and virtual track expansion.
  - Search queries directly match physical tracks and folders in `media_track_index`.
- In `LocalStorageDirectoryResolver` and `LocalStorageIndexScanner`:
  - Remove all CUE file parsing and virtual track insertion for mounted local storage directories.
- In `WebDavSyncScanner`:
  - Remove CUE virtual track indexing hooks.

### 5. UI Presentation & Interaction Normalization
- In `DirectoryBrowserScreen`:
  - Delete `CueAlbumCard` component and remove all references to CUE albums, expand/collapse states, and virtual track click handlers.
  - Remove filtering logic that concealed companion audio files; companion files are displayed as regular audio tracks.
  - Render `.cue` files using standard non-audio/unsupported file appearance (half-opacity, non-clickable).
- In `DirectoryBrowserViewModel` and its UI state:
  - Remove `cueAlbums` state field and related methods (`toggleCueAlbumExpanded`, `playCueAlbum`).
- In `SearchResultsViews`:
  - Remove `CueTagBadge` component.
- In `PlaylistTrackDetailDialog`:
  - Remove CUE virtual track identifier and CUE file path info rows.

### 6. Domain Documentation & Architectural Governance
- Publish ADR 0034 (`docs/adr/0034-deprecate-and-purge-cue-sheet-support.md`) recording this decision and marking ADR 0014 and ADR 0021 as `Superseded`.
- Update `GLOSSARY.md` to deprecate `Virtual Track` and `Cue Sheet Resolver` and cleanse related entries.

## Testing Decisions

### What Makes a Good Test
- Tests must verify external observable behavior of domain and repository seams, never internal helper methods or implementation trivia.
- Tests must prove that directories containing audio files and `.cue` files expose audio files directly for playback while listing `.cue` files as inert items.
- Tests must verify that playback sessions start and progress using native stream positions without offset translation.
- Tests must verify database migration safety: existing v7 databases migrate cleanly to v8, legacy CUE virtual tracks are purged, and all normal playlists and audio indices remain 100% intact.

### Modules to Be Tested
1. **Database Migration (`DatabaseMigrationTest`)**:
   - Verify `MIGRATION_7_8` migrates schema from version 7 to 8.
   - Assert that virtual tracks (`#cue_`) in `playlist_tracks` are deleted.
   - Assert that standard audio tracks in playlists are preserved with exact metadata.
   - Assert that `isCue = 1` rows in `media_track_index` are purged while regular tracks remain.
2. **Directory Repository (`DirectoryRepositoryTest`)**:
   - Verify that directory content emits standard files and directories with empty/absent CUE album structures.
   - Verify companion audio files are emitted in the main file list as playable audio.
3. **Playback Engine & Session (`MusicPlayerAppSessionTest`, `PlaybackSessionResumptionTest`, `SessionPersistenceCoordinatorTest`)**:
   - Verify standard queue playback without CUE parameters.
   - Verify cold start restoration succeeds without fetching CUE files or attempting virtual track offset math.
4. **Library Search (`LibrarySearchRepositoryTest`)**:
   - Verify search queries match audio file names and indexed tags, returning genuine physical `AudioTrack` items.
5. **Directory Browser UI State (`DirectoryBrowserViewModelTest`)**:
   - Verify UI state cleanly reflects directories and audio tracks without CUE album collections or collapse/expand state handlers.

### Prior Art for the Tests
- Existing `DatabaseMigrationTest` in `app/src/test/java/com/webdav/player/data/local/DatabaseMigrationTest.kt` provides clear patterns for testing Room migrations.
- Existing `DirectoryRepositoryTest`, `MusicPlayerAppSessionTest`, and `LibrarySearchRepositoryTest` provide established fakes and fixtures that can be pruned of CUE mocks to assert pure physical streaming behavior.

## Out of Scope
- Automatic audio file splitting or slicing: The player will not attempt to split monolithic FLAC/WAV files on the server or device. Monolithic audio files will play as single continuous tracks.
- Embedded CUE sheet parsing: Parsing CUE metadata embedded inside FLAC Vorbis comments is out of scope.
- Third-party CUE converter utilities: Converting `.cue` sheets to `.m3u` or external playlists is out of scope.

## Further Notes
- This refactoring permanently purges approximately 3,500+ lines of high-complexity code.
- Verification must ensure zero compilation warnings, clean Room schema migrations, all unit tests passing, and responsive UI interactions without visual regression.
