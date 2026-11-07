package com.webdav.player.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "directory_sync_checkpoint",
    primaryKeys = ["serverId", "path"],
    indices = [
        Index(value = ["serverId"]),
        Index(value = ["serverId", "path"]),
    ],
)
data class DirectorySyncCheckpointEntity(
    val serverId: Long,
    val path: String,
    val lastModified: Long,
    val etag: String?,
    val lastScannedMs: Long,
)
