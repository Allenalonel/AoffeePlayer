package com.webdav.player.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioQuality
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.PlaybackProgress
import com.webdav.player.domain.model.PlaylistTrackEntry
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.RemoteFileType
import com.webdav.player.domain.model.TrackMetadata
import com.webdav.player.domain.model.isLocal
import com.webdav.player.ui.theme.AppIcons
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
object TrackDetailBottomSheetDefaults {
    val Properties: ModalBottomSheetProperties =
        ModalBottomSheetProperties(
            securePolicy = SecureFlagPolicy.Inherit,
            isFocusable = false,
            shouldDismissOnBackPress = true,
        )
}

/**
 * Unified Material 3 Expressive bottom sheet for presenting audio track details,
 * audiophile specs (sample rate, bit depth, bitrate, Hi-Res badges), and formatted
 * volume storage paths.
 *
 * Enforces focus isolation with `isFocusable = false` and smooth exit animations via `sheetState.hide()`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailBottomSheet(
    track: AudioTrack,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val coroutineScope = rememberCoroutineScope()
    var isDismissing by remember { mutableStateOf(false) }

    val animateDismiss: () -> Unit = {
        if (!isDismissing) {
            isDismissing = true
            coroutineScope.launch {
                sheetState.hide()
            }.invokeOnCompletion {
                onDismiss()
            }
        }
    }

    BackHandler(enabled = true) {
        animateDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = {
            animateDismiss()
        },
        sheetState = sheetState,
        properties = TrackDetailBottomSheetDefaults.Properties,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 28.dp),
        ) {
            // Header Bar
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = AppIcons.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "曲目详细信息",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = animateDismiss,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Hero section: Cover Thumbnail + Title + Artist / Album
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(64.dp),
                ) {
                    CoverThumbnailImage(
                        thumbnailPath = track.coverThumbnailPath,
                        contentDescription = "封面",
                        modifier = Modifier.fillMaxSize(),
                        fallback = {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = AppIcons.Audiotrack,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(30.dp),
                                )
                            }
                        },
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = AudioTrackRowDefaults.formatTrackSubtitle(track.artist, track.album)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Audiophile Specs Card
            val specs = remember(track) { track.audiophileSpecsModel }
            val badge = remember(track) { track.qualityBadge }

            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "音频规格参数",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    // Badges row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AudioQualityBadgePill(badge = badge)
                        if (specs.isHiRes) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                            ) {
                                Text(
                                    text = "⚡ Hi-Res",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (specs.isLossless) {
                            TrackTagPill(text = "无损")
                        }
                    }

                    // Key-Value Grid for Specs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TrackDetailItem(label = "采样率", value = specs.sampleRate ?: "44.1kHz", modifier = Modifier.weight(1f))
                        TrackDetailItem(label = "位深", value = specs.bitDepth ?: "16-bit", modifier = Modifier.weight(1f))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TrackDetailItem(
                            label = "比特率",
                            value = specs.bitrateKbps?.let { "$it kbps" } ?: "动态码率",
                            modifier = Modifier.weight(1f),
                        )
                        TrackDetailItem(
                            label = "音频格式",
                            value = track.format.name,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Storage and Physical Attributes Card
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "文件属性与存储",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    val isLocal = track.isLocal
                    val formattedPath =
                        remember(track.remotePath, isLocal) {
                            formatVolumePath(track.remotePath, isLocal)
                        }

                    TrackDetailItem(
                        label = if (isLocal) "文件存储路径" else "远程文件路径",
                        value = formattedPath,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TrackDetailItem(
                            label = "文件大小",
                            value = formatTrackFileSize(track.size),
                            modifier = Modifier.weight(1f),
                        )
                        TrackDetailItem(
                            label = "曲目时长",
                            value = formatTrackDuration(track.durationMs),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    TrackDetailItem(
                        label = "存储来源",
                        value = if (isLocal) "本地设备存储 (SAF)" else "远程 WebDAV 服务器 (ID: ${track.serverId})",
                    )
                }
            }

            // Bottom Buttons
            if (onPlay != null) {
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = animateDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("关闭")
                    }
                    Button(
                        onClick = {
                            animateDismiss()
                            onPlay()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = AppIcons.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("立即播放")
                    }
                }
            }
        }
    }
}

/**
 * Convenience overload for [PlaylistTrackEntry].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailBottomSheet(
    track: PlaylistTrackEntry,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    TrackDetailBottomSheet(
        track = track.toAudioTrack(),
        onDismiss = onDismiss,
        modifier = modifier,
        onPlay = onPlay,
        sheetState = sheetState,
    )
}

/**
 * Convenience overload for [RemoteFile] and optional [TrackMetadata].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailBottomSheet(
    file: RemoteFile,
    metadata: TrackMetadata? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val track =
        remember(file, metadata) {
            val isLocal =
                file.path.startsWith("content://") ||
                    file.path.startsWith("file://") ||
                    file.path.startsWith("/local/")
            val format =
                (file.fileType as? RemoteFileType.Audio)?.format
                    ?: AudioFormat.fromExtension(file.name.substringAfterLast('.', ""))
                    ?: AudioFormat.MP3
            AudioTrack(
                id = file.path,
                serverId = if (isLocal) LOCAL_STORAGE_ID else 0L,
                remotePath = file.path,
                title = metadata?.displayTitle(file.name) ?: file.name,
                artist = metadata?.displayArtist() ?: metadata?.artist,
                album = metadata?.album,
                durationMs = metadata?.durationMs ?: 0L,
                size = file.size,
                format = format,
                coverThumbnailPath = metadata?.coverThumbnailPath,
            )
        }
    TrackDetailBottomSheet(
        track = track,
        onDismiss = onDismiss,
        modifier = modifier,
        onPlay = onPlay,
        sheetState = sheetState,
    )
}

@Composable
internal fun TrackDetailItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Formats a local SAF or remote WebDAV path into a standardized volume label path.
 * E.g. "[内部存储] Music/01.flac" or "[SD卡 (1234-5678)] Music/01.flac" or "/Music/01.flac".
 */
fun formatVolumePath(
    path: String,
    isLocal: Boolean = false,
): String {
    val display =
        if (isLocal || path.startsWith("content://") || path.startsWith("file://") || path.startsWith("/local/")) {
            TrackPathFormatter.formatDisplayPath(path)
        } else {
            path
        }
    return when {
        display.startsWith("内部存储/") -> "[内部存储] ${display.removePrefix("内部存储/")}"
        display == "内部存储" -> "[内部存储]"
        display.startsWith("SD卡") && display.contains("/") -> {
            val volume = display.substringBefore("/")
            val rest = display.substringAfter("/")
            "[$volume] $rest"
        }
        display.startsWith("SD卡") -> "[$display]"
        else -> display
    }
}

/**
 * Formats file size in bytes to a human-readable string with units (B, KB, MB, GB).
 */
fun formatTrackFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.US, "%.2f %s", value, units[digitGroups])
}

/**
 * Formats track duration in milliseconds into mm:ss format.
 */
fun formatTrackDuration(durationMs: Long): String {
    return if (durationMs > 0L) {
        PlaybackProgress.formatMs(durationMs)
    } else {
        "--:--"
    }
}
