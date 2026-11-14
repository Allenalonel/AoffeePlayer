package com.webdav.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioQualityTest {
    private val sampleServer =
        WebDavServer(
            id = 1L,
            name = "NAS",
            url = "https://nas.example.com",
            port = 5006,
            isDefault = true
        )

    @Test
    fun audioQualityLevel_hasExpectedEnumerations() {
        val levels = AudioQualityLevel.entries
        assertTrue(levels.contains(AudioQualityLevel.LOSSLESS))
        assertTrue(levels.contains(AudioQualityLevel.HIGH_QUALITY))
        assertTrue(levels.contains(AudioQualityLevel.STANDARD))
        assertTrue(levels.contains(AudioQualityLevel.COMPRESSED))
    }

    @Test
    fun estimateBitrateKbps_fromFilenamePatterns() {
        assertEquals(320, estimateBitrateKbps("Artist - Song [320k].mp3", 0L, 0L))
        assertEquals(256, estimateBitrateKbps("Artist - Song (256kbps).mp3", 0L, 0L))
        assertEquals(128, estimateBitrateKbps("Track 128k.m4a", 0L, 0L))
        assertNull(estimateBitrateKbps("Artist - Song.mp3", 0L, 0L))
        assertNull(estimateBitrateKbps("Song [5k].mp3", 0L, 0L))
    }

    @Test
    fun estimateBitrateKbps_fromFileSizeAndDuration() {
        // 9.6 MB in 240 seconds = 320 kbps
        assertEquals(320, estimateBitrateKbps("song.mp3", 9_600_000L, 240_000L))
        // 7.68 MB in 240 seconds = 256 kbps
        assertEquals(256, estimateBitrateKbps("song.mp3", 7_680_000L, 240_000L))
        // 6.72 MB in 240 seconds = 224 kbps
        assertEquals(224, estimateBitrateKbps("song.mp3", 6_720_000L, 240_000L))
        // 5.76 MB in 240 seconds = 192 kbps
        assertEquals(192, estimateBitrateKbps("song.mp3", 5_760_000L, 240_000L))
        // 4.80 MB in 240 seconds = 160 kbps
        assertEquals(160, estimateBitrateKbps("song.mp3", 4_800_000L, 240_000L))
        // 3.84 MB in 240 seconds = 128 kbps
        assertEquals(128, estimateBitrateKbps("song.mp3", 3_840_000L, 240_000L))
        // 2.88 MB in 240 seconds = 96 kbps
        assertEquals(96, estimateBitrateKbps("song.mp3", 2_880_000L, 240_000L))
        // 1.92 MB in 240 seconds = 64 kbps
        assertEquals(64, estimateBitrateKbps("song.mp3", 1_920_000L, 240_000L))

        // Duration too short
        assertNull(estimateBitrateKbps("song.mp3", 9_600_000L, 500L))
        // File size zero
        assertNull(estimateBitrateKbps("song.mp3", 0L, 240_000L))
    }

    @Test
    fun flacTrack_evaluatesAsLossless() {
        val file =
            RemoteFile(
                name = "hotel_california.flac",
                path = "/Music/hotel_california.flac",
                size = 35_000_000L
            )
        val metadata =
            TrackMetadata(
                serverId = 1L,
                remotePath = file.path,
                title = "Hotel California",
                durationMs = 390_000L
            )

        val track = AudioTrack.fromRemoteFile(sampleServer, file, metadata)
        assertNotNull(track)
        assertEquals("FLAC", track!!.badge.label)
        assertEquals(AudioFormat.FLAC, track.badge.format)
        assertTrue(track.isLossless)
        assertEquals(AudioQualityLevel.LOSSLESS, track.qualityLevel)
        assertNull(track.estimatedBitrateKbps)
        assertTrue(track.qualitySummary.contains("FLAC") && track.qualitySummary.contains("无损"))
        assertEquals(track.badge, track.resolveBadge())
        assertEquals(track.qualitySummary, track.formatQualitySummary())

        // RemoteFile extension methods
        val fileBadge = file.resolveBadge(metadata)
        assertNotNull(fileBadge)
        assertEquals(track.badge, fileBadge)
        assertEquals(track.qualitySummary, file.formatQualitySummary(metadata))
    }

    @Test
    fun wavTrack_evaluatesAsLossless() {
        val file =
            RemoteFile(
                name = "session_master.wav",
                path = "/Studio/session_master.wav",
                size = 60_000_000L
            )
        val track =
            AudioTrack(
                id = "1:${file.path}",
                serverId = 1L,
                remotePath = file.path,
                title = "Session Master",
                durationMs = 300_000L,
                size = file.size,
                format = AudioFormat.WAV
            )

        assertTrue(track.isLossless)
        assertEquals(AudioQualityLevel.LOSSLESS, track.qualityLevel)
        assertEquals("WAV", track.badge.label)
        assertTrue(track.qualitySummary.contains("WAV") && track.qualitySummary.contains("无损"))
    }

    @Test
    fun mp3Track_highQualityEvaluation() {
        val track =
            AudioTrack(
                id = "1:/Music/bohemian_rhapsody.mp3",
                serverId = 1L,
                remotePath = "/Music/bohemian_rhapsody.mp3",
                title = "Bohemian Rhapsody",
                durationMs = 240_000L,
                size = 9_600_000L,
                format = AudioFormat.MP3
            )

        assertFalse(track.isLossless)
        assertEquals(AudioQualityLevel.HIGH_QUALITY, track.qualityLevel)
        assertEquals(320, track.estimatedBitrateKbps)
        assertEquals("MP3 320k", track.badge.label)
        assertTrue(track.qualitySummary.contains("MP3") && track.qualitySummary.contains("320"))
    }

    @Test
    fun mp3Track_standardAndCompressedTiers() {
        val standardTrack =
            AudioTrack(
                id = "1:/standard.mp3",
                serverId = 1L,
                remotePath = "/standard.mp3",
                title = "Standard",
                durationMs = 240_000L,
                size = 5_760_000L,
                format = AudioFormat.MP3
            )
        assertEquals(AudioQualityLevel.STANDARD, standardTrack.qualityLevel)
        assertEquals(192, standardTrack.estimatedBitrateKbps)
        assertEquals("MP3 192k", standardTrack.badge.label)

        val compressedTrack =
            AudioTrack(
                id = "1:/compressed.mp3",
                serverId = 1L,
                remotePath = "/compressed.mp3",
                title = "Compressed",
                durationMs = 240_000L,
                size = 3_840_000L,
                format = AudioFormat.MP3
            )
        assertEquals(AudioQualityLevel.COMPRESSED, compressedTrack.qualityLevel)
        assertEquals(128, compressedTrack.estimatedBitrateKbps)
        assertEquals("MP3 128k", compressedTrack.badge.label)
    }

    @Test
    fun mp3Track_withoutDurationOrBitrate() {
        val track =
            AudioTrack(
                id = "1:/unknown.mp3",
                serverId = 1L,
                remotePath = "/unknown.mp3",
                title = "Unknown",
                durationMs = 0L,
                size = 0L,
                format = AudioFormat.MP3
            )
        assertEquals(AudioQualityLevel.STANDARD, track.qualityLevel)
        assertNull(track.estimatedBitrateKbps)
        assertEquals("MP3", track.badge.label)
    }

    @Test
    fun otherFormats_aacOggM4aWma() {
        val aacTrack =
            AudioTrack(
                id = "1:/song.aac",
                serverId = 1L,
                remotePath = "/song.aac",
                title = "AAC Song",
                durationMs = 240_000L,
                size = 7_680_000L,
                format = AudioFormat.AAC
            )
        assertEquals(AudioQualityLevel.HIGH_QUALITY, aacTrack.qualityLevel)
        assertEquals("AAC 256k", aacTrack.badge.label)

        val oggTrack =
            AudioTrack(
                id = "1:/song.ogg",
                serverId = 1L,
                remotePath = "/song.ogg",
                title = "OGG Song",
                durationMs = 240_000L,
                size = 5_760_000L,
                format = AudioFormat.OGG
            )
        assertEquals(AudioQualityLevel.STANDARD, oggTrack.qualityLevel)
        assertEquals("OGG 192k", oggTrack.badge.label)

        val m4aTrack =
            AudioTrack(
                id = "1:/song.m4a",
                serverId = 1L,
                remotePath = "/song.m4a",
                title = "M4A Song",
                durationMs = 240_000L,
                size = 7_680_000L,
                format = AudioFormat.M4A
            )
        assertEquals(AudioQualityLevel.STANDARD, m4aTrack.qualityLevel)
        assertEquals("M4A 256k", m4aTrack.badge.label)

        val wmaTrack =
            AudioTrack(
                id = "1:/song.wma",
                serverId = 1L,
                remotePath = "/song.wma",
                title = "WMA Song",
                durationMs = 0L,
                size = 0L,
                format = AudioFormat.WMA
            )
        assertEquals(AudioQualityLevel.COMPRESSED, wmaTrack.qualityLevel)
        assertEquals("WMA", wmaTrack.badge.label)
    }

    @Test
    fun remoteFile_lyricsAndOtherFiles() {
        val lrcFile = RemoteFile(name = "lyrics.lrc", path = "/lyrics.lrc", fileType = RemoteFileType.Lyrics)
        val lrcBadge = lrcFile.resolveBadge()
        assertNotNull(lrcBadge)
        assertEquals("LRC", lrcBadge!!.label)
        assertNull(lrcBadge.format)
        assertEquals("LRC 歌词文件", lrcFile.formatQualitySummary())

        val pdfFile = RemoteFile(name = "booklet.pdf", path = "/booklet.pdf", fileType = RemoteFileType.Other)
        val pdfBadge = pdfFile.resolveBadge()
        assertNotNull(pdfBadge)
        assertEquals("PDF", pdfBadge!!.label)
        assertEquals("PDF 文件", pdfFile.formatQualitySummary())

        val unknownFile = RemoteFile(name = "no_extension", path = "/no_extension", fileType = RemoteFileType.Other)
        assertNull(unknownFile.resolveBadge())
        assertEquals("未知文件", unknownFile.formatQualitySummary())
    }

    @Test
    fun audioTrack_withMetadata_recalculatesQuality() {
        val baseTrack =
            AudioTrack(
                id = "1:/song.mp3",
                serverId = 1L,
                remotePath = "/song.mp3",
                title = "Song",
                durationMs = 0L,
                size = 9_600_000L,
                format = AudioFormat.MP3
            )
        assertEquals(AudioQualityLevel.STANDARD, baseTrack.qualityLevel)
        assertNull(baseTrack.estimatedBitrateKbps)

        val updatedTrack =
            baseTrack.withMetadata(
                TrackMetadata(
                    serverId = 1L,
                    remotePath = "/song.mp3",
                    durationMs = 240_000L
                )
            )
        assertEquals(AudioQualityLevel.HIGH_QUALITY, updatedTrack.qualityLevel)
        assertEquals(320, updatedTrack.estimatedBitrateKbps)
        assertEquals("MP3 320k", updatedTrack.badge.label)
    }

    @Test
    fun strictGating_whenPhysicalSpecsPresent_formatsAuthoritativeSpecsAndSetsHiRes() {
        val track =
            AudioTrack(
                id = "1:/music/01. Hotel California.flac",
                serverId = 1L,
                remotePath = "/music/01. Hotel California.flac",
                title = "Hotel California",
                format = AudioFormat.FLAC,
                sampleRate = 96000,
                bitDepth = 24,
                bitrateKbps = 2450,
                channels = 2
            )
        val specs = AudioQuality.resolveAudiophileSpecs(track)
        assertNotNull(specs)
        assertEquals("96kHz", specs!!.sampleRate)
        assertEquals("24-bit", specs.bitDepth)
        assertEquals(2450, specs.bitrateKbps)
        assertTrue(specs.isHiRes)
        assertTrue(specs.isLossless)
        assertEquals("⚡ FLAC · 96kHz / 24-bit · 2450 kbps", specs.formatted)
    }

    @Test
    fun strictGating_whenComplexCdQualityTrack_neverMislabelsAsHiResEvenWithHighBitrate() {
        val track =
            AudioTrack(
                id = "1:/music/Symphony.flac",
                serverId = 1L,
                remotePath = "/music/Symphony.flac",
                title = "Symphony",
                durationMs = 240_000L,
                size = 73_500_000L,
                format = AudioFormat.FLAC,
                sampleRate = 44100,
                bitDepth = 16,
                bitrateKbps = 2450,
                channels = 2
            )
        val specs = AudioQuality.resolveAudiophileSpecs(track)
        assertNotNull(specs)
        assertEquals("44.1kHz", specs!!.sampleRate)
        assertEquals("16-bit", specs.bitDepth)
        assertEquals(2450, specs.bitrateKbps)
        assertFalse("16-bit/44.1kHz must NEVER be labeled Hi-Res even with bitrate > 2000kbps", specs.isHiRes)
        assertEquals("FLAC · 44.1kHz / 16-bit · 2450 kbps", specs.formatted)
    }

    @Test
    fun strictGating_whenPhysicalSpecsMissing_returnsGracefulFallbackWithoutGuesswork() {
        val track =
            AudioTrack(
                id = "1:/music/Track [96kHz-24bit].flac",
                serverId = 1L,
                remotePath = "/music/Track [96kHz-24bit].flac",
                title = "Track",
                format = AudioFormat.FLAC,
                sampleRate = null,
                bitDepth = null,
                bitrateKbps = null,
                channels = null
            )
        val specs = AudioQuality.resolveAudiophileSpecs(track)
        assertNotNull(specs)
        assertNull("Missing physical sampleRate must not be guessed from filename", specs!!.sampleRate)
        assertNull("Missing physical bitDepth must not be guessed from filename", specs.bitDepth)
        assertFalse(specs.isHiRes)
        assertEquals("FLAC", specs.formatted)
    }

    @Test
    fun strictGating_hiResSampleRateThreshold_88200HzTriggersHiRes() {
        val track88 = AudioTrack(
            id = "1:/track88.flac",
            serverId = 1L,
            remotePath = "/track88.flac",
            title = "Track 88",
            format = AudioFormat.FLAC,
            sampleRate = 88200,
            bitDepth = 16,
            bitrateKbps = 1500,
            channels = 2
        )
        val specs88 = AudioQuality.resolveAudiophileSpecs(track88)
        assertTrue(specs88!!.isHiRes)
        assertEquals("⚡ FLAC · 88.2kHz / 16-bit · 1500 kbps", specs88.formatted)

        val track48_24 = AudioTrack(
            id = "1:/track48.flac",
            serverId = 1L,
            remotePath = "/track48.flac",
            title = "Track 48-24",
            format = AudioFormat.FLAC,
            sampleRate = 48000,
            bitDepth = 24,
            bitrateKbps = 1500,
            channels = 2
        )
        val specs48_24 = AudioQuality.resolveAudiophileSpecs(track48_24)
        assertTrue(specs48_24!!.isHiRes)
        assertEquals("⚡ FLAC · 48kHz / 24-bit · 1500 kbps", specs48_24.formatted)

        val track48_16 = AudioTrack(
            id = "1:/track48_16.flac",
            serverId = 1L,
            remotePath = "/track48_16.flac",
            title = "Track 48-16",
            format = AudioFormat.FLAC,
            sampleRate = 48000,
            bitDepth = 16,
            bitrateKbps = 1500,
            channels = 2
        )
        val specs48_16 = AudioQuality.resolveAudiophileSpecs(track48_16)
        assertFalse(specs48_16!!.isHiRes)
        assertEquals("FLAC · 48kHz / 16-bit · 1500 kbps", specs48_16.formatted)
    }

    @Test
    fun strictGating_whenOnlyBitDepthPresent_formatsHiResWithBitDepth() {
        val track =
            AudioTrack(
                id = "1:/track24.flac",
                serverId = 1L,
                remotePath = "/track24.flac",
                title = "Track 24-bit",
                format = AudioFormat.FLAC,
                sampleRate = null,
                bitDepth = 24,
                bitrateKbps = 2450,
                channels = 2
            )
        val specs = AudioQuality.resolveAudiophileSpecs(track)
        assertNotNull(specs)
        assertTrue(specs!!.isHiRes)
        assertNull(specs.sampleRate)
        assertEquals("24-bit", specs.bitDepth)
        assertEquals("⚡ FLAC · 24-bit · 2450 kbps", specs.formatted)

        val trackWithoutBitrate = track.copy(bitrateKbps = null)
        val specsNoBitrate = AudioQuality.resolveAudiophileSpecs(trackWithoutBitrate)
        assertTrue(specsNoBitrate!!.isHiRes)
        assertEquals("⚡ FLAC · 24-bit", specsNoBitrate.formatted)
    }

    @Test
    fun strictGating_neverGuessesBitrateFromFilenameRegex() {
        val trackWithRegexInName =
            AudioTrack(
                id = "1:/music/Song [320k] [96-24].flac",
                serverId = 1L,
                remotePath = "/music/Song [320k] [96-24].flac",
                title = "Song",
                format = AudioFormat.FLAC,
                sampleRate = null,
                bitDepth = null,
                bitrateKbps = null,
                channels = null,
                size = 0L,
                durationMs = 0L
            )
        val specs = AudioQuality.resolveAudiophileSpecs(trackWithRegexInName)
        assertNotNull(specs)
        assertNull("Missing physical bitrate must NOT be guessed from filename regex", specs!!.bitrateKbps)
        assertFalse(specs.isHiRes)
        assertEquals("FLAC", specs.formatted)
    }

    @Test
    fun resolveAudiophileSpecs_withAudioPhysicalSpecs_mapsPropertiesCorrectly() {
        val physical = AudioPhysicalSpecs(
            sampleRate = 96000,
            bitDepth = 24,
            bitrateKbps = 2450,
            channels = 2
        )
        val specs = AudioQuality.resolveAudiophileSpecs(
            format = AudioFormat.FLAC,
            specs = physical
        )
        assertTrue(specs.isHiRes)
        assertEquals("96kHz", specs.sampleRate)
        assertEquals("24-bit", specs.bitDepth)
        assertEquals(2450, specs.bitrateKbps)
        assertEquals("⚡ FLAC · 96kHz / 24-bit · 2450 kbps", specs.formatted)
    }

    @Test
    fun formatAudiophileSpecs_flacHiResWithExplicitTags() {
        val track =
            AudioTrack(
                id = "1:/music/track.flac",
                serverId = 1L,
                remotePath = "/music/Hotel California.flac",
                title = "Hotel California",
                durationMs = 240_000L,
                size = 73_500_000L,
                format = AudioFormat.FLAC,
                sampleRate = 96000,
                bitDepth = 24,
                bitrateKbps = 2450
            )
        val specs = formatAudiophileSpecs(track)
        assertEquals("⚡ FLAC · 96kHz / 24-bit · 2450 kbps", specs)
        assertEquals(specs, track.audiophileSpecs)
        assertEquals(specs, track.audiophileSpecsModel.formatted)
    }

    @Test
    fun formatAudiophileSpecs_flacWithoutPhysicalSpecs_fallsBackToCleanBitrateOnly() {
        val track =
            AudioTrack(
                id = "1:/music/track.flac",
                serverId = 1L,
                remotePath = "/music/Track.flac",
                title = "Track",
                durationMs = 240_000L,
                size = 73_500_000L,
                format = AudioFormat.FLAC
            )
        val specs = formatAudiophileSpecs(track)
        assertEquals("FLAC · 2450 kbps", specs)
        assertFalse(track.audiophileSpecsModel.isHiRes)
    }

    @Test
    fun formatAudiophileSpecs_flacStandardCd() {
        val track =
            AudioTrack(
                id = "1:/music/track.flac",
                serverId = 1L,
                remotePath = "/music/Track.flac",
                title = "Track",
                durationMs = 240_000L,
                size = 25_500_000L,
                format = AudioFormat.FLAC,
                sampleRate = 44100,
                bitDepth = 16,
                bitrateKbps = 850
            )
        val specs = formatAudiophileSpecs(track)
        assertEquals("FLAC · 44.1kHz / 16-bit · 850 kbps", specs)
    }

    @Test
    fun formatAudiophileSpecs_wavStandardCd() {
        val track =
            AudioTrack(
                id = "1:/music/track.wav",
                serverId = 1L,
                remotePath = "/music/Symphony.wav",
                title = "Symphony",
                durationMs = 240_000L,
                size = 42_336_000L,
                format = AudioFormat.WAV,
                sampleRate = 44100,
                bitDepth = 16,
                bitrateKbps = 1411
            )
        val specs = formatAudiophileSpecs(track)
        assertEquals("WAV · 44.1kHz / 16-bit · 1411 kbps", specs)
    }

    @Test
    fun formatAudiophileSpecs_wavHiRes() {
        val track =
            AudioTrack(
                id = "1:/music/track.wav",
                serverId = 1L,
                remotePath = "/music/Symphony.wav",
                title = "Symphony",
                durationMs = 240_000L,
                size = 138_240_000L,
                format = AudioFormat.WAV,
                sampleRate = 96000,
                bitDepth = 24,
                bitrateKbps = 4608
            )
        val specs = formatAudiophileSpecs(track)
        assertEquals("⚡ WAV · 96kHz / 24-bit · 4608 kbps", specs)
    }

    @Test
    fun formatAudiophileSpecs_mp3WithBitrateTag() {
        val track =
            AudioTrack(
                id = "1:/music/song.mp3",
                serverId = 1L,
                remotePath = "/music/Artist - Song [320k].mp3",
                title = "Song",
                durationMs = 240_000L,
                size = 9_600_000L,
                format = AudioFormat.MP3
            )
        val specs = formatAudiophileSpecs(track)
        assertEquals("MP3 · 320 kbps", specs)
    }

    @Test
    fun formatAudiophileSpecs_lossyFormats() {
        val aacTrack =
            AudioTrack(
                id = "1:/music/song.aac",
                serverId = 1L,
                remotePath = "/music/song.aac",
                title = "AAC Song",
                durationMs = 240_000L,
                size = 7_680_000L,
                format = AudioFormat.AAC
            )
        assertEquals("AAC · 256 kbps", formatAudiophileSpecs(aacTrack))

        val oggTrack =
            AudioTrack(
                id = "1:/music/song.ogg",
                serverId = 1L,
                remotePath = "/music/song.ogg",
                title = "OGG Song",
                durationMs = 240_000L,
                size = 5_760_000L,
                format = AudioFormat.OGG
            )
        assertEquals("OGG · 192 kbps", formatAudiophileSpecs(oggTrack))

        val m4aTrack =
            AudioTrack(
                id = "1:/music/song.m4a",
                serverId = 1L,
                remotePath = "/music/song.m4a",
                title = "M4A Song",
                durationMs = 240_000L,
                size = 7_680_000L,
                format = AudioFormat.M4A
            )
        assertEquals("M4A · 256 kbps", formatAudiophileSpecs(m4aTrack))

        val wmaTrack =
            AudioTrack(
                id = "1:/music/song.wma",
                serverId = 1L,
                remotePath = "/music/song.wma",
                title = "WMA Song",
                durationMs = 240_000L,
                size = 3_840_000L,
                format = AudioFormat.WMA
            )
        assertEquals("WMA · 128 kbps", formatAudiophileSpecs(wmaTrack))
    }

    @Test
    fun formatAudiophileSpecs_nullTrack_returnsEmpty() {
        assertEquals("", formatAudiophileSpecs(null))
    }

    @Test
    fun formatAudiophileSpecs_remoteFile() {
        val flacFile =
            RemoteFile(
                name = "test.flac",
                path = "/music/test.flac",
                size = 73_500_000L
            )
        val metadata =
            TrackMetadata(
                serverId = 1L,
                remotePath = flacFile.path,
                durationMs = 240_000L,
                sampleRate = 96000,
                bitDepth = 24,
                bitrateKbps = 2450
            )
        val specs = formatAudiophileSpecs(flacFile, metadata)
        assertEquals("⚡ FLAC · 96kHz / 24-bit · 2450 kbps", specs)
        assertEquals(specs, flacFile.formatAudiophileSpecs(metadata))
    }

    @Test
    fun audiophileSpecs_allSevenCodecs_verifiesFormatSampleRateBitDepthAndBitrate() {
        // 1. FLAC (Hi-Res audiophile master)
        val flacSpecs = AudioQuality.resolveAudiophileSpecs(
            format = AudioFormat.FLAC,
            sampleRate = 96000,
            bitDepth = 24,
            bitrateKbps = 2450,
            fileName = "Master.flac",
            fileSize = 73_500_000L,
            durationMs = 240_000L
        )
        assertEquals(AudioFormat.FLAC, flacSpecs.format)
        assertEquals("96kHz", flacSpecs.sampleRate)
        assertEquals("24-bit", flacSpecs.bitDepth)
        assertEquals(2450, flacSpecs.bitrateKbps)
        assertTrue(flacSpecs.isHiRes)
        assertTrue(flacSpecs.isLossless)
        assertEquals("⚡ FLAC · 96kHz / 24-bit · 2450 kbps", flacSpecs.formatted)

        // 2. WAV (Standard Redbook CD)
        val wavSpecs = AudioQuality.resolveAudiophileSpecs(
            format = AudioFormat.WAV,
            sampleRate = 44100,
            bitDepth = 16,
            bitrateKbps = 1411,
            fileName = "Redbook.wav",
            fileSize = 42_336_000L,
            durationMs = 240_000L
        )
        assertEquals(AudioFormat.WAV, wavSpecs.format)
        assertEquals("44.1kHz", wavSpecs.sampleRate)
        assertEquals("16-bit", wavSpecs.bitDepth)
        assertEquals(1411, wavSpecs.bitrateKbps)
        assertFalse(wavSpecs.isHiRes)
        assertTrue(wavSpecs.isLossless)
        assertEquals("WAV · 44.1kHz / 16-bit · 1411 kbps", wavSpecs.formatted)

        // 3. MP3 (Lossy CBR/VBR)
        val mp3Specs = AudioQuality.resolveAudiophileSpecs(
            format = AudioFormat.MP3,
            fileName = "Broadcast [320k].mp3",
            fileSize = 9_600_000L,
            durationMs = 240_000L
        )
        assertEquals(AudioFormat.MP3, mp3Specs.format)
        assertNull(mp3Specs.sampleRate)
        assertNull(mp3Specs.bitDepth)
        assertEquals(320, mp3Specs.bitrateKbps)
        assertFalse(mp3Specs.isHiRes)
        assertFalse(mp3Specs.isLossless)
        assertEquals("MP3 · 320 kbps", mp3Specs.formatted)

        // 4. AAC (Advanced Audio Coding)
        val aacSpecs = AudioQuality.resolveAudiophileSpecs(
            format = AudioFormat.AAC,
            fileName = "Stream [256k].aac",
            fileSize = 7_680_000L,
            durationMs = 240_000L
        )
        assertEquals(AudioFormat.AAC, aacSpecs.format)
        assertNull(aacSpecs.sampleRate)
        assertNull(aacSpecs.bitDepth)
        assertEquals(256, aacSpecs.bitrateKbps)
        assertFalse(aacSpecs.isHiRes)
        assertFalse(aacSpecs.isLossless)
        assertEquals("AAC · 256 kbps", aacSpecs.formatted)

        // 5. OGG (Vorbis Stream)
        val oggSpecs = AudioQuality.resolveAudiophileSpecs(
            format = AudioFormat.OGG,
            fileName = "Stream [192k].ogg",
            fileSize = 5_760_000L,
            durationMs = 240_000L
        )
        assertEquals(AudioFormat.OGG, oggSpecs.format)
        assertNull(oggSpecs.sampleRate)
        assertNull(oggSpecs.bitDepth)
        assertEquals(192, oggSpecs.bitrateKbps)
        assertFalse(oggSpecs.isHiRes)
        assertFalse(oggSpecs.isLossless)
        assertEquals("OGG · 192 kbps", oggSpecs.formatted)

        // 6. M4A (MPEG-4 Audio)
        val m4aSpecs = AudioQuality.resolveAudiophileSpecs(
            format = AudioFormat.M4A,
            fileName = "Track [256k].m4a",
            fileSize = 7_680_000L,
            durationMs = 240_000L
        )
        assertEquals(AudioFormat.M4A, m4aSpecs.format)
        assertNull(m4aSpecs.sampleRate)
        assertNull(m4aSpecs.bitDepth)
        assertEquals(256, m4aSpecs.bitrateKbps)
        assertFalse(m4aSpecs.isHiRes)
        assertFalse(m4aSpecs.isLossless)
        assertEquals("M4A · 256 kbps", m4aSpecs.formatted)

        // 7. WMA (Windows Media Audio)
        val wmaSpecs = AudioQuality.resolveAudiophileSpecs(
            format = AudioFormat.WMA,
            fileName = "Legacy [128k].wma",
            fileSize = 3_840_000L,
            durationMs = 240_000L
        )
        assertEquals(AudioFormat.WMA, wmaSpecs.format)
        assertNull(wmaSpecs.sampleRate)
        assertNull(wmaSpecs.bitDepth)
        assertEquals(128, wmaSpecs.bitrateKbps)
        assertFalse(wmaSpecs.isHiRes)
        assertFalse(wmaSpecs.isLossless)
        assertEquals("WMA · 128 kbps", wmaSpecs.formatted)
    }

    @Test
    fun audiophileSpecs_lossyCodecsFallbackWithoutBitrate_returnsBareFormat() {
        val lossyFormats = listOf(
            AudioFormat.MP3,
            AudioFormat.AAC,
            AudioFormat.OGG,
            AudioFormat.M4A,
            AudioFormat.WMA
        )
        for (format in lossyFormats) {
            val specs = AudioQuality.resolveAudiophileSpecs(
                format = format,
                fileName = "unknown.${format.extension}",
                fileSize = 0L,
                durationMs = 0L
            )
            assertEquals(format.extension.uppercase(), specs.formatted)
            assertNull(specs.bitrateKbps)
            assertFalse(specs.isHiRes)
            assertFalse(specs.isLossless)
        }
    }

    @Test
    fun formatSampleRate_comprehensiveHertzValues() {
        assertEquals("44.1kHz", AudioQuality.formatSampleRate(44100))
        assertEquals("48kHz", AudioQuality.formatSampleRate(48000))
        assertEquals("88.2kHz", AudioQuality.formatSampleRate(88200))
        assertEquals("96kHz", AudioQuality.formatSampleRate(96000))
        assertEquals("176.4kHz", AudioQuality.formatSampleRate(176400))
        assertEquals("192kHz", AudioQuality.formatSampleRate(192000))
        assertEquals("352.8kHz", AudioQuality.formatSampleRate(352800))
        assertEquals("384kHz", AudioQuality.formatSampleRate(384000))
    }
}
