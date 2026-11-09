# 04: Enforce Packaging Seam Invariants and Verify

**What to build:** Establish automated regression assertions in `PackagingSeamVerificationTest` to guard against circular dependencies between the storage repository and media library repository, and execute the full test suite to guarantee 0 regressions across the codebase.

**Blocked by:** 03: Streamline Application Container DAG and Purge Downcast

**Status:** ready-for-agent

- [ ] In `PackagingSeamVerificationTest`, assert that `ServerRepositoryImpl.kt` does not import `com.webdav.player.domain.repository.MediaLibraryRepository`.
- [ ] In `PackagingSeamVerificationTest`, assert that `WebDavApplication.kt` contains zero downcasts (`as? ServerRepositoryImpl` or `as ServerRepositoryImpl`).
- [ ] In `PackagingSeamVerificationTest`, assert that `ServerRepositoryImpl.kt` contains zero mutable `var` repository dependencies.
- [ ] Execute `./gradlew testDebugUnitTest` and ensure all unit tests pass with zero failures.
- [ ] Execute `./gradlew PackagingSeamVerificationTest` to verify that all architectural packaging invariants hold.
