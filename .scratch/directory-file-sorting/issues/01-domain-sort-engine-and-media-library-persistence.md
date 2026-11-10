# 01: Domain Sort Engine and Media Library Sort Preference Persistence

**What to build:** A unified domain sorting model and natural order sorting engine for media library directories, coupled with persistent global sorting preference retention in the Media Library repository. The engine sorts directories and files across three dimensions (name with natural alphanumeric ordering, modification time, and file size) in ascending or descending direction, while strictly maintaining the invariant that subdirectories are always pinned above files. The user's chosen sort order is persisted globally and restored seamlessly across application restarts.

**Blocked by:** None (can start immediately)

**Status:** completed

- [x] Domain models `DirectorySortOrder`, `SortField` (`NAME`, `LAST_MODIFIED`, `SIZE`), and `SortDirection` (`ASCENDING`, `DESCENDING`) are established in the domain model package.
- [x] `NaturalOrderComparator` is implemented to provide case-insensitive, chunk-based natural alphanumeric comparison, ensuring numbers within filenames sort naturally (`Track 1` < `Track 2` < `Track 10`).
- [x] `RemoteDirectorySorter` correctly sorts any `RemoteDirectory` instance: subdirectories are always grouped and pinned at the top, files are grouped below, and each group is sorted internally according to the specified `DirectorySortOrder`.
- [x] Modification time sorting properly parses RFC 1123 / ISO date strings and numerical timestamps, gracefully handling null or unparseable timestamps.
- [x] File size sorting accurately orders items by byte size in both ascending and descending directions.
- [x] `MediaLibraryRepository` exposes public methods to read and write the global `DirectorySortOrder`, defaulting to `NAME` ascending for clean installs.
- [x] Updated sort orders persist across repository instances using internal SharedPreferences with zero-latency in-memory cache, without touching audio playback queue storage.
- [x] Unit tests (`NaturalOrderComparatorTest`, `RemoteDirectorySorterTest`, `MediaLibraryRepositoryTest`) thoroughly verify natural ordering, all sort dimensions, folder pinning invariants, and preference persistence.
