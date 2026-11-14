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
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val bitrateKbps: Int? = null,
    val channels: Int? = null
) {
    val identity: TrackIdentity
        get() = TrackIdentity(serverId, remotePath)

    val isLocal: Boolean
        get() = identity.isLocal

    val physicalSpecs: AudioPhysicalSpecs
        get() = AudioPhysicalSpecs(sampleRate, bitDepth, bitrateKbps, channels)

    fun toAudioTrack(): AudioTrack {
        val ident = identity
        return AudioTrack(
            id = ident.trackId,
            serverId = ident.serverId,
            remotePath = ident.remotePath,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            size = size,
            format = format,
            coverThumbnailPath = coverThumbnailPath,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps,
            channels = channels
        )
    }

    companion object {
        fun fromAudioTrack(
            playlistId: Long,
            track: AudioTrack,
            sortOrder: Int
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
                sampleRate = track.sampleRate,
                bitDepth = track.bitDepth,
                bitrateKbps = track.bitrateKbps,
                channels = track.channels
            )
    }
}

enum class InactiveReason {
    SERVER_UNREACHABLE,
    FILE_NOT_FOUND,
    SERVER_CREDENTIAL_INVALID
}
