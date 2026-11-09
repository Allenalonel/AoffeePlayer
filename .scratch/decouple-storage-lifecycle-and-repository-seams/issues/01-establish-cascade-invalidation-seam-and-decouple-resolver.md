# 01: Establish StorageCascadeInvalidator Seam and Decouple Local Directory Resolver

**What to build:** Establish the `StorageCascadeInvalidator` domain seam interface in `domain.repository` implemented by `MediaLibraryRepository`, and refactor `LocalStorageDirectoryResolver` to inject `LocalStorageFolderDao` directly instead of `ServerRepository`, cutting the reverse dependency edge from the media library to the storage repository.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] Create `StorageCascadeInvalidator` interface in `domain.repository` declaring `suspend fun clearStorageData(serverId: Long)` and `suspend fun clearLocalFolderData(uriString: String)`.
- [ ] Update `MediaLibraryRepository` interface to extend `StorageCascadeInvalidator` with zero modifications to existing method signatures or binary compatibility.
- [ ] Refactor `LocalStorageDirectoryResolver` constructor to receive `LocalStorageFolderDao` instead of `ServerRepository`.
- [ ] Update `LocalStorageDirectoryResolver.resolve()` to read mounted folder entries via `localStorageFolderDao.getAllFoldersFlow().first().map { it.toDomain() }`.
- [ ] Update `LocalStorageDirectoryResolverTest` and `MediaLibraryRepositoryTest` to pass `LocalStorageFolderDao` to `LocalStorageDirectoryResolver`.
- [ ] Run `./gradlew test --tests "*LocalStorageDirectoryResolverTest*" --tests "*MediaLibraryRepositoryTest*"` and ensure all tests pass green.
