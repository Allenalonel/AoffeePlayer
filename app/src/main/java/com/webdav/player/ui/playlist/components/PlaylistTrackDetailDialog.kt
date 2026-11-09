package com.webdav.player.ui.playlist.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.PlaybackProgress
import com.webdav.player.domain.model.PlaylistTrackEntry
import com.webdav.player.ui.common.TrackPathFormatter
import com.webdav.player.ui.motion.expressiveDialog
import com.webdav.player.ui.theme.AppIcons
import java.util.Locale

@Composable
internal fun PlaylistTrackDetailDialog(
    track: PlaylistTrackEntry,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
) {
    val isLocal =
        remember(track) {
            track.serverId == LOCAL_STORAGE_ID ||
                track.remotePath.startsWith("content://") ||
                track.remotePath.startsWith("file://")
        }

    val directoryPath =
        remember(track.remotePath, isLocal) {
            if (isLocal) {
                TrackPathFormatter.formatDirectoryPath(track.remotePath)
            } else {
                val slashIdx = track.remotePath.lastIndexOf('/')
                if (slashIdx > 0) track.remotePath.substring(0, slashIdx + 1) else "/"
            }
        }

    val displayFilePath =
        remember(track.remotePath, isLocal) {
            if (isLocal) {
                TrackPathFormatter.formatDisplayPath(track.remotePath)
            } else {
                track.remotePath
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.expressiveDialog(),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "音频详细信息",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaylistInfoRow(label = "歌曲标题", value = track.title)
                if (!track.artist.isNullOrBlank()) {
                    PlaylistInfoRow(label = "艺术家", value = track.artist)
                }
                if (!track.album.isNullOrBlank()) {
                    PlaylistInfoRow(label = "专辑名称", value = track.album)
                }
                PlaylistInfoRow(label = "音频格式", value = track.format.name)
                if (track.durationMs > 0L) {
                    PlaylistInfoRow(label = "曲目时长", value = PlaybackProgress.formatMs(track.durationMs))
                }
                if (track.size > 0L) {
                    PlaylistInfoRow(label = "文件大小", value = formatTrackFileSize(track.size))
                }
                PlaylistInfoRow(
                    label = if (isLocal) "本地存储目录" else "来源目录",
                    value = directoryPath,
                )
                PlaylistInfoRow(
                    label = if (isLocal) "本地文件路径" else "远程完整路径",
                    value = displayFilePath,
                )
                if (isLocal) {
                    PlaylistInfoRow(label = "存储来源", value = "本地设备存储 (SAF)")
                } else {
                    PlaylistInfoRow(label = "所属服务器 ID", value = "${track.serverId}")
                }
            }
        },
        confirmButton = {
            Button(onClick = onPlay) {
                Text("立即播放")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
internal fun PlaylistInfoRow(
    label: String,
    value: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

internal fun formatTrackFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@Preview(showBackground = true)
@Composable
private fun PlaylistTrackDetailDialogPreview() {
    MaterialTheme {
        PlaylistTrackDetailDialog(
            track =
                PlaylistTrackEntry(
                    id = 1L,
                    playlistId = 1L,
                    serverId = 1L,
                    remotePath = "/Music/Hi-Res/Symphony No. 5.flac",
                    sortOrder = 0,
                    title = "Symphony No. 5",
                    artist = "Ludwig van Beethoven",
                    album = "Beethoven: Complete Symphonies",
                    durationMs = 432000L,
                    size = 45200000L,
                    format = AudioFormat.FLAC,
                ),
            onDismiss = {},
            onPlay = {},
        )
    }
}
