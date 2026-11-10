package com.webdav.player.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.webdav.player.data.local.AppDatabase
import com.webdav.player.data.local.DirectoryCacheDao
import com.webdav.player.data.local.DirectoryCacheEntity
import com.webdav.player.data.local.DirectorySyncCheckpointDao
import com.webdav.player.data.local.DirectorySyncCheckpointEntity
import com.webdav.player.data.local.LocalStorageFolderDao
import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.local.MediaTrackIndexEntity
import com.webdav.player.data.local.RemoteDirectoryJsonSerializer
import com.webdav.player.data.local.TrackMetadataDao
import com.webdav.player.data.local.TrackMetadataEntity
import com.webdav.player.data.remote.ConnectionResult
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.LibrarySyncState
import com.webdav.player.domain.model.ListDirectoryResult
import com.webdav.player.data.local.LocalStorageFolderEntity
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.RemoteFileType
import com.webdav.player.domain.model.TrackMetadata
import com.webdav.player.domain.model.WebDavServer
import kotlinx.coroutines.runBlocking
import com.webdav.player.domain.repository.TrackMetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaLibraryRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var directoryCacheDao: DirectoryCacheDao
    private lateinit var trackMetadataDao: TrackMetadataDao
    private lateinit var mediaTrackIndexDao: MediaTrackIndexDao
    private lateinit var checkpointDao: DirectorySyncCheckpointDao
    private lateinit var localStorageFolderDao: LocalStorageFolderDao

    private lateinit var fakeWebDavClient: FakeWebDavClient
    private lateinit var fakeTrackMetadataRepository: FakeTrackMetadataRepository
    private lateinit var localResolver: LocalStorageDirectoryResolver
    private lateinit var repository: MediaLibraryRepositoryImpl

    private val testServer =
        WebDavServer(
            id = 1L,
            name = "Test WebDAV Server",
            url = "http://localhost",
            port = 8080,
            pathPrefix = "/music",
        )
    private val secondServer =
        WebDavServer(
            id = 2L,
            name = "Second Server",
            url = "http://remotehost",
            port = 8080,
        )
    private val localServer = WebDavServer.createLocalStorage()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        directoryCacheDao = database.directoryCacheDao()
        trackMetadataDao = database.trackMetadataDao()
        mediaTrackIndexDao = database.mediaTrackIndexDao()
        checkpointDao = database.directorySyncCheckpointDao()
        localStorageFolderDao = database.localStorageFolderDao()

        fakeWebDavClient = FakeWebDavClient()
        fakeTrackMetadataRepository = FakeTrackMetadataRepository()
        runBlocking {
            localStorageFolderDao.insertFolder(
                LocalStorageFolderEntity(
                    uriString = "content://test.storage/tree/Music",
                    displayName = "MusicFolder",
                    dateAdded = 1000L,
                ),
            )
        }
        localResolver =
            LocalStorageDirectoryResolver(
                context = context,
                localStorageFolderDao = localStorageFolderDao,
            )

        repository = MediaLibraryRepositoryImpl(
            webDavClient = fakeWebDavClient,
            directoryCacheDao = directoryCacheDao,
            trackMetadataDao = trackMetadataDao,
            mediaTrackIndexDao = mediaTrackIndexDao,
            directorySyncCheckpointDao = checkpointDao,
            trackMetadataRepository = fakeTrackMetadataRepository,
            localStorageDirectoryResolver = localResolver,
            localStorageFolderDao = localStorageFolderDao,
            context = context,
        )
        repository.clearSearchHistory()
    }

    @After
    fun tearDown() {
        repository.clearSearchHistory()
        context.getSharedPreferences(InternalBrowsingCursorStore.DEFAULT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        database.close()
    }

    // =========================================================================
    // 1. SWR Directory Observation & Caching Tests
    // =========================================================================

    @Test
    fun observeDirectoryContent_withCacheMiss_emitsInitialEnriching_thenEnrichedFinished() =
        runTest {
            val audioFile = RemoteFile(
                name = "song.mp3",
                path = "/music/song.mp3",
                size = 1024L,
                fileType = RemoteFileType.Audio(AudioFormat.MP3),
            )
            val dir = RemoteDirectory(
                path = "/music/",
                name = "music",
                files = listOf(audioFile),
            )
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Success(dir)

            val emissions = repository.observeDirectoryContent(testServer, "/music/").toList()

            assertEquals(2, emissions.size)
            assertTrue("First emission must mark enriching true", emissions[0].isEnriching)
            assertEquals(dir, emissions[0].directory)

            assertFalse("Final emission must mark enriching false", emissions[1].isEnriching)
            assertEquals(1, fakeTrackMetadataRepository.resolveCount)
        }

    @Test
    fun observeDirectoryContent_withCacheHitAndDiff_emitsCached_thenRemoteUpdate_thenEnriched() =
        runTest {
            val fileA = RemoteFile(name = "songA.mp3", path = "/music/songA.mp3", size = 1000L)
            val initialDir = RemoteDirectory(path = "/music/", name = "music", files = listOf(fileA))
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Success(initialDir)

            // 1. Pre-warm cache
            repository.observeDirectoryContent(testServer, "/music/").toList()

            // 2. Remote updates on server
            val fileB = RemoteFile(name = "songB.mp3", path = "/music/songB.mp3", size = 2000L)
            val updatedDir = RemoteDirectory(path = "/music/", name = "music", files = listOf(fileA, fileB))
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Success(updatedDir)

            val emissions = repository.observeDirectoryContent(testServer, "/music/").toList()

            assertEquals(3, emissions.size)
            // Emission 1: 0ms cache hit
            assertEquals(initialDir, emissions[0].directory)
            assertTrue(emissions[0].isEnriching)
            // Emission 2: Remote difference discovered
            assertEquals(updatedDir, emissions[1].directory)
            assertTrue(emissions[1].isEnriching)
            // Emission 3: Enriched final snapshot
            assertEquals(updatedDir, emissions[2].directory)
            assertFalse(emissions[2].isEnriching)
        }

    @Test
    fun observeDirectoryContent_withCacheHitIdentical_emitsCached_thenEnrichedSnapshot() =
        runTest {
            val fileA = RemoteFile(name = "songA.mp3", path = "/music/songA.mp3", size = 1000L)
            val dir = RemoteDirectory(path = "/music/", name = "music", files = listOf(fileA))
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Success(dir)

            repository.observeDirectoryContent(testServer, "/music/").toList()

            val emissions = repository.observeDirectoryContent(testServer, "/music/").toList()

            assertEquals(2, emissions.size)
            assertEquals(dir, emissions[0].directory)
            assertTrue(emissions[0].isEnriching)
            assertEquals(dir, emissions[1].directory)
            assertFalse(emissions[1].isEnriching)
        }

    @Test
    fun observeDirectoryContent_withCacheHit_whenRemoteFails_suppressesErrorAndEmitsCached() =
        runTest {
            val fileA = RemoteFile(name = "songA.mp3", path = "/music/songA.mp3", size = 1000L)
            val dir = RemoteDirectory(path = "/music/", name = "music", files = listOf(fileA))
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Success(dir)
            repository.observeDirectoryContent(testServer, "/music/").toList()

            // Network goes down
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Failure("Network timeout")

            val emissions = repository.observeDirectoryContent(testServer, "/music/").toList()

            assertEquals(2, emissions.size)
            assertEquals(dir, emissions[0].directory)
            assertTrue(emissions[0].isEnriching)
            assertEquals(dir, emissions[1].directory)
            assertFalse(emissions[1].isEnriching)
        }

    @Test(expected = IllegalStateException::class)
    fun observeDirectoryContent_withCacheMiss_whenRemoteFails_throwsException() =
        runTest {
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Failure("Server 500 error")
            repository.observeDirectoryContent(testServer, "/music/").toList()
        }

    @Test
    fun observeDirectoryContent_withForceRefresh_bypassesCacheAndQueriesRemote() =
        runTest {
            val dir = RemoteDirectory(path = "/music/", name = "music")
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Success(dir)

            repository.observeDirectoryContent(testServer, "/music/").toList()
            val callsBefore = fakeWebDavClient.callCount["/music/"] ?: 0

            val emissions = repository.observeDirectoryContent(testServer, "/music/", forceRefresh = true).toList()

            assertEquals(callsBefore + 1, fakeWebDavClient.callCount["/music/"])
            assertEquals(dir, emissions.first().directory)
        }

    @Test
    fun memoryCache_whenExceedingCapacity_evictsLruAndFallsBackToRoomL2() =
        runTest {
            // Fill 101 directories into repository
            for (i in 1..101) {
                val path = "/music/folder_$i/"
                val dir = RemoteDirectory(path = path, name = "folder_$i")
                fakeWebDavClient.results[path] = ListDirectoryResult.Success(dir)
                repository.observeDirectoryContent(testServer, path).toList()
            }

            // folder_1 should have been evicted from L1 memory cache (capacity 100)
            // But still present in L2 Room persistent directory_cache
            val roomCached = directoryCacheDao.getCache(testServer.id, "/music/folder_1/")
            assertNotNull("Room persistent cache must retain evicted folder_1", roomCached)

            // getCachedDirectory should seamlessly restore it from Room L2 into L1
            val restored = repository.getCachedDirectory(testServer, "/music/folder_1/")
            assertNotNull(restored)
            assertEquals("/music/folder_1/", restored?.path)
        }

    @Test
    fun clearMemoryCache_clearsL1Cache_leavingRoomL2Intact() =
        runTest {
            val dir = RemoteDirectory(path = "/music/", name = "music")
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Success(dir)
            repository.observeDirectoryContent(testServer, "/music/").toList()

            repository.clearMemoryCache()

            // Room still has it
            assertNotNull(directoryCacheDao.getCache(testServer.id, "/music/"))
            // getCachedDirectory fetches from Room
            val restored = repository.getCachedDirectory(testServer, "/music/")
            assertNotNull(restored)
        }

    @Test
    fun clearAllCaches_clearsBothMemoryCacheAndRoomDirectoryCache() =
        runTest {
            val dir = RemoteDirectory(path = "/music/", name = "music")
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Success(dir)
            repository.observeDirectoryContent(testServer, "/music/").toList()

            repository.clearAllCaches()

            assertNull(repository.getCachedDirectory(testServer, "/music/"))
            assertNull(directoryCacheDao.getCache(testServer.id, "/music/"))
        }

    // =========================================================================
    // 2. Write-Through Indexing Tests
    // =========================================================================

    @Test
    fun observeDirectoryContent_writesThroughAudioTracksToMediaTrackIndex() =
        runTest {
            val audio1 = RemoteFile(
                name = "Artist - Track1.flac",
                path = "/music/Artist - Track1.flac",
                size = 15000000L,
                lastModified = "1672531200000",
                fileType = RemoteFileType.Audio(AudioFormat.FLAC),
            )
            val audio2 = RemoteFile(
                name = "Track2.mp3",
                path = "/music/Track2.mp3",
                size = 5000000L,
                fileType = RemoteFileType.Audio(AudioFormat.MP3),
            )
            val textFile = RemoteFile(
                name = "notes.txt",
                path = "/music/notes.txt",
                size = 100L,
                fileType = RemoteFileType.Other,
            )
            val dir = RemoteDirectory(
                path = "/music/",
                name = "music",
                files = listOf(audio1, audio2, textFile),
            )
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Success(dir)

            repository.observeDirectoryContent(testServer, "/music/").toList()

            val indexedTracks = mediaTrackIndexDao.getTracksByParentPath(testServer.id, "/music/")
            assertEquals(2, indexedTracks.size)

            val track1 = indexedTracks.firstOrNull { it.remotePath == "/music/Artist - Track1.flac" }
            assertNotNull(track1)
            assertEquals("FLAC", track1?.format)
            assertEquals("Track1", track1?.parsedTitle)
            assertEquals("Artist", track1?.parsedArtist)

            val track2 = indexedTracks.firstOrNull { it.remotePath == "/music/Track2.mp3" }
            assertNotNull(track2)
            assertEquals("MP3", track2?.format)
        }

    @Test
    fun observeDirectoryContent_writeThrough_deletesStaleTracksInParentPath() =
        runTest {
            val audio1 = RemoteFile(name = "song1.mp3", path = "/music/song1.mp3")
            val audio2 = RemoteFile(name = "song2.mp3", path = "/music/song2.mp3")
            val dirV1 = RemoteDirectory(path = "/music/", name = "music", files = listOf(audio1, audio2))
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Success(dirV1)

            // Initial visit writes song1 and song2
            repository.observeDirectoryContent(testServer, "/music/").toList()
            assertEquals(2, mediaTrackIndexDao.getTracksByParentPath(testServer.id, "/music/").size)

            // Remote server deletes song1.mp3
            val dirV2 = RemoteDirectory(path = "/music/", name = "music", files = listOf(audio2))
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Success(dirV2)

            // Second visit with force refresh writes through and deletes stale track
            repository.observeDirectoryContent(testServer, "/music/", forceRefresh = true).toList()

            val indexedAfter = mediaTrackIndexDao.getTracksByParentPath(testServer.id, "/music/")
            assertEquals(1, indexedAfter.size)
            assertEquals("/music/song2.mp3", indexedAfter[0].remotePath)
        }

    @Test
    fun observeDirectoryContent_writesThroughCheckpointsForDirectoryAndSubDirectories() =
        runTest {
            val sub1 = RemoteDirectory(path = "/music/Rock/", name = "Rock")
            val sub2 = RemoteDirectory(path = "/music/Pop/", name = "Pop")
            val dir =
                RemoteDirectory(
                    path = "/music/",
                    name = "music",
                    subDirectories = listOf(sub1, sub2),
                    lastModified = 12345L,
                    etag = "etag-root",
                )
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Success(dir)

            repository.observeDirectoryContent(testServer, "/music/").toList()

            // Verify parent directory checkpoint was written
            val parentCp = checkpointDao.getCheckpoint(testServer.id, "/music/")
            assertNotNull(parentCp)
            assertEquals("etag-root", parentCp?.etag)

            // Verify subdirectories checkpoints were written
            val rockCp = checkpointDao.getCheckpoint(testServer.id, "/music/Rock/")
            assertNotNull("Subdirectory Rock must have checkpoint written", rockCp)

            val popCp = checkpointDao.getCheckpoint(testServer.id, "/music/Pop/")
            assertNotNull("Subdirectory Pop must have checkpoint written", popCp)

            // Verify they are immediately searchable via repository.search
            val searchResult = repository.search("Rock", testServer.id).first()
            assertTrue(searchResult.matchedDirectories.any { it.name == "Rock" && it.path == "/music/Rock/" })
        }

    // =========================================================================
    // 3. Local Directory Browsing Tests
    // =========================================================================

    @Test
    fun observeDirectoryContent_forLocalStorage_resolvesViaLocalStorageResolver() =
        runTest {
            val emissions = repository.observeDirectoryContent(localServer, "/").toList()

            assertTrue(emissions.isNotEmpty())
            val rootDir = emissions.first().directory
            assertEquals("/", rootDir.path)
            assertEquals(LOCAL_STORAGE_ID, rootDir.serverId)
            assertEquals(1, rootDir.subDirectories.size)
            assertEquals("MusicFolder", rootDir.subDirectories[0].name)
        }

    // =========================================================================
    // 4. Pure B-Tree Search Tests (Zero dataJson Scanning)
    // =========================================================================

    @Test
    fun search_emptyOrBlankQuery_returnsEmptyResult() =
        runTest {
            val res1 = repository.search("").first()
            assertTrue(res1.isEmpty)

            val res2 = repository.search("   ").first()
            assertTrue(res2.isEmpty)
        }

    @Test
    fun search_matchesMediaTrackIndex_pureBTreeSearch() =
        runTest {
            mediaTrackIndexDao.upsertTracks(
                listOf(
                    MediaTrackIndexEntity(
                        serverId = 1L,
                        remotePath = "/music/Bohemian Rhapsody.flac",
                        fileName = "Bohemian Rhapsody.flac",
                        fileSize = 40000000L,
                        lastModified = 1000L,
                        format = "FLAC",
                        parentPath = "/music/",
                        parsedTitle = "Bohemian Rhapsody",
                        parsedArtist = "Queen",
                    ),
                    MediaTrackIndexEntity(
                        serverId = 1L,
                        remotePath = "/music/Stairway to Heaven.mp3",
                        fileName = "Stairway to Heaven.mp3",
                        fileSize = 10000000L,
                        lastModified = 1000L,
                        format = "MP3",
                        parentPath = "/music/",
                        parsedTitle = "Stairway to Heaven",
                        parsedArtist = "Led Zeppelin",
                    ),
                ),
            )

            val result = repository.search("Bohemian").first()

            assertEquals(1, result.matchedTracks.size)
            val track = result.matchedTracks[0]
            assertEquals("Bohemian Rhapsody", track.title)
            assertEquals("Queen", track.artist)
            assertEquals(AudioFormat.FLAC, track.format)
        }

    @Test
    fun search_matchesDirectorySyncCheckpoint() =
        runTest {
            checkpointDao.upsertCheckpoint(
                DirectorySyncCheckpointEntity(
                    serverId = 1L,
                    path = "/music/Rock Classics/",
                    lastModified = 2000L,
                    etag = "etag-123",
                    lastScannedMs = System.currentTimeMillis(),
                ),
            )

            val result = repository.search("Rock").first()

            assertEquals(1, result.matchedDirectories.size)
            val dir = result.matchedDirectories[0]
            assertEquals("/music/Rock Classics/", dir.path)
            assertEquals("Rock Classics", dir.name)
        }

    @Test
    fun search_matchesParentPathOfIndexedTrack() =
        runTest {
            mediaTrackIndexDao.upsertTracks(
                listOf(
                    MediaTrackIndexEntity(
                        serverId = 1L,
                        remotePath = "/genres/Jazz/Miles Davis - So What.mp3",
                        fileName = "Miles Davis - So What.mp3",
                        fileSize = 5000000L,
                        lastModified = 1000L,
                        format = "MP3",
                        parentPath = "/genres/Jazz/",
                        parsedTitle = "So What",
                        parsedArtist = "Miles Davis",
                    ),
                ),
            )

            val result = repository.search("Jazz").first()

            assertEquals(1, result.matchedDirectories.size)
            assertEquals("/genres/Jazz/", result.matchedDirectories[0].path)
            assertEquals("Jazz", result.matchedDirectories[0].name)
        }

    @Test
    fun search_enrichesIndexedTracksWithTrackMetadata() =
        runTest {
            val remotePath = "/music/Thriller.flac"
            mediaTrackIndexDao.upsertTracks(
                listOf(
                    MediaTrackIndexEntity(
                        serverId = 1L,
                        remotePath = remotePath,
                        fileName = "Thriller.flac",
                        fileSize = 25000000L,
                        lastModified = 1000L,
                        format = "FLAC",
                        parentPath = "/music/",
                        parsedTitle = "Thriller",
                        parsedArtist = "Michael Jackson",
                    ),
                ),
            )
            trackMetadataDao.insertOrUpdate(
                TrackMetadataEntity(
                    serverId = 1L,
                    remotePath = remotePath,
                    title = "Thriller (Remastered)",
                    artist = "Michael Jackson",
                    album = "Thriller 40th",
                    trackNumber = 4,
                    durationMs = 357000L,
                    coverThumbnailPath = "/thumbnails/thriller.jpg",
                ),
            )

            val result = repository.search("Thriller").first()

            assertEquals(1, result.matchedTracks.size)
            val track = result.matchedTracks[0]
            assertEquals("Thriller (Remastered)", track.title)
            assertEquals("Thriller 40th", track.album)
            assertEquals(357000L, track.durationMs)
            assertEquals("/thumbnails/thriller.jpg", track.coverThumbnailPath)
        }

    @Test
    fun search_sourceIsolation_whenWebDavServerId_filtersOutLocalTracks() =
        runTest {
            mediaTrackIndexDao.upsertTracks(
                listOf(
                    MediaTrackIndexEntity(
                        serverId = 1L,
                        remotePath = "/music/WebDavSong.mp3",
                        fileName = "WebDavSong.mp3",
                        fileSize = 1000L,
                        lastModified = 1000L,
                        format = "MP3",
                        parentPath = "/music/",
                        parsedTitle = "WebDavSong",
                        parsedArtist = null,
                    ),
                    MediaTrackIndexEntity(
                        serverId = LOCAL_STORAGE_ID,
                        remotePath = "content://media/external/audio/LocalSong.mp3",
                        fileName = "LocalSong.mp3",
                        fileSize = 1000L,
                        lastModified = 1000L,
                        format = "MP3",
                        parentPath = "/local/",
                        parsedTitle = "LocalSong",
                        parsedArtist = null,
                    ),
                ),
            )

            // Search restricted to WebDavServer 1L
            val result = repository.search("Song", serverId = 1L).first()

            assertEquals(1, result.matchedTracks.size)
            assertEquals("WebDavSong", result.matchedTracks[0].title)
            assertEquals(1L, result.matchedTracks[0].serverId)
        }

    @Test
    fun search_sourceIsolation_whenLocalStorageId_filtersOutWebDavTracks() =
        runTest {
            mediaTrackIndexDao.upsertTracks(
                listOf(
                    MediaTrackIndexEntity(
                        serverId = 1L,
                        remotePath = "/music/WebDavSong.mp3",
                        fileName = "WebDavSong.mp3",
                        fileSize = 1000L,
                        lastModified = 1000L,
                        format = "MP3",
                        parentPath = "/music/",
                        parsedTitle = "WebDavSong",
                        parsedArtist = null,
                    ),
                    MediaTrackIndexEntity(
                        serverId = LOCAL_STORAGE_ID,
                        remotePath = "content://media/external/audio/LocalSong.mp3",
                        fileName = "LocalSong.mp3",
                        fileSize = 1000L,
                        lastModified = 1000L,
                        format = "MP3",
                        parentPath = "/local/",
                        parsedTitle = "LocalSong",
                        parsedArtist = null,
                    ),
                ),
            )

            // Search restricted to LOCAL_STORAGE_ID
            val result = repository.search("Song", serverId = LOCAL_STORAGE_ID).first()

            assertEquals(1, result.matchedTracks.size)
            assertEquals("LocalSong", result.matchedTracks[0].title)
            assertEquals(LOCAL_STORAGE_ID, result.matchedTracks[0].serverId)
        }

    @Test
    fun search_globalSearch_deduplicatesAcrossSources() =
        runTest {
            mediaTrackIndexDao.upsertTracks(
                listOf(
                    MediaTrackIndexEntity(
                        serverId = 1L,
                        remotePath = "/music/SharedTrack.mp3",
                        fileName = "SharedTrack.mp3",
                        fileSize = 1000L,
                        lastModified = 1000L,
                        format = "MP3",
                        parentPath = "/music/",
                        parsedTitle = "SharedTrack",
                        parsedArtist = null,
                    ),
                ),
            )
            // Same track also present in track_metadata table
            trackMetadataDao.insertOrUpdate(
                TrackMetadataEntity(
                    serverId = 1L,
                    remotePath = "/music/SharedTrack.mp3",
                    title = "SharedTrack",
                    artist = "Artist",
                    album = "Album",
                    trackNumber = null,
                    durationMs = 200000L,
                    coverThumbnailPath = null,
                ),
            )

            // Global search (serverId = null)
            val result = repository.search("SharedTrack", serverId = null).first()

            assertEquals("Must deduplicate track appearing in both index and metadata", 1, result.matchedTracks.size)
            assertEquals("SharedTrack", result.matchedTracks[0].title)
        }

    // =========================================================================
    // 5. Incremental Sync & RebuildIndex Progress Emission Tests
    // =========================================================================

    @Test
    fun syncServer_forWebDav_emitsScanningAndCompleted() =
        runTest {
            val audio = RemoteFile(name = "Song.mp3", path = "/music/Song.mp3")
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Success(
                RemoteDirectory(path = "/music/", name = "music", files = listOf(audio))
            )

            val syncResult = repository.syncServer(testServer)
            assertTrue(syncResult is com.webdav.player.domain.model.LibrarySyncResult.Success)

            val state = repository.observeSyncState(testServer.id).first()
            assertTrue("Final sync state must be Completed", state is LibrarySyncState.Completed)
        }

    @Test
    fun observeSyncState_isolatesStateByServerId() =
        runTest {
            val syncResult = repository.syncServer(testServer)
            assertTrue(syncResult is com.webdav.player.domain.model.LibrarySyncResult.Success)

            val server1State = repository.observeSyncState(1L).first()
            val server2State = repository.observeSyncState(2L).first()
            val globalState = repository.observeSyncState(null).first()

            assertTrue("Server 1 must be Completed", server1State is LibrarySyncState.Completed)
            assertEquals("Server 2 must remain Idle", LibrarySyncState.Idle, server2State)
            assertTrue("Global state must reflect Completed", globalState is LibrarySyncState.Completed)
        }

    @Test
    fun rebuildIndex_clearsCheckpointsAndTrackIndex_thenResyncs() =
        runTest {
            // Seed obsolete track and checkpoint
            checkpointDao.upsertCheckpoint(
                DirectorySyncCheckpointEntity(
                    serverId = testServer.id,
                    path = "/music/old/",
                    lastModified = 100L,
                    etag = "old",
                    lastScannedMs = 100L,
                ),
            )
            mediaTrackIndexDao.upsertTracks(
                listOf(
                    MediaTrackIndexEntity(
                        serverId = testServer.id,
                        remotePath = "/music/old/deleted.mp3",
                        fileName = "deleted.mp3",
                        fileSize = 100L,
                        lastModified = 100L,
                        format = "MP3",
                        parentPath = "/music/old/",
                        parsedTitle = "deleted",
                        parsedArtist = null,
                    ),
                ),
            )

            // Setup fresh scan directory
            val freshAudio = RemoteFile(name = "fresh.mp3", path = "/music/fresh.mp3")
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Success(
                RemoteDirectory(path = "/music/", name = "music", files = listOf(freshAudio))
            )

            val rebuildResult = repository.rebuildIndex(testServer)
            assertTrue(rebuildResult is com.webdav.player.domain.model.LibrarySyncResult.Success)

            // Old track must be erased
            val oldTrack = mediaTrackIndexDao.getTrack(testServer.id, "/music/old/deleted.mp3")
            assertNull("Old deleted track must be purged on rebuildIndex", oldTrack)

            // State finishes as Completed
            val finalState = repository.observeSyncState(testServer.id).first()
            assertTrue(finalState is LibrarySyncState.Completed)
        }

    // =========================================================================
    // 6. Search History Management Tests
    // =========================================================================

    @Test
    fun recordSearchQuery_persistsInLruOrder() {
        repository.recordSearchQuery("Rock")
        repository.recordSearchQuery("Pop")
        repository.recordSearchQuery("Jazz")

        val history = repository.getSearchHistory()
        assertEquals(listOf("Jazz", "Pop", "Rock"), history)
    }

    @Test
    fun recordSearchQuery_trimsWhitespace_andIgnoresBlank() {
        repository.recordSearchQuery("   ")
        assertTrue(repository.getSearchHistory().isEmpty())

        repository.recordSearchQuery("  Classical  ")
        assertEquals(listOf("Classical"), repository.getSearchHistory())
    }

    @Test
    fun recordSearchQuery_deduplicatesAndBumpsToFront() {
        repository.recordSearchQuery("Rock")
        repository.recordSearchQuery("Jazz")
        repository.recordSearchQuery("Rock")

        val history = repository.getSearchHistory()
        assertEquals(listOf("Rock", "Jazz"), history)
    }

    @Test
    fun recordSearchQuery_capsAt20Items() {
        for (i in 1..25) {
            repository.recordSearchQuery("Query $i")
        }
        val history = repository.getSearchHistory()
        assertEquals(20, history.size)
        assertEquals("Query 25", history.first())
    }

    @Test
    fun removeSearchHistory_removesItem() {
        repository.recordSearchQuery("Rock")
        repository.recordSearchQuery("Jazz")

        val updated = repository.removeSearchHistory("Rock")
        assertEquals(listOf("Jazz"), updated)
        assertEquals(listOf("Jazz"), repository.getSearchHistory())
    }

    @Test
    fun clearSearchHistory_clearsAll() {
        repository.recordSearchQuery("Rock")
        repository.recordSearchQuery("Jazz")

        repository.clearSearchHistory()
        assertTrue(repository.getSearchHistory().isEmpty())
    }

    // =========================================================================
    // 7. Cascade Invalidation Tests
    // =========================================================================

    @Test
    fun clearStorageData_clearsMemoryCacheDirectoryCacheTrackIndexAndCheckpointsForServer() =
        runTest {
            // Seed Server 1 and Server 2 across all 4 tiers
            val dir1 = RemoteDirectory(path = "/music/", name = "music")
            val dir2 = RemoteDirectory(path = "/remote/", name = "remote")
            fakeWebDavClient.results["/music/"] = ListDirectoryResult.Success(dir1)
            fakeWebDavClient.results["/remote/"] = ListDirectoryResult.Success(dir2)

            repository.observeDirectoryContent(testServer, "/music/").toList()
            repository.observeDirectoryContent(secondServer, "/remote/").toList()

            checkpointDao.upsertCheckpoint(
                DirectorySyncCheckpointEntity(serverId = 1L, path = "/music/", lastModified = 1L, etag = "e1", lastScannedMs = 1L)
            )
            checkpointDao.upsertCheckpoint(
                DirectorySyncCheckpointEntity(serverId = 2L, path = "/remote/", lastModified = 2L, etag = "e2", lastScannedMs = 2L)
            )

            // Wipe Server 1
            repository.clearStorageData(1L)

            // Verify Server 1 is gone from memory and Room
            assertNull("Server 1 directory must be purged from cache", repository.getCachedDirectory(testServer, "/music/"))
            assertNull("Server 1 directory_cache must be deleted", directoryCacheDao.getCache(1L, "/music/"))
            assertTrue("Server 1 track index must be empty", mediaTrackIndexDao.getTracksByServerId(1L).isEmpty())
            assertTrue("Server 1 checkpoints must be empty", checkpointDao.getCheckpointsByServerId(1L).isEmpty())

            // Verify Server 2 remains completely intact
            assertNotNull("Server 2 directory must remain intact", repository.getCachedDirectory(secondServer, "/remote/"))
            assertNotNull("Server 2 directory_cache must remain intact", directoryCacheDao.getCache(2L, "/remote/"))
            assertEquals("Server 2 checkpoints must remain intact", 1, checkpointDao.getCheckpointsByServerId(2L).size)
        }

    @Test
    fun clearLocalFolderData_clearsLocalFolderEntriesAcrossAllTiers() =
        runTest {
            val uriString = "content://test.storage/tree/Music"
            val localPrefix = LocalStorageIndexScanner.buildLocalRootPath(uriString)

            mediaTrackIndexDao.upsertTracks(
                listOf(
                    MediaTrackIndexEntity(
                        serverId = LOCAL_STORAGE_ID,
                        remotePath = "${localPrefix}song.mp3",
                        fileName = "song.mp3",
                        fileSize = 1000L,
                        lastModified = 1000L,
                        format = "MP3",
                        parentPath = localPrefix,
                        parsedTitle = "song",
                        parsedArtist = null,
                    ),
                ),
            )
            checkpointDao.upsertCheckpoint(
                DirectorySyncCheckpointEntity(
                    serverId = LOCAL_STORAGE_ID,
                    path = localPrefix,
                    lastModified = 1000L,
                    etag = null,
                    lastScannedMs = 1000L,
                ),
            )
            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = LOCAL_STORAGE_ID,
                    path = localPrefix,
                    dataJson = RemoteDirectoryJsonSerializer.serialize(RemoteDirectory(path = localPrefix, name = "Music")),
                    lastUpdatedMs = 1000L,
                ),
            )

            repository.clearLocalFolderData(uriString)

            assertTrue("Local track index for folder must be deleted", mediaTrackIndexDao.getTracksByParentPath(LOCAL_STORAGE_ID, localPrefix).isEmpty())
            assertNull("Local checkpoint for folder must be deleted", checkpointDao.getCheckpoint(LOCAL_STORAGE_ID, localPrefix))
            assertNull("Local directory cache must be cleared", directoryCacheDao.getCache(LOCAL_STORAGE_ID, localPrefix))
        }

    // =========================================================================
    // 8. Coroutine Cancellation Exception Rethrow Tests
    // =========================================================================

    @Test(expected = CancellationException::class)
    fun observeDirectoryContent_rethrowsCancellationException() =
        runTest {
            fakeWebDavClient.shouldThrowCancellation = true
            repository.observeDirectoryContent(testServer, "/music/").toList()
        }

    @Test(expected = CancellationException::class)
    fun syncServer_rethrowsCancellationException() =
        runTest {
            val deferred = async {
                coroutineContext.cancel(CancellationException("Sync cancelled"))
                repository.syncServer(testServer)
            }
            deferred.await()
        }

    @Test(expected = CancellationException::class)
    fun rebuildIndex_rethrowsCancellationException() =
        runTest {
            val deferred = async {
                coroutineContext.cancel(CancellationException("Rebuild cancelled"))
                repository.rebuildIndex(testServer)
            }
            deferred.await()
        }

    // =========================================================================
    // 9. Browsing Cursor Retention & Cascade Reset Tests
    // =========================================================================

    @Test
    fun getLastBrowsingPath_returnsRootByDefault_forUnvisitedServer() {
        assertEquals("/", repository.getLastBrowsingPath(testServer.id))
        assertEquals("/", repository.getLastBrowsingPath(secondServer.id))
        assertEquals("/", repository.getLastBrowsingPath(LOCAL_STORAGE_ID))
        assertEquals("/", repository.getLastBrowsingPath(999L))
    }

    @Test
    fun setLastBrowsingPath_updatesAndPersistsAcrossInstances() {
        val path = "/music/Rock/"
        repository.setLastBrowsingPath(testServer.id, path)
        assertEquals(path, repository.getLastBrowsingPath(testServer.id))

        val secondRepo = MediaLibraryRepositoryImpl(
            webDavClient = fakeWebDavClient,
            directoryCacheDao = directoryCacheDao,
            trackMetadataDao = trackMetadataDao,
            mediaTrackIndexDao = mediaTrackIndexDao,
            directorySyncCheckpointDao = checkpointDao,
            trackMetadataRepository = fakeTrackMetadataRepository,
            localStorageDirectoryResolver = localResolver,
            localStorageFolderDao = localStorageFolderDao,
            context = context,
        )
        assertEquals(path, secondRepo.getLastBrowsingPath(testServer.id))
    }

    @Test
    fun serverIsolation_multipleServersMaintainDistinctBrowsingCursors() {
        repository.setLastBrowsingPath(1L, "/music/classical/")
        repository.setLastBrowsingPath(2L, "/remote/jazz/")
        repository.setLastBrowsingPath(LOCAL_STORAGE_ID, "/local/albums/")

        assertEquals("/music/classical/", repository.getLastBrowsingPath(1L))
        assertEquals("/remote/jazz/", repository.getLastBrowsingPath(2L))
        assertEquals("/local/albums/", repository.getLastBrowsingPath(LOCAL_STORAGE_ID))
    }

    @Test
    fun clearStorageData_purgesBrowsingCursorForServer() =
        runTest {
            repository.setLastBrowsingPath(1L, "/music/classical/")
            repository.setLastBrowsingPath(2L, "/remote/jazz/")

            repository.clearStorageData(1L)

            assertEquals("/", repository.getLastBrowsingPath(1L))
            assertEquals("/remote/jazz/", repository.getLastBrowsingPath(2L))
        }

    @Test
    fun clearLocalFolderData_resetsLocalCursorToRoot_whenCursorInsideUnmountedFolder() =
        runTest {
            val unmountedUri = "content://test.storage/tree/Music"
            val unmountedLocalPrefix = LocalStorageIndexScanner.buildLocalRootPath(unmountedUri)
            val subfolderPath = "${unmountedLocalPrefix}Rock/"

            // Case 1: Cursor is inside the unmounted folder hierarchy
            repository.setLastBrowsingPath(LOCAL_STORAGE_ID, subfolderPath)
            assertEquals(subfolderPath, repository.getLastBrowsingPath(LOCAL_STORAGE_ID))

            repository.clearLocalFolderData(unmountedUri)
            assertEquals("/", repository.getLastBrowsingPath(LOCAL_STORAGE_ID))

            // Case 2: Cursor is in another mounted folder -> should NOT reset
            val otherUri = "content://test.storage/tree/Other"
            val otherLocalPrefix = LocalStorageIndexScanner.buildLocalRootPath(otherUri)
            val otherSubfolder = "${otherLocalPrefix}Ambient/"
            repository.setLastBrowsingPath(LOCAL_STORAGE_ID, otherSubfolder)

            repository.clearLocalFolderData(unmountedUri)
            assertEquals(otherSubfolder, repository.getLastBrowsingPath(LOCAL_STORAGE_ID))
        }

    // =========================================================================
    // Test Doubles (Fakes)
    // =========================================================================

    private class FakeWebDavClient : WebDavClient {
        val results = mutableMapOf<String, ListDirectoryResult>()
        val callCount = mutableMapOf<String, Int>()
        var shouldThrowCancellation = false

        override suspend fun testConnection(server: WebDavServer): ConnectionResult = ConnectionResult.Success

        override suspend fun listDirectory(
            server: WebDavServer,
            path: String,
        ): ListDirectoryResult {
            if (shouldThrowCancellation) throw CancellationException("Cancelled")
            callCount[path] = (callCount[path] ?: 0) + 1
            return results[path]
                ?: ListDirectoryResult.Success(RemoteDirectory(path = path, name = path.trim('/').substringAfterLast('/')))
        }

        override suspend fun fetchRange(
            server: WebDavServer,
            remotePath: String,
            startByte: Long,
            endByte: Long,
        ): ByteArray? = null

        override suspend fun fetchText(
            server: WebDavServer,
            remotePath: String,
        ): String? = null
    }

    private class FakeTrackMetadataRepository : TrackMetadataRepository {
        val cachedMetadata = mutableMapOf<String, TrackMetadata>()
        var resolveCount = 0

        override suspend fun resolveMetadata(
            server: WebDavServer,
            files: List<RemoteFile>,
            forceRefresh: Boolean,
        ) {
            resolveCount++
            for (file in files) {
                val meta =
                    cachedMetadata["${server.id}:${file.path}"]
                        ?: TrackMetadata(
                            serverId = server.id,
                            remotePath = file.path,
                            title = file.name.substringBeforeLast('.'),
                            artist = "Test Artist",
                            album = "Test Album",
                            durationMs = 180000L,
                        )
                cachedMetadata["${server.id}:${file.path}"] = meta
            }
        }

        override suspend fun resolveSingleTrackMetadata(
            server: WebDavServer,
            file: RemoteFile,
        ): TrackMetadata =
            cachedMetadata["${server.id}:${file.path}"]
                ?: TrackMetadata(
                    serverId = server.id,
                    remotePath = file.path,
                    title = file.name.substringBeforeLast('.'),
                    artist = "Test Artist",
                    album = "Test Album",
                    durationMs = 180000L,
                )

        override suspend fun getCachedMetadata(
            serverId: Long,
            remotePath: String,
        ): TrackMetadata? = cachedMetadata["$serverId:$remotePath"]

        override fun getAllMetadataFlow(serverId: Long): Flow<List<TrackMetadata>> = flowOf(emptyList())
        override fun getMetadataForPathsFlow(serverId: Long, remotePaths: List<String>): Flow<List<TrackMetadata>> = flowOf(emptyList())
        override fun getMetadataFlow(serverId: Long, remotePath: String): Flow<TrackMetadata?> = flowOf(cachedMetadata["$serverId:$remotePath"])
    }
}
