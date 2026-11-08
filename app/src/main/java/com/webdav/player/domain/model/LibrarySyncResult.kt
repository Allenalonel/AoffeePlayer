package com.webdav.player.domain.model

/**
 * Result returned by synchronization operations.
 */
sealed interface LibrarySyncResult {
    data class Success(
        val totalTracksIndexed: Int,
        val directoriesScanned: Int = 0,
        val directoriesSkipped: Int = 0,
    ) : LibrarySyncResult

    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : LibrarySyncResult
}
