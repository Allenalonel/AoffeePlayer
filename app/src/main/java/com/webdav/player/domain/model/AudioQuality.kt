package com.webdav.player.domain.model

enum class AudioQualityLevel {
    LOSSLESS,
    HIGH_QUALITY,
    STANDARD,
    COMPRESSED
}

data class AudioQualityBadge(
    val label: String,
    val format: AudioFormat? = null,
    val isLossless: Boolean = false,
    val estimatedBitrateKbps: Int? = null,
    val qualityLevel: AudioQualityLevel = AudioQualityLevel.STANDARD
)

data class AudiophileSpecs(
    val format: AudioFormat,
    val sampleRate: String? = null,
    val bitDepth: String? = null,
    val bitrateKbps: Int? = null,
    val channels: Int? = null,
    val isHiRes: Boolean = false,
    val isLossless: Boolean = false,
    val formatted: String
)

object AudioQuality {
    private val BITRATE_PATTERN = Regex("""(?:\[|\(|\b)(\d{2,4})\s*k(?:bps)?(?:\]|\)|\b)""", RegexOption.IGNORE_CASE)
    private val BITRATE_EXTENDED_PATTERN = Regex("""(?:\[|\(|\b)(\d{2,5})\s*k(?:bps)?(?:\]|\)|\b)""", RegexOption.IGNORE_CASE)

    fun estimateBitrateKbps(
        fileName: String,
        fileSize: Long,
        durationMs: Long
    ): Int? {
        // 1. Check if filename contains explicit bitrate tag like [320k] or (256kbps)
        val match = BITRATE_PATTERN.find(fileName)
        if (match != null) {
            val rate = match.groupValues[1].toIntOrNull()
            if (rate != null && rate in 32..1411) {
                return rate
            }
        }

        // 2. Estimate from file size and duration
        if (fileSize > 0L && durationMs >= 1000L) {
            val raw = (fileSize * 8L) / durationMs
            return when (raw) {
                in 300..360 -> 320
                in 240..275 -> 256
                in 210..235 -> 224
                in 180..205 -> 192
                in 150..175 -> 160
                in 115..140 -> 128
                in 85..110 -> 96
                in 55..75 -> 64
                in 32..999 -> raw.toInt()
                else -> null
            }
        }

        return null
    }

