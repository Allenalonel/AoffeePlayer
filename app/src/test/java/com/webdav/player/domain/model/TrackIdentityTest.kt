package com.webdav.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackIdentityTest {

    @Test
    fun remoteTrack_retainsServerId_andGeneratesExpectedKeys() {
        val identity = TrackIdentity(serverId = 1L, remotePath = "/Music/song.mp3")

        assertEquals(1L, identity.serverId)
        assertEquals("/Music/song.mp3", identity.remotePath)
        assertFalse(identity.isLocal)
        assertEquals("1:/Music/song.mp3", identity.compositeKey)
        assertEquals("1:/Music/song.mp3", identity.trackId)
    }

    @Test
    fun safContentUri_isClampedToLocalStorageId() {
        val identity = TrackIdentity(
            serverId = 42L,
            remotePath = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/123"
        )

        assertEquals(LOCAL_STORAGE_ID, identity.serverId)
        assertTrue(identity.isLocal)
        assertEquals(
            "local:content://com.android.externalstorage.documents/tree/primary%3AMusic/document/123",
            identity.compositeKey
        )
        assertEquals(
            "0:content://com.android.externalstorage.documents/tree/primary%3AMusic/document/123",
            identity.trackId
        )
    }

    @Test
    fun fileUri_isClampedToLocalStorageId() {
        val identity = TrackIdentity(
            serverId = 7L,
            remotePath = "file:///storage/emulated/0/Music/track.flac"
        )

        assertEquals(LOCAL_STORAGE_ID, identity.serverId)
        assertTrue(identity.isLocal)
        assertEquals("local:file:///storage/emulated/0/Music/track.flac", identity.compositeKey)
        assertEquals("0:file:///storage/emulated/0/Music/track.flac", identity.trackId)
    }

    @Test
    fun localNormalizedPath_isClampedToLocalStorageId() {
        val identity = TrackIdentity(
            serverId = 99L,
            remotePath = "/local/tree%3AMusic/Album/01.wav"
        )

        assertEquals(LOCAL_STORAGE_ID, identity.serverId)
        assertTrue(identity.isLocal)
        assertEquals("local:/local/tree%3AMusic/Album/01.wav", identity.compositeKey)
        assertEquals("0:/local/tree%3AMusic/Album/01.wav", identity.trackId)
    }

    @Test
    fun localStorageIdWithStandardPath_isMarkedLocal() {
        val identity = TrackIdentity(
            serverId = LOCAL_STORAGE_ID,
            remotePath = "/Music/cached_song.mp3"
        )

        assertEquals(LOCAL_STORAGE_ID, identity.serverId)
        assertTrue(identity.isLocal)
        assertEquals("local:/Music/cached_song.mp3", identity.compositeKey)
        assertEquals("0:/Music/cached_song.mp3", identity.trackId)
    }

    @Test
    fun matchesScope_evaluatesAllLocalAndRemoteScopesCorrectly() {
        val remote = TrackIdentity(1L, "/song.mp3")
        val local = TrackIdentity(LOCAL_STORAGE_ID, "/local/song.mp3")

        // Scope == null matches all
        assertTrue(remote.matchesScope(null))
        assertTrue(local.matchesScope(null))

        // Scope == LOCAL_STORAGE_ID matches only local
        assertFalse(remote.matchesScope(LOCAL_STORAGE_ID))
        assertTrue(local.matchesScope(LOCAL_STORAGE_ID))

        // Specific remote server scope
        assertTrue(remote.matchesScope(1L))
        assertFalse(remote.matchesScope(2L))
        assertFalse(local.matchesScope(1L))
    }

    @Test
    fun copy_enforcesClampingInvariants() {
        val remote = TrackIdentity(1L, "/remote.mp3")
        val copiedToLocal = remote.copy(remotePath = "content://media/audio/1")

        assertEquals(LOCAL_STORAGE_ID, copiedToLocal.serverId)
        assertTrue(copiedToLocal.isLocal)
        assertEquals("local:content://media/audio/1", copiedToLocal.compositeKey)
    }

    @Test
    fun valueSemanticsAndEquality() {
        val id1 = TrackIdentity(1L, "/a.mp3")
        val id2 = TrackIdentity(1L, "/a.mp3")
        val id3 = TrackIdentity(2L, "/a.mp3")
        val (serverId, path) = id1

        assertEquals(1L, serverId)
        assertEquals("/a.mp3", path)
        assertEquals(id1, id2)
        assertEquals(id1.hashCode(), id2.hashCode())
        assertNotEquals(id1, id3)
    }

    @Test
    fun extractFileName_safContentUriWithNestedTree_extractsDecodedFileName() {
        val safPath =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F%E6%88%91%E7%9A%84%E6%9C%80%E7%88%B1%2Fundressed-sombr.flac"
        assertEquals("undressed-sombr.flac", TrackIdentity.extractFileName(safPath))

        val identity = TrackIdentity(LOCAL_STORAGE_ID, safPath)
        assertEquals("undressed-sombr.flac", identity.fileName)
    }

    @Test
    fun extractFileName_safContentUriRootFile_extractsDecodedFileName() {
        val safPath =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3Aroot_song.mp3"
        assertEquals("root_song.mp3", TrackIdentity.extractFileName(safPath))
    }

    @Test
    fun extractFileName_standardWebDavPath_extractsFileName() {
        assertEquals("song.flac", TrackIdentity.extractFileName("/Music/Rock/song.flac"))
    }

    @Test
    fun extractFileName_fileUri_extractsFileName() {
        assertEquals("track.wav", TrackIdentity.extractFileName("file:///storage/emulated/0/Music/track.wav"))
    }

    @Test
    fun extractFileName_blankOrEdgeCase_returnsSafeString() {
        assertEquals("", TrackIdentity.extractFileName(""))
        assertEquals("track", TrackIdentity.extractFileName("track"))
    }

    @Test
    fun cleanTitle_removesFileExtensionAndSafEncoding() {
        assertEquals("undressed-sombr", TrackIdentity.cleanTitle("content://tree/primary%3AMusic%2Fundressed-sombr.flac"))
        assertEquals("song", TrackIdentity.cleanTitle("/Music/Rock/song.flac"))
        assertEquals("Hotel California", TrackIdentity.cleanTitle("Hotel California.flac"))
        assertEquals("track", TrackIdentity.cleanTitle("track"))
        org.junit.Assert.assertNull(TrackIdentity.cleanTitle(null))
        org.junit.Assert.assertNull(TrackIdentity.cleanTitle(""))
    }

    @Test
    fun sanitizePersistedTitle_preservesNormalTitles_andCleansSafTokens() {
        // Normal song titles with dots or spaces remain intact
        assertEquals("Hotel California", TrackIdentity.sanitizePersistedTitle("Hotel California"))
        assertEquals("Mr. Brightside", TrackIdentity.sanitizePersistedTitle("Mr. Brightside"))

        // SAF encoded tokens get cleaned
        val safToken = "primary%3AMusic%2FSong.flac"
        assertEquals("Song", TrackIdentity.sanitizePersistedTitle(safToken))

        org.junit.Assert.assertNull(TrackIdentity.sanitizePersistedTitle(null))
    }
}
