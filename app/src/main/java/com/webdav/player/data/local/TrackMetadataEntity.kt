package com.webdav.player.data.local

import androidx.room.Entity
import androidx.room.Index
import com.webdav.player.domain.model.TrackIdentity
import com.webdav.player.domain.model.TrackMetadata

@Entity(
    tableName = "track_metadata",
    primaryKeys = ["serverId", "remotePath"],
    indices = [
        Index(value = ["serverId"]),
        Index(value = ["serverId", "remotePath"]),
        Index(value = ["title"]),
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["remotePath"])
    ]
)
data class TrackMetadataEntity(
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
    fun toDomain(): TrackMetadata =
        TrackMetadata(
            serverId = serverId,
            remotePath = remotePath,
            title = TrackIdentity.sanitizePersistedTitle(title),
            artist = artist,
            album = album,
            trackNumber = trackNumber,
            durationMs = durationMs,
            coverThumbnailPath = coverThumbnailPath,
            lyrics = lyrics,
            companionLyricsPath = companionLyricsPath,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps,
            channels = channels
        )

    companion object {
        fun fromDomain(domain: TrackMetadata): TrackMetadataEntity {
            val identity = domain.identity
            return TrackMetadataEntity(
                serverId = identity.serverId,
                remotePath = identity.remotePath,
                title = domain.title,
                artist = domain.artist,
                album = domain.album,
                trackNumber = domain.trackNumber,
                durationMs = domain.durationMs,
                coverThumbnailPath = domain.coverThumbnailPath,
                lyrics = domain.lyrics,
                companionLyricsPath = domain.companionLyricsPath,
                sampleRate = domain.sampleRate,
                bitDepth = domain.bitDepth,
                bitrateKbps = domain.bitrateKbps,
                channels = domain.channels
            )
        }
    }
}
