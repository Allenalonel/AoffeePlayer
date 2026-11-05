package com.webdav.player.ui.browser

import com.webdav.player.domain.model.AudioQualityBadge
import com.webdav.player.domain.model.AudioQualityLevel
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.TrackMetadata
import com.webdav.player.ui.common.AudioTrackRowDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackItemRowTest {
    private fun formatSubtitleFor(metadata: TrackMetadata?): String =
        AudioTrackRowDefaults.formatTrackSubtitle(
            artist = metadata?.displayArtist(),
            album = metadata?.album,
        )

    @Test
    fun formatTrackSubtitle_withArtistAndAlbum_joinsCorrectly() {
        val file = RemoteFile(name = "01. Time.flac", path = "/Music/01. Time.flac")
        val metadata =
            TrackMetadata(
                serverId = 1L,
                remotePath = file.path,
                title = "Time",
                artist = "Pink Floyd",
                album = "The Dark Side of the Moon",
            )
        val subtitle = formatSubtitleFor(metadata)
        assertEquals("Pink Floyd · The Dark Side of the Moon", subtitle)
    }

    @Test
    fun formatTrackSubtitle_withArtistOnly_returnsArtist() {
        val file = RemoteFile(name = "Money.mp3", path = "/Music/Money.mp3")
        val metadata =
            TrackMetadata(
                serverId = 1L,
                remotePath = file.path,
                artist = "Pink Floyd",
                album = null,
            )
        val subtitle = formatSubtitleFor(metadata)
        assertEquals("Pink Floyd", subtitle)
    }

    @Test
    fun formatTrackSubtitle_withAlbumOnly_returnsAlbum() {
        val file = RemoteFile(name = "Track01.wav", path = "/Music/Track01.wav")
        val metadata =
            TrackMetadata(
                serverId = 1L,
                remotePath = file.path,
                artist = null,
                album = "Greatest Hits",
            )
        val subtitle = formatSubtitleFor(metadata)
        assertEquals("Greatest Hits", subtitle)
    }

    @Test
    fun formatTrackSubtitle_withoutMetadata_returnsUnknown() {
        val subtitle = formatSubtitleFor(null)
        assertEquals("未知", subtitle)
    }

    @Test
    fun formatTrackSubtitle_withoutMetadataOrSize_returnsUnknown() {
        val subtitle = formatSubtitleFor(null)
        assertEquals("未知", subtitle)
    }

    @Test
    fun formatTrackSubtitle_completelyEmpty_returnsUnknown() {
        val metadata = TrackMetadata(serverId = 1L, remotePath = "/Music/Track.flac", artist = "", album = "")
        val subtitle = formatSubtitleFor(metadata)
        assertEquals("未知", subtitle)
    }

    @Test
    fun badgeResolution_integratesWithRemoteFile() {
        val flacFile = RemoteFile(name = "track.flac", path = "/Music/track.flac", size = 20_000_000L)
        val flacBadge = flacFile.resolveBadge(null)
        assertNotNull(flacBadge)
        assertEquals("FLAC", flacBadge!!.label)
        assertEquals(AudioQualityLevel.LOSSLESS, flacBadge.qualityLevel)

        val mp3File = RemoteFile(name = "track.mp3", path = "/Music/track.mp3", size = 7_200_000L)
        val metadata = TrackMetadata(serverId = 1L, remotePath = mp3File.path, durationMs = 180_000L)
        val mp3Badge = mp3File.resolveBadge(metadata)
        assertNotNull(mp3Badge)
        assertEquals("MP3 320k", mp3Badge!!.label)
        assertEquals(320, mp3Badge.estimatedBitrateKbps)
    }
}
