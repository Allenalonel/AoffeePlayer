package com.webdav.player.domain.model

data class LocalStorageFolder(
    val uriString: String,
    val displayName: String,
    val dateAdded: Long = System.currentTimeMillis(),
)
