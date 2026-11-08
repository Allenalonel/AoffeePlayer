# 01: Room v8 Database Migration and Playlist Purge

**What to build:** When users upgrade the application, database migration `MIGRATION_7_8` automatically upgrades the persistent schema to Room version 8. Obsolete CUE virtual tracks previously saved in custom playlists or the media index are cleanly purged without crashing, while all standard songs, user playlists, and server configurations are preserved intact.

**Blocked by:** None (can start immediately)

**Status:** closed

- [x] Implement `MIGRATION_7_8` in `AppDatabase.kt` and update Room database version to 8.
- [x] Recreate `playlist_tracks` using SQLite 3.28-compatible temporary table swapping to eliminate `cuePath` and `virtualTrackId` columns while deleting all legacy rows where `remotePath LIKE '%#cue_%'` or `virtualTrackId IS NOT NULL`.
- [x] Recreate `media_track_index` using temporary table swapping to eliminate the `isCue` column and delete legacy rows where `isCue = 1` or `remotePath LIKE '%#cue_%'`.
- [x] Update `PlaylistTrackEntity` and `MediaTrackIndexEntity` definitions to remove obsolete CUE fields while preserving all existing indices and cascade deletion constraints.
- [x] Simplify SQL queries in `TrackMetadataDao` and `DirectoryCacheDao` to eliminate `#cue_` and `.cue` special-case clauses.
- [x] Remove `KEY_CUE_PATH` and related persistence methods from `DataStorePlaybackSessionStore`.
- [x] Add and execute tests in `DatabaseMigrationTest` verifying that a v7 database with legacy CUE virtual tracks migrates cleanly to v8, removing orphan virtual tracks while preserving all authentic tracks.
