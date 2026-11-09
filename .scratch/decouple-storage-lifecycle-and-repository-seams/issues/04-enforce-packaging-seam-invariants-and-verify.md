# 04: Enforce Packaging Seam Invariants and Verify

**What to build:** Establish automated regression assertions in `PackagingSeamVerificationTest` to guard against circular dependencies between the storage repository and media library repository, and execute the full test suite to guarantee 0 regressions across the codebase.

**Blocked by:** 03: Streamline Application Container DAG and Purge Downcast

**Status:** ready-for-agent

- [x] In `PackagingSeamVerificationTest`, assert that `ServerRepositoryImpl.kt` does not import `com.webdav.player.domain.repository.MediaLibraryRepository`.
- [x] In `PackagingSeamVerificationTest`, assert that `WebDavApplication.kt` contains zero downcasts (`as? ServerRepositoryImpl` or `as ServerRepositoryImpl`).
- [x] In `PackagingSeamVerificationTest`, assert that `ServerRepositoryImpl.kt` contains zero mutable `var` repository dependencies.
- [x] Execute `./gradlew testDebugUnitTest` and ensure all unit tests pass with zero failures.
- [x] Execute `./gradlew PackagingSeamVerificationTest` to verify that all architectural packaging invariants hold.
