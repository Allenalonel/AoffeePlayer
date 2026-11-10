package com.webdav.player.domain.repository

import com.webdav.player.domain.model.DirectoryContent
import com.webdav.player.domain.model.LibrarySearchResult
import com.webdav.player.domain.model.LibrarySyncResult
import com.webdav.player.domain.model.LibrarySyncState
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.WebDavServer
import kotlinx.coroutines.flow.Flow

/**
 * Unified deep repository interface serving the entire media library:
 * directory browsing with SWR caching and write-through indexing,
 * pure B-Tree search, incremental synchronization, search history,
 * and cascading storage invalidation.
 */
interface MediaLibraryRepository : StorageCascadeInvalidator {
    // SWR Directory Browsing
    fun observeDirectoryContent(
        server: WebDavServer,
        path: String,
        forceRefresh: Boolean = false,
    ): Flow<DirectoryContent>

    suspend fun getCachedDirectory(
        server: WebDavServer,
        path: String,
    ): RemoteDirectory?

    // Multi-source & Single-source B-Tree Search
    fun search(
        query: String,
        serverId: Long? = null,
    ): Flow<LibrarySearchResult>

    // Incremental Background Sync & Progress
    fun observeSyncState(serverId: Long? = null): Flow<LibrarySyncState>

    suspend fun syncServer(
        server: WebDavServer,
        force: Boolean = false,
    ): LibrarySyncResult

    suspend fun rebuildIndex(server: WebDavServer): LibrarySyncResult

    // Search History Management
    fun getSearchHistory(): List<String>

    fun recordSearchQuery(query: String): List<String>

    fun removeSearchHistory(query: String): List<String>

    fun clearSearchHistory()

    // Browsing Cursor Retention
    fun getLastBrowsingPath(serverId: Long): String = "/"

    fun setLastBrowsingPath(serverId: Long, path: String) {}

    // Unified Storage Deletion Cascade Invalidation
    override suspend fun clearStorageData(serverId: Long)

    override suspend fun clearLocalFolderData(uriString: String)

    suspend fun clearAllCaches()

    fun clearMemoryCache()
}
