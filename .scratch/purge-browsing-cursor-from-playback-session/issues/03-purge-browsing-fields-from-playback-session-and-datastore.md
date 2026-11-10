# 03: Purge Browsing Fields from Playback Session and DataStore

**What to build:** The audio playback session and its persistent storage are completely purified to handle only streaming playback concerns. All directory browsing methods, state fields, and DataStore serialization keys are physically eliminated. Browsing files never triggers playback queue serialization or disk writes, and session persistence focuses solely on audio tracks, shuffle queue mirrors, and playback progress.

**Blocked by:** 02: Migrate Directory Browser View Model to Media Library Seam and Eliminate Startup Wait

**Status:** in-progress

- [ ] The playback session interface physically eliminates `getLastDirectoryForServer` and `setCurrentDirectoryPath`.
- [ ] The playback session reactive state and snapshot data models physically eliminate `currentDirectoryPath` and `serverLastDirectories`.
- [ ] DataStore session serialization and deserialization physically eliminate the directory path preference key.
- [ ] Session persistence coordinator flushes playback snapshots only on genuine playback events (track transition, queue mutation, periodic progress) and never on folder navigation.
- [ ] Cold start playback resumption and process restart simulation tests restore the queue, active track, and progress cleanly without directory fields.
- [ ] All playback session test doubles and unit test suites are updated to remove obsolete directory path assertions.
