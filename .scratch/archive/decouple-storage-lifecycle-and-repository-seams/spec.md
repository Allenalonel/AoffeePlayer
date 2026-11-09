# Decouple Storage Lifecycle and Eliminate Repository Circular Seams

Status: ready-for-agent

## Problem Statement

From a music listener and storage administrator's perspective, configuring, switching, deleting WebDAV servers, and mounting or unmounting local device folders should be instantaneous, reliable, and leave no residual artifacts. When a server or directory is removed, the app must guarantee that search results and cached views immediately reflect that change without stale ghost entries or silent cache memory leaks.

However, behind the scenes, the storage management subsystem and the media library subsystem are tightly coupled in a circular dependency:
1. **Hidden Initialization Hazards & Late-Binding Fragility**: At application startup, the storage repository cannot be properly constructed as an immutable component. Because the local directory resolver depends on the storage repository, while the storage repository depends on the media library repository for cascade deletion, the application container is forced to use a mutable backdoor setter (`var mediaLibraryRepository`) and an unchecked runtime downcast. If any component attempts to invoke storage invalidation before this late property is mutated, or if startup sequence changes, cascading deletion silently fails.
2. **Brittle Test Surfaces**: Unit and integration tests for storage management are forced to construct or mock complex, multi-faceted media library repositories just to test basic server deletion or folder unmounting, expanding the test blast radius unnecessarily and increasing maintenance friction.
3. **Leaky Boundary Discipline**: The local directory resolver needs nothing from the storage repository other than the set of user-authorized storage folders. Forcing it to depend on the entire storage repository leaks remote server management, active server state tracking, and network credentials into a purely local file-tree resolution component.

## Solution

The solution completely untangles the circular dependency between storage configuration and media library caching by establishing a clean, unidirectional, immutable pipeline:
1. **Narrow Domain Seam for Storage Cascade Invalidation**:
   Introduce a minimal, single-responsibility domain interface dedicated strictly to storage node removal: `StorageCascadeInvalidator`. The `MediaLibraryRepository` fulfills this contract as part of its external test surface.
2. **Immutable Inverted Constructor Injection**:
   The storage repository accepts the narrow invalidation interface as an immutable constructor dependency. The mutable property backdoor and runtime type-casting during application bootstrap are permanently eliminated.
3. **Focused Local Directory Resolution**:
   The local storage directory resolver is pruned to depend strictly on the local storage folder data access interface, completely removing its unnecessary upstream coupling to the storage repository.
4. **Pure Unidirectional Dependency Pipeline (DAG)**:
   The application startup container instantiates components in a strict, deterministic, acyclic order: Database DAOs → Local Directory Resolver → Media Library Repository → Storage Repository.
5. **Zero-Regression Architectural Packaging Guard**:
   Automated architectural rules in the packaging verification suite ensure that the storage repository never imports the media library repository, and the application bootstrap container never performs downcasts or late-mutation on repository singletons.

## User Stories

1. As a music listener removing an old or decommissioned WebDAV server, I want all associated search results, cached folder hierarchies, and sync checkpoints to be immediately purged, so that my music search never displays dead or unplayable tracks.
2. As a music listener unmounting a local device folder, I want its contents to disappear cleanly from the media library and search index, so that unmounted local tracks leave zero trace on my device.
3. As a user launching the application, I want storage accounts and the media library to initialize predictably with zero race conditions or startup order dependency bugs, so that playback and browsing are immediately available.
4. As a user on a battery-saving device, I want storage deletions to clear in-memory caches deterministically, so that memory is freed up immediately rather than waiting for garbage collection or app restarts.
5. As an audio enthusiast with multiple NAS servers, I want deleting one server to leave the cache and indexing of all other servers completely intact, ensuring isolated and safe multi-server management.
6. As a listener browsing local storage, I want local folder resolution to load quickly and independently from remote server connectivity, so that offline listening is never blocked by remote network checks.
7. As a listener who frequently renames or re-mounts storage directories, I want unmounting and remounting to happen without database foreign-key collisions or stale checkpoint remnants.
8. As a developer maintaining the codebase, I want `ServerRepositoryImpl` to be 100% immutable upon construction, so that thread-safety and dependency injection are transparent and robust.
9. As a developer writing tests for storage management, I want to test server deletion using a lightweight, two-method test double, without having to spin up or mock full media library search and browsing implementations.
10. As a developer maintaining local file discovery, I want the local directory resolver to depend only on mounted folder data, so that local file resolution logic is decoupled from remote server credentials and network clients.
11. As an architectural reviewer, I want automated regression tests to fail immediately if any class introduces circular imports between the storage layer and the media library, preventing architectural rot from recurring.
12. As a quality engineer running automated CI pipelines, I want startup container wiring to be verifiable with zero runtime type-casts, guaranteeing 100% static type safety in dependency injection.
13. As a listener managing storage in the settings screen, I want feedback from server deletion to be synchronous and guaranteed before the dialog closes, ensuring UI state parity with persistent database state.
14. As a maintainer inspecting codebase architecture, I want domain boundaries to match the documented ADRs and GLOSSARY, so that cognitive load remains low and new contributors can navigate the project easily.
15. As a system component handling Android OS low-memory callbacks, I want storage and media library caches to be cleanly isolatable for selective trimming without circular lock contention.

