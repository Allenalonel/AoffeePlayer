# 02: Invert Storage Repository Cascade Invalidation and Make Constructor Immutable

**What to build:** Invert the storage repository's cascade deletion dependency by having `ServerRepositoryImpl` accept `StorageCascadeInvalidator` as an immutable constructor argument, permanently eliminating the mutable `var mediaLibraryRepository` property and cutting all imports of `MediaLibraryRepository`.

**Blocked by:** 01: Establish StorageCascadeInvalidator Seam and Decouple Local Directory Resolver

**Status:** ready-for-agent

- [ ] Update `ServerRepositoryImpl` constructor to receive `private val cascadeInvalidator: StorageCascadeInvalidator? = null`.
- [ ] Permanently remove mutable field `var mediaLibraryRepository: MediaLibraryRepository? = null` from `ServerRepositoryImpl`.
- [ ] Remove import of `com.webdav.player.domain.repository.MediaLibraryRepository` from `ServerRepositoryImpl.kt`.
- [ ] Update `ServerRepositoryImpl.deleteServer(id)` to invoke `cascadeInvalidator?.clearStorageData(id)`.
- [ ] Update `ServerRepositoryImpl.removeMountedLocalFolder(uriString)` to invoke `cascadeInvalidator?.clearLocalFolderData(uriString)`.
- [ ] Update `ServerRepositoryTest` and `ServerCascadeDeletionTest` to pass `cascadeInvalidator` in constructor (using `FakeMediaLibraryRepository` or `MediaLibraryRepositoryImpl`).
- [ ] Run `./gradlew test --tests "*ServerRepositoryTest*" --tests "*ServerCascadeDeletionTest*"` and ensure all tests pass green.