    fun resolveBadge(
        format: AudioFormat,
        fileName: String,
        fileSize: Long = 0L,
        durationMs: Long = 0L
    ): AudioQualityBadge =
        when (format) {
            AudioFormat.FLAC -> {
                AudioQualityBadge(
                    label = "FLAC",
                    format = AudioFormat.FLAC,
                    isLossless = true,
                    estimatedBitrateKbps = null,
                    qualityLevel = AudioQualityLevel.LOSSLESS
                )
            }

            AudioFormat.WAV -> {
                AudioQualityBadge(
                    label = "WAV",
                    format = AudioFormat.WAV,
                    isLossless = true,
                    estimatedBitrateKbps = null,
                    qualityLevel = AudioQualityLevel.LOSSLESS
                )
            }

            AudioFormat.MP3 -> {
                val bitrate = estimateBitrateKbps(fileName, fileSize, durationMs)
                val label = if (bitrate != null) "MP3 ${bitrate}k" else "MP3"
                val level =
                    when {
                        bitrate != null && bitrate >= 300 -> AudioQualityLevel.HIGH_QUALITY
                        bitrate != null && bitrate >= 180 -> AudioQualityLevel.STANDARD
                        bitrate != null && bitrate < 180 -> AudioQualityLevel.COMPRESSED
                        else -> AudioQualityLevel.STANDARD
                    }
                AudioQualityBadge(
                    label = label,
                    format = AudioFormat.MP3,
                    isLossless = false,
                    estimatedBitrateKbps = bitrate,
                    qualityLevel = level
                )
            }

            AudioFormat.AAC -> {
                val bitrate = estimateBitrateKbps(fileName, fileSize, durationMs)
                val label = if (bitrate != null) "AAC ${bitrate}k" else "AAC"
                val level = if (bitrate != null && bitrate >= 256) AudioQualityLevel.HIGH_QUALITY else AudioQualityLevel.STANDARD
                AudioQualityBadge(
                    label = label,
                    format = AudioFormat.AAC,
                    isLossless = false,
                    estimatedBitrateKbps = bitrate,
                    qualityLevel = level
                )
            }

            AudioFormat.OGG -> {
                val bitrate = estimateBitrateKbps(fileName, fileSize, durationMs)
                val label = if (bitrate != null) "OGG ${bitrate}k" else "OGG"
                AudioQualityBadge(
                    label = label,
                    format = AudioFormat.OGG,
                    isLossless = false,
                    estimatedBitrateKbps = bitrate,
                    qualityLevel = AudioQualityLevel.STANDARD
                )
            }

            AudioFormat.M4A -> {
                val bitrate = estimateBitrateKbps(fileName, fileSize, durationMs)
                val label = if (bitrate != null) "M4A ${bitrate}k" else "M4A"
                AudioQualityBadge(
                    label = label,
                    format = AudioFormat.M4A,
                    isLossless = false,
                    estimatedBitrateKbps = bitrate,
                    qualityLevel = AudioQualityLevel.STANDARD
                )
            }

            AudioFormat.WMA -> {
                val bitrate = estimateBitrateKbps(fileName, fileSize, durationMs)
                val label = if (bitrate != null) "WMA ${bitrate}k" else "WMA"
                AudioQualityBadge(
                    label = label,
                    format = AudioFormat.WMA,
                    isLossless = false,
                    estimatedBitrateKbps = bitrate,
                    qualityLevel = AudioQualityLevel.COMPRESSED
                )
            }

            AudioFormat.APE -> {
                AudioQualityBadge(
                    label = "APE",
                    format = AudioFormat.APE,
                    isLossless = true,
                    estimatedBitrateKbps = null,
                    qualityLevel = AudioQualityLevel.LOSSLESS
                )
            }
        }

    fun resolveBadge(
        file: RemoteFile,
        metadata: TrackMetadata? = null
    ): AudioQualityBadge? =
        when (val type = file.fileType) {
            is RemoteFileType.Audio -> {
                resolveBadge(
                    format = type.format,
                    fileName = file.name,
                    fileSize = file.size,
                    durationMs = metadata?.durationMs ?: 0L
                )
            }

            is RemoteFileType.Lyrics -> {
                AudioQualityBadge(
                    label = "LRC",
                    format = null,
                    isLossless = false,
                    qualityLevel = AudioQualityLevel.STANDARD
                )
            }

            is RemoteFileType.Other -> {
                val ext =
                    file.name
                        .substringAfterLast('.', "")
                        .uppercase()
                        .take(4)
                if (ext.isNotBlank()) {
                    AudioQualityBadge(
                        label = ext,
                        format = null,
                        isLossless = false,
                        qualityLevel = AudioQualityLevel.COMPRESSED
                    )
                } else {
                    null
                }
            }
        }

    fun formatQualitySummary(
        badge: AudioQualityBadge,
        isLyrics: Boolean = false
    ): String {
        val bitrateStr = badge.estimatedBitrateKbps?.let { " ($it kbps)" } ?: ""
        return when {
            badge.isLossless -> {
                "${badge.label} 无损音频"
            }

            badge.qualityLevel == AudioQualityLevel.HIGH_QUALITY -> {
                "${badge.format?.extension?.uppercase() ?: badge.label} 极高品质$bitrateStr"
            }

            badge.qualityLevel == AudioQualityLevel.STANDARD && badge.format != null -> {
                "${badge.format.extension.uppercase()} 标准音质$bitrateStr"
            }

            badge.qualityLevel == AudioQualityLevel.COMPRESSED && badge.format != null -> {
                "${badge.format.extension.uppercase()} 压缩音频$bitrateStr"
            }

            isLyrics -> {
                "LRC 歌词文件"
            }

            else -> {
                "${badge.label} 文件"
            }
        }
    }

