# 06: ADR 0034, Glossary, and Full Verification Gate

**What to build:** Architectural documentation and domain language are updated to reflect the deprecation and complete removal of CUE support. The entire application passes full unit testing and packaging verification, validating that all regressions are eliminated and the release pipeline remains green.

**Blocked by:** 01: Room v8 Database Migration and Playlist Purge, 05: Physical Deletion of CUE Modules and DI Cleanup

**Status:** closed

- [x] Create and publish `docs/adr/0034-deprecate-and-purge-cue-sheet-support.md` detailing the background, problem statement, decisions, migration steps, and consequences of retiring CUE virtual track synthesis.
- [x] Update `docs/adr/0014-cue-virtual-track-and-folder-ring-gapless-streaming.md` and `docs/adr/0021-deepen-cue-sheet-resolver-and-purge-network-leakage.md` headers to `Status: Superseded by ADR 0034`.
- [x] Update `GLOSSARY.md` to deprecate `Virtual Track` and `Cue Sheet Resolver` entries and clean up companion CUE references under `Local Storage`, `Library Search Index`, and `Session Persistence Coordinator`.
- [x] Execute the full project unit test suite (`./gradlew testDebugUnitTest`) and verify 100% pass across all modules.
- [x] Execute debug build packaging verification (`./gradlew assembleDebug`) to ensure zero resource leakage, clean APK generation, and no broken layouts.
- [x] Perform UI interaction smoke checks using ADB (screen inspection and logcat monitoring) if wireless debugging is active.
