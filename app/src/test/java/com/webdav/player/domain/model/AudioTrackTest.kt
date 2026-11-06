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
                format = AudioFormat.MP3,
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
                format = AudioFormat.FLAC,
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
                format = AudioFormat.WAV,
            )
        assertTrue(track.isLocal)
    }

    @Test
    fun isLocal_whenRemoteServerAndPath_returnsFalse() {
        val track =
            AudioTrack(
                id = "1:/remote/Music/song.mp3",
                serverId = 1L,
                remotePath = "/remote/Music/song.mp3",
                title = "Song",
                format = AudioFormat.MP3,
            )
        assertFalse(track.isLocal)
    }
}
