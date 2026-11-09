# 03: Streamline Application Container DAG and Purge Downcast

**What to build:** Reorganize the application container bootstrap in `WebDavApplication` into a pure, deterministic Directed Acyclic Graph (DAG) by constructing `LocalStorageDirectoryResolver` and `MediaLibraryRepositoryImpl` first, passing `mediaLibraryRepository` directly into `ServerRepositoryImpl`'s constructor, and deleting the runtime downcast `(serverRepository as? ServerRepositoryImpl)?.mediaLibraryRepository = ...`.

**Blocked by:** 02: Invert Storage Repository Cascade Invalidation and Make Constructor Immutable

**Status:** ready-for-agent

- [x] In `WebDavApplication.onCreate()`, instantiate `LocalStorageDirectoryResolver` using `database.localStorageFolderDao()`.
- [x] Instantiate `MediaLibraryRepositoryImpl` with the newly created resolver.
- [x] Instantiate `ServerRepositoryImpl` passing `mediaLibraryRepository` as the `cascadeInvalidator` constructor argument.
- [x] Permanently remove `(serverRepository as? ServerRepositoryImpl)?.mediaLibraryRepository = mediaLibraryRepository` from `WebDavApplication.kt`.
- [x] Verify that `WebDavApplication` contains zero downcasts to `ServerRepositoryImpl`.
- [x] Verify that application startup and memory trimming (`onTrimMemory`) continue to function without errors.
