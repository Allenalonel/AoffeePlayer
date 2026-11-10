package com.webdav.player.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.webdav.player.data.local.AppDatabase
import com.webdav.player.data.local.PlaylistDao
import com.webdav.player.data.local.PlaylistTrackEntity
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.InactiveReason
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.PlaylistTrackEntry
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.TrackMetadata
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.repository.TrackMetadataRepository
import com.webdav.player.domain.storage.FakeStorageReachabilityTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaylistRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: PlaylistDao
    private lateinit var repository: PlaylistRepositoryImpl
    private var simulatedTime: Long = 1_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.playlistDao()
        repository =
            PlaylistRepositoryImpl(
                playlistDao = dao,
                timeProvider = { simulatedTime },
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createPlaylist_success_andRetrieveFlow() =
        runBlocking {
            val id = repository.createPlaylist("我的车载精选")
            assertTrue(id > 0)

            val playlist = repository.getPlaylist(id)
            assertNotNull(playlist)
            assertEquals("我的车载精选", playlist?.name)
            assertEquals(0, playlist?.trackCount)
            assertEquals(1_000_000L, playlist?.createdAtMs)
            assertEquals(1_000_000L, playlist?.updatedAtMs)

            val all = repository.getAllPlaylistsFlow().first()
            assertEquals(1, all.size)
            assertEquals("我的车载精选", all[0].name)
        }

    @Test
    fun createPlaylist_emptyName_throwsException() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.createPlaylist("   ")
            }
        }
    }

    @Test
    fun createPlaylist_duplicateName_throwsException() {
        runBlocking {
            repository.createPlaylist("流行榜单")
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.createPlaylist("流行榜单")
                }
            }
        }
    }

    @Test
    fun isPlaylistNameExists_checksDuplicatesAccurately() =
        runBlocking {
            val id = repository.createPlaylist("摇滚专区")
            assertTrue(repository.isPlaylistNameExists("摇滚专区"))
            assertTrue(repository.isPlaylistNameExists("  摇滚专区  "))
            assertFalse(repository.isPlaylistNameExists("古典"))

            // When excludePlaylistId matches, it should return false
            assertFalse(repository.isPlaylistNameExists("摇滚专区", excludePlaylistId = id))
            // But if checking another playlist with same name, returns true
            assertTrue(repository.isPlaylistNameExists("摇滚专区", excludePlaylistId = 9999L))
        }

    @Test
    fun renamePlaylist_success_updatesNameAndTimestamp() =
        runBlocking {
            val id = repository.createPlaylist("旧歌单")
            simulatedTime = 2_000_000L

            repository.renamePlaylist(id, "新歌单")

            val playlist = repository.getPlaylist(id)
            assertEquals("新歌单", playlist?.name)
            assertEquals(1_000_000L, playlist?.createdAtMs)
            assertEquals(2_000_000L, playlist?.updatedAtMs)
        }

    @Test
    fun renamePlaylist_duplicateOrSelfName() {
        runBlocking {
            val id1 = repository.createPlaylist("歌单A")
            repository.createPlaylist("歌单B")

            // Renaming to self should succeed
            repository.renamePlaylist(id1, "歌单A")

            // Renaming to existing other playlist should fail
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.renamePlaylist(id1, "歌单B")
                }
            }
        }
    }

    @Test
    fun deletePlaylist_cascadesTrackDeletion() =
        runBlocking {
            val playlistId = repository.createPlaylist("待删除歌单")
            val track =
                AudioTrack(
                    id = "1:/music/a.mp3",
                    serverId = 1L,
                    remotePath = "/music/a.mp3",
                    title = "Song A",
                    format = AudioFormat.MP3,
                )
            repository.addTrackToPlaylist(playlistId, track)

            val tracksBefore = repository.getTracksForPlaylist(playlistId)
            assertEquals(1, tracksBefore.size)

            repository.deletePlaylist(playlistId)

            assertNull(repository.getPlaylist(playlistId))
            val tracksAfter = repository.getTracksForPlaylist(playlistId)
            assertEquals(0, tracksAfter.size)
        }

    @Test
    fun addTrackToPlaylist_success_andMaintainsSortOrder() =
        runBlocking {
            val playlistId = repository.createPlaylist("排序测试")
            val track1 = AudioTrack(id = "1:/1.mp3", serverId = 1L, remotePath = "/1.mp3", title = "T1", format = AudioFormat.MP3)
            val track2 = AudioTrack(id = "1:/2.mp3", serverId = 1L, remotePath = "/2.mp3", title = "T2", format = AudioFormat.MP3)

            val id1 = repository.addTrackToPlaylist(playlistId, track1)
            val id2 = repository.addTrackToPlaylist(playlistId, track2)

            assertTrue(id1 > 0)
            assertTrue(id2 > 0)

            val tracks = repository.getTracksForPlaylistFlow(playlistId).first()
            assertEquals(2, tracks.size)
            assertEquals(0, tracks[0].sortOrder)
            assertEquals("T1", tracks[0].title)
            assertEquals(1, tracks[1].sortOrder)
            assertEquals("T2", tracks[1].title)

            val playlist = repository.getPlaylist(playlistId)
            assertEquals(2, playlist?.trackCount)
        }

    @Test
    fun addTrackToPlaylist_duplicateRemotePath_returnsNegativeOneAndDoesNotInsert() =
        runBlocking {
            val playlistId = repository.createPlaylist("去重测试")
            val track1 = AudioTrack(id = "1:/same.mp3", serverId = 1L, remotePath = "/same.mp3", title = "T1", format = AudioFormat.MP3)
            val trackDuplicate =
                AudioTrack(id = "1:/same.mp3", serverId = 1L, remotePath = "/same.mp3", title = "T1 Dup", format = AudioFormat.MP3)

            val id1 = repository.addTrackToPlaylist(playlistId, track1)
            val id2 = repository.addTrackToPlaylist(playlistId, trackDuplicate)

            assertTrue(id1 > 0)
            assertEquals(-1L, id2)

            val tracks = repository.getTracksForPlaylist(playlistId)
            assertEquals(1, tracks.size)
            assertEquals("T1", tracks[0].title)
            assertEquals(1, repository.getPlaylist(playlistId)?.trackCount)
        }

    @Test
    fun addTracksToPlaylist_batchAddsUniqueTracks_andIgnoresDuplicates() =
        runBlocking {
            val playlistId = repository.createPlaylist("批量添加测试")
            val track1 = AudioTrack(id = "1:/1.mp3", serverId = 1L, remotePath = "/1.mp3", title = "T1", format = AudioFormat.MP3)
            val track2 = AudioTrack(id = "1:/2.mp3", serverId = 1L, remotePath = "/2.mp3", title = "T2", format = AudioFormat.MP3)
            val track3 = AudioTrack(id = "1:/3.mp3", serverId = 1L, remotePath = "/3.mp3", title = "T3", format = AudioFormat.MP3)

            repository.addTrackToPlaylist(playlistId, track1)

            simulatedTime = 3_000_000L
            // Adding batch containing track1 (duplicate) and track2, track3
            val insertedIds = repository.addTracksToPlaylist(playlistId, listOf(track1, track2, track3))

            assertEquals(2, insertedIds.size)
            val tracks = repository.getTracksForPlaylist(playlistId)
            assertEquals(3, tracks.size)
            assertEquals(listOf(0, 1, 2), tracks.map { it.sortOrder })
            assertEquals(listOf("T1", "T2", "T3"), tracks.map { it.title })

            val playlist = repository.getPlaylist(playlistId)
            assertEquals(3, playlist?.trackCount)
            assertEquals(3_000_000L, playlist?.updatedAtMs)
        }

    @Test
    fun addTracksToPlaylist_whenDuplicateWithinBatch_deduplicatesFirst() =
        runBlocking {
            val playlistId = repository.createPlaylist("批内重复测试")
            val track1 = AudioTrack(id = "1:/dup.mp3", serverId = 1L, remotePath = "/dup.mp3", title = "Dup 1", format = AudioFormat.MP3)
            val track1Again =
                AudioTrack(id = "1:/dup.mp3", serverId = 1L, remotePath = "/dup.mp3", title = "Dup 2", format = AudioFormat.MP3)

            val insertedIds = repository.addTracksToPlaylist(playlistId, listOf(track1, track1Again))
            assertEquals(1, insertedIds.size)

            val tracks = repository.getTracksForPlaylist(playlistId)
            assertEquals(1, tracks.size)
            assertEquals("Dup 1", tracks[0].title)
        }

    @Test
    fun removeTrackFromPlaylist_deletesTrack_andUpdatesPlaylistTimestamp() =
        runBlocking {
            val playlistId = repository.createPlaylist("单曲删除测试")
            val track1 = AudioTrack(id = "1:/1.mp3", serverId = 1L, remotePath = "/1.mp3", title = "T1", format = AudioFormat.MP3)
            val track2 = AudioTrack(id = "1:/2.mp3", serverId = 1L, remotePath = "/2.mp3", title = "T2", format = AudioFormat.MP3)

            val id1 = repository.addTrackToPlaylist(playlistId, track1)
            val id2 = repository.addTrackToPlaylist(playlistId, track2)

            simulatedTime = 4_000_000L
            repository.removeTrackFromPlaylist(id1)

            val tracks = repository.getTracksForPlaylist(playlistId)
            assertEquals(1, tracks.size)
            assertEquals(id2, tracks[0].id)
            assertEquals("T2", tracks[0].title)

            val playlist = repository.getPlaylist(playlistId)
            assertEquals(1, playlist?.trackCount)
            assertEquals(4_000_000L, playlist?.updatedAtMs)
        }

    @Test
    fun updateTrackOrders_persistsNewSortOrdersCorrectly() =
        runBlocking {
            val playlistId = repository.createPlaylist("拖拽排序测试")
            val track1 = AudioTrack(id = "1:/1.mp3", serverId = 1L, remotePath = "/1.mp3", title = "T1", format = AudioFormat.MP3)
            val track2 = AudioTrack(id = "1:/2.mp3", serverId = 1L, remotePath = "/2.mp3", title = "T2", format = AudioFormat.MP3)
            val track3 = AudioTrack(id = "1:/3.mp3", serverId = 1L, remotePath = "/3.mp3", title = "T3", format = AudioFormat.MP3)

            val id1 = repository.addTrackToPlaylist(playlistId, track1)
            val id2 = repository.addTrackToPlaylist(playlistId, track2)
            val id3 = repository.addTrackToPlaylist(playlistId, track3)

            // Reorder to: id3, id1, id2
            repository.updateTrackOrders(playlistId, listOf(id3, id1, id2))

            val tracks = repository.getTracksForPlaylist(playlistId)
            assertEquals(3, tracks.size)
            assertEquals(id3, tracks[0].id)
            assertEquals(0, tracks[0].sortOrder)
            assertEquals(id1, tracks[1].id)
            assertEquals(1, tracks[1].sortOrder)
            assertEquals(id2, tracks[2].id)
            assertEquals(2, tracks[2].sortOrder)
        }

    @Test
    fun updateTrackCover_updatesThumbnailPath() =
        runBlocking {
            val playlistId = repository.createPlaylist("封面更新测试")
            val track = AudioTrack(id = "1:/1.mp3", serverId = 1L, remotePath = "/1.mp3", title = "T1", format = AudioFormat.MP3)
            val entryId = repository.addTrackToPlaylist(playlistId, track)

            repository.updateTrackCover(entryId, "/covers/test.jpg")

            val tracks = repository.getTracksForPlaylist(playlistId)
            assertEquals("/covers/test.jpg", tracks[0].coverThumbnailPath)
        }

    @Test
    fun removeTracksFromPlaylist_batchRemovesEntriesAndUpdateCount() =
        runBlocking {
            val playlistId = repository.createPlaylist("批量移除测试")
            val track1 = AudioTrack(id = "1:/1.mp3", serverId = 1L, remotePath = "/1.mp3", title = "T1", format = AudioFormat.MP3)
            val track2 = AudioTrack(id = "1:/2.mp3", serverId = 1L, remotePath = "/2.mp3", title = "T2", format = AudioFormat.MP3)
            val track3 = AudioTrack(id = "1:/3.mp3", serverId = 1L, remotePath = "/3.mp3", title = "T3", format = AudioFormat.MP3)

            val id1 = repository.addTrackToPlaylist(playlistId, track1)
            repository.addTrackToPlaylist(playlistId, track2)
            val id3 = repository.addTrackToPlaylist(playlistId, track3)

            assertEquals(3, repository.getPlaylist(playlistId)?.trackCount)

            simulatedTime = 5_000_000L
            repository.removeTracksFromPlaylist(playlistId, listOf(id1, id3))

            val remaining = repository.getTracksForPlaylist(playlistId)
            assertEquals(1, remaining.size)
            assertEquals("T2", remaining[0].title)
            assertEquals(1, repository.getPlaylist(playlistId)?.trackCount)
            assertEquals(5_000_000L, repository.getPlaylist(playlistId)?.updatedAtMs)
        }

    @Test
    fun getTracksForPlaylistFlow_combinesWithTracker_whenTrackReportedUnavailable() =
        runBlocking {
            val tracker = FakeStorageReachabilityTracker()
            val repo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    storageReachabilityTracker = tracker,
                    timeProvider = { simulatedTime },
                )
            val playlistId = repo.createPlaylist("单曲失效响应流测试")
            val track1 = AudioTrack(id = "1:/1.mp3", serverId = 1L, remotePath = "/1.mp3", title = "T1", format = AudioFormat.MP3)
            val track2 = AudioTrack(id = "1:/2.mp3", serverId = 1L, remotePath = "/2.mp3", title = "T2", format = AudioFormat.MP3)
            repo.addTrackToPlaylist(playlistId, track1)
            repo.addTrackToPlaylist(playlistId, track2)

            // Initially both active
            val initialTracks = repo.getTracksForPlaylistFlow(playlistId).first()
            assertFalse(initialTracks[0].isInactive)
            assertFalse(initialTracks[1].isInactive)

            // Report track 1 unavailable
            tracker.reportTrackUnavailable(1L, "/1.mp3", InactiveReason.FILE_NOT_FOUND)

            val flowTracks = repo.getTracksForPlaylistFlow(playlistId).first()
            assertEquals(2, flowTracks.size)
            assertTrue(flowTracks[0].isInactive)
            assertEquals(InactiveReason.FILE_NOT_FOUND, flowTracks[0].inactiveReason)
            assertFalse(flowTracks[1].isInactive)
            assertNull(flowTracks[1].inactiveReason)

            // Suspend getTracksForPlaylist snapshot matches
            val snapshotTracks = repo.getTracksForPlaylist(playlistId)
            assertEquals(flowTracks, snapshotTracks)
        }

    @Test
    fun getTracksForPlaylistFlow_combinesWithTracker_whenServerReportedUnavailableAndReachable() =
        runBlocking {
            val tracker = FakeStorageReachabilityTracker()
            val repo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    storageReachabilityTracker = tracker,
                    timeProvider = { simulatedTime },
                )
            val playlistId = repo.createPlaylist("服务器失效与恢复响应流测试")
            val track1 = AudioTrack(id = "10:/s1.mp3", serverId = 10L, remotePath = "/s1.mp3", title = "S1", format = AudioFormat.MP3)
            val track2 = AudioTrack(id = "20:/s2.mp3", serverId = 20L, remotePath = "/s2.mp3", title = "S2", format = AudioFormat.MP3)
            val e1 = repo.addTrackToPlaylist(playlistId, track1)
            val e2 = repo.addTrackToPlaylist(playlistId, track2)

            // Report server 10 unavailable
            tracker.reportServerUnavailable(10L, InactiveReason.SERVER_UNREACHABLE)

            val flowTracks = repo.getTracksForPlaylistFlow(playlistId).first()
            val t1 = flowTracks.first { it.id == e1 }
            val t2 = flowTracks.first { it.id == e2 }
            assertTrue(t1.isInactive)
            assertEquals(InactiveReason.SERVER_UNREACHABLE, t1.inactiveReason)
            assertFalse(t2.isInactive)
            assertNull(t2.inactiveReason)

            // Report server 10 reachable
            tracker.reportServerReachable(10L)

            val restoredTracks = repo.getTracksForPlaylistFlow(playlistId).first()
            val restoredT1 = restoredTracks.first { it.id == e1 }
            assertFalse(restoredT1.isInactive)
            assertNull(restoredT1.inactiveReason)
            assertFalse(restoredTracks.first { it.id == e2 }.isInactive)

            // Parity with snapshot
            val snapshotTracks = repo.getTracksForPlaylist(playlistId)
            assertEquals(restoredTracks, snapshotTracks)
        }

    @Test
    fun coldStart_eliminatesPersistentDirtyState_whenTrackerDoesNotFlagInactive() =
        runBlocking {
            val playlistId = repository.createPlaylist("消除冷启动脏数据测试")
            val track =
                AudioTrack(id = "1:/dirty.mp3", serverId = 1L, remotePath = "/dirty.mp3", title = "Dirty Track", format = AudioFormat.MP3)
            val entryId = repository.addTrackToPlaylist(playlistId, track)

            // Simulate legacy dirty state on disk where entity is stamped inactive
            val currentEntity = dao.getTrackById(entryId)!!
            dao.updateTracks(listOf(currentEntity.copy(isInactive = true, inactiveReason = InactiveReason.SERVER_UNREACHABLE.name)))
            val entityOnDisk = dao.getTrackById(entryId)!!
            assertTrue("Disk entity is stamped inactive", entityOnDisk.isInactive)

            // New repo with fresh tracker (not flagging track or server)
            val tracker = FakeStorageReachabilityTracker()
            val newRepo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    storageReachabilityTracker = tracker,
                    timeProvider = { simulatedTime },
                )

            // Reactive flow must eliminate persistent dirty state
            val flowTracks = newRepo.getTracksForPlaylistFlow(playlistId).first()
            assertEquals(1, flowTracks.size)
            assertFalse("Track must be active despite dirty disk state", flowTracks[0].isInactive)
            assertNull("Inactive reason must be cleared", flowTracks[0].inactiveReason)

            // Suspend query also eliminates dirty state
            val snapshotTracks = newRepo.getTracksForPlaylist(playlistId)
            assertFalse("Snapshot track must be active", snapshotTracks[0].isInactive)
            assertNull("Snapshot inactive reason must be cleared", snapshotTracks[0].inactiveReason)
        }

    @Test
    fun getTracksForPlaylistFlow_withoutTracker_defaultsToActiveTracks() =
        runBlocking {
            val repoWithoutTracker =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    storageReachabilityTracker = null,
                    timeProvider = { simulatedTime },
                )
            val playlistId = repoWithoutTracker.createPlaylist("无Tracker默认在线测试")
            val track = AudioTrack(id = "1:/track.mp3", serverId = 1L, remotePath = "/track.mp3", title = "T", format = AudioFormat.MP3)
            repoWithoutTracker.addTrackToPlaylist(playlistId, track)

            val tracks = repoWithoutTracker.getTracksForPlaylistFlow(playlistId).first()
            assertEquals(1, tracks.size)
            assertFalse(tracks[0].isInactive)
            assertNull(tracks[0].inactiveReason)
        }

    private class TestTrackMetadataRepository : TrackMetadataRepository {
        val cachedMap = mutableMapOf<String, TrackMetadata>()
        val singleResolvedMap = mutableMapOf<String, TrackMetadata>()
        var singleResolveCalls = 0

        override fun getAllMetadataFlow(serverId: Long): Flow<List<TrackMetadata>> = emptyFlow()

        override fun getMetadataForPathsFlow(
            serverId: Long,
            remotePaths: List<String>,
        ): Flow<List<TrackMetadata>> = emptyFlow()

        override fun getMetadataFlow(
            serverId: Long,
            remotePath: String,
        ): Flow<TrackMetadata?> = emptyFlow()

        override suspend fun getCachedMetadata(
            serverId: Long,
            remotePath: String,
        ): TrackMetadata? = cachedMap["$serverId:$remotePath"]

        override suspend fun resolveMetadata(
            server: WebDavServer,
            files: List<RemoteFile>,
            forceRefresh: Boolean,
        ) {}

        override suspend fun resolveSingleTrackMetadata(
            server: WebDavServer,
            file: RemoteFile,
        ): TrackMetadata {
            singleResolveCalls++
            return singleResolvedMap["${server.id}:${file.path}"] ?: TrackMetadata(serverId = server.id, remotePath = file.path)
        }
    }

    @Test
    fun getTracksForPlaylistFlow_withTrackMetadataRepository_enrichesCachedCover() =
        runBlocking {
            val metadataRepo = TestTrackMetadataRepository()

            // /cached.mp3 has cached cover in Room metadata table
            metadataRepo.cachedMap["1:/cached.mp3"] =
                TrackMetadata(
                    serverId = 1L,
                    remotePath = "/cached.mp3",
                    title = "Cached Song",
                    coverThumbnailPath = "/covers/cached_thumb.jpg",
                )

            val repo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    trackMetadataRepository = metadataRepo,
                    timeProvider = { simulatedTime },
                )

            val playlistId = repo.createPlaylist("封面丰富测试")
            val trackCached =
                AudioTrack(id = "1:/cached.mp3", serverId = 1L, remotePath = "/cached.mp3", title = "Cached", format = AudioFormat.MP3)
            val trackPlain =
                AudioTrack(id = "1:/plain.mp3", serverId = 1L, remotePath = "/plain.mp3", title = "Plain", format = AudioFormat.MP3)

            val entryCachedId = repo.addTrackToPlaylist(playlistId, trackCached)
            repo.addTrackToPlaylist(playlistId, trackPlain)

            val tracks = repo.getTracksForPlaylistFlow(playlistId).first()
            assertEquals(2, tracks.size)

            val cachedTrack = tracks.first { it.remotePath == "/cached.mp3" }
            assertEquals("/covers/cached_thumb.jpg", cachedTrack.coverThumbnailPath)

            val plainTrack = tracks.first { it.remotePath == "/plain.mp3" }
            assertNull(plainTrack.coverThumbnailPath)

            // Verify the cached track cover has been updated in DB
            kotlinx.coroutines.delay(100)
            val updatedCachedEntry = dao.getTrackById(entryCachedId)
            assertEquals("/covers/cached_thumb.jpg", updatedCachedEntry?.coverThumbnailPath)

            // Verify snapshot query also observes enriched cover
            val snapshotTracks = repo.getTracksForPlaylist(playlistId)
            assertEquals("/covers/cached_thumb.jpg", snapshotTracks.first { it.remotePath == "/cached.mp3" }.coverThumbnailPath)
        }

    @Test
    fun getTracksForPlaylist_withTrackMetadataRepository_enrichesCachedCover() =
        runBlocking {
            val metadataRepo = TestTrackMetadataRepository()

            metadataRepo.cachedMap["1:/cached.mp3"] =
                TrackMetadata(
                    serverId = 1L,
                    remotePath = "/cached.mp3",
                    title = "Cached Song",
                    coverThumbnailPath = "/covers/cached_thumb.jpg",
                )

            val repo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    trackMetadataRepository = metadataRepo,
                    timeProvider = { simulatedTime },
                )

            val playlistId = repo.createPlaylist("快照封面丰富测试")
            val trackCached =
                AudioTrack(id = "1:/cached.mp3", serverId = 1L, remotePath = "/cached.mp3", title = "Cached", format = AudioFormat.MP3)

            val entryCachedId = repo.addTrackToPlaylist(playlistId, trackCached)

            val tracks = repo.getTracksForPlaylist(playlistId)
            assertEquals(1, tracks.size)
            assertEquals("/covers/cached_thumb.jpg", tracks[0].coverThumbnailPath)

            kotlinx.coroutines.delay(100)
            val updatedCachedEntry = dao.getTrackById(entryCachedId)
            assertEquals("/covers/cached_thumb.jpg", updatedCachedEntry?.coverThumbnailPath)
        }

    @Test
    fun reconcilePlaylistTracks_flowAndSnapshotParity_deduplicationFaultToleranceAndCoverHealing() =
        runBlocking {
            val tracker = FakeStorageReachabilityTracker()
            val metadataRepo = TestTrackMetadataRepository()

            metadataRepo.cachedMap["1:/cached.mp3"] =
                TrackMetadata(
                    serverId = 1L,
                    remotePath = "/cached.mp3",
                    title = "Cached Song",
                    coverThumbnailPath = "/covers/cached_thumb.jpg",
                )

            val repo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    trackMetadataRepository = metadataRepo,
                    storageReachabilityTracker = tracker,
                    timeProvider = { simulatedTime },
                )

            val playlistId = repo.createPlaylist("全流程对账一致性测试")

            val track1 =
                AudioTrack(
                    id = "1:/cached.mp3",
                    serverId = 1L,
                    remotePath = "/cached.mp3",
                    title = "T1",
                    format = AudioFormat.MP3,
                )
            val track2 =
                AudioTrack(
                    id = "2:/offline.mp3",
                    serverId = 2L,
                    remotePath = "/offline.mp3",
                    title = "T2",
                    format = AudioFormat.MP3,
                )
            val track1Duplicate =
                AudioTrack(
                    id = "1:/cached.mp3",
                    serverId = 1L,
                    remotePath = "/cached.mp3",
                    title = "T1 Dup",
                    format = AudioFormat.MP3,
                )

            repo.addTrackToPlaylist(playlistId, track1)
            repo.addTrackToPlaylist(playlistId, track2)

            // Manually insert duplicate row into DAO to simulate duplicate database rows
            dao.insertTrack(
                PlaylistTrackEntity(
                    playlistId = playlistId,
                    serverId = track1Duplicate.serverId,
                    remotePath = track1Duplicate.remotePath,
                    sortOrder = 2,
                    title = track1Duplicate.title,
                    format = "MP3",
                ),
            )

            assertEquals(3, dao.getTracksForPlaylist(playlistId).size)

            // Report server 2 unreachable via tracker
            tracker.reportServerUnavailable(2L, InactiveReason.SERVER_UNREACHABLE)

            val flowResult = repo.getTracksForPlaylistFlow(playlistId).first()
            val snapshotResult = repo.getTracksForPlaylist(playlistId)

            // Deduplication parity: both return 2 unique tracks
            assertEquals(2, flowResult.size)
            assertEquals(2, snapshotResult.size)
            assertEquals(flowResult, snapshotResult)

            // Cover enrichment parity
            assertEquals("/covers/cached_thumb.jpg", flowResult[0].coverThumbnailPath)
            assertEquals("/covers/cached_thumb.jpg", snapshotResult[0].coverThumbnailPath)

            // Fault tolerance parity: server 2 track is dimmed with SERVER_UNREACHABLE
            assertTrue(flowResult[1].isInactive)
            assertEquals(InactiveReason.SERVER_UNREACHABLE, flowResult[1].inactiveReason)
            assertTrue(snapshotResult[1].isInactive)
            assertEquals(InactiveReason.SERVER_UNREACHABLE, snapshotResult[1].inactiveReason)
        }

    @Test
    fun storingMixedWebDavAndLocalStorageTracksInSamePlaylist() =
        runBlocking {
            val repo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    timeProvider = { simulatedTime },
                )

            val playlistId = repo.createPlaylist("混合源歌单")

            val webDavTrack =
                AudioTrack(
                    id = "1:/cloud/song.mp3",
                    serverId = 1L,
                    remotePath = "/cloud/song.mp3",
                    title = "Cloud Song",
                    format = AudioFormat.MP3,
                    durationMs = 200_000L,
                )
            val localTrack =
                AudioTrack(
                    id = "0:content://media/external/audio/1234",
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://media/external/audio/1234",
                    title = "Local Lossless Song",
                    format = AudioFormat.FLAC,
                    durationMs = 300_000L,
                )

            val webDavEntryId = repo.addTrackToPlaylist(playlistId, webDavTrack)
            val localEntryId = repo.addTrackToPlaylist(playlistId, localTrack)

            assertTrue(webDavEntryId > 0)
            assertTrue(localEntryId > 0)

            val tracks = repo.getTracksForPlaylist(playlistId)
            assertEquals(2, tracks.size)

            val t1 = tracks.first { it.id == webDavEntryId }
            assertEquals(1L, t1.serverId)
            assertEquals("/cloud/song.mp3", t1.remotePath)
            assertEquals("Cloud Song", t1.title)
            assertFalse(t1.isInactive)

            val t2 = tracks.first { it.id == localEntryId }
            assertEquals(LOCAL_STORAGE_ID, t2.serverId)
            assertEquals("content://media/external/audio/1234", t2.remotePath)
            assertEquals("Local Lossless Song", t2.title)
            assertEquals(AudioFormat.FLAC, t2.format)
            assertFalse(t2.isInactive)

            // Verify toAudioTrack reconstructions
            val reconstructedLocal = t2.toAudioTrack()
            assertEquals(LOCAL_STORAGE_ID, reconstructedLocal.serverId)
            assertEquals("content://media/external/audio/1234", reconstructedLocal.remotePath)
            assertEquals("Local Lossless Song", reconstructedLocal.title)

            val reconstructedWebDav = t1.toAudioTrack()
            assertEquals(1L, reconstructedWebDav.serverId)
            assertEquals("/cloud/song.mp3", reconstructedWebDav.remotePath)
        }

    @Test
    fun deduplicationOfLocalTracksInBatchAdditions() =
        runBlocking {
            val playlistId = repository.createPlaylist("本地曲目批量去重测试")

            val local1 =
                AudioTrack(
                    id = "0:content://media/audio/1",
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://media/audio/1",
                    title = "Local 1",
                    format = AudioFormat.FLAC,
                )
            val local2 =
                AudioTrack(
                    id = "0:content://media/audio/2",
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://media/audio/2",
                    title = "Local 2",
                    format = AudioFormat.FLAC,
                )
            val remoteWithSamePath =
                AudioTrack(
                    id = "1:content://media/audio/1",
                    serverId = 1L,
                    remotePath = "content://media/audio/1",
                    title = "Remote Track with same path",
                    format = AudioFormat.MP3,
                )

            // First batch: add local1, local2, and a remote track with the same path as local1 (different serverId)
            val firstBatchResult = repository.addTracksToPlaylist(playlistId, listOf(local1, local2, remoteWithSamePath))
            assertEquals(3, firstBatchResult.size)

            val tracks = repository.getTracksForPlaylist(playlistId)
            assertEquals(3, tracks.size)
            assertEquals(LOCAL_STORAGE_ID, tracks[0].serverId)
            assertEquals("content://media/audio/1", tracks[0].remotePath)
            assertEquals(LOCAL_STORAGE_ID, tracks[1].serverId)
            assertEquals("content://media/audio/2", tracks[1].remotePath)
            assertEquals(1L, tracks[2].serverId)
            assertEquals("content://media/audio/1", tracks[2].remotePath)

            // Second batch addition of existing tracks should return empty
            val secondBatchResult = repository.addTracksToPlaylist(playlistId, listOf(local1, local2))
            assertTrue(secondBatchResult.isEmpty())
            assertEquals(3, repository.getTracksForPlaylist(playlistId).size)
        }

    @Test
    fun localTrackInPlaylist_triggersCoverSelfHealingWithLocalStorageId() =
        runBlocking {
            val metadataRepo = TestTrackMetadataRepository()
            val localUri = "content://media/external/audio/local_cover_test"
            metadataRepo.singleResolvedMap["$LOCAL_STORAGE_ID:$localUri"] =
                TrackMetadata(
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = localUri,
                    title = "Local Healed Song",
                    coverThumbnailPath = "/covers/local_healed.jpg",
                )

            val repo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    trackMetadataRepository = metadataRepo,
                    timeProvider = { simulatedTime },
                )

            val playlistId = repo.createPlaylist("本地封面自愈测试")
            val localTrack =
                AudioTrack(
                    id = "$LOCAL_STORAGE_ID:$localUri",
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = localUri,
                    title = "Local Song Without Cover",
                    format = AudioFormat.FLAC,
                )
            val entryId = repo.addTrackToPlaylist(playlistId, localTrack)

            // Trigger reconciliation which launches background cover healing for local track
            repo.getTracksForPlaylist(playlistId)
            kotlinx.coroutines.delay(200)

            val healedEntity = dao.getTrackById(entryId)
            assertEquals("/covers/local_healed.jpg", healedEntity?.coverThumbnailPath)
        }

    @Test
    fun localTrackInPlaylist_whenCoverThumbnailPathPointsToNonExistentFile_reconcilesToValidCachedMetadata() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val metadataRepo = TestTrackMetadataRepository()
            val localUri = "content://media/external/audio/melody_live"

            val validCoverFile =
                File(context.cacheDir, "cover_0_valid.jpg").apply {
                    parentFile?.mkdirs()
                    writeBytes(byteArrayOf(1, 2, 3))
                }

            metadataRepo.cachedMap["$LOCAL_STORAGE_ID:$localUri"] =
                TrackMetadata(
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = localUri,
                    title = "Melody (Live)",
                    coverThumbnailPath = validCoverFile.absolutePath,
                )

            val repo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    trackMetadataRepository = metadataRepo,
                    timeProvider = { simulatedTime },
                )

            val playlistId = repo.createPlaylist("本地测试歌单")

            // Track in playlist with a dead cover path (file does not exist on disk)
            val deadPath = File(context.cacheDir, "cover_1_dead_orphan.jpg").absolutePath
            val localTrack =
                AudioTrack(
                    id = "$LOCAL_STORAGE_ID:$localUri",
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = localUri,
                    title = "Melody (Live)",
                    coverThumbnailPath = deadPath,
                    format = AudioFormat.FLAC,
                )
            val entryId = repo.addTrackToPlaylist(playlistId, localTrack)

            // Trigger reconciliation
            val reconciledTracks = repo.getTracksForPlaylist(playlistId)
            assertEquals(1, reconciledTracks.size)

            // Should heal to the valid cached metadata path
            assertEquals(validCoverFile.absolutePath, reconciledTracks[0].coverThumbnailPath)

            kotlinx.coroutines.delay(100)
            val updatedInDb = dao.getTrackById(entryId)
            assertEquals(validCoverFile.absolutePath, updatedInDb?.coverThumbnailPath)
        }
}
