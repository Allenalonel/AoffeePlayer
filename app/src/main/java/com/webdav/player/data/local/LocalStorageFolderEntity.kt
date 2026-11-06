package com.webdav.player.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.webdav.player.domain.model.LocalStorageFolder

@Entity(tableName = "local_storage_folders")
data class LocalStorageFolderEntity(
    @PrimaryKey
    val uriString: String,
    val displayName: String,
    val dateAdded: Long,
) {
    fun toDomain(): LocalStorageFolder =
        LocalStorageFolder(
            uriString = uriString,
            displayName = displayName,
            dateAdded = dateAdded,
        )

    companion object {
        fun fromDomain(domain: LocalStorageFolder): LocalStorageFolderEntity =
            LocalStorageFolderEntity(
                uriString = domain.uriString,
                displayName = domain.displayName,
                dateAdded = domain.dateAdded,
            )
    }
}
