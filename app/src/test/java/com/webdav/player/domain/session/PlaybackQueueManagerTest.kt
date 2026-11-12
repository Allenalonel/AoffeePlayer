package com.webdav.player.domain.session

import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.PlaybackMode
import com.webdav.player.domain.model.PlaybackQueue
import com.webdav.player.domain.model.PlaylistTrackEntry
import com.webdav.player.domain.model.QueueOrigin
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.TrackMetadata
import com.webdav.player.domain.model.WebDavServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaybackQueueManagerTest {
    private lateinit var queueManager: PlaybackQueueManager

    private val testServer =
        WebDavServer(
            id = 1L,
            name = "Server1",
            url = "http://example.com",
            username = "u",
            password = "p",
        )

    private fun createTrack(
        id: String,
        durationMs: Long = 180_000L,
    ): AudioTrack =
        AudioTrack(
            id = id,
            serverId = 1L,
            remotePath = "/$id.mp3",
            title = "Track $id",
            durationMs = durationMs,
            format = AudioFormat.MP3,
        )

    private val t1 = createTrack("1")
    private val t2 = createTrack("2")
    private val t3 = createTrack("3")

    @Before
    fun setUp() {
        queueManager = PlaybackQueueManager()
    }

    @Test
    fun calculateNextAndPrevious_listLoop_cyclesThroughAllIndices() {
        val q = PlaybackQueue(listOf(t1, t2, t3), currentIndex = 0)
        assertEquals(1, queueManager.calculateNextQueueIndex(q, PlaybackMode.LIST_LOOP))

        val qEnd = PlaybackQueue(listOf(t1, t2, t3), currentIndex = 2)
        assertEquals(0, queueManager.calculateNextQueueIndex(qEnd, PlaybackMode.LIST_LOOP))

        assertEquals(2, queueManager.calculatePreviousQueueIndex(q, PlaybackMode.LIST_LOOP))
        assertEquals(1, queueManager.calculatePreviousQueueIndex(qEnd, PlaybackMode.LIST_LOOP))
    }

    @Test
    fun calculateNextAndPrevious_singleLoop_staysOnCurrentTrackWhenNoFailures() {
        val q = PlaybackQueue(listOf(t1, t2, t3), currentIndex = 1)
        assertEquals(1, queueManager.calculateNextQueueIndex(q, PlaybackMode.SINGLE_LOOP))
        assertEquals(1, queueManager.calculatePreviousQueueIndex(q, PlaybackMode.SINGLE_LOOP))
    }

    @Test
    fun calculateNext_singleLoop_advancesWhenTracksHaveFailed() {
        val q = PlaybackQueue(listOf(t1, t2, t3), currentIndex = 1)
        assertTrue(queueManager.recordFailedTrack(t2.id, q.size))

        val nextIdx = queueManager.calculateNextQueueIndex(q, PlaybackMode.SINGLE_LOOP)
        assertEquals(2, nextIdx)
    }

    @Test
    fun recordFailedTrack_exhaustsCycleWhenAllTracksFail_andSupportsClear() {
        val q = PlaybackQueue(listOf(t1, t2), currentIndex = 0)
        assertTrue(queueManager.recordFailedTrack(t1.id, q.size))
        assertTrue(queueManager.hasFailedTracks())
        assertEquals(1, queueManager.failedTrackCount)

        // Entire queue failed -> abort skipping and clear cycle tracking
        assertFalse(queueManager.recordFailedTrack(t2.id, q.size))
        assertFalse(queueManager.hasFailedTracks())
        assertEquals(0, queueManager.failedTrackCount)

        assertTrue(queueManager.recordFailedTrack(t1.id, 3))
        queueManager.clearFailedTracks()
        assertFalse(queueManager.hasFailedTracks())
    }

    @Test
    fun calculateNext_shuffle_advancesLinearlyInShuffledQueue() {
        val q = PlaybackQueue(listOf(t1, t2, t3), currentIndex = 0, originalTracks = listOf(t1, t3, t2))
        assertEquals(1, queueManager.calculateNextQueueIndex(q, PlaybackMode.SHUFFLE))
    }

    @Test
    fun advanceToNext_reshufflesAtEndOfShuffleQueue() {
        val q = PlaybackQueue(listOf(t1, t2, t3), currentIndex = 2, originalTracks = listOf(t1, t2, t3))
        val endAdvance = queueManager.advanceToNext(q, PlaybackMode.SHUFFLE)
        assertNotNull(endAdvance)
        assertEquals(0, endAdvance!!.targetIndex)
        val reshuffled = endAdvance.reshuffledQueue
        assertNotNull(reshuffled)
        assertEquals(0, reshuffled!!.currentIndex)
        assertEquals(3, reshuffled.size)

        val midQ = q.copy(currentIndex = 0)
        val midAdvance = queueManager.advanceToNext(midQ, PlaybackMode.SHUFFLE)
        assertEquals(1, midAdvance!!.targetIndex)
        assertNull(midAdvance.reshuffledQueue)

        assertNull(queueManager.advanceToNext(PlaybackQueue.EMPTY, PlaybackMode.LIST_LOOP))
    }

    @Test
    fun advanceOnTrackTransition_advancesIndexAndReturnsTargetTrack() {
        val q = PlaybackQueue(listOf(t1, t2, t3), currentIndex = 0)
        val transition = queueManager.advanceOnTrackTransition(q, PlaybackMode.LIST_LOOP)
        assertNotNull(transition)
        assertEquals(1, transition!!.queue.currentIndex)
        assertEquals(t2, transition.nextTrack)

        assertNull(queueManager.advanceOnTrackTransition(PlaybackQueue.EMPTY, PlaybackMode.LIST_LOOP))
    }

    @Test
    fun modeManagement_cycleAndSet_transformsQueueToShuffledAndRestores() {
        val initialQueue = PlaybackQueue(listOf(t1, t2, t3), currentIndex = 0)

        val shuffled = queueManager.setPlaybackMode(initialQueue, PlaybackMode.SHUFFLE)
        assertEquals(PlaybackMode.SHUFFLE, shuffled.playbackMode)
        assertTrue(shuffled.queue.isShuffled)
        assertEquals(3, shuffled.queue.originalTracks.size)

        val cycled = queueManager.cyclePlaybackMode(shuffled.queue, shuffled.playbackMode)
        assertEquals(PlaybackMode.LIST_LOOP, cycled.playbackMode)
        assertFalse(cycled.queue.isShuffled)
        assertEquals(listOf(t1, t2, t3), cycled.queue.tracks)
    }

    @Test
    fun playNext_insertsTrackImmediatelyAfterCurrent_orPlaysAsNewWhenEmpty() {
        val insert = createTrack("inserted")
        val queue = PlaybackQueue(listOf(t1, t2, t3), currentIndex = 0)

        val updated = queueManager.playNext(queue, insert)
        assertTrue(updated is PlayNextDecision.QueueUpdated)
        val q = (updated as PlayNextDecision.QueueUpdated).queue
        assertEquals(4, q.size)
        assertEquals(listOf(t1, insert, t2, t3), q.tracks)
        assertEquals(0, q.currentIndex)

        val emptyDecision = queueManager.playNext(PlaybackQueue.EMPTY, insert)
        assertEquals(PlayNextDecision.PlayAsNew(insert), emptyDecision)
    }

    @Test
    fun playNextAndAdvance_insertsAndReturnsTargetIndex_orPlaysAsNewWhenEmpty() {
        val insert = createTrack("advance")
        val queue = PlaybackQueue(listOf(t1, t2, t3), currentIndex = 0)

        val result = queueManager.playNextAndAdvance(queue, insert)
        assertTrue(result is PlayNextAndAdvanceResult.QueueUpdatedAndAdvance)
        val advance = result as PlayNextAndAdvanceResult.QueueUpdatedAndAdvance
        assertEquals(4, advance.queue.size)
        assertEquals(insert, advance.queue.tracks[1])
        assertEquals(1, advance.targetIndex)

        val emptyResult = queueManager.playNextAndAdvance(PlaybackQueue.EMPTY, insert)
        assertEquals(PlayNextAndAdvanceResult.PlayAsNew(insert), emptyResult)
    }

    @Test
    fun removeQueueTrack_handlesAllOutcomes() {
        val queue = PlaybackQueue(listOf(t1, t2, t3), currentIndex = 0)

        val outOfBounds = queueManager.removeQueueTrack(queue, 99)
        assertEquals(RemoveQueueTrackResult.Unchanged, outOfBounds)

        val nonCurrent = queueManager.removeQueueTrack(queue, 2)
        assertTrue(nonCurrent is RemoveQueueTrackResult.QueueUpdated)
        val updatedQueue = (nonCurrent as RemoveQueueTrackResult.QueueUpdated).queue
        assertEquals(listOf(t1, t2), updatedQueue.tracks)
        assertEquals(0, updatedQueue.currentIndex)

        val currentRemoved = queueManager.removeQueueTrack(queue, 0)
        assertTrue(currentRemoved is RemoveQueueTrackResult.CurrentTrackChanged)
        val changed = currentRemoved as RemoveQueueTrackResult.CurrentTrackChanged
        assertEquals(listOf(t2, t3), changed.queue.tracks)
        assertEquals(t2, changed.nextTrack)

        val single = PlaybackQueue(listOf(t1), currentIndex = 0)
        assertEquals(RemoveQueueTrackResult.Cleared, queueManager.removeQueueTrack(single, 0))
    }

    @Test
    fun shufflePermutation_remainsStableAcrossQueueProgressionAndSettingSameMode() {
        val baseQueue = PlaybackQueue(listOf(t1, t2, t3), currentIndex = 0)
        val shuffled = queueManager.setPlaybackMode(baseQueue, PlaybackMode.SHUFFLE)
        val initialShuffledTracks = shuffled.queue.tracks
        assertEquals(3, initialShuffledTracks.size)

        val nextIdx = queueManager.calculateNextQueueIndex(shuffled.queue, PlaybackMode.SHUFFLE)
        assertEquals(1, nextIdx)

        val idempotent = queueManager.setPlaybackMode(shuffled.queue, PlaybackMode.SHUFFLE)
        assertEquals(initialShuffledTracks, idempotent.queue.tracks)
    }

    @Test
    fun buildDirectoryQueue_filtersAudioAndHonorsShuffleMode() {
        val f1 = RemoteFile(name = "01.mp3", path = "/01.mp3", size = 100L)
        val cover = RemoteFile(name = "cover.jpg", path = "/cover.jpg", size = 50L)
        val f2 = RemoteFile(name = "02.flac", path = "/02.flac", size = 200L)
        val dir = RemoteDirectory(path = "/", name = "root", files = listOf(f1, cover, f2))
        val meta =
            mapOf(
                f2.path to TrackMetadata(serverId = 1L, remotePath = f2.path, title = "Track Two", durationMs = 240_000L),
            )

        val req = queueManager.buildDirectoryQueue(dir, f2, meta, testServer, PlaybackMode.SHUFFLE)
        assertNotNull(req)
        assertEquals(2, req!!.queue.size)
        assertTrue(req.queue.isShuffled)
        assertEquals(0, req.queue.currentIndex)
        assertEquals("Track Two", req.targetTrack.title)
        assertEquals(240_000L, req.targetTrack.durationMs)
    }

    @Test
    fun buildPlaylistQueue_andBuildTrackQueue_constructExpectedQueues() {
        val entries =
            listOf(
                PlaylistTrackEntry(1L, 10L, 1L, "/a.mp3", 0, "A", format = AudioFormat.MP3),
                PlaylistTrackEntry(2L, 10L, 2L, "/b.flac", 1, "B", format = AudioFormat.FLAC),
            )
        val playlistReq = queueManager.buildPlaylistQueue(entries, startIndex = 1, playbackMode = PlaybackMode.LIST_LOOP)
        assertNotNull(playlistReq)
        assertEquals(QueueOrigin.PLAYLIST, playlistReq!!.queue.origin)
        assertEquals(1, playlistReq.queue.currentIndex)
        assertEquals("B", playlistReq.targetTrack.title)

        assertNull(queueManager.buildPlaylistQueue(emptyList(), 0))

        val adHocReq = queueManager.buildTrackQueue(t1)
        assertEquals(QueueOrigin.AD_HOC, adHocReq.queue.origin)
        assertEquals(listOf(t1), adHocReq.queue.tracks)
        assertEquals(t1, adHocReq.targetTrack)
    }

    @Test
    fun updateCurrentTrackDuration_updatesOnlyCurrentTrack() {
        val q = PlaybackQueue(listOf(t1, t2), currentIndex = 1)
        val updated = queueManager.updateCurrentTrackDuration(q, 321_000L)
        assertEquals(180_000L, updated.tracks[0].durationMs)
        assertEquals(321_000L, updated.tracks[1].durationMs)
    }
}
