# 03: Streamline Application Container DAG and Purge Downcast

**What to build:** Reorganize the application container bootstrap in `WebDavApplication` into a pure, deterministic Directed Acyclic Graph (DAG) by constructing `LocalStorageDirectoryResolver` and `MediaLibraryRepositoryImpl` first, passing `mediaLibraryRepository` directly into `ServerRepositoryImpl`'s constructor, and deleting the runtime downcast `(serverRepository as? ServerRepositoryImpl)?.mediaLibraryRepository = ...`.

**Blocked by:** 02: Invert Storage Repository Cascade Invalidation and Make Constructor Immutable

**Status:** ready-for-agent

- [ ] In `WebDavApplication.onCreate()`, instantiate `LocalStorageDirectoryResolver` using `database.localStorageFolderDao()`.
- [ ] Instantiate `MediaLibraryRepositoryImpl` with the newly created resolver.
- [ ] Instantiate `ServerRepositoryImpl` passing `mediaLibraryRepository` as the `cascadeInvalidator` constructor argument.
- [ ] Permanently remove `(serverRepository as? ServerRepositoryImpl)?.mediaLibraryRepository = mediaLibraryRepository` from `WebDavApplication.kt`.
- [ ] Verify that `WebDavApplication` contains zero downcasts to `ServerRepositoryImpl`.
- [ ] Verify that application startup and memory trimming (`onTrimMemory`) continue to function without errors.
