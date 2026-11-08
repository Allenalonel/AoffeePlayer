# 03: Repositories and Sync Scanners Purification

**What to build:** Repositories and multi-source scanners discover, cache, and search music collections purely as physical audio files and directories. Companion audio files are returned directly in directory contents without CUE nesting, while `.cue` files are treated as inert non-audio auxiliary items. Library search queries execute instantly against indexed physical audio tracks without synthetic virtual track synthesis.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] Refactor `DirectoryRepository` and `DirectoryRepositoryImpl` to eliminate CUE sheet resolution and skeleton placeholder generation, listing companion audio files directly in the main file collection and removing `cueAlbums` from `DirectoryContent`.
- [ ] Refactor `LibrarySearchRepository` and `LibrarySearchRepositoryImpl` to perform direct B-Tree queries on indexed audio tracks without CUE text cache querying, companion resolution, or synthetic `#cue_` deduplication.
- [ ] Refactor `LocalStorageDirectoryResolver` and `LocalStorageIndexScanner` to stop reading local `.cue` files into memory and stop generating synthetic virtual track records.
- [ ] Refactor `WebDavSyncScanner` to remove `CueVirtualTrackIndexer` integration and index only genuine audio files.
- [ ] Refactor `PlaylistRepository` and `PlaylistRepositoryImpl` to remove `cuePath` parameters and map cleanly to the updated Room entities.
- [ ] Update and execute repository tests (`DirectoryRepositoryTest`, `LibrarySearchRepositoryTest`, `LocalStorageDirectoryResolverTest`, `LocalStorageIndexScannerTest`, `WebDavSyncScannerTest`, `PlaylistRepositoryTest`) ensuring all tests pass without CUE dependencies.
