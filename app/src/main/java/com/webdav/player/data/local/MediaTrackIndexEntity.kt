package com.webdav.player.data.local

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioTrack

@Entity(
    tableName = "media_track_index",
    primaryKeys = ["serverId", "remotePath"],
    indices = [
        Index(value = ["serverId", "fileName"]),
        Index(value = ["fileName"]),
        Index(value = ["serverId", "remotePath"]),
        Index(value = ["parsedTitle"]),
        Index(value = ["parsedArtist"]),
    ],
)
data class MediaTrackIndexEntity(
    val serverId: Long,
    val remotePath: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Long,
    val format: String,
    val parentPath: String,
    val parsedTitle: String,
    val parsedArtist: String?,
) {
    val isCue: Boolean
        get() = false

    @Ignore
    constructor(
        serverId: Long,
        remotePath: String,
        fileName: String,
        fileSize: Long,
        lastModified: Long,
        format: String,
        @Suppress("UNUSED_PARAMETER") isCue: Boolean,
        parentPath: String,
        parsedTitle: String,
        parsedArtist: String?,
    ) : this(
        serverId = serverId,
        remotePath = remotePath,
        fileName = fileName,
        fileSize = fileSize,
        lastModified = lastModified,
        format = format,
        parentPath = parentPath,
        parsedTitle = parsedTitle,
        parsedArtist = parsedArtist,
    )

    fun toAudioTrack(
        metadata: TrackMetadataEntity? = null,
        albumFallback: String? = null,
        cuePath: String? = null,
    ): AudioTrack {
        val audioFormat =
            AudioFormat.fromExtension(format)
                ?: AudioFormat.fromFileName(fileName)
                ?: AudioFormat.MP3
        val physicalRemotePath =
            if (isCue && remotePath.contains("#cue_")) {
                remotePath.substringBefore("#cue_")
            } else {
                remotePath
            }
        val resolvedCuePath =
            cuePath ?: if (isCue && remotePath.contains("#cue_")) {
                physicalRemotePath.substringBeforeLast('.') + ".cue"
            } else {
                null
            }
        return AudioTrack(
            id = "$serverId:$remotePath",
            serverId = serverId,
            remotePath = physicalRemotePath,
            title = metadata?.title?.takeIf { it.isNotBlank() } ?: parsedTitle.ifBlank { fileName },
            artist = metadata?.artist?.takeIf { it.isNotBlank() } ?: parsedArtist,
            album = metadata?.album?.takeIf { it.isNotBlank() } ?: albumFallback,
            durationMs = metadata?.durationMs ?: 0L,
            size = fileSize,
            format = audioFormat,
            coverThumbnailPath = metadata?.coverThumbnailPath,
            cuePath = resolvedCuePath,
        )
    }
}
