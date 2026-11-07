# 03: CUE Sheet Virtual Track Indexing & Cross-Source Global Search Harmonization

**What to build:**
Integration of CUE sheet virtual track discovery into the unified search index and harmonization of cross-source global search. Single-file album rips accompanied by `.cue` files have their individual virtual tracks parsed, indexed, and made directly discoverable in search. Users can seamlessly toggle between searching within their currently active source (local storage or a specific server) and performing an all-source global search across their entire music collection. In-memory results harmonize lightweight index rows with rich ID3 tag metadata and album artwork when available.

**Blocked by:** 01: Core Media Index Schema & Local SAF Fast Indexing & Search

**Status:** claimed

- [ ] Directory synchronization identifies `.cue` files alongside parent audio files, resolving track listings into virtual track entries within the index.
- [ ] Searching for a song title or performer contained inside a CUE sheet returns the virtual track with parent duration and cue offsets.
- [ ] Global search across all configured sources (`serverId = null`) combines results from local storage and all remote WebDAV servers in a single unified flow.
- [ ] Single-source search restricts results strictly to the designated `serverId` (including local storage `serverId = 0L`).
- [ ] Search results harmonize indexed track entries with cached ID3 metadata (exact duration, album name, embedded artwork thumbnail) from the metadata repository when present.
- [ ] Tests verify CUE virtual track search indexing, cross-source vs. single-source filtering, and metadata harmonization.
