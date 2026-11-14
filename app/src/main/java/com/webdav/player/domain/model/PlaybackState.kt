package com.webdav.player.domain.model

enum class PlaybackErrorKind {
    FILE_NOT_FOUND,
    AUTH_INVALID,
    SERVER_UNREACHABLE,
    UNSUPPORTED_RANGE,
    UNKNOWN
}

sealed interface PlaybackState {
    data object Idle : PlaybackState

    data object Buffering : PlaybackState

    data object Playing : PlaybackState

    data object Paused : PlaybackState

    data object Ended : PlaybackState

    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val kind: PlaybackErrorKind = PlaybackErrorKind.UNKNOWN
    ) : PlaybackState
}
