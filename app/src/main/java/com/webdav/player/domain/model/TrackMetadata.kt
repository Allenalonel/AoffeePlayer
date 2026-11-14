package com.webdav.player.domain.model

data class TrackMetadata(
    val serverId: Long,
    val remotePath: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val durationMs: Long = 0L,
    val coverThumbnailPath: String? = null,
    val lyrics: String? = null,
    val companionLyricsPath: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val bitrateKbps: Int? = null,
    val channels: Int? = null
) {
    val physicalSpecs: AudioPhysicalSpecs
        get() = AudioPhysicalSpecs(
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps,
            channels = channels
        )

    val identity: TrackIdentity
        get() = TrackIdentity(serverId, remotePath)

    /**
     * Returns a displayable title, falling back to clean file name if title is null or blank.
     */
    fun displayTitle(fallbackFileName: String): String {
        val raw = if (!title.isNullOrBlank()) {
            title
        } else {
            fallbackFileName
        }
        return TrackIdentity.cleanTitle(raw) ?: fallbackFileName
    }

    /**
     * Returns a displayable artist, or null if empty/blank.
     */
    fun displayArtist(): String? {
        return artist?.takeIf { it.isNotBlank() }
    }
}
