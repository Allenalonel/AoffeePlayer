package com.webdav.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueTest {
    private fun createTrack(
        id: String,
        title: String,
    ): AudioTrack =
        AudioTrack(
            id = id,
            serverId = 1L,
            remotePath = "/$id",
            title = title,
            format = AudioFormat.MP3,
        )

    @Test
    fun playbackMode_cycleTransitionsCorrectly() {
        assertEquals(PlaybackMode.SINGLE_LOOP, PlaybackMode.LIST_LOOP.next())
        assertEquals(PlaybackMode.SHUFFLE, PlaybackMode.SINGLE_LOOP.next())
        assertEquals(PlaybackMode.LIST_LOOP, PlaybackMode.SHUFFLE.next())
    }

    @Test
    fun queueOrigin_defaultsAndCustomOrigins() {
        val t1 = createTrack("1", "Song 1")
        val defaultQueue = PlaybackQueue(tracks = listOf(t1), currentIndex = 0)
        assertEquals(QueueOrigin.DIRECTORY, defaultQueue.origin)
        assertTrue(defaultQueue.isDirectoryQueue)
        assertFalse(defaultQueue.isPlaylistQueue)
        assertFalse(defaultQueue.isAdHocQueue)

        val playlistQueue =
            PlaybackQueue(
                tracks = listOf(t1),
                currentIndex = 0,
                origin = QueueOrigin.PLAYLIST,
            )
        assertEquals(QueueOrigin.PLAYLIST, playlistQueue.origin)
        assertTrue(playlistQueue.isPlaylistQueue)
        assertFalse(playlistQueue.isDirectoryQueue)

        val fromTracksQueue =
            PlaybackQueue.fromTracks(
                tracks = listOf(t1),
                origin = QueueOrigin.PLAYLIST,
            )
        assertEquals(QueueOrigin.PLAYLIST, fromTracksQueue.origin)
        assertTrue(fromTracksQueue.isPlaylistQueue)
    }

    @Test
    fun emptyQueue_behavior() {
        val queue = PlaybackQueue.EMPTY
        assertTrue(queue.isEmpty)
        assertEquals(0, queue.size)
        assertNull(queue.currentTrack)
        assertFalse(queue.hasNext)
        assertFalse(queue.hasPrevious)
        assertNull(queue.getNextIndex(PlaybackMode.LIST_LOOP))
        assertNull(queue.getPreviousIndex(PlaybackMode.LIST_LOOP))
    }

    @Test
    fun playTrackAt_updatesCurrentIndex() {
        val t1 = createTrack("1", "Song 1")
        val t2 = createTrack("2", "Song 2")
        val queue = PlaybackQueue(tracks = listOf(t1, t2), currentIndex = 0)

        assertEquals(t1, queue.currentTrack)
        val updated = queue.playTrackAt(1)
        assertEquals(1, updated.currentIndex)
        assertEquals(t2, updated.currentTrack)

        // Invalid index does not change queue
        val invalid = queue.playTrackAt(5)
        assertEquals(0, invalid.currentIndex)
    }

    @Test
    fun removeTrackAt_whenIndexBeforeCurrent_decrementsCurrentIndex() {
        val t1 = createTrack("1", "Song 1")
        val t2 = createTrack("2", "Song 2")
        val t3 = createTrack("3", "Song 3")
        val queue = PlaybackQueue(tracks = listOf(t1, t2, t3), currentIndex = 1) // current is Song 2

        val updated = queue.removeTrackAt(0) // remove Song 1
        assertEquals(2, updated.size)
        assertEquals(listOf(t2, t3), updated.tracks)
        assertEquals(0, updated.currentIndex)
        assertEquals(t2, updated.currentTrack) // Still Song 2
    }

    @Test
    fun removeTrackAt_whenIndexAfterCurrent_preservesCurrentIndex() {
        val t1 = createTrack("1", "Song 1")
        val t2 = createTrack("2", "Song 2")
        val t3 = createTrack("3", "Song 3")
        val queue = PlaybackQueue(tracks = listOf(t1, t2, t3), currentIndex = 1) // current is Song 2

        val updated = queue.removeTrackAt(2) // remove Song 3
        assertEquals(2, updated.size)
        assertEquals(listOf(t1, t2), updated.tracks)
        assertEquals(1, updated.currentIndex)
        assertEquals(t2, updated.currentTrack) // Still Song 2
    }

    @Test
    fun removeTrackAt_whenIndexIsCurrentAndHasNext_pointsToNextTrack() {
        val t1 = createTrack("1", "Song 1")
        val t2 = createTrack("2", "Song 2")
        val t3 = createTrack("3", "Song 3")
        val queue = PlaybackQueue(tracks = listOf(t1, t2, t3), currentIndex = 1) // current is Song 2

        val updated = queue.removeTrackAt(1) // remove current (Song 2)
        assertEquals(2, updated.size)
        assertEquals(listOf(t1, t3), updated.tracks)
        assertEquals(1, updated.currentIndex)
        assertEquals(t3, updated.currentTrack) // Points to Song 3 (was at index 2)
    }

    @Test
    fun removeTrackAt_whenIndexIsCurrentAndIsLast_pointsToNewLastTrack() {
        val t1 = createTrack("1", "Song 1")
        val t2 = createTrack("2", "Song 2")
        val queue = PlaybackQueue(tracks = listOf(t1, t2), currentIndex = 1) // current is Song 2

        val updated = queue.removeTrackAt(1) // remove Song 2
        assertEquals(1, updated.size)
        assertEquals(listOf(t1), updated.tracks)
        assertEquals(0, updated.currentIndex)
        assertEquals(t1, updated.currentTrack)
    }

    @Test
    fun removeTrackAt_whenOnlyTrackRemoved_becomesEmpty() {
        val t1 = createTrack("1", "Song 1")
        val queue = PlaybackQueue(tracks = listOf(t1), currentIndex = 0)

        val updated = queue.removeTrackAt(0)
        assertTrue(updated.isEmpty)
        assertEquals(-1, updated.currentIndex)
        assertNull(updated.currentTrack)
    }

    @Test
    fun removeTrackAt_invalidIndex_returnsOriginalQueue() {
        val t1 = createTrack("1", "Song 1")
        val queue = PlaybackQueue(tracks = listOf(t1), currentIndex = 0)

        val updated = queue.removeTrackAt(99)
        assertEquals(queue, updated)
    }

    @Test
    fun navigationIndices_listLoop() {
        val tracks = (1..3).map { createTrack("$it", "Song $it") }
        val queue = PlaybackQueue(tracks = tracks, currentIndex = 0)

        // 0 -> 1 -> 2 -> 0 (loop)
        assertEquals(1, queue.getNextIndex(PlaybackMode.LIST_LOOP))
        assertEquals(2, queue.getPreviousIndex(PlaybackMode.LIST_LOOP))

        val atLast = queue.copy(currentIndex = 2)
        assertEquals(0, atLast.getNextIndex(PlaybackMode.LIST_LOOP))
        assertEquals(1, atLast.getPreviousIndex(PlaybackMode.LIST_LOOP))
    }

    @Test
    fun navigationIndices_singleLoop() {
        val tracks = (1..3).map { createTrack("$it", "Song $it") }
        val queue = PlaybackQueue(tracks = tracks, currentIndex = 1)

        // Single loop keeps the same track
        assertEquals(1, queue.getNextIndex(PlaybackMode.SINGLE_LOOP))
        assertEquals(1, queue.getPreviousIndex(PlaybackMode.SINGLE_LOOP))
    }

    @Test
    fun toShuffled_preservesCurrentTrackAtHead_andToUnshuffledRestoresOriginalOrder() {
        val t0 = createTrack("0", "Song 0")
        val t1 = createTrack("1", "Song 1")
        val t2 = createTrack("2", "Song 2")
        val t3 = createTrack("3", "Song 3")
        val originalQueue = PlaybackQueue(tracks = listOf(t0, t1, t2, t3), currentIndex = 2)

        val shuffled = originalQueue.toShuffled()
        assertTrue(shuffled.isShuffled)
        assertEquals(0, shuffled.currentIndex)
        assertEquals(t2, shuffled.currentTrack)
        assertEquals(listOf(t0, t1, t2, t3), shuffled.originalTracks)
        assertEquals(4, shuffled.tracks.size)
        assertEquals(t2, shuffled.tracks[0])
        assertEquals(setOf(t0, t1, t2, t3), shuffled.tracks.toSet())

        // In shuffle mode, navigation is strictly linear and deterministic on the shuffled list
        assertEquals(1, shuffled.getNextIndex(PlaybackMode.SHUFFLE))
        assertEquals(3, shuffled.getPreviousIndex(PlaybackMode.SHUFFLE))

        // Restoring original order restores original tracks and maps current track index back
        val unshuffled = shuffled.toUnshuffled()
        assertFalse(unshuffled.isShuffled)
        assertEquals(listOf(t0, t1, t2, t3), unshuffled.tracks)
        assertEquals(2, unshuffled.currentIndex)
        assertEquals(t2, unshuffled.currentTrack)
    }

    @Test
    fun insertNext_intoEmptyQueue_createsSingleTrackQueue() {
        val t1 = createTrack("1", "Song 1")
        val queue = PlaybackQueue.EMPTY.insertNext(t1)

        assertEquals(1, queue.size)
        assertEquals(0, queue.currentIndex)
        assertEquals(t1, queue.currentTrack)
    }

    @Test
    fun insertNext_whenPlayingFirstTrack_insertsAtSecondPosition() {
        val t1 = createTrack("1", "Song 1")
        val t2 = createTrack("2", "Song 2")
        val tNew = createTrack("99", "New Next Song")

        val queue = PlaybackQueue(tracks = listOf(t1, t2), currentIndex = 0)
        val updated = queue.insertNext(tNew)

        assertEquals(3, updated.size)
        assertEquals(0, updated.currentIndex)
        assertEquals(t1, updated.currentTrack)
        assertEquals(listOf(t1, tNew, t2), updated.tracks)
    }

    @Test
    fun insertNext_whenPlayingLastTrack_insertsAtEnd() {
        val t1 = createTrack("1", "Song 1")
        val t2 = createTrack("2", "Song 2")
        val tNew = createTrack("99", "New Next Song")

        val queue = PlaybackQueue(tracks = listOf(t1, t2), currentIndex = 1)
        val updated = queue.insertNext(tNew)

        assertEquals(3, updated.size)
        assertEquals(1, updated.currentIndex)
        assertEquals(t2, updated.currentTrack)
        assertEquals(listOf(t1, t2, tNew), updated.tracks)
    }

    @Test
    fun insertNext_whenShuffled_updatesTracksAndOriginalTracks() {
        val t1 = createTrack("1", "Song 1")
        val t2 = createTrack("2", "Song 2")
        val tNew = createTrack("99", "New Next Song")

        val baseQueue = PlaybackQueue(tracks = listOf(t1, t2), currentIndex = 0).toShuffled()
        val updated = baseQueue.insertNext(tNew)

        assertEquals(3, updated.size)
        assertEquals(listOf(t1, tNew, t2), updated.tracks)
        assertEquals(listOf(t1, tNew, t2), updated.originalTracks)

        val unrolled = updated.toUnshuffled()
        assertEquals(listOf(t1, tNew, t2), unrolled.tracks)
        assertEquals(0, unrolled.currentIndex)
    }

    @Test
    fun folderRingPlayback_strictlyConfinedWithinCurrentFolder() {
        val folderTracks = (1..5).map { createTrack("f_$it", "Folder Track $it") }
        val queue = PlaybackQueue(tracks = folderTracks, currentIndex = 0)

        // Forward traversal: 0 -> 1 -> 2 -> 3 -> 4 -> 0 (loop back)
        var current = queue
        for (expectedIndex in listOf(1, 2, 3, 4, 0, 1)) {
            val nextIndex = current.getNextIndex(PlaybackMode.LIST_LOOP)!!
            assertEquals(expectedIndex, nextIndex)
            current = current.copy(currentIndex = nextIndex)
        }

        // Backward traversal from 0 wraps back to the end of the folder (index 4)
        val atStart = queue.copy(currentIndex = 0)
        assertEquals(4, atStart.getPreviousIndex(PlaybackMode.LIST_LOOP))

        // Single track folder strictly returns 0 and does not jump out
        val singleTrackQueue = PlaybackQueue(tracks = listOf(createTrack("solo", "Solo")), currentIndex = 0)
        assertEquals(0, singleTrackQueue.getNextIndex(PlaybackMode.LIST_LOOP))
        assertEquals(0, singleTrackQueue.getPreviousIndex(PlaybackMode.LIST_LOOP))
    }

    @Test
    fun fromTracks_buildsPhysicalQueueDirectlyAndPreservesIndices() {
        val t1 = createTrack("track_1", "Intro").copy(remotePath = "/music/01.mp3")
        val tAlbum =
            createTrack("parent_album", "Full Album").copy(
                remotePath = "/music/album.flac",
                format = AudioFormat.FLAC,
            )
        val t3 = createTrack("track_3", "Outro").copy(remotePath = "/music/03.mp3")

        val rawTracks = listOf(t1, tAlbum, t3)

        // Case 1: Clicked index 0
        val queueAt0 = PlaybackQueue.fromTracks(rawTracks, selectedIndex = 0)
        assertEquals(3, queueAt0.size)
        assertEquals(0, queueAt0.currentIndex)
        assertEquals("Intro", queueAt0.currentTrack?.title)

        // Case 2: Clicked index 1
        val queueAt1 = PlaybackQueue.fromTracks(rawTracks, selectedIndex = 1)
        assertEquals(3, queueAt1.size)
        assertEquals(1, queueAt1.currentIndex)
        assertEquals("Full Album", queueAt1.currentTrack?.title)

        // Case 3: Clicked index 2
        val queueAt2 = PlaybackQueue.fromTracks(rawTracks, selectedIndex = 2)
        assertEquals(3, queueAt2.size)
        assertEquals(2, queueAt2.currentIndex)
        assertEquals("Outro", queueAt2.currentTrack?.title)
    }
}