    fun formatQualitySummary(
        file: RemoteFile,
        metadata: TrackMetadata? = null
    ): String {
        val badge = resolveBadge(file, metadata) ?: return "未知文件"
        return formatQualitySummary(badge, isLyrics = file.isLyrics)
    }

    fun formatSampleRate(hz: Int): String =
        if (hz % 1000 == 0) {
            "${hz / 1000}kHz"
        } else if (hz % 100 == 0) {
            "${hz / 1000.0}kHz"
        } else {
            val khz = String.format(java.util.Locale.US, "%.1f", hz / 1000.0)
            "${khz}kHz"
        }

    fun formatBitDepth(bits: Int): String = "$bits-bit"

    fun calculateBitrateKbps(
        fileName: String,
        fileSize: Long,
        durationMs: Long
    ): Int? {
        val match = BITRATE_EXTENDED_PATTERN.find(fileName)
        if (match != null) {
            val rate = match.groupValues[1].toIntOrNull()
            if (rate != null && rate in 32..9999) {
                return rate
            }
        }

        val standard = estimateBitrateKbps(fileName, fileSize, durationMs)
        if (standard != null) return standard

        if (fileSize > 0L && durationMs >= 1000L) {
            val raw = (fileSize * 8L) / durationMs
            if (raw in 1380..1440) return 1411
            if (raw in 32..9999) return raw.toInt()
        }
        return null
    }

    fun resolveAudiophileSpecs(
        format: AudioFormat,
        sampleRate: Int? = null,
        bitDepth: Int? = null,
        bitrateKbps: Int? = null,
        channels: Int? = null,
        @Suppress("UNUSED_PARAMETER") fileName: String = "",
        fileSize: Long = 0L,
        durationMs: Long = 0L
    ): AudiophileSpecs {
        val formatName = format.extension.uppercase()
        val isLossless = format == AudioFormat.FLAC || format == AudioFormat.WAV
        val resolvedBitrate =
            bitrateKbps ?: if (fileSize > 0L && durationMs >= 1000L) {
                val raw = (fileSize * 8L) / durationMs
                if (raw in 1380..1440) {
                    1411
                } else if (raw in 32..9999) {
                    raw.toInt()
                } else {
                    null
                }
            } else {
                null
            }

        val sampleRateStr = if (sampleRate != null && sampleRate > 0) formatSampleRate(sampleRate) else null
        val bitDepthStr = if (bitDepth != null && bitDepth > 0) formatBitDepth(bitDepth) else null

        val isHiRes = (sampleRate != null && sampleRate >= 88200) || (bitDepth != null && bitDepth >= 24)
        val prefix = if (isHiRes) "⚡ " else ""

        val formatted =
            when {
                sampleRateStr != null && bitDepthStr != null -> {
                    if (resolvedBitrate != null) {
                        "${prefix}$formatName · $sampleRateStr / $bitDepthStr · $resolvedBitrate kbps"
                    } else {
                        "${prefix}$formatName · $sampleRateStr / $bitDepthStr"
                    }
                }
                sampleRateStr != null -> {
                    if (resolvedBitrate != null) {
                        "${prefix}$formatName · $sampleRateStr · $resolvedBitrate kbps"
                    } else {
                        "${prefix}$formatName · $sampleRateStr"
                    }
                }
                bitDepthStr != null -> {
                    if (resolvedBitrate != null) {
                        "${prefix}$formatName · $bitDepthStr · $resolvedBitrate kbps"
                    } else {
                        "${prefix}$formatName · $bitDepthStr"
                    }
                }
                resolvedBitrate != null -> {
                    "$formatName · $resolvedBitrate kbps"
                }
                else -> {
                    formatName
                }
            }

        return AudiophileSpecs(
            format = format,
            sampleRate = sampleRateStr,
            bitDepth = bitDepthStr,
            bitrateKbps = resolvedBitrate,
            channels = channels,
            isHiRes = isHiRes,
            isLossless = isLossless,
            formatted = formatted
        )
    }

