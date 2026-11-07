package com.webdav.player.domain.model

/**
 * Reactive synchronization state representing background library traversal progress.
 */
sealed interface LibrarySyncState {
    data object Idle : LibrarySyncState

    data class Scanning(
        val progress: Float = 0f,
        val currentPath: String = "",
        val tracksFound: Int = 0,
    ) : LibrarySyncState

    data class Completed(
        val totalTracks: Int,
    ) : LibrarySyncState

    data class Failed(
        val message: String,
    ) : LibrarySyncState
}
