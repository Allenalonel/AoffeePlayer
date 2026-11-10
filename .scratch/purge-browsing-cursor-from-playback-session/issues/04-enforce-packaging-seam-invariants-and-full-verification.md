# 04: Enforce Packaging Seam Invariants and Full Verification Gate

**What to build:** Automated packaging seam tests enforce that the playback session interface and session models remain free of browsing directory contamination. The full end-to-end integration and unit test suite passes green, certifying zero reverse coupling, zero playback disk I/O on browsing, and complete conformance with architecture standards.

**Blocked by:** 03: Purge Browsing Fields from Playback Session and DataStore

**Status:** in-progress

- [ ] Packaging seam verification tests assert that the playback session facade exposes zero directory browsing methods.
- [ ] Packaging seam verification tests assert that playback session data models contain zero directory path properties.
- [ ] End-to-end cache resilience and playback pipeline integration tests pass without regression.
- [ ] Full unit test verification suite (`./gradlew testDebugUnitTest`) passes with 100% green tests.
- [ ] Release packaging checks (`./gradlew verifyReleasePackaging`) pass with zero budget regressions.
