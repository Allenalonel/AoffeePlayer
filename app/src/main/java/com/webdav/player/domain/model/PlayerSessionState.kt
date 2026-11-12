package com.webdav.player.domain.model

data class PlayerSessionState(
    val activeServer: WebDavServer? = null,
    val queue: PlaybackQueue = PlaybackQueue.EMPTY,
    val playbackState: PlaybackState = PlaybackState.Idle,
    val playbackMode: PlaybackMode = PlaybackMode.LIST_LOOP,
    val durationMs: Long = 0L,
    val errorMessage: String? = null,
    val lyrics: Lyrics? = null,
    val isLoadingLyrics: Boolean = false,
    val isWeakNetwork: Boolean = false,
    val canManualRetry: Boolean = false,
    val isReconnecting: Boolean = false,
    val wasKilledBySystem: Boolean = false,
    val isUnsupportedRange: Boolean = false,
) {
    val currentTrack: AudioTrack? get() = queue.currentTrack
    val isPlaying: Boolean get() = playbackState is PlaybackState.Playing
    val isBuffering: Boolean get() = playbackState is PlaybackState.Buffering
    val isPaused: Boolean get() = playbackState is PlaybackState.Paused
    val isIdle: Boolean get() = playbackState is PlaybackState.Idle
    val hasTrack: Boolean get() = currentTrack != null
    val isSuspendedRetry: Boolean get() = isWeakNetwork && canManualRetry

    fun withWeakNetwork(
        isWeakNetwork: Boolean,
        canManualRetry: Boolean,
        playbackState: PlaybackState = this.playbackState,
    ): PlayerSessionState =
        copy(
            playbackState = playbackState,
            isWeakNetwork = isWeakNetwork,
            canManualRetry = canManualRetry,
            isReconnecting = isWeakNetwork,
        )

    fun withClearedNetworkFlags(
        playbackState: PlaybackState = this.playbackState,
        errorMessage: String? = this.errorMessage,
        isUnsupportedRange: Boolean = this.isUnsupportedRange,
    ): PlayerSessionState =
        copy(
            playbackState = playbackState,
            errorMessage = errorMessage,
            isWeakNetwork = false,
            canManualRetry = false,
            isReconnecting = false,
            isUnsupportedRange = isUnsupportedRange,
        )

    fun withClearedQueue(
        playbackState: PlaybackState = this.playbackState,
        clearActiveServer: Boolean = false,
    ): PlayerSessionState =
        copy(
            activeServer = if (clearActiveServer) null else activeServer,
            queue = PlaybackQueue.EMPTY,
            playbackState = playbackState,
            durationMs = 0L,
            lyrics = null,
            isLoadingLyrics = false,
        )

    companion object {
        const val WEAK_NETWORK_MESSAGE = "网络受阻，正在重连..."
        const val RETRY_BANNER_MESSAGE = "网络连接受阻，点击重试"
        const val RETRY_BUTTON_LABEL = "点击重试"
    }
}
