# 01: Core Media Index Schema & Local SAF Fast Indexing & Search

**What to build:**
A persistent media index schema and high-speed B-Tree search engine for local storage. When local storage folders are mounted via the Storage Access Framework (SAF), the system rapidly scans the file tree using direct ContentResolver cursor projection, indexing all audio files in seconds. Users can search for local music by title, artist, or file name and receive instant results (<10ms) without needing to browse into subdirectories first, replacing slow JSON deserialization queries.

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] Room database migration v6 -> v7 creates `media_track_index` with B-Tree indexes on `(serverId, fileName)`, `(fileName)`, and `(serverId, remotePath)`, along with `directory_sync_checkpoint` for incremental sync tracking.
- [x] Direct ContentResolver cursor projection traversal indexes mounted local SAF directory trees into `media_track_index` with `serverId = 0L`.
- [x] `LibrarySearchRepository` is refactored from `dataJson LIKE` queries to query `media_track_index` directly with case-insensitive matching across title, artist, and file name.
- [x] Heuristic filename parsing automatically extracts title and artist patterns (e.g., `Artist - Title.ext`) for tracks without extracted ID3 tags.
- [x] Searching local storage returns matching tracks and folders from deep unvisited directories in under 10ms.
- [x] Unit and Robolectric tests verify database migration, SAF tree indexing, and instant local search retrieval.
