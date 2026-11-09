package com.webdav.player.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackPathFormatterTest {
    @Test
    fun formatDisplayPath_forSafPrimaryDocumentUri_returnsHumanReadablePath() {
        val uri =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FC-Pop%2FMelody%20(Live)%20-%20%E9%99%B6%E5%96%86.flac"
        val displayPath = TrackPathFormatter.formatDisplayPath(uri)
        val dirPath = TrackPathFormatter.formatDirectoryPath(uri)

        assertEquals("内部存储/Music/C-Pop/Melody (Live) - 陶喆.flac", displayPath)
        assertEquals("内部存储/Music/C-Pop/", dirPath)
    }

    @Test
    fun formatDisplayPath_forSafSdCardUri_returnsSdCardVolumePath() {
        val uri =
            "content://com.android.externalstorage.documents/tree/1234-5678%3AMusic/document/1234-5678%3AMusic%2FRock%2Ftrack.mp3"
        val displayPath = TrackPathFormatter.formatDisplayPath(uri)
        val dirPath = TrackPathFormatter.formatDirectoryPath(uri)

        assertEquals("SD卡 (1234-5678)/Music/Rock/track.mp3", displayPath)
        assertEquals("SD卡 (1234-5678)/Music/Rock/", dirPath)
    }

    @Test
    fun formatDisplayPath_forFileUri_removesFileScheme() {
        val uri = "file:///storage/emulated/0/Music/song.mp3"
        val displayPath = TrackPathFormatter.formatDisplayPath(uri)
        val dirPath = TrackPathFormatter.formatDirectoryPath(uri)

        assertEquals("/storage/emulated/0/Music/song.mp3", displayPath)
        assertEquals("/storage/emulated/0/Music/", dirPath)
    }

    @Test
    fun formatDisplayPath_forWebDavRemotePath_preservesPath() {
        val remotePath = "/Music/Jazz/take_five.flac"
        val displayPath = TrackPathFormatter.formatDisplayPath(remotePath)
        val dirPath = TrackPathFormatter.formatDirectoryPath(remotePath)

        assertEquals("/Music/Jazz/take_five.flac", displayPath)
        assertEquals("/Music/Jazz/", dirPath)
    }

    @Test
    fun formatDisplayPath_forRootRemotePath_returnsRootDirectory() {
        val remotePath = "/song.flac"
        val displayPath = TrackPathFormatter.formatDisplayPath(remotePath)
        val dirPath = TrackPathFormatter.formatDirectoryPath(remotePath)

        assertEquals("/song.flac", displayPath)
        assertEquals("/", dirPath)
    }
}
