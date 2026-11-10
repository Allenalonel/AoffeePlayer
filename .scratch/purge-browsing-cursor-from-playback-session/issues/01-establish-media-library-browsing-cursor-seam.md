# 01: Establish Media Library Browsing Cursor Deep Seam and Store

**What to build:** Independent and persistent browsing cursor retention directly through the Media Library repository. The media library independently remembers the last visited directory for every storage source (both remote WebDAV servers and local device storage), returns the root directory by default for fresh sources, and automatically clears or resets the cursor when a storage source is deleted or unmounted.

**Blocked by:** None (can start immediately)

**Status:** in-progress

- [ ] The Media Library repository exposes public methods to read and update the last browsing path for any storage source ID.
- [ ] Unvisited storage sources return the root directory ("/") by default.
- [ ] Updated browsing paths persist across repository instances and do not touch audio playback queue storage.
- [ ] Different storage sources maintain completely isolated browsing cursors without cross-contamination.
- [ ] Deleting a storage source via cascade invalidation automatically and permanently purges its remembered browsing cursor.
- [ ] Unmounting a local storage folder safely resets the local browsing cursor to root if it pointed within the unmounted tree.
- [ ] Unit tests for the Media Library repository thoroughly verify browsing cursor persistence, isolation, defaults, and cascade invalidation without mocking audio playback components.
