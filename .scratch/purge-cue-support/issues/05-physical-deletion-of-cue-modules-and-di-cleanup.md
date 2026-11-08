# 05: Physical Deletion of CUE Modules and DI Cleanup

**What to build:** With all callers and repositories fully decoupled, all remaining CUE data, domain, and UI files are completely removed from the project. Dependency injection bindings are cleaned up, deleting ~3,500+ lines of obsolete code and leaving the codebase clean, lean, and free of zombie stubs.

**Blocked by:** 02: Playback Engine and Session Stream Consolidation, 03: Repositories and Sync Scanners Purification, 04: Directory Browser and Playlist UI Normalization

**Status:** closed

- [x] Physically delete all files in `app/src/main/java/com/webdav/player/data/cue/` (`CueParser.kt`, `CueVirtualTrackIndexer.kt`, `DefaultCueSheetResolver.kt`).
- [x] Physically delete all files in `app/src/main/java/com/webdav/player/domain/cue/` (`CueSheetResolver.kt`).
- [x] Physically delete domain models `CueAlbumItem.kt` and `VirtualTrack.kt` from `app/src/main/java/com/webdav/player/domain/model/`.
- [x] Physically delete UI component `CueAlbumCard.kt` from `app/src/main/java/com/webdav/player/ui/browser/components/`.
- [x] Unregister `cueSheetResolver` property and factory constructor from `AppContainer`.
- [x] Clean `RemoteFileType.kt` and `AudioQuality.kt` of all references to `RemoteFileType.Cue` and CUE badge definitions.
- [x] Physically delete obsolete test suites in `app/src/test/java/com/webdav/player/data/cue/` (`CueParserTest.kt`, `CueVirtualTrackIndexerTest.kt`, `DefaultCueSheetResolverTest.kt`).
- [x] Verify that the complete project compiles with zero compilation errors.
