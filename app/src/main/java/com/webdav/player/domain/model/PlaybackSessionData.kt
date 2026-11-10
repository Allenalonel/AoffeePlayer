package com.webdav.player.domain.model

data class PlaybackSessionData(
    val activeServerId: Long?,
    val queueTracks: List<AudioTrack>,
    val currentTrackIndex: Int,
    val positionMs: Long,
    val playbackMode: PlaybackMode,
    val cleanExit: Boolean = true,
    val queueOrigin: QueueOrigin = QueueOrigin.DIRECTORY,
    val originalTracks: List<AudioTrack> = emptyList(),
) {
    companion object {
        val EMPTY =
            PlaybackSessionData(
                activeServerId = null,
                queueTracks = emptyList(),
                currentTrackIndex = -1,
                positionMs = 0L,
                playbackMode = PlaybackMode.LIST_LOOP,
                cleanExit = true,
                queueOrigin = QueueOrigin.DIRECTORY,
                originalTracks = emptyList(),
            )
    }
}