## Implementation Decisions

### 1. Dedicated Narrow Domain Invalidation Seam
- Create a minimal, focused domain interface for storage invalidation:

```kotlin
interface StorageCascadeInvalidator {
    suspend fun clearStorageData(serverId: Long)
    suspend fun clearLocalFolderData(uriString: String)
}
```

- Have `MediaLibraryRepository` extend `StorageCascadeInvalidator`. Because `MediaLibraryRepository` already provides these exact method signatures for cascading cleanup, no signature modifications or adapter layers are necessary.

### 2. Immutable Constructor Injection in Storage Repository
- Refactor the storage repository implementation constructor to take `StorageCascadeInvalidator` as an immutable parameter.
- Completely delete the mutable property `var mediaLibraryRepository`.
- Refactor server deletion and local folder removal to invoke the injected invalidator directly.
- Strip all imports of `MediaLibraryRepository` from the storage repository implementation, establishing strict compile-time decoupling.

### 3. Local Storage Directory Resolver Dependency Pruning
- Prune the local storage directory resolver constructor so that it receives the local storage folder data access interface directly instead of the entire storage repository.
- Update directory resolution to obtain mounted local folders directly from the local folder data source, eliminating the reverse dependency edge from media library internals back to the storage repository.

### 4. Deterministic Acyclic Bootstrap Order in Application Container
- Re-order the initialization sequence in the application startup container into a strict Directed Acyclic Graph (DAG):
  1. Initialize Room database and core DAOs.
  2. Instantiate local directory resolver using the local folder DAO.
  3. Instantiate the media library repository using the local directory resolver and index DAOs.
  4. Instantiate the storage repository, passing the media library repository as the `StorageCascadeInvalidator` constructor argument.
  5. Completely remove the runtime downcast `(serverRepository as? ServerRepositoryImpl)?.mediaLibraryRepository = ...`.

### 5. Packaging Seam and Architecture Invariants
- Add automated architectural assertions to the packaging seam verification test suite:
  - Assert that the storage repository implementation never imports the media library repository interface or implementation.
  - Assert that the application class contains zero downcasts to the storage repository implementation.
  - Assert that the storage repository implementation contains zero mutable repository properties.

## Testing Decisions

### Test Surface and Black-Box Verification
- **High-Seam External Testing**: Tests must interact solely through public repository interfaces (`ServerRepository` and `MediaLibraryRepository`) and observe real side effects in persistent storage tables (index counts, checkpoint entries, and directory cache snapshots).
- **Zero-Implementation-Detail Assertions**: Tests must not inspect internal private fields or reflect on constructor parameter positions.
- **Synchronous Cascade Parity**: Tests will continue to assert that `deleteServer` and `removeMountedLocalFolder` execute cascading invalidation to completion before the suspend function returns, preventing asynchronous race conditions.

### Modules Under Test
- **Storage Repository Integration**: Verifies that deleting a server or unmounting a local folder properly invokes cascade invalidation and clears database and cache state.
- **Local Directory Resolver**: Verifies that resolving local directory trees (including root and nested document hierarchies) works flawlessly with the direct DAO dependency.
- **Application Container & Packaging Seam**: Verifies that runtime component assembly succeeds cleanly and no circular dependencies or banned downcasts exist.

### Prior Art
- `ServerCascadeDeletionTest`: Existing integration test validating end-to-end cascading deletion from server removal through search index and checkpoint cleanup.
- `ServerRepositoryTest`: Existing unit test suite verifying server and mounted folder CRUD operations.
- `LocalStorageDirectoryResolverTest`: Existing unit test suite validating SAF cursor projection and directory parsing.
- `PackagingSeamVerificationTest`: Existing architectural verification suite enforcing packaging boundaries, binary thinning, and anti-leak rules.

## Out of Scope

- Modifying the underlying database schema (`webdav_servers`, `local_storage_folders`, `media_track_index`, `directory_cache`).
- Changing the public method signatures of `ServerRepository` or `MediaLibraryRepository` (other than extending the narrow seam interface).
- Altering the user interface of the Server Management Screen (`ServerListScreen`) or the Directory Browser (`DirectoryBrowserScreen`).
- Modifying playback streaming, audio decoding, or metadata extraction pipelines.

## Further Notes

- Aligns directly with **ADR-0037** (`0037-storage-lifecycle-cascade-and-unidirectional-repository-seams.md`).
- Fully consistent with the domain vocabulary established in `GLOSSARY.md` (specifically **Storage Source**, **Local Storage**, **Media Library**, and **Storage Cascade Invalidation**).
- Complies strictly with the module depth, zero artificial fragmentation, and main-safety contracts codified in `CODING_STANDARDS.md`.
