# 02: Migrate Directory Browser View Model to Media Library Seam and Eliminate Startup Wait

**What to build:** The directory browser loads and renders files immediately upon application launch or server switching by reading the browsing cursor directly from the Media Library repository. It completely removes the asynchronous delay waiting for the audio playback session to restore. Navigating into directories updates the media library cursor without sending any navigation events or triggering queue saves in the playback session.

**Blocked by:** 01: Establish Media Library Browsing Cursor Deep Seam and Store

**Status:** completed

- [x] The Directory Browser view model retrieves its initial browsing path directly and synchronously from the Media Library repository on launch.
- [x] Directory browser initialization does not suspend or wait on the playback session's restoration state.
- [x] Switching between remote WebDAV servers and local storage immediately restores the target source's remembered browsing cursor from the Media Library.
- [x] Navigating into folders or breadcrumbs updates the browsing cursor in the Media Library repository and does not invoke directory methods on the playback session.
- [x] The Directory Browser view model continues to support audio playback commands and active track indicator observation without regression.
- [x] Directory browser unit tests and presentation tests verify immediate initial directory rendering, server switching, and folder navigation without stubbing or mocking directory methods on the playback session facade.
