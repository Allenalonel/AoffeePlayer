package com.webdav.player.domain.repository

import com.webdav.player.domain.model.DirectoryContent
import com.webdav.player.domain.model.LibrarySearchResult
import com.webdav.player.domain.model.LibrarySyncResult
import com.webdav.player.domain.model.LibrarySyncState
import com.webdav.player.domain.model.ListDirectoryResult
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.WebDavServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow

open class FakeMediaLibraryRepository(
    var trackMetadataRepository: TrackMetadataRepository? = null,
) : MediaLibraryRepository {
    val clearedStorageServerIds = mutableListOf<Long>()
    val clearedLocalFolderUris = mutableListOf<String>()
    var clearAllCachesCount = 0
    var clearMemoryCacheCount = 0

    // Directory browsing mocks / state
    private val results = mutableMapOf<String, ListDirectoryResult>()
    private val cachedDirectories = mutableMapOf<String, RemoteDirectory>()
    val forceRefreshCount = mutableMapOf<String, Int>()
    val observeCalls = mutableListOf<Triple<WebDavServer, String, Boolean>>()
    var listCalls = 0
    val contentResults = mutableMapOf<String, DirectoryContent>()
    val contentEmissions = mutableMapOf<String, List<DirectoryContent>>()
    var defaultDirectoryFallback: Boolean = false

    fun setResult(
        path: String,
        result: ListDirectoryResult,
    ) {
        results[path] = result
    }

    fun setCached(
        path: String,
        directory: RemoteDirectory,
    ) {
        cachedDirectories[path] = directory
    }

    fun setDirectoryContent(
        path: String,
        content: DirectoryContent,
    ) {
        contentResults[path] = content
    }

    fun setDirectoryContentEmissions(
        path: String,
        emissions: List<DirectoryContent>,
    ) {
        contentEmissions[path] = emissions
    }

    suspend fun listDirectory(
        path: String,
        forceRefresh: Boolean = false,
    ): ListDirectoryResult {
        listCalls++
        if (forceRefresh) {
            forceRefreshCount[path] = (forceRefreshCount[path] ?: 0) + 1
        }
        return results[path] ?: ListDirectoryResult.Failure("Not found")
    }

    override fun observeDirectoryContent(
        server: WebDavServer,
        path: String,
        forceRefresh: Boolean,
    ): Flow<DirectoryContent> =
        flow {
            observeCalls.add(Triple(server, path, forceRefresh))

            val custom = contentEmissions[path]
            if (custom != null) {
                for (c in custom) emit(c)
                return@flow
            }

            val explicit = contentResults[path]
            if (explicit != null) {
                emit(explicit)
                return@flow
            }

            val cached = if (!forceRefresh) cachedDirectories[path] else null
            if (cached != null) {
                emit(DirectoryContent(directory = cached, isEnriching = true))
            }

            if (defaultDirectoryFallback && !results.containsKey(path)) {
                val fallbackDir =
                    RemoteDirectory(
                        path = path,
                        name = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" },
                        serverId = server.id,
                    )
                emit(DirectoryContent(directory = fallbackDir, isEnriching = false))
                return@flow
            }

            val result = listDirectory(path, forceRefresh)
            when (result) {
                is ListDirectoryResult.Success -> {
                    val dir = result.directory

                    val metaMap =
                        trackMetadataRepository
                            ?.getAllMetadataFlow(server.id)
                            ?.firstOrNull()
                            ?.associateBy { it.remotePath } ?: emptyMap()

                    if (cached == null || dir != cached) {
                        emit(DirectoryContent(directory = dir, isEnriching = true))
                    }
                    emit(
                        DirectoryContent(
                            directory = dir,
                            metadataMap = metaMap,
                            isEnriching = false,
                        ),
                    )
                }

                is ListDirectoryResult.Failure -> {
                    if (cached != null && !forceRefresh) {
                        val metaMap =
                            trackMetadataRepository
                                ?.getAllMetadataFlow(server.id)
                                ?.firstOrNull()
                                ?.associateBy { it.remotePath } ?: emptyMap()
                        emit(DirectoryContent(directory = cached, metadataMap = metaMap, isEnriching = false))
                    } else {
                        throw IllegalStateException(result.message)
                    }
                }
            }
        }

    override suspend fun getCachedDirectory(
        server: WebDavServer,
        path: String,
    ): RemoteDirectory? = cachedDirectories[path]

    // Multi-source & Single-source B-Tree Search
    var lastSearchQuery: String? = null
    var lastSearchServerId: Long? = null
    val searchResultFlow = MutableStateFlow(LibrarySearchResult())

    fun emitResult(result: LibrarySearchResult) {
        searchResultFlow.value = result
    }

    override fun search(
        query: String,
        serverId: Long?,
    ): Flow<LibrarySearchResult> {
        lastSearchQuery = query
        lastSearchServerId = serverId
        return searchResultFlow
    }

    // Incremental Background Sync & Progress
    val syncStateFlow = MutableStateFlow<LibrarySyncState>(LibrarySyncState.Idle)
    val syncServerCalls = mutableListOf<Pair<WebDavServer, Boolean>>()

    override fun observeSyncState(serverId: Long?): Flow<LibrarySyncState> = syncStateFlow.asStateFlow()

    override suspend fun syncServer(
        server: WebDavServer,
        force: Boolean,
    ): LibrarySyncResult {
        syncServerCalls.add(server to force)
        return LibrarySyncResult.Success(0, 0, 0)
    }

    override suspend fun rebuildIndex(server: WebDavServer): LibrarySyncResult =
        LibrarySyncResult.Success(0, 0, 0)

    // Search History Management
    val historyList = mutableListOf<String>()

    override fun getSearchHistory(): List<String> = historyList.toList()

    override fun recordSearchQuery(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isNotBlank()) {
            historyList.remove(trimmed)
            historyList.add(0, trimmed)
        }
        return historyList.toList()
    }

    override fun removeSearchHistory(query: String): List<String> {
        historyList.remove(query)
        return historyList.toList()
    }

    override fun clearSearchHistory() {
        historyList.clear()
    }

    // Unified Storage Deletion Cascade Invalidation
    override suspend fun clearStorageData(serverId: Long) {
        clearedStorageServerIds.add(serverId)
    }

    override suspend fun clearLocalFolderData(uriString: String) {
        clearedLocalFolderUris.add(uriString)
    }

    override suspend fun clearAllCaches() {
        clearAllCachesCount++
        results.clear()
        cachedDirectories.clear()
    }

    override fun clearMemoryCache() {
        clearMemoryCacheCount++
        cachedDirectories.clear()
    }

    fun clearCache() {
        results.clear()
        cachedDirectories.clear()
    }
}