    fun resolveAudiophileSpecs(
        format: AudioFormat,
        specs: AudioPhysicalSpecs?,
        fileName: String = "",
        fileSize: Long = 0L,
        durationMs: Long = 0L
    ): AudiophileSpecs =
        resolveAudiophileSpecs(
            format = format,
            sampleRate = specs?.sampleRate,
            bitDepth = specs?.bitDepth,
            bitrateKbps = specs?.bitrateKbps,
            channels = specs?.channels,
            fileName = fileName,
            fileSize = fileSize,
            durationMs = durationMs
        )

    fun resolveAudiophileSpecs(
        format: AudioFormat,
        fileName: String,
        fileSize: Long = 0L,
        durationMs: Long = 0L
    ): AudiophileSpecs =
        resolveAudiophileSpecs(
            format = format,
            sampleRate = null,
            bitDepth = null,
            bitrateKbps = null,
            channels = null,
            fileName = fileName,
            fileSize = fileSize,
            durationMs = durationMs
        )

    fun resolveAudiophileSpecs(track: AudioTrack?): AudiophileSpecs? {
        if (track == null) return null
        return resolveAudiophileSpecs(
            format = track.format,
            sampleRate = track.sampleRate,
            bitDepth = track.bitDepth,
            bitrateKbps = track.bitrateKbps,
            channels = track.channels,
            fileName = track.fileName,
            fileSize = track.size,
            durationMs = track.durationMs
        )
    }

    fun formatAudiophileSpecs(track: AudioTrack?): String {
        if (track == null) return ""
        return resolveAudiophileSpecs(track)?.formatted.orEmpty()
    }

    fun formatAudiophileSpecs(
        file: RemoteFile,
        metadata: TrackMetadata? = null
    ): String {
        val audioType = file.fileType as? RemoteFileType.Audio ?: return ""
        return resolveAudiophileSpecs(
            format = audioType.format,
            sampleRate = metadata?.sampleRate,
            bitDepth = metadata?.bitDepth,
            bitrateKbps = metadata?.bitrateKbps,
            channels = metadata?.channels,
            fileName = file.name,
            fileSize = file.size,
            durationMs = metadata?.durationMs ?: 0L
        ).formatted
    }
}

// Top-level convenience functions
fun resolveAudiophileSpecs(track: AudioTrack?): AudiophileSpecs? = AudioQuality.resolveAudiophileSpecs(track)

fun formatAudiophileSpecs(track: AudioTrack?): String = AudioQuality.formatAudiophileSpecs(track)

fun formatAudiophileSpecs(
    file: RemoteFile,
    metadata: TrackMetadata? = null
): String = AudioQuality.formatAudiophileSpecs(file, metadata)

fun resolveAudiophileSpecs(
    format: AudioFormat,
    fileName: String,
    fileSize: Long = 0L,
    durationMs: Long = 0L
): AudiophileSpecs = AudioQuality.resolveAudiophileSpecs(format, fileName, fileSize, durationMs)

fun estimateBitrateKbps(
    fileName: String,
    fileSize: Long,
    durationMs: Long
): Int? = AudioQuality.estimateBitrateKbps(fileName, fileSize, durationMs)

fun resolveBadge(
    file: RemoteFile,
    metadata: TrackMetadata? = null
): AudioQualityBadge? = AudioQuality.resolveBadge(file, metadata)

fun resolveBadge(
    format: AudioFormat,
    fileName: String,
    fileSize: Long = 0L,
    durationMs: Long = 0L
): AudioQualityBadge = AudioQuality.resolveBadge(format, fileName, fileSize, durationMs)

fun formatQualitySummary(
    file: RemoteFile,
    metadata: TrackMetadata? = null
): String = AudioQuality.formatQualitySummary(file, metadata)

fun formatQualitySummary(
    badge: AudioQualityBadge,
    isLyrics: Boolean = false
): String = AudioQuality.formatQualitySummary(badge, isLyrics)
