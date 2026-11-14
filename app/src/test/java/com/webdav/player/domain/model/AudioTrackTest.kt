package com.webdav.player.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackTest {
    @Test
    fun isLocal_whenServerIdIsLocalStorageId_returnsTrue() {
        val track =
            AudioTrack(
                id = "0:/local/path/song.mp3",
                serverId = LOCAL_STORAGE_ID,
                remotePath = "/local/path/song.mp3",
                title = "Song",
                format = AudioFormat.MP3
            )
        assertTrue(track.isLocal)
    }

    @Test
    fun isLocal_whenRemotePathIsContentUri_returnsTrue() {
        val track =
            AudioTrack(
                id = "5:content://media/external/audio/1",
                serverId = 5L,
                remotePath = "content://media/external/audio/1",
                title = "Song",
                format = AudioFormat.FLAC
            )
        assertTrue(track.isLocal)
    }

    @Test
    fun isLocal_whenRemotePathIsFileUri_returnsTrue() {
        val track =
            AudioTrack(
                id = "5:file:///sdcard/Music/song.wav",
                serverId = 5L,
                remotePath = "file:///sdcard/Music/song.wav",
                title = "Song",
                format = AudioFormat.WAV
            )
        assertTrue(track.isLocal)
    }

    @Test
    fun fileName_whenSafContentUri_extractsRealFileNameWithoutDocumentIdPrefix() {
        val track =
            AudioTrack(
                id = "0:content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F%E6%88%91%E7%9A%84%E6%9C%80%E7%88%B1%2Fundressed-sombr.flac",
                serverId = LOCAL_STORAGE_ID,
                remotePath = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F%E6%88%91%E7%9A%84%E6%9C%80%E7%88%B1%2Fundressed-sombr.flac",
                title = "undressed-sombr",
                format = AudioFormat.FLAC
            )
        org.junit.Assert.assertEquals("undressed-sombr.flac", track.fileName)
    }

    @Test
    fun isLocal_whenRemoteServerAndPath_returnsFalse() {
        val track =
            AudioTrack(
                id = "1:/remote/Music/song.mp3",
                serverId = 1L,
                remotePath = "/remote/Music/song.mp3",
                title = "Song",
                format = AudioFormat.MP3
            )
        assertFalse(track.isLocal)
    }

    @Test
    fun fileName_whenStandardRemotePath_extractsFileName() {
        val track =
            AudioTrack(
                id = "1:/remote/Music/song.mp3",
                serverId = 1L,
                remotePath = "/remote/Music/song.mp3",
                title = "Song",
                format = AudioFormat.MP3
            )
        org.junit.Assert.assertEquals("song.mp3", track.fileName)
    }

    @Test
    fun physicalSpecs_holdsAuthoritativeParameters() {
        val track =
            AudioTrack(
                id = "1:/remote/Music/hires.flac",
                serverId = 1L,
                remotePath = "/remote/Music/hires.flac",
                title = "HiRes Song",
                format = AudioFormat.FLAC,
                sampleRate = 96000,
                bitDepth = 24,
                bitrateKbps = 2450,
                channels = 2
            )
        org.junit.Assert.assertEquals(96000, track.sampleRate)
        org.junit.Assert.assertEquals(24, track.bitDepth)
        org.junit.Assert.assertEquals(2450, track.bitrateKbps)
        org.junit.Assert.assertEquals(2, track.channels)
        org.junit.Assert.assertEquals(96000, track.physicalSpecs.sampleRate)
        org.junit.Assert.assertEquals(24, track.physicalSpecs.bitDepth)
        org.junit.Assert.assertEquals(2450, track.physicalSpecs.bitrateKbps)
        org.junit.Assert.assertEquals(2, track.physicalSpecs.channels)
    }

    @Test
    fun playlistTrackEntry_bidirectionalMapping_preservesPhysicalSpecs() {
        val originalTrack =
            AudioTrack(
                id = "1:/remote/Music/hires.flac",
                serverId = 1L,
                remotePath = "/remote/Music/hires.flac",
                title = "HiRes Song",
                format = AudioFormat.FLAC,
                sampleRate = 96000,
                bitDepth = 24,
                bitrateKbps = 2450,
                channels = 2
            )
        val entry = PlaylistTrackEntry.fromAudioTrack(
            playlistId = 10L,
            track = originalTrack,
            sortOrder = 0
        )
        org.junit.Assert.assertEquals(96000, entry.sampleRate)
        org.junit.Assert.assertEquals(24, entry.bitDepth)
        org.junit.Assert.assertEquals(2450, entry.bitrateKbps)
        org.junit.Assert.assertEquals(2, entry.channels)

        val restoredTrack = entry.toAudioTrack()
        org.junit.Assert.assertEquals(96000, restoredTrack.sampleRate)
        org.junit.Assert.assertEquals(24, restoredTrack.bitDepth)
        org.junit.Assert.assertEquals(2450, restoredTrack.bitrateKbps)
        org.junit.Assert.assertEquals(2, restoredTrack.channels)
        org.junit.Assert.assertTrue(restoredTrack.audiophileSpecsModel.isHiRes)
    }

    @Test
    fun mediaTrackIndexEntity_toAudioTrack_preservesPhysicalSpecs() {
        val indexEntity = com.webdav.player.data.local.MediaTrackIndexEntity(
            serverId = 1L,
            remotePath = "/music/song.flac",
            fileName = "song.flac",
            fileSize = 50_000_000L,
            lastModified = 1000L,
            format = "FLAC",
            parentPath = "/music/",
            parsedTitle = "Song",
            parsedArtist = "Artist"
        )
        val metadataEntity = com.webdav.player.data.local.TrackMetadataEntity(
            serverId = 1L,
            remotePath = "/music/song.flac",
            title = "Song",
            artist = "Artist",
            sampleRate = 96000,
            bitDepth = 24,
            bitrateKbps = 2450,
            channels = 2
        )
        val track = indexEntity.toAudioTrack(metadataEntity)
        org.junit.Assert.assertEquals(96000, track.sampleRate)
        org.junit.Assert.assertEquals(24, track.bitDepth)
        org.junit.Assert.assertEquals(2450, track.bitrateKbps)
        org.junit.Assert.assertEquals(2, track.channels)
        org.junit.Assert.assertTrue(track.audiophileSpecsModel.isHiRes)
    }
}
