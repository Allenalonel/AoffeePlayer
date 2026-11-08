package com.webdav.player.domain.model

data class DirectoryContent(
    val directory: RemoteDirectory,
    val metadataMap: Map<String, TrackMetadata> = emptyMap(),
    val isEnriching: Boolean = false,
)
