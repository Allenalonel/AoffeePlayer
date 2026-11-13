package com.webdav.player.domain.model

import com.webdav.player.domain.storage.TrackKey
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
            remotePath = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/123",
        )

        assertEquals(LOCAL_STORAGE_ID, identity.serverId)
        assertTrue(identity.isLocal)
        assertEquals(
            "local:content://com.android.externalstorage.documents/tree/primary%3AMusic/document/123",
            identity.compositeKey,
        )
        assertEquals(
            "0:content://com.android.externalstorage.documents/tree/primary%3AMusic/document/123",
            identity.trackId,
        )
    }

    @Test
    fun fileUri_isClampedToLocalStorageId() {
        val identity = TrackIdentity(
            serverId = 7L,
            remotePath = "file:///storage/emulated/0/Music/track.flac",
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
            remotePath = "/local/tree%3AMusic/Album/01.wav",
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
            remotePath = "/Music/cached_song.mp3",
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
    fun valueSemanticsAndTrackKeyAliasCompatibility() {
        val id1 = TrackIdentity(1L, "/a.mp3")
        val id2 = TrackIdentity(1L, "/a.mp3")
        val id3 = TrackIdentity(2L, "/a.mp3")
        val (serverId, path) = id1

        assertEquals(1L, serverId)
        assertEquals("/a.mp3", path)
        assertEquals(id1, id2)
        assertEquals(id1.hashCode(), id2.hashCode())
        assertNotEquals(id1, id3)

        // Compatibility alias TrackKey
        val key: TrackKey = TrackKey(1L, "/a.mp3")
        assertEquals(id1, key)
    }
}
