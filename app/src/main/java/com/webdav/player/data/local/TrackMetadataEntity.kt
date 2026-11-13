package com.webdav.player.data.local

import androidx.room.Entity
import androidx.room.Index
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
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
        Index(value = ["remotePath"]),
    ],
)
data class TrackMetadataEntity(
    val serverId: Long,
    val remotePath: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val trackNumber: Int?,
    val durationMs: Long,
    val coverThumbnailPath: String?,
    val lyrics: String? = null,
    val companionLyricsPath: String? = null,
) {
    fun toDomain(): TrackMetadata =
        TrackMetadata(
            serverId = serverId,
            remotePath = remotePath,
            title = title,
            artist = artist,
            album = album,
            trackNumber = trackNumber,
            durationMs = durationMs,
            coverThumbnailPath = coverThumbnailPath,
            lyrics = lyrics,
            companionLyricsPath = companionLyricsPath,
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
            )
        }
    }
}
