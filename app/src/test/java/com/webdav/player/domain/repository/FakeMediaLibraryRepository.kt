package com.webdav.player.domain.repository

import com.webdav.player.domain.model.DirectoryContent
import com.webdav.player.domain.model.LibrarySearchResult
import com.webdav.player.domain.model.LibrarySyncResult
import com.webdav.player.domain.model.LibrarySyncState
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.WebDavServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

open class FakeMediaLibraryRepository : MediaLibraryRepository {
    val clearedStorageServerIds = mutableListOf<Long>()
    val clearedLocalFolderUris = mutableListOf<String>()
    var clearAllCachesCount = 0
    var clearMemoryCacheCount = 0

    override suspend fun clearStorageData(serverId: Long) {
        clearedStorageServerIds.add(serverId)
    }

    override suspend fun clearLocalFolderData(uriString: String) {
        clearedLocalFolderUris.add(uriString)
    }

    override suspend fun clearAllCaches() {
        clearAllCachesCount++
    }

    override fun clearMemoryCache() {
        clearMemoryCacheCount++
    }

    override fun observeDirectoryContent(
        server: WebDavServer,
        path: String,
        forceRefresh: Boolean,
    ): Flow<DirectoryContent> = emptyFlow()

    override suspend fun getCachedDirectory(
        server: WebDavServer,
        path: String,
    ): RemoteDirectory? = null

    override fun search(
        query: String,
        serverId: Long?,
    ): Flow<LibrarySearchResult> = emptyFlow()

    override fun observeSyncState(serverId: Long?): Flow<LibrarySyncState> = emptyFlow()

    override suspend fun syncServer(
        server: WebDavServer,
        force: Boolean,
    ): LibrarySyncResult = LibrarySyncResult.Success(0, 0, 0)

    override suspend fun rebuildIndex(server: WebDavServer): LibrarySyncResult =
        LibrarySyncResult.Success(0, 0, 0)

    override fun getSearchHistory(): List<String> = emptyList()

    override fun recordSearchQuery(query: String): List<String> = emptyList()

    override fun removeSearchHistory(query: String): List<String> = emptyList()

    override fun clearSearchHistory() {}
}
