package com.webdav.player.data.repository

import android.content.Context
import com.webdav.player.data.local.CoverArtStorage
import com.webdav.player.data.local.LocalStorageFolderDao
import com.webdav.player.data.local.LocalStorageFolderEntity
import com.webdav.player.data.local.TrackMetadataDao
import com.webdav.player.data.local.WebDavServerDao
import com.webdav.player.data.local.WebDavServerEntity
import com.webdav.player.data.remote.OkHttpWebDavClient
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.LocalStorageFolder
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.repository.MediaLibraryRepository
import com.webdav.player.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

class ServerRepositoryImpl(
    private val serverDao: WebDavServerDao,
    private val coverArtStorage: CoverArtStorage? = null,
    private val webDavClient: OkHttpWebDavClient? = null,
    private val trackMetadataDao: TrackMetadataDao? = null,
    private val localStorageFolderDao: LocalStorageFolderDao? = null,
    private val context: Context? = null,
    var mediaLibraryRepository: MediaLibraryRepository? = null,
) : ServerRepository {
    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val isLocalStorageActive =
        MutableStateFlow(prefs?.getLong(KEY_ACTIVE_STORAGE_ID, -1L) == LOCAL_STORAGE_ID)

    override fun getAllServers(): Flow<List<WebDavServer>> =
        combine(serverDao.getAllServersFlow(), getActiveServer()) { remoteServers, activeServer ->
            val localNode =
                WebDavServer.createLocalStorage(
                    isDefault = (activeServer?.id == LOCAL_STORAGE_ID),
                )
            listOf(localNode) + remoteServers.map { it.toDomain() }
        }

    override fun getActiveServer(): Flow<WebDavServer?> =
        combine(serverDao.getActiveServerFlow(), isLocalStorageActive) { remoteActive, localActive ->
            if (localActive) {
                WebDavServer.createLocalStorage(isDefault = true)
            } else {
                remoteActive?.toDomain()
            }
        }

    override suspend fun getServerById(id: Long): WebDavServer? =
        if (id == LOCAL_STORAGE_ID) {
            WebDavServer.createLocalStorage(isDefault = isLocalStorageActive.value)
        } else {
            serverDao.getServerById(id)?.toDomain()
        }

    override suspend fun saveServer(server: WebDavServer): Long {
        val totalCount = serverDao.getServerCount()
        // If this is the very first server or explicitly marked as default
        val shouldBeDefault = server.isDefault || totalCount == 0

        val entity = WebDavServerEntity.fromDomain(server.copy(isDefault = shouldBeDefault))

        return if (server.id == 0L) {
            val newId = serverDao.insertServer(entity)
            if (shouldBeDefault) {
                prefs?.edit()?.remove(KEY_ACTIVE_STORAGE_ID)?.apply()
                serverDao.setActiveServer(newId)
                isLocalStorageActive.value = false
            }
            newId
        } else {
            if (server.isDefault) {
                prefs?.edit()?.remove(KEY_ACTIVE_STORAGE_ID)?.apply()
                serverDao.setActiveServer(server.id)
                isLocalStorageActive.value = false
            }
            serverDao.updateServer(entity)
            webDavClient?.invalidateServer(server.id)
            server.id
        }
    }

    override suspend fun deleteServer(id: Long) {
        if (id == LOCAL_STORAGE_ID) {
            return
        }
        val serverToDelete = serverDao.getServerById(id)
        coverArtStorage?.deleteServerCovers(id)
        webDavClient?.invalidateServer(id)
        mediaLibraryRepository?.clearStorageData(id)
        trackMetadataDao?.deleteMetadataForServer(id)
        serverDao.deleteServerById(id)

        if (serverToDelete?.isDefault == true) {
            val remaining = serverDao.getAllServersFlow().firstOrNull().orEmpty()
            val nextDefault = remaining.firstOrNull()
            if (nextDefault != null) {
                serverDao.setActiveServer(nextDefault.id)
            }
        }
    }

    override suspend fun setActiveServer(id: Long) {
        if (id == LOCAL_STORAGE_ID) {
            prefs?.edit()?.putLong(KEY_ACTIVE_STORAGE_ID, LOCAL_STORAGE_ID)?.apply()
            serverDao.setActiveServer(LOCAL_STORAGE_ID)
            isLocalStorageActive.value = true
        } else {
            prefs?.edit()?.remove(KEY_ACTIVE_STORAGE_ID)?.apply()
            serverDao.setActiveServer(id)
            isLocalStorageActive.value = false
        }
    }

    override fun getMountedLocalFolders(): Flow<List<LocalStorageFolder>> =
        localStorageFolderDao?.getAllFoldersFlow()?.map { list ->
            list.map { it.toDomain() }
        } ?: kotlinx.coroutines.flow.flowOf(emptyList())

    override suspend fun addMountedLocalFolder(
        uriString: String,
        displayName: String,
    ) {
        localStorageFolderDao?.insertFolder(
            LocalStorageFolderEntity(
                uriString = uriString,
                displayName = displayName,
                dateAdded = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun removeMountedLocalFolder(uriString: String) {
        mediaLibraryRepository?.clearLocalFolderData(uriString)
        localStorageFolderDao?.deleteFolderByUri(uriString)
    }

    companion object {
        private const val PREFS_NAME = "local_storage_prefs"
        private const val KEY_ACTIVE_STORAGE_ID = "active_storage_id"
    }
}
