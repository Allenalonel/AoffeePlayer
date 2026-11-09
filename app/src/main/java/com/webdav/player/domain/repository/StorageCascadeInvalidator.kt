package com.webdav.player.domain.repository

/**
 * Narrow domain seam for cascading invalidation when storage nodes (WebDAV servers or local folders)
 * are removed, unmounted, or decommissioned.
 */
interface StorageCascadeInvalidator {
    suspend fun clearStorageData(serverId: Long)

    suspend fun clearLocalFolderData(uriString: String)
}
