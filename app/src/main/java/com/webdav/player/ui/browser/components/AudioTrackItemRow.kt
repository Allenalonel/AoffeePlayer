package com.webdav.player.ui.browser.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.webdav.player.domain.model.AudioQualityBadge
import com.webdav.player.domain.model.PlaybackProgress
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.TrackMetadata
import com.webdav.player.ui.common.AudioQualityBadgePill
import com.webdav.player.ui.common.AudioTrackRow
import com.webdav.player.ui.common.AudioTrackRowDefaults
import com.webdav.player.ui.common.TrackDetailBottomSheet
import com.webdav.player.ui.common.formatTrackFileSize
import com.webdav.player.ui.theme.AppIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioTrackItemRow(
    file: RemoteFile,
    metadata: TrackMetadata? = null,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onPlayNext: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showDetailSheet by remember { mutableStateOf(false) }
    val badgeInfo = remember(file, metadata) { file.resolveBadge(metadata) }

    if (showDetailSheet && file.isAudio) {
        TrackDetailBottomSheet(
            file = file,
            metadata = metadata,
            onDismiss = { showDetailSheet = false },
            onPlay = {
                showDetailSheet = false
                onClick()
            }
        )
    }

    if (file.isAudio) {
        val displayTitle = remember(file.name, metadata?.title) { metadata?.displayTitle(file.name) ?: file.name }
        val subtitle =
            remember(file, metadata) {
                AudioTrackRowDefaults.formatTrackSubtitle(
                    artist = metadata?.displayArtist(),
                    album = metadata?.album
                )
            }
        val durationStr =
            remember(metadata?.durationMs, file.size) {
                val d = metadata?.durationMs ?: 0L
                if (d > 0L) {
                    PlaybackProgress.formatMs(d)
                } else if (file.size > 0L) {
                    val bitrateKbps = badgeInfo?.estimatedBitrateKbps ?: 320
                    val estMs = (file.size * 8000L) / (bitrateKbps * 1000L)
                    if (estMs > 0L) PlaybackProgress.formatMs(estMs) else null
                } else {
                    null
                }
            }
        val surfaceColor =
            if (isActive) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        val effectiveLongClick: (() -> Unit)? =
            when {
                isSelectionMode -> onLongClick
                onLongClick != null -> onLongClick
                file.isAudio -> {
                    { showDetailSheet = true }
                }
                else -> null
            }

        Surface(
            modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp),
            shape = RoundedCornerShape(16.dp),
            color = surfaceColor
        ) {
            AudioTrackRow(
                title = displayTitle,
                subtitle = subtitle,
                durationText = durationStr,
                coverThumbnailPath = metadata?.coverThumbnailPath,
                audioQualityBadge = badgeInfo,
                isActive = isActive,
                isPlaying = isPlaying,
                containerColor = Color.Transparent,
                onClick = onClick,
                onLongClick = effectiveLongClick,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                actionsMenu = { dismiss ->
                    DropdownMenuItem(
                        text = { Text("下一首播放") },
                        leadingIcon = {
                            Icon(AppIcons.QueueMusic, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        onClick = {
                            dismiss()
                            onPlayNext()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("添加到歌单") },
                        leadingIcon = {
                            Icon(AppIcons.PlaylistAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        onClick = {
                            dismiss()
                            onAddToPlaylist()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("查看详细信息") },
                        leadingIcon = {
                            Icon(AppIcons.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        onClick = {
                            dismiss()
                            showDetailSheet = true
                        }
                    )
                }
            )
        }
    } else {
        NonAudioFileRow(file = file, badgeInfo = badgeInfo, modifier = modifier)
    }
}

@Composable
private fun NonAudioFileRow(
    file: RemoteFile,
    badgeInfo: AudioQualityBadge?,
    modifier: Modifier = Modifier
) {
    val (icon, label) =
        when {
            file.isLyrics -> AppIcons.Description to "歌词"
            else -> AppIcons.InsertDriveFile to "其他文件"
        }
    val (containerColor, contentColor) =
        if (file.isLyrics) {
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        modifier =
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        ListItem(
            modifier = Modifier.fillMaxWidth().alpha(if (file.isLyrics) 1f else 0.5f),
            leadingContent = {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = containerColor,
                    modifier = Modifier.size(AudioTrackRowDefaults.ThumbnailSize)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            contentDescription = label,
                            tint = contentColor,
                            modifier = Modifier.size(AudioTrackRowDefaults.LeadingIconSize)
                        )
                    }
                }
            },
            headlineContent = {
                Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                val sub = if (file.size > 0) formatTrackFileSize(file.size) else file.lastModified
                if (!sub.isNullOrBlank()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            trailingContent = { badgeInfo?.let { AudioQualityBadgePill(it) } },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
