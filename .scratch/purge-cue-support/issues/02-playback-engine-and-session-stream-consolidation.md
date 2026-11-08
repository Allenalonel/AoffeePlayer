# 02: Playback Engine and Session Stream Consolidation

**What to build:** The playback engine and session coordinator operate strictly 1:1 on authentic physical audio streams. Playback progress, duration, seeking, shuffle transitions, and cold-start session resumption reflect native media positions without synthetic URI transformations (`#cue_`) or virtual timeline offset arithmetic.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] Strip `PlaybackTimelineCoordinator` of `activeCuePath`, `applyCueContextAndGetStartMs()`, and virtual timeline offset translation so ExoPlayer timeline positions and durations reflect native stream values directly.
- [ ] Simplify `PlaybackQueueManager` to operate exclusively on physical audio tracks, removing `resolveDirectoryCueMap()`, `playVirtualTracks()`, and `#cue_` synthetic track ID parsing.
- [ ] Refactor `SessionPersistenceCoordinator` so cold-start session recovery operates purely on persisted track metadata without secondary network requests to fetch or parse companion `.cue` files.
- [ ] Remove `playVirtualTrack()` and `cuePath` arguments from `MusicPlayerAppSession` and `MusicPlayerAppSessionImpl`.
- [ ] Clean domain model `AudioTrack` by removing `cuePath` and `isVirtualTrack`, and remove `cuePath` from `PlaybackSessionData`.
- [ ] Update and verify playback session unit tests (`MusicPlayerAppSessionTest`, `PlaybackSessionResumptionTest`, `SessionPersistenceCoordinatorTest`, `PlaybackQueueTest`, `ProcessRestartSimulationTest`) to ensure all assertions validate direct physical streaming.
