package com.webdav.player.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.webdav.player.data.local.AppDatabase
import com.webdav.player.data.local.PlaylistDao
import com.webdav.player.data.local.PlaylistTrackEntity
import com.webdav.player.data.remote.ConnectionResult
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.InactiveReason
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.ListDirectoryResult
import com.webdav.player.domain.model.PlaylistTrackEntry
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.TrackMetadata
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.repository.ServerRepository
import com.webdav.player.domain.repository.TrackMetadataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

            // Exclude current playlist id during rename check
            assertFalse(repository.isPlaylistNameExists("摇滚专区", excludePlaylistId = id))
        }

    @Test
    fun renamePlaylist_success_andUpdatesTimestamp() =
        runBlocking {
            val id = repository.createPlaylist("原名称")
            simulatedTime = 2_000_000L

            repository.renamePlaylist(id, "新名称")
            val updated = repository.getPlaylist(id)
            assertNotNull(updated)
            assertEquals("新名称", updated?.name)
            assertEquals(2_000_000L, updated?.updatedAtMs)
            assertEquals(1_000_000L, updated?.createdAtMs)
        }

    @Test
    fun renamePlaylist_duplicateName_throwsException() {
        runBlocking {
            repository.createPlaylist("歌单A")
            val id2 = repository.createPlaylist("歌单B")

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.renamePlaylist(id2, "歌单A")
                }
            }
        }
    }

    @Test
    fun deletePlaylist_cascadesDeleteToTracks() =
        runBlocking {
            val playlistId = repository.createPlaylist("待删除歌单")
            val track1 =
                AudioTrack(
                    id = "1:/track1.flac",
                    serverId = 1L,
                    remotePath = "/track1.flac",
                    title = "Track 1",
                    format = AudioFormat.FLAC,
                )
            repository.addTrackToPlaylist(playlistId, track1)

            val tracksBefore = repository.getTracksForPlaylist(playlistId)
            assertEquals(1, tracksBefore.size)

            repository.deletePlaylist(playlistId)
            assertNull(repository.getPlaylist(playlistId))

            val tracksAfter = repository.getTracksForPlaylist(playlistId)
            assertTrue(tracksAfter.isEmpty())
        }

    @Test
    fun addTrackToPlaylist_duplicateTrackReturnsMinusOneAndDoesNotDuplicate() =
        runBlocking {
            val playlistId = repository.createPlaylist("防重测试歌单")
            val track =
                AudioTrack(
                    id = "1:/i_do.mp3",
                    serverId = 1L,
                    remotePath = "/i_do.mp3",
                    title = "I Do",
                    artist = "911",
                    format = AudioFormat.MP3,
                )

            val firstInsertId = repository.addTrackToPlaylist(playlistId, track)
            assertTrue(firstInsertId > 0L)

            val secondInsertId = repository.addTrackToPlaylist(playlistId, track)
            assertEquals(-1L, secondInsertId)

            val tracks = repository.getTracksForPlaylist(playlistId)
            assertEquals(1, tracks.size)
            assertEquals("I Do", tracks[0].title)
        }

    @Test
    fun addTracksToPlaylist_filtersDuplicates() =
        runBlocking {
            val playlistId = repository.createPlaylist("批量防重歌单")
            val track1 = AudioTrack(id = "1:/1.mp3", serverId = 1L, remotePath = "/1.mp3", title = "T1", format = AudioFormat.MP3)
            val track2 = AudioTrack(id = "1:/2.mp3", serverId = 1L, remotePath = "/2.mp3", title = "T2", format = AudioFormat.MP3)

            repository.addTrackToPlaylist(playlistId, track1)

            // Batch adding track1 and track2: track1 is duplicate, only track2 should be inserted
            val insertedIds = repository.addTracksToPlaylist(playlistId, listOf(track1, track2))
            assertEquals(1, insertedIds.size)

            val tracks = repository.getTracksForPlaylist(playlistId)
            assertEquals(2, tracks.size)
        }

    @Test
    fun getTracksForPlaylist_selfHealsExistingDuplicates() =
        runBlocking {
            val playlistId = repository.createPlaylist("自愈重复歌单")
            val track = AudioTrack(id = "1:/i_do.mp3", serverId = 1L, remotePath = "/i_do.mp3", title = "I Do", format = AudioFormat.MP3)

            // Manually insert duplicate rows directly into DAO to simulate existing legacy duplicates
            val entity1 =
                com.webdav.player.data.local.PlaylistTrackEntity(
                    playlistId = playlistId,
                    serverId = track.serverId,
                    remotePath = track.remotePath,
                    sortOrder = 0,
                    title = track.title,
                    format = "MP3",
                )
            val entity2 =
                com.webdav.player.data.local.PlaylistTrackEntity(
                    playlistId = playlistId,
                    serverId = track.serverId,
                    remotePath = track.remotePath,
                    sortOrder = 1,
                    title = track.title,
                    format = "MP3",
                )
            database.playlistDao().insertTrack(entity1)
            database.playlistDao().insertTrack(entity2)

            // Direct DAO count has 2
            assertEquals(2, database.playlistDao().getTracksForPlaylist(playlistId).size)

            // Flow query self-heals: filters duplicate in return list
            val flowTracks = repository.getTracksForPlaylistFlow(playlistId).first()
            assertEquals(1, flowTracks.size)
            assertEquals("I Do", flowTracks[0].title)

            // Repository query self-heals: filters duplicate in return list and launches background cleanup
            val tracks = repository.getTracksForPlaylist(playlistId)
            assertEquals(1, tracks.size)
            assertEquals("I Do", tracks[0].title)
            assertEquals(flowTracks, tracks)
        }

    @Test
    fun addTrackToPlaylist_crossServerTracksPersistedCorrectly() =
        runBlocking {
            val playlistId = repository.createPlaylist("跨服务器歌单")

            // Server 1 track
            val server1Track =
                AudioTrack(
                    id = "1:/music/nas_song.flac",
                    serverId = 1L,
                    remotePath = "/music/nas_song.flac",
                    title = "NAS 歌曲",
                    artist = "NAS 歌手",
                    album = "NAS 专辑",
                    durationMs = 240000L,
                    size = 35000000L,
                    format = AudioFormat.FLAC,
                    coverThumbnailPath = "/covers/1.jpg",
                )

            // Server 2 track
            val server2Track =
                AudioTrack(
                    id = "2:/cloud/work_song.mp3",
                    serverId = 2L,
                    remotePath = "/cloud/work_song.mp3",
                    title = "办公云盘歌曲",
                    artist = "云端歌手",
                    album = "云端专辑",
                    durationMs = 180000L,
                    size = 8000000L,
                    format = AudioFormat.MP3,
                )

            val id1 = repository.addTrackToPlaylist(playlistId, server1Track)
            val id2 = repository.addTrackToPlaylist(playlistId, server2Track)
            assertTrue(id1 > 0)
            assertTrue(id2 > 0)

            val playlist = repository.getPlaylist(playlistId)
            assertEquals(2, playlist?.trackCount)

            val tracks = repository.getTracksForPlaylist(playlistId)
            assertEquals(2, tracks.size)

            val entry1 = tracks[0]
            assertEquals(1L, entry1.serverId)
            assertEquals("/music/nas_song.flac", entry1.remotePath)
            assertEquals("NAS 歌曲", entry1.title)
            assertEquals("NAS 歌手", entry1.artist)
            assertEquals("NAS 专辑", entry1.album)
            assertEquals(240000L, entry1.durationMs)
            assertEquals(35000000L, entry1.size)
            assertEquals(AudioFormat.FLAC, entry1.format)
            assertEquals(0, entry1.sortOrder)

            val entry2 = tracks[1]
            assertEquals(2L, entry2.serverId)
            assertEquals("/cloud/work_song.mp3", entry2.remotePath)
            assertEquals("办公云盘歌曲", entry2.title)
            assertEquals(AudioFormat.MP3, entry2.format)
            assertEquals(1, entry2.sortOrder)
        }

    @Test
    fun addTrackToPlaylist_physicalAudioTrackPersistedCorrectly() =
        runBlocking {
            val playlistId = repository.createPlaylist("精选歌单")

            val audioTrack =
                AudioTrack(
                    id = "1:/music/album.wav",
                    serverId = 1L,
                    remotePath = "/music/album.wav",
                    title = "交响乐第三乐章",
                    artist = "乐团",
                    album = "交响曲",
                    durationMs = 310000L,
                    size = 450000000L,
                    format = AudioFormat.WAV,
                )

            repository.addTrackToPlaylist(playlistId, audioTrack)

            val tracks = repository.getTracksForPlaylist(playlistId)
            assertEquals(1, tracks.size)

            val entry = tracks[0]
            assertEquals(1L, entry.serverId)
            assertEquals("/music/album.wav", entry.remotePath)
            assertEquals("交响乐第三乐章", entry.title)

            val reconstructedAudioTrack = entry.toAudioTrack()
            assertEquals("1:/music/album.wav", reconstructedAudioTrack.id)
            assertEquals(1L, reconstructedAudioTrack.serverId)
        }

    @Test
    fun addTracksToPlaylist_batchAdditionMaintainsSortOrder() =
        runBlocking {
            val playlistId = repository.createPlaylist("批量添加")

            val tracks =
                (1..5).map { i ->
                    AudioTrack(
                        id = "1:/track_$i.mp3",
                        serverId = 1L,
                        remotePath = "/track_$i.mp3",
                        title = "Track $i",
                        format = AudioFormat.MP3,
                    )
                }

            val ids = repository.addTracksToPlaylist(playlistId, tracks)
            assertEquals(5, ids.size)

            val stored = repository.getTracksForPlaylist(playlistId)
            assertEquals(5, stored.size)
            for (i in 0 until 5) {
                assertEquals(i, stored[i].sortOrder)
                assertEquals("Track ${i + 1}", stored[i].title)
            }
        }

    @Test
    fun removeTrackFromPlaylist_removesEntryAndUpdateCount() =
        runBlocking {
            val playlistId = repository.createPlaylist("测试移除")
            val track1 = AudioTrack(id = "1:/1.mp3", serverId = 1L, remotePath = "/1.mp3", title = "T1", format = AudioFormat.MP3)
            val track2 = AudioTrack(id = "1:/2.mp3", serverId = 1L, remotePath = "/2.mp3", title = "T2", format = AudioFormat.MP3)

            val entryId1 = repository.addTrackToPlaylist(playlistId, track1)
            repository.addTrackToPlaylist(playlistId, track2)

            assertEquals(2, repository.getPlaylist(playlistId)?.trackCount)

            repository.removeTrackFromPlaylist(entryId1)

            val remaining = repository.getTracksForPlaylist(playlistId)
            assertEquals(1, remaining.size)
            assertEquals("T2", remaining[0].title)
            assertEquals(1, repository.getPlaylist(playlistId)?.trackCount)
        }

    @Test
    fun updateTrackOrder_reordersTracksAtomically() =
        runBlocking {
            val playlistId = repository.createPlaylist("拖拽排序测试")
            val trackA = AudioTrack(id = "1:/a.mp3", serverId = 1L, remotePath = "/a.mp3", title = "A", format = AudioFormat.MP3)
            val trackB = AudioTrack(id = "1:/b.mp3", serverId = 1L, remotePath = "/b.mp3", title = "B", format = AudioFormat.MP3)
            val trackC = AudioTrack(id = "1:/c.mp3", serverId = 1L, remotePath = "/c.mp3", title = "C", format = AudioFormat.MP3)

            repository.addTrackToPlaylist(playlistId, trackA)
            repository.addTrackToPlaylist(playlistId, trackB)
            repository.addTrackToPlaylist(playlistId, trackC)

            val initial = repository.getTracksForPlaylist(playlistId)
            // Reverse order: C, B, A
            val reversed = listOf(initial[2], initial[1], initial[0])

            simulatedTime = 3_000_000L
            repository.updateTrackOrders(playlistId, reversed.map { it.id })

            val updated = repository.getTracksForPlaylist(playlistId)
            assertEquals(3, updated.size)
            assertEquals("C", updated[0].title)
            assertEquals(0, updated[0].sortOrder)
            assertEquals("B", updated[1].title)
            assertEquals(1, updated[1].sortOrder)
            assertEquals("A", updated[2].title)
            assertEquals(2, updated[2].sortOrder)

            assertEquals(3_000_000L, repository.getPlaylist(playlistId)?.updatedAtMs)
        }

    @Test
    fun updateTrackOrders_reordersUsingIdsList() =
        runBlocking {
            val playlistId = repository.createPlaylist("ID列表排序测试")
            val track1 = AudioTrack(id = "1:/1.mp3", serverId = 1L, remotePath = "/1.mp3", title = "T1", format = AudioFormat.MP3)
            val track2 = AudioTrack(id = "1:/2.mp3", serverId = 1L, remotePath = "/2.mp3", title = "T2", format = AudioFormat.MP3)
            val track3 = AudioTrack(id = "1:/3.mp3", serverId = 1L, remotePath = "/3.mp3", title = "T3", format = AudioFormat.MP3)

            val id1 = repository.addTrackToPlaylist(playlistId, track1)
            val id2 = repository.addTrackToPlaylist(playlistId, track2)
            val id3 = repository.addTrackToPlaylist(playlistId, track3)

            simulatedTime = 4_000_000L
            repository.updateTrackOrders(playlistId, listOf(id3, id1, id2))

            val updated = repository.getTracksForPlaylist(playlistId)
            assertEquals(3, updated.size)
            assertEquals("T3", updated[0].title)
            assertEquals(0, updated[0].sortOrder)
            assertEquals("T1", updated[1].title)
            assertEquals(1, updated[1].sortOrder)
            assertEquals("T2", updated[2].title)
            assertEquals(2, updated[2].sortOrder)

            assertEquals(4_000_000L, repository.getPlaylist(playlistId)?.updatedAtMs)
        }

    @Test
    fun removeTrackFromPlaylist_byIdOnly_removesEntryAndUpdateCount() =
        runBlocking {
            val playlistId = repository.createPlaylist("单ID移除测试")
            val track1 = AudioTrack(id = "1:/1.mp3", serverId = 1L, remotePath = "/1.mp3", title = "T1", format = AudioFormat.MP3)
            val track2 = AudioTrack(id = "1:/2.mp3", serverId = 1L, remotePath = "/2.mp3", title = "T2", format = AudioFormat.MP3)

            val entryId1 = repository.addTrackToPlaylist(playlistId, track1)
            repository.addTrackToPlaylist(playlistId, track2)
            assertEquals(2, repository.getPlaylist(playlistId)?.trackCount)

            simulatedTime = 5_000_000L
            repository.removeTrackFromPlaylist(entryId1)

            val remaining = repository.getTracksForPlaylist(playlistId)
            assertEquals(1, remaining.size)
            assertEquals("T2", remaining[0].title)
            assertEquals(1, repository.getPlaylist(playlistId)?.trackCount)
            assertEquals(5_000_000L, repository.getPlaylist(playlistId)?.updatedAtMs)
        }

    @Test
    fun markTrackInactive_updatesTracksFlowWithInactiveReason() =
        runBlocking {
            val playlistId = repository.createPlaylist("失效标记测试")
            val track1 = AudioTrack(id = "1:/1.mp3", serverId = 1L, remotePath = "/1.mp3", title = "T1", format = AudioFormat.MP3)
            val track2 = AudioTrack(id = "1:/2.mp3", serverId = 1L, remotePath = "/2.mp3", title = "T2", format = AudioFormat.MP3)

            repository.addTrackToPlaylist(playlistId, track1)
            repository.addTrackToPlaylist(playlistId, track2)

            repository.markTrackInactive(1L, "/1.mp3", InactiveReason.FILE_NOT_FOUND)

            val tracks = repository.getTracksForPlaylistFlow(playlistId).first()
            assertEquals(2, tracks.size)
            assertTrue(tracks[0].isInactive)
            assertEquals(InactiveReason.FILE_NOT_FOUND, tracks[0].inactiveReason)
            assertFalse(tracks[1].isInactive)
            assertNull(tracks[1].inactiveReason)

            val snapshotTracks = repository.getTracksForPlaylist(playlistId)
            assertEquals(tracks, snapshotTracks)
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

            repository.removeTracksFromPlaylist(playlistId, listOf(id1, id3))

            val remaining = repository.getTracksForPlaylist(playlistId)
            assertEquals(1, remaining.size)
            assertEquals("T2", remaining[0].title)
            assertEquals(1, repository.getPlaylist(playlistId)?.trackCount)
        }

    @Test
    fun markTrackInactive_persistsToDatabaseAndSurvivesRestart() =
        runBlocking {
            val playlistId = repository.createPlaylist("持久化失效测试")
            val track1 = AudioTrack(id = "1:/1.mp3", serverId = 1L, remotePath = "/1.mp3", title = "T1", format = AudioFormat.MP3)
            repository.addTrackToPlaylist(playlistId, track1)

            repository.markTrackInactive(1L, "/1.mp3", InactiveReason.FILE_NOT_FOUND)
            kotlinx.coroutines.delay(100)

            val newRepo = PlaylistRepositoryImpl(database.playlistDao(), timeProvider = { simulatedTime })
            val tracks = newRepo.getTracksForPlaylist(playlistId)
            assertEquals(1, tracks.size)
            assertTrue(tracks[0].isInactive)
            assertEquals(InactiveReason.FILE_NOT_FOUND, tracks[0].inactiveReason)
        }

    private class TestServerRepository(
        initialServers: List<WebDavServer> = emptyList(),
    ) : ServerRepository {
        val serversFlow = MutableStateFlow(initialServers)

        override fun getAllServers(): Flow<List<WebDavServer>> = serversFlow.asStateFlow()

        override fun getActiveServer(): Flow<WebDavServer?> = serversFlow.map { it.firstOrNull() }

        override suspend fun getServerById(id: Long): WebDavServer? = serversFlow.value.firstOrNull { it.id == id }

        override suspend fun saveServer(server: WebDavServer): Long = server.id

        override suspend fun deleteServer(id: Long) {
            serversFlow.update { list -> list.filterNot { it.id == id } }
        }

        override suspend fun setActiveServer(id: Long) {}
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
    fun getTracksForPlaylistFlow_withServerRepository_marksUnreachableServersInactive() =
        runBlocking {
            val server1 = WebDavServer(id = 1L, name = "Active NAS", url = "http://192.168.1.100")
            val serverRepo = TestServerRepository(listOf(server1))

            val repoWithServer =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    serverRepository = serverRepo,
                    timeProvider = { simulatedTime },
                )

            val playlistId = repoWithServer.createPlaylist("跨服连通性测试")
            val track1 =
                AudioTrack(id = "1:/online.mp3", serverId = 1L, remotePath = "/online.mp3", title = "Online", format = AudioFormat.MP3)
            val track2 =
                AudioTrack(
                    id = "2:/offline.mp3",
                    serverId = 2L,
                    remotePath = "/offline.mp3",
                    title = "Offline Server",
                    format = AudioFormat.MP3,
                )

            repoWithServer.addTrackToPlaylist(playlistId, track1)
            repoWithServer.addTrackToPlaylist(playlistId, track2)

            val tracks = repoWithServer.getTracksForPlaylistFlow(playlistId).first()
            assertEquals(2, tracks.size)

            val online = tracks.first { it.remotePath == "/online.mp3" }
            assertFalse(online.isInactive)
            assertNull(online.inactiveReason)

            val offline = tracks.first { it.remotePath == "/offline.mp3" }
            assertTrue(offline.isInactive)
            assertEquals(InactiveReason.SERVER_UNREACHABLE, offline.inactiveReason)

            // Also verify suspend getTracksForPlaylist
            val snapshotTracks = repoWithServer.getTracksForPlaylist(playlistId)
            val snapshotOffline = snapshotTracks.first { it.remotePath == "/offline.mp3" }
            assertTrue(snapshotOffline.isInactive)
            assertEquals(InactiveReason.SERVER_UNREACHABLE, snapshotOffline.inactiveReason)
        }

    @Test
    fun getTracksForPlaylistFlow_withTrackMetadataRepository_enrichesCachedCoverAndTriggersSelfHealing() =
        runBlocking {
            val server1 = WebDavServer(id = 1L, name = "Active NAS", url = "http://192.168.1.100")
            val serverRepo = TestServerRepository(listOf(server1))
            val metadataRepo = TestTrackMetadataRepository()

            // /cached.mp3 has cached cover in Room metadata table
            metadataRepo.cachedMap["1:/cached.mp3"] =
                TrackMetadata(
                    serverId = 1L,
                    remotePath = "/cached.mp3",
                    title = "Cached Song",
                    coverThumbnailPath = "/covers/cached_thumb.jpg",
                )

            // /resolve.mp3 needs on-demand resolve
            metadataRepo.singleResolvedMap["1:/resolve.mp3"] =
                TrackMetadata(
                    serverId = 1L,
                    remotePath = "/resolve.mp3",
                    title = "Resolved Song",
                    coverThumbnailPath = "/covers/resolved_thumb.jpg",
                )

            val repo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    serverRepository = serverRepo,
                    trackMetadataRepository = metadataRepo,
                    timeProvider = { simulatedTime },
                )

            val playlistId = repo.createPlaylist("自愈测试")
            val trackCached =
                AudioTrack(id = "1:/cached.mp3", serverId = 1L, remotePath = "/cached.mp3", title = "Cached", format = AudioFormat.MP3)
            val trackResolve =
                AudioTrack(id = "1:/resolve.mp3", serverId = 1L, remotePath = "/resolve.mp3", title = "Resolve", format = AudioFormat.MP3)

            val entryCachedId = repo.addTrackToPlaylist(playlistId, trackCached)
            val entryResolveId = repo.addTrackToPlaylist(playlistId, trackResolve)

            val tracks = repo.getTracksForPlaylistFlow(playlistId).first()
            assertEquals(2, tracks.size)

            val cachedTrack = tracks.first { it.remotePath == "/cached.mp3" }
            assertEquals("/covers/cached_thumb.jpg", cachedTrack.coverThumbnailPath)

            // Wait for background self-healing coroutine to complete
            kotlinx.coroutines.delay(200)

            // Verify the resolved track has been healed and saved to DB
            val updatedResolveEntry = dao.getTrackById(entryResolveId)
            assertEquals("/covers/resolved_thumb.jpg", updatedResolveEntry?.coverThumbnailPath)

            val updatedCachedEntry = dao.getTrackById(entryCachedId)
            assertEquals("/covers/cached_thumb.jpg", updatedCachedEntry?.coverThumbnailPath)

            // Verify snapshot query also observes healed covers
            val snapshotTracks = repo.getTracksForPlaylist(playlistId)
            assertEquals("/covers/cached_thumb.jpg", snapshotTracks.first { it.remotePath == "/cached.mp3" }.coverThumbnailPath)
            assertEquals("/covers/resolved_thumb.jpg", snapshotTracks.first { it.remotePath == "/resolve.mp3" }.coverThumbnailPath)
        }

    @Test
    fun getTracksForPlaylist_withTrackMetadataRepository_enrichesCachedCoverAndTriggersSelfHealing() =
        runBlocking {
            val server1 = WebDavServer(id = 1L, name = "Active NAS", url = "http://192.168.1.100")
            val serverRepo = TestServerRepository(listOf(server1))
            val metadataRepo = TestTrackMetadataRepository()

            metadataRepo.cachedMap["1:/cached.mp3"] =
                TrackMetadata(
                    serverId = 1L,
                    remotePath = "/cached.mp3",
                    title = "Cached Song",
                    coverThumbnailPath = "/covers/cached_thumb.jpg",
                )

            metadataRepo.singleResolvedMap["1:/resolve.mp3"] =
                TrackMetadata(
                    serverId = 1L,
                    remotePath = "/resolve.mp3",
                    title = "Resolved Song",
                    coverThumbnailPath = "/covers/resolved_thumb.jpg",
                )

            val repo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    serverRepository = serverRepo,
                    trackMetadataRepository = metadataRepo,
                    timeProvider = { simulatedTime },
                )

            val playlistId = repo.createPlaylist("快照自愈测试")
            val trackCached =
                AudioTrack(id = "1:/cached.mp3", serverId = 1L, remotePath = "/cached.mp3", title = "Cached", format = AudioFormat.MP3)
            val trackResolve =
                AudioTrack(id = "1:/resolve.mp3", serverId = 1L, remotePath = "/resolve.mp3", title = "Resolve", format = AudioFormat.MP3)

            val entryCachedId = repo.addTrackToPlaylist(playlistId, trackCached)
            val entryResolveId = repo.addTrackToPlaylist(playlistId, trackResolve)

            val tracks = repo.getTracksForPlaylist(playlistId)
            assertEquals(2, tracks.size)

            val cachedTrack = tracks.first { it.remotePath == "/cached.mp3" }
            assertEquals("/covers/cached_thumb.jpg", cachedTrack.coverThumbnailPath)

            // Wait for background self-healing coroutine to complete
            kotlinx.coroutines.delay(200)

            // Verify the resolved track has been healed and saved to DB
            val updatedResolveEntry = dao.getTrackById(entryResolveId)
            assertEquals("/covers/resolved_thumb.jpg", updatedResolveEntry?.coverThumbnailPath)

            val updatedCachedEntry = dao.getTrackById(entryCachedId)
            assertEquals("/covers/cached_thumb.jpg", updatedCachedEntry?.coverThumbnailPath)
        }

    private class FakeProbeWebDavClient : WebDavClient {
        val testConnectionResults = mutableMapOf<Long, ConnectionResult>()
        var testConnectionCalls = 0

        override suspend fun testConnection(server: WebDavServer): ConnectionResult {
            testConnectionCalls++
            return testConnectionResults[server.id] ?: ConnectionResult.Success
        }

        override suspend fun listDirectory(
            server: WebDavServer,
            path: String,
        ): ListDirectoryResult = ListDirectoryResult.Success(RemoteDirectory(path = path, name = "root", files = emptyList()))

        override suspend fun fetchRange(
            server: WebDavServer,
            remotePath: String,
            startByte: Long,
            endByte: Long,
        ): ByteArray? = null
    }

    @Test
    fun playlistDao_batchQueriesAndUpdates_forUnreachableServerStatus() =
        runBlocking {
            val playlistId = repository.createPlaylist("DAO批处理测试")
            val t1 = AudioTrack(id = "10:/s1.mp3", serverId = 10L, remotePath = "/s1.mp3", title = "S1", format = AudioFormat.MP3)
            val t2 = AudioTrack(id = "20:/s2.mp3", serverId = 20L, remotePath = "/s2.mp3", title = "S2", format = AudioFormat.MP3)
            val t3 = AudioTrack(id = "30:/s3.mp3", serverId = 30L, remotePath = "/s3.mp3", title = "S3", format = AudioFormat.MP3)

            repository.addTrackToPlaylist(playlistId, t1)
            repository.addTrackToPlaylist(playlistId, t2)
            repository.addTrackToPlaylist(playlistId, t3)

            dao.updateTrackInactiveStatus(10L, "/s1.mp3", true, InactiveReason.SERVER_UNREACHABLE.name)
            dao.updateTrackInactiveStatus(20L, "/s2.mp3", true, InactiveReason.SERVER_UNREACHABLE.name)
            dao.updateTrackInactiveStatus(30L, "/s3.mp3", true, InactiveReason.FILE_NOT_FOUND.name)

            val unreachableServerIds = dao.getDistinctServerIdsForInactiveReason(InactiveReason.SERVER_UNREACHABLE.name)
            assertEquals(2, unreachableServerIds.size)
            assertTrue(unreachableServerIds.contains(10L))
            assertTrue(unreachableServerIds.contains(20L))
            assertFalse(unreachableServerIds.contains(30L))

            // Clear server 10
            val cleared = dao.clearServerUnreachableStatus(10L, InactiveReason.SERVER_UNREACHABLE.name)
            assertEquals(1, cleared)

            val remainingUnreachable = dao.getDistinctServerIdsForInactiveReason(InactiveReason.SERVER_UNREACHABLE.name)
            assertEquals(listOf(20L), remainingUnreachable)

            val track1FromDb = dao.findTrackInPlaylist(playlistId, 10L, "/s1.mp3")
            assertNotNull(track1FromDb)
            assertFalse(track1FromDb!!.isInactive)
            assertNull(track1FromDb.inactiveReason)
        }

    @Test
    fun triggerReachabilitySelfHealing_restoresDimmedTracksForSuccessfulServers() =
        runBlocking {
            val server1 = WebDavServer(id = 1L, name = "Online NAS", url = "http://192.168.1.100")
            val server2 = WebDavServer(id = 2L, name = "Offline NAS", url = "http://192.168.1.200")
            val serverRepo = TestServerRepository(listOf(server1, server2))

            val fakeWebDavClient = FakeProbeWebDavClient()
            fakeWebDavClient.testConnectionResults[1L] = ConnectionResult.Success
            fakeWebDavClient.testConnectionResults[2L] = ConnectionResult.Failure("Connection refused")

            val healingRepo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    serverRepository = serverRepo,
                    webDavClient = fakeWebDavClient,
                    timeProvider = { simulatedTime },
                )

            val playlistId = healingRepo.createPlaylist("自愈恢复测试")
            val track1 =
                AudioTrack(id = "1:/online.mp3", serverId = 1L, remotePath = "/online.mp3", title = "Online", format = AudioFormat.MP3)
            val track2 =
                AudioTrack(id = "2:/offline.mp3", serverId = 2L, remotePath = "/offline.mp3", title = "Offline", format = AudioFormat.MP3)

            val e1 = healingRepo.addTrackToPlaylist(playlistId, track1)
            val e2 = healingRepo.addTrackToPlaylist(playlistId, track2)

            // Mark both as SERVER_UNREACHABLE
            healingRepo.markTrackInactive(1L, "/online.mp3", InactiveReason.SERVER_UNREACHABLE)
            healingRepo.markTrackInactive(2L, "/offline.mp3", InactiveReason.SERVER_UNREACHABLE)

            // Verify both tracks are currently dimmed
            val beforeTracks = healingRepo.getTracksForPlaylistFlow(playlistId).first()
            assertTrue(beforeTracks.first { it.id == e1 }.isInactive)
            assertTrue(beforeTracks.first { it.id == e2 }.isInactive)

            // Trigger self-healing
            healingRepo.triggerReachabilitySelfHealing()

            // Verify after healing: Server 1 track restored, Server 2 track remains dimmed
            val afterTracks = healingRepo.getTracksForPlaylistFlow(playlistId).first()
            val restoredTrack1 = afterTracks.first { it.id == e1 }
            assertFalse("Track 1 on Online NAS must be restored", restoredTrack1.isInactive)
            assertNull("Track 1 inactiveReason must be cleared", restoredTrack1.inactiveReason)

            val dimmedTrack2 = afterTracks.first { it.id == e2 }
            assertTrue("Track 2 on Offline NAS must remain inactive", dimmedTrack2.isInactive)
            assertEquals(InactiveReason.SERVER_UNREACHABLE, dimmedTrack2.inactiveReason)

            // Verify DB entity state for track 1
            val dbTrack1 = dao.getTrackById(e1)
            assertNotNull(dbTrack1)
            assertFalse(dbTrack1!!.isInactive)
            assertNull(dbTrack1.inactiveReason)
        }

    @Test
    fun markServerInactive_dimsAllTracksUnderGivenServer_andClearServerUnreachableRestoresThem() =
        runBlocking {
            val playlistId = repository.createPlaylist("整机不可达与恢复测试")
            val track1 = AudioTrack(id = "99:/t1.mp3", serverId = 99L, remotePath = "/t1.mp3", title = "T1", format = AudioFormat.MP3)
            val track2 = AudioTrack(id = "99:/t2.mp3", serverId = 99L, remotePath = "/t2.mp3", title = "T2", format = AudioFormat.MP3)
            val trackOther =
                AudioTrack(id = "88:/other.mp3", serverId = 88L, remotePath = "/other.mp3", title = "Other", format = AudioFormat.MP3)

            val e1 = repository.addTrackToPlaylist(playlistId, track1)
            val e2 = repository.addTrackToPlaylist(playlistId, track2)
            val eOther = repository.addTrackToPlaylist(playlistId, trackOther)

            // Mark server 99 inactive
            repository.markServerInactive(99L, InactiveReason.SERVER_UNREACHABLE)

            // Allow async DB write to complete
            kotlinx.coroutines.delay(100)

            val flowTracks = repository.getTracksForPlaylistFlow(playlistId).first()
            val snapshotTracks = repository.getTracksForPlaylist(playlistId)
            assertEquals(flowTracks, snapshotTracks)

            val t1Entry = flowTracks.first { it.id == e1 }
            val t2Entry = flowTracks.first { it.id == e2 }
            val otherEntry = flowTracks.first { it.id == eOther }

            assertTrue("Track 1 must be inactive", t1Entry.isInactive)
            assertEquals(InactiveReason.SERVER_UNREACHABLE, t1Entry.inactiveReason)
            assertTrue("Track 2 must be inactive", t2Entry.isInactive)
            assertEquals(InactiveReason.SERVER_UNREACHABLE, t2Entry.inactiveReason)
            assertFalse("Other server track must not be inactive", otherEntry.isInactive)

            // Now clear server 99
            repository.clearServerUnreachableStatus(99L)

            val restoredTracks = repository.getTracksForPlaylistFlow(playlistId).first()
            val restoredSnapshot = repository.getTracksForPlaylist(playlistId)
            assertEquals(restoredTracks, restoredSnapshot)

            assertFalse("Track 1 must be restored", restoredTracks.first { it.id == e1 }.isInactive)
            assertFalse("Track 2 must be restored", restoredTracks.first { it.id == e2 }.isInactive)
        }

    @Test
    fun triggerReachabilitySelfHealing_restoresServerCredentialInvalidTracks() =
        runBlocking {
            val server1 = WebDavServer(id = 1L, name = "Online NAS", url = "http://192.168.1.100")
            val serverRepo = TestServerRepository(listOf(server1))

            val fakeWebDavClient = FakeProbeWebDavClient()
            fakeWebDavClient.testConnectionResults[1L] = ConnectionResult.Success

            val healingRepo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    serverRepository = serverRepo,
                    webDavClient = fakeWebDavClient,
                    timeProvider = { simulatedTime },
                )

            val playlistId = healingRepo.createPlaylist("凭据自愈恢复测试")
            val track1 =
                AudioTrack(id = "1:/auth.mp3", serverId = 1L, remotePath = "/auth.mp3", title = "Auth Track", format = AudioFormat.MP3)
            val e1 = healingRepo.addTrackToPlaylist(playlistId, track1)

            healingRepo.markTrackInactive(1L, "/auth.mp3", InactiveReason.SERVER_CREDENTIAL_INVALID)

            val beforeTracks = healingRepo.getTracksForPlaylistFlow(playlistId).first()
            assertTrue(beforeTracks.first { it.id == e1 }.isInactive)
            assertEquals(InactiveReason.SERVER_CREDENTIAL_INVALID, beforeTracks.first { it.id == e1 }.inactiveReason)

            healingRepo.triggerReachabilitySelfHealing()

            val afterTracks = healingRepo.getTracksForPlaylistFlow(playlistId).first()
            val restoredTrack1 = afterTracks.first { it.id == e1 }
            assertFalse("Track 1 must be restored", restoredTrack1.isInactive)
            assertNull("Track 1 inactiveReason must be cleared", restoredTrack1.inactiveReason)
        }

    @Test
    fun reconcilePlaylistTracks_flowAndSnapshotParity_deduplicationFaultToleranceAndCoverHealing() =
        runBlocking {
            val server1 = WebDavServer(id = 1L, name = "Active NAS", url = "http://192.168.1.100")
            val serverRepo = TestServerRepository(listOf(server1))
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
                    serverRepository = serverRepo,
                    trackMetadataRepository = metadataRepo,
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
                com.webdav.player.data.local.PlaylistTrackEntity(
                    playlistId = playlistId,
                    serverId = track1Duplicate.serverId,
                    remotePath = track1Duplicate.remotePath,
                    sortOrder = 2,
                    title = track1Duplicate.title,
                    format = "MP3",
                ),
            )

            assertEquals(3, dao.getTracksForPlaylist(playlistId).size)

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
            val server1 = WebDavServer(id = 1L, name = "Cloud Server", url = "http://192.168.1.100")
            val serverRepo = TestServerRepository(listOf(server1))

            val repo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    serverRepository = serverRepo,
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
            val playlistId = repository.createPlaylist("本地去重测试")

            val local1 =
                AudioTrack(
                    id = "0:content://media/audio/1",
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://media/audio/1",
                    title = "Local 1",
                    format = AudioFormat.MP3,
                )
            val local2 =
                AudioTrack(
                    id = "0:content://media/audio/2",
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://media/audio/2",
                    title = "Local 2",
                    format = AudioFormat.MP3,
                )
            val local1Dup =
                AudioTrack(
                    id = "0:content://media/audio/1",
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://media/audio/1",
                    title = "Local 1 Dup",
                    format = AudioFormat.MP3,
                )
            val remoteWithSamePath =
                AudioTrack(
                    id = "1:content://media/audio/1",
                    serverId = 1L,
                    remotePath = "content://media/audio/1",
                    title = "Remote With Matching Path String",
                    format = AudioFormat.MP3,
                )

            // Batch add containing 2 unique local tracks, 1 local duplicate, and 1 remote track with identical remotePath string
            val insertedIds =
                repository.addTracksToPlaylist(
                    playlistId,
                    listOf(local1, local2, local1Dup, remoteWithSamePath),
                )

            // Exactly 3 tracks inserted (local1, local2, remoteWithSamePath; local1Dup skipped)
            assertEquals(3, insertedIds.size)

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
    fun localTrackReachabilityValidation_marksFileNotFound_whenLocalUriIsInaccessible() =
        runBlocking {
            val accessibleUris = mutableSetOf("content://media/audio/present")
            val testRepo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    localUriReachabilityChecker = { path -> accessibleUris.contains(path) },
                    timeProvider = { simulatedTime },
                )

            val playlistId = testRepo.createPlaylist("可达性校验测试")
            val localMissingTrack =
                AudioTrack(
                    id = "0:content://media/audio/deleted",
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://media/audio/deleted",
                    title = "Deleted Song",
                    format = AudioFormat.MP3,
                )

            val entryId = testRepo.addTrackToPlaylist(playlistId, localMissingTrack)
            val entityBefore = dao.getTrackById(entryId)!!
            assertFalse("Track initially active before reachability probe", entityBefore.isInactive)

            // Validate reachability on inaccessible local track
            val isReachable = testRepo.checkTrackReachability(entityBefore)
            assertFalse("Reachability check must report false for inaccessible local URI", isReachable)

            // DB entity and snapshot must reflect inactive state with FILE_NOT_FOUND
            val entityAfter = dao.getTrackById(entryId)!!
            assertTrue("Entity in DB must be marked inactive", entityAfter.isInactive)
            assertEquals("Entity inactiveReason must be FILE_NOT_FOUND", InactiveReason.FILE_NOT_FOUND.name, entityAfter.inactiveReason)

            val tracks = testRepo.getTracksForPlaylist(playlistId)
            val trackEntry = tracks.first { it.id == entryId }
            assertTrue("Snapshot track must be inactive", trackEntry.isInactive)
            assertEquals(InactiveReason.FILE_NOT_FOUND, trackEntry.inactiveReason)
        }

    @Test
    fun reachabilitySelfHealing_restoresLocalTrackToActive_whenUriBecomesAccessibleAgain() =
        runBlocking {
            val accessibleUris = mutableSetOf<String>() // SD card unmounted: empty
            val testRepo =
                PlaylistRepositoryImpl(
                    playlistDao = dao,
                    localUriReachabilityChecker = { path -> accessibleUris.contains(path) },
                    timeProvider = { simulatedTime },
                )

            val playlistId = testRepo.createPlaylist("自愈恢复测试-本地")
            val localTrack =
                AudioTrack(
                    id = "0:content://sdcard/song.flac",
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://sdcard/song.flac",
                    title = "SD Card Song",
                    format = AudioFormat.FLAC,
                )

            val entryId = testRepo.addTrackToPlaylist(playlistId, localTrack)

            // Mark inactive due to SD card being removed
            testRepo.markTrackInactive(LOCAL_STORAGE_ID, "content://sdcard/song.flac", InactiveReason.FILE_NOT_FOUND)
            kotlinx.coroutines.delay(100)

            // Ensure DB and repo reflect inactive status
            val entityDimmed = dao.getTrackById(entryId)!!
            assertTrue(entityDimmed.isInactive)
            assertEquals(InactiveReason.FILE_NOT_FOUND.name, entityDimmed.inactiveReason)

            val dimmedTracks = testRepo.getTracksForPlaylistFlow(playlistId).first()
            assertTrue(dimmedTracks.first { it.id == entryId }.isInactive)
            assertEquals(InactiveReason.FILE_NOT_FOUND, dimmedTracks.first { it.id == entryId }.inactiveReason)

            // SD card reinserted: URI becomes accessible again
            accessibleUris.add("content://sdcard/song.flac")

            // Trigger reachability self-healing
            testRepo.triggerReachabilitySelfHealing()

            // Verify symmetric self-healing: local track restored to active playable state
            val entityRestored = dao.getTrackById(entryId)!!
            assertFalse("Local track must be restored to active in DB", entityRestored.isInactive)
            assertNull("Inactive reason must be cleared in DB", entityRestored.inactiveReason)

            val restoredTracks = testRepo.getTracksForPlaylistFlow(playlistId).first()
            val restoredTrackEntry = restoredTracks.first { it.id == entryId }
            assertFalse("Track in flow must be active", restoredTrackEntry.isInactive)
            assertNull("Inactive reason in flow must be null", restoredTrackEntry.inactiveReason)
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
                    format = AudioFormat.FLAC,
                    coverThumbnailPath = deadPath,
                )
            val entryId = repo.addTrackToPlaylist(playlistId, localTrack)

            // Trigger reconciliation
            val tracks = repo.getTracksForPlaylistFlow(playlistId).first()
            val trackEntry = tracks.first { it.id == entryId }

            // Must NOT return the dead non-existent path; must reconcile to valid cover
            assertEquals(validCoverFile.absolutePath, trackEntry.coverThumbnailPath)

            kotlinx.coroutines.delay(100)
            val healedEntity = dao.getTrackById(entryId)
            assertEquals(validCoverFile.absolutePath, healedEntity?.coverThumbnailPath)
        }
}
