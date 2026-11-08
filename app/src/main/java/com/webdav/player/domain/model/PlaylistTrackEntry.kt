package com.webdav.player.domain.model

data class PlaylistTrackEntry(
    val id: Long = 0L,
    val playlistId: Long,
    val serverId: Long,
    val remotePath: String,
    val sortOrder: Int,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    val size: Long = 0L,
    val format: AudioFormat = AudioFormat.MP3,
    val coverThumbnailPath: String? = null,
    val isInactive: Boolean = false,
    val inactiveReason: InactiveReason? = null,
) {
    val cuePath: String? = null
    val virtualTrackId: String? = null
    val isVirtualTrack: Boolean
        get() = false

    fun toAudioTrack(): AudioTrack =
        AudioTrack(
            id = "$serverId:$remotePath",
            serverId = serverId,
            remotePath = remotePath,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            size = size,
            format = format,
            coverThumbnailPath = coverThumbnailPath,
        )

    @Suppress("UNUSED_PARAMETER")
    @Deprecated("CUE support is purged; cuePath and virtualTrackId are ignored")
    constructor(
        id: Long = 0L,
        playlistId: Long,
        serverId: Long,
        remotePath: String,
        sortOrder: Int,
        title: String,
        artist: String? = null,
        album: String? = null,
        durationMs: Long = 0L,
        size: Long = 0L,
        format: AudioFormat = AudioFormat.MP3,
        coverThumbnailPath: String? = null,
        cuePath: String? = null,
        virtualTrackId: String? = null,
        isInactive: Boolean = false,
        inactiveReason: InactiveReason? = null,
    ) : this(
        id = id,
        playlistId = playlistId,
        serverId = serverId,
        remotePath = remotePath,
        sortOrder = sortOrder,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        size = size,
        format = format,
        coverThumbnailPath = coverThumbnailPath,
        isInactive = isInactive,
        inactiveReason = inactiveReason,
    )

    companion object {
        fun fromAudioTrack(
            playlistId: Long,
            track: AudioTrack,
            sortOrder: Int,
        ): PlaylistTrackEntry =
            PlaylistTrackEntry(
                id = 0L,
                playlistId = playlistId,
                serverId = track.serverId,
                remotePath = track.remotePath,
                sortOrder = sortOrder,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                size = track.size,
                format = track.format,
                coverThumbnailPath = track.coverThumbnailPath,
            )

        @Suppress("UNUSED_PARAMETER")
        @Deprecated("CUE support is purged; cuePath is ignored")
        fun fromAudioTrack(
            playlistId: Long,
            track: AudioTrack,
            sortOrder: Int,
            cuePath: String?,
        ): PlaylistTrackEntry = fromAudioTrack(playlistId, track, sortOrder)
    }
}

enum class InactiveReason {
    SERVER_UNREACHABLE,
    FILE_NOT_FOUND,
    SERVER_CREDENTIAL_INVALID,
}
