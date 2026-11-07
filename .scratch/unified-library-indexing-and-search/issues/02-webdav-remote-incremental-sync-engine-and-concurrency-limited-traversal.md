# 02: WebDAV Remote Incremental Sync Engine & Concurrency-Limited Traversal

**What to build:**
An incremental, rate-limited background synchronization provider for remote WebDAV servers. The player recursively crawls remote directories using a concurrency limiter (maximum 3 concurrent requests) to avoid overloading NAS hardware or hitting cloud rate limits. Routine sync passes skip unchanged directory subtrees by comparing modification timestamps and ETags against stored checkpoints, completing rescan passes across thousands of tracks in 1 to 2 seconds. All discovered remote music files are indexed and immediately searchable.

**Blocked by:** 01: Core Media Index Schema & Local SAF Fast Indexing & Search

**Status:** ready-for-review

- [x] WebDAV directory synchronization crawls remote directories using BFS traversal guarded by a strict concurrency limiter (max 3 concurrent requests).
- [x] Known system and noise directories (e.g., `.@eaDir`, `#recycle`, `.Trash-*`, `LOST.DIR`, `.git`) are automatically excluded from indexing.
- [x] Incremental rescan compares server directory modification timestamps and ETags against `directory_sync_checkpoint`, skipping traversal of unchanged subtrees.
- [x] Discovered remote audio tracks are batched and upserted into `media_track_index` under the server's unique ID.
- [x] Transient network drops during sync do not crash the engine or erase previously indexed tracks.
- [x] End-to-end repository tests verify recursive traversal, concurrency safety, incremental skipping of unchanged folders, and search retrieval for remote tracks.
