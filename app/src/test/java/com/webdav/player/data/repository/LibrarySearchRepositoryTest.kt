package com.webdav.player.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.webdav.player.data.local.AppDatabase
import com.webdav.player.data.local.DirectoryCacheDao
import com.webdav.player.data.local.DirectoryCacheEntity
import com.webdav.player.data.local.DirectorySyncCheckpointDao
import com.webdav.player.data.local.DirectorySyncCheckpointEntity
import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.local.MediaTrackIndexEntity
import com.webdav.player.data.local.RemoteDirectoryJsonSerializer
import com.webdav.player.data.local.TrackMetadataDao
import com.webdav.player.data.local.TrackMetadataEntity
import com.webdav.player.data.remote.ConnectionResult
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.ListDirectoryResult
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.RemoteFileType
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.repository.LibrarySearchRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibrarySearchRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var directoryCacheDao: DirectoryCacheDao
    private lateinit var trackMetadataDao: TrackMetadataDao
    private lateinit var mediaTrackIndexDao: MediaTrackIndexDao
    private lateinit var checkpointDao: DirectorySyncCheckpointDao
    private lateinit var repository: LibrarySearchRepository

    private val fakeWebDavClient =
        object : WebDavClient {
            val textResponses = mutableMapOf<String, String>()

            override suspend fun testConnection(server: WebDavServer): ConnectionResult = ConnectionResult.Success

            override suspend fun listDirectory(
                server: WebDavServer,
                path: String,
            ): ListDirectoryResult = ListDirectoryResult.Failure("Not implemented")

            override suspend fun fetchRange(
                server: WebDavServer,
                remotePath: String,
                startByte: Long,
                endByte: Long,
            ): ByteArray? = null

            override suspend fun fetchText(
                server: WebDavServer,
                remotePath: String,
            ): String? = textResponses["${server.id}:$remotePath"] ?: textResponses[remotePath]
        }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        directoryCacheDao = database.directoryCacheDao()
        trackMetadataDao = database.trackMetadataDao()
        mediaTrackIndexDao = database.mediaTrackIndexDao()
        checkpointDao = database.directorySyncCheckpointDao()
        fakeWebDavClient.textResponses.clear()

        repository =
            LibrarySearchRepositoryImpl(
                directoryCacheDao = directoryCacheDao,
                trackMetadataDao = trackMetadataDao,
                mediaTrackIndexDao = mediaTrackIndexDao,
                directorySyncCheckpointDao = checkpointDao,
                context = context,
            )
        repository.clearSearchHistory()
    }

    @After
    fun tearDown() {
        repository.clearSearchHistory()
        database.close()
    }

    @Test
    fun search_withEmptyOrBlankQuery_returnsEmptyResult() =
        runTest {
            val dir = RemoteDirectory(path = "/Music/Rock/", name = "Rock")
            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = 1L,
                    path = "/Music/Rock/",
                    dataJson = RemoteDirectoryJsonSerializer.serialize(dir),
                    lastUpdatedMs = 1000L,
                ),
            )

            val resultEmpty = repository.search("").first()
            assertTrue(resultEmpty.isEmpty)
            assertTrue(resultEmpty.matchedDirectories.isEmpty())
            assertTrue(resultEmpty.matchedTracks.isEmpty())

            val resultSpaces = repository.search("   ").first()
            assertTrue(resultSpaces.isEmpty)

            val resultTabs = repository.search("\t\n").first()
            assertTrue(resultTabs.isEmpty)
        }

    @Test
    fun search_withDirectoryNameMatch_caseInsensitive() =
        runTest {
            val rockDir = RemoteDirectory(path = "/Music/Rock/", name = "Rock")
            val jazzDir = RemoteDirectory(path = "/Music/Jazz/", name = "Jazz")

            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = 1L,
                    path = "/Music/Rock/",
                    dataJson = RemoteDirectoryJsonSerializer.serialize(rockDir),
                    lastUpdatedMs = 1000L,
                ),
            )
            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = 1L,
                    path = "/Music/Jazz/",
                    dataJson = RemoteDirectoryJsonSerializer.serialize(jazzDir),
                    lastUpdatedMs = 1000L,
                ),
            )

            // Lowercase query
            val resultLower = repository.search("rock").first()
            assertEquals(1, resultLower.matchedDirectories.size)
            assertEquals("/Music/Rock/", resultLower.matchedDirectories.first().path)

            // Uppercase query
            val resultUpper = repository.search("ROCK").first()
            assertEquals(1, resultUpper.matchedDirectories.size)
            assertEquals("Rock", resultUpper.matchedDirectories.first().name)

            // Mixed case query
            val resultMixed = repository.search("rOcK").first()
            assertEquals(1, resultMixed.matchedDirectories.size)
        }

    @Test
    fun search_withDirectoryPathMatch_matchesSubpathSegments() =
        runTest {
            val dir = RemoteDirectory(path = "/Classical/Beethoven/Symphony/", name = "Symphony")
            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = 1L,
                    path = "/Classical/Beethoven/Symphony/",
                    dataJson = RemoteDirectoryJsonSerializer.serialize(dir),
                    lastUpdatedMs = 1000L,
                ),
            )

            val result = repository.search("beethoven").first()
            assertEquals(1, result.matchedDirectories.size)
            assertEquals("/Classical/Beethoven/Symphony/", result.matchedDirectories.first().path)
        }

    @Test
    fun search_withSubDirectoriesInCachedDirectory_matchesSubDirectories() =
        runTest {
            val rootDir =
                RemoteDirectory(
                    path = "/Music/",
                    name = "Music",
                    subDirectories =
                        listOf(
                            RemoteDirectory(path = "/Music/Metal/", name = "Metal"),
                            RemoteDirectory(path = "/Music/Pop/", name = "Pop"),
                        ),
                )
            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = 1L,
                    path = "/Music/",
                    dataJson = RemoteDirectoryJsonSerializer.serialize(rootDir),
                    lastUpdatedMs = 1000L,
                ),
            )

            val result = repository.search("metal").first()
            assertEquals(1, result.matchedDirectories.size)
            assertEquals("/Music/Metal/", result.matchedDirectories.first().path)
            assertEquals("Metal", result.matchedDirectories.first().name)
        }

    @Test
    fun search_withTrackMetadataMatch_titleArtistAlbum() =
        runTest {
            val file1 =
                RemoteFile(
                    name = "bohemian.mp3",
                    path = "/Music/bohemian.mp3",
                    size = 5000000L,
                    fileType = RemoteFileType.Audio(AudioFormat.MP3),
                )
            val file2 =
                RemoteFile(
                    name = "hotel_california.flac",
                    path = "/Music/hotel_california.flac",
                    size = 25000000L,
                    fileType = RemoteFileType.Audio(AudioFormat.FLAC),
                )
            val dir = RemoteDirectory(path = "/Music/", name = "Music", files = listOf(file1, file2))
            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = 1L,
                    path = "/Music/",
                    dataJson = RemoteDirectoryJsonSerializer.serialize(dir),
                    lastUpdatedMs = 1000L,
                ),
            )

            trackMetadataDao.insertOrUpdateAll(
                listOf(
                    TrackMetadataEntity(
                        serverId = 1L,
                        remotePath = "/Music/bohemian.mp3",
                        title = "Bohemian Rhapsody",
                        artist = "Queen",
                        album = "A Night at the Opera",
                        trackNumber = 4,
                        durationMs = 354000L,
                        coverThumbnailPath = null,
                    ),
                    TrackMetadataEntity(
                        serverId = 1L,
                        remotePath = "/Music/hotel_california.flac",
                        title = "Hotel California",
                        artist = "Eagles",
                        album = "Hotel California",
                        trackNumber = 1,
                        durationMs = 390000L,
                        coverThumbnailPath = null,
                    ),
                ),
            )

            // Match by artist
            val artistResult = repository.search("queen").first()
            assertEquals(1, artistResult.matchedTracks.size)
            assertEquals("Bohemian Rhapsody", artistResult.matchedTracks.first().title)
            assertEquals("Queen", artistResult.matchedTracks.first().artist)

            // Match by album
            val albumResult = repository.search("opera").first()
            assertEquals(1, albumResult.matchedTracks.size)
            assertEquals("Bohemian Rhapsody", albumResult.matchedTracks.first().title)

            // Match by title
            val titleResult = repository.search("california").first()
            assertEquals(1, titleResult.matchedTracks.size)
            assertEquals("Hotel California", titleResult.matchedTracks.first().title)
        }

    @Test
    fun search_withTrackFileNameMatch_whenMetadataNotPresent() =
        runTest {
            val file =
                RemoteFile(
                    name = "stairway_to_heaven.mp3",
                    path = "/Music/stairway_to_heaven.mp3",
                    size = 8000000L,
                    fileType = RemoteFileType.Audio(AudioFormat.MP3),
                )
            val dir = RemoteDirectory(path = "/Music/", name = "Music", files = listOf(file))
            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = 1L,
                    path = "/Music/",
                    dataJson = RemoteDirectoryJsonSerializer.serialize(dir),
                    lastUpdatedMs = 1000L,
                ),
            )

            val result = repository.search("stairway").first()
            assertEquals(1, result.matchedTracks.size)
            val track = result.matchedTracks.first()
            assertEquals("stairway_to_heaven.mp3", track.title)
            assertEquals(1L, track.serverId)
            assertEquals("/Music/stairway_to_heaven.mp3", track.remotePath)
        }

    @Test
    fun search_withDirectoryContainingCueAndAudio_returnsPhysicalAudioTrackDirectly() =
        runTest {
            val cueFile =
                RemoteFile(
                    name = "Symphony.cue",
                    path = "/Music/Symphony.cue",
                    size = 1024L,
                )
            val flacFile =
                RemoteFile(
                    name = "Symphony.flac",
                    path = "/Music/Symphony.flac",
                    size = 100000000L,
                    fileType = RemoteFileType.Audio(AudioFormat.FLAC),
                )
            val dir =
                RemoteDirectory(
                    path = "/Music/",
                    name = "Music",
                    files = listOf(cueFile, flacFile),
                )
            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = 1L,
                    path = "/Music/",
                    dataJson = RemoteDirectoryJsonSerializer.serialize(dir),
                    lastUpdatedMs = 1000L,
                ),
            )

            // Search by track filename
            val symphonyResult = repository.search("Symphony").first()
            assertEquals(1, symphonyResult.matchedTracks.size)
            val track = symphonyResult.matchedTracks.first()
            assertEquals("Symphony.flac", track.title)
            assertEquals("/Music/Symphony.flac", track.remotePath)
        }

    @Test
    fun search_withSpecialCharacters_doesNotCrashAndMatchesLiterally() =
        runTest {
            val file1 =
                RemoteFile(
                    name = "[FLAC] Best of 2024 (Live) [100%].flac",
                    path = "/Music/[FLAC] Best of 2024 (Live) [100%].flac",
                    size = 30000000L,
                    fileType = RemoteFileType.Audio(AudioFormat.FLAC),
                )
            val dir = RemoteDirectory(path = "/Music/", name = "Music", files = listOf(file1))
            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = 1L,
                    path = "/Music/",
                    dataJson = RemoteDirectoryJsonSerializer.serialize(dir),
                    lastUpdatedMs = 1000L,
                ),
            )

            // Query with brackets
            val resultBrackets = repository.search("[FLAC]").first()
            assertEquals(1, resultBrackets.matchedTracks.size)

            // Query with percent
            val resultPercent = repository.search("100%").first()
            assertEquals(1, resultPercent.matchedTracks.size)

            // Query with parenthesis
            val resultParen = repository.search("(Live)").first()
            assertEquals(1, resultParen.matchedTracks.size)
        }

    @Test
    fun search_withChineseAndMixedCharacters_matchesCorrectly() =
        runTest {
            val file =
                RemoteFile(
                    name = "晴天.flac",
                    path = "/Jay/晴天.flac",
                    size = 20000000L,
                    fileType = RemoteFileType.Audio(AudioFormat.FLAC),
                )
            val dir = RemoteDirectory(path = "/Jay/", name = "周杰伦", files = listOf(file))
            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = 1L,
                    path = "/Jay/",
                    dataJson = RemoteDirectoryJsonSerializer.serialize(dir),
                    lastUpdatedMs = 1000L,
                ),
            )
            trackMetadataDao.insertOrUpdate(
                TrackMetadataEntity(
                    serverId = 1L,
                    remotePath = "/Jay/晴天.flac",
                    title = "晴天",
                    artist = "周杰伦",
                    album = "叶惠美",
                    trackNumber = 3,
                    durationMs = 269000L,
                    coverThumbnailPath = null,
                ),
            )

            // Search directory by Chinese name
            val dirResult = repository.search("周杰伦").first()
            assertEquals(1, dirResult.matchedDirectories.size)
            assertEquals("周杰伦", dirResult.matchedDirectories.first().name)
            assertEquals(1, dirResult.matchedTracks.size)
            assertEquals("晴天", dirResult.matchedTracks.first().title)

            // Search track by Chinese album
            val albumResult = repository.search("叶惠美").first()
            assertEquals(1, albumResult.matchedTracks.size)
            assertEquals("晴天", albumResult.matchedTracks.first().title)

            // Search track by Chinese partial title
            val titleResult = repository.search("晴").first()
            assertEquals(1, titleResult.matchedTracks.size)
        }

    @Test
    fun search_withServerIdFilter_onlyReturnsTargetServerItems() =
        runTest {
            val dir1 = RemoteDirectory(path = "/Music/Pop/", name = "Pop")
            val dir2 = RemoteDirectory(path = "/Music/Pop/", name = "Pop")
            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = 1L,
                    path = "/Music/Pop/",
                    dataJson = RemoteDirectoryJsonSerializer.serialize(dir1),
                    lastUpdatedMs = 1000L,
                ),
            )
            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = 2L,
                    path = "/Music/Pop/",
                    dataJson = RemoteDirectoryJsonSerializer.serialize(dir2),
                    lastUpdatedMs = 1000L,
                ),
            )

            // Query with serverId = 1
            val result1 = repository.search("Pop", serverId = 1L).first()
            assertEquals(1, result1.matchedDirectories.size)

            // Query with serverId = 2
            val result2 = repository.search("Pop", serverId = 2L).first()
            assertEquals(1, result2.matchedDirectories.size)

            // Query without serverId: matches both
            val resultAll = repository.search("Pop", serverId = null).first()
            assertEquals(2, resultAll.matchedDirectories.size)
        }

    @Test
    fun search_deduplicatesDirectoriesAndTracks() =
        runTest {
            // Root has subfolder /Music/Rock/
            val rootDir =
                RemoteDirectory(
                    path = "/Music/",
                    name = "Music",
                    subDirectories = listOf(RemoteDirectory(path = "/Music/Rock/", name = "Rock")),
                )
            // And /Music/Rock/ is also cached as its own entity
            val rockDir =
                RemoteDirectory(
                    path = "/Music/Rock/",
                    name = "Rock",
                )
            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = 1L,
                    path = "/Music/",
                    dataJson = RemoteDirectoryJsonSerializer.serialize(rootDir),
                    lastUpdatedMs = 1000L,
                ),
            )
            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = 1L,
                    path = "/Music/Rock/",
                    dataJson = RemoteDirectoryJsonSerializer.serialize(rockDir),
                    lastUpdatedMs = 1000L,
                ),
            )

            val result = repository.search("Rock").first()
            // Deduplicated: should be exactly 1 Rock directory for server 1
            assertEquals(1, result.matchedDirectories.size)
            assertEquals("/Music/Rock/", result.matchedDirectories.first().path)
        }

    @Test
    fun recordSearchQuery_persistsAndReturnsHistoryInLruOrder() =
        runTest {
            assertTrue(repository.getSearchHistory().isEmpty())

            val history1 = repository.recordSearchQuery("Beethoven")
            assertEquals(listOf("Beethoven"), history1)
            assertEquals(listOf("Beethoven"), repository.getSearchHistory())

            val history2 = repository.recordSearchQuery("Mozart")
            assertEquals(listOf("Mozart", "Beethoven"), history2)
            assertEquals(listOf("Mozart", "Beethoven"), repository.getSearchHistory())

            val history3 = repository.recordSearchQuery("Bach")
            assertEquals(listOf("Bach", "Mozart", "Beethoven"), history3)
            assertEquals(listOf("Bach", "Mozart", "Beethoven"), repository.getSearchHistory())
        }

    @Test
    fun recordSearchQuery_withWhitespace_trimsQuery() =
        runTest {
            val history = repository.recordSearchQuery("   Chopin   ")
            assertEquals(listOf("Chopin"), history)
            assertEquals(listOf("Chopin"), repository.getSearchHistory())
        }

    @Test
    fun recordSearchQuery_withEmptyOrBlank_doesNotRecord() =
        runTest {
            repository.recordSearchQuery("Brahms")

            val afterEmpty = repository.recordSearchQuery("")
            assertEquals(listOf("Brahms"), afterEmpty)

            val afterSpaces = repository.recordSearchQuery("   \t  ")
            assertEquals(listOf("Brahms"), afterSpaces)
            assertEquals(listOf("Brahms"), repository.getSearchHistory())
        }

    @Test
    fun recordSearchQuery_withDuplicates_movesToFrontWithoutDuplication() =
        runTest {
            repository.recordSearchQuery("Track A")
            repository.recordSearchQuery("Track B")
            repository.recordSearchQuery("Track C")
            assertEquals(listOf("Track C", "Track B", "Track A"), repository.getSearchHistory())

            // Re-record "Track B", should jump to head
            val updated = repository.recordSearchQuery("Track B")
            assertEquals(listOf("Track B", "Track C", "Track A"), updated)
            assertEquals(listOf("Track B", "Track C", "Track A"), repository.getSearchHistory())
        }

    @Test
    fun recordSearchQuery_exceedingCapacity_capsAt20Items() =
        runTest {
            for (i in 1..25) {
                repository.recordSearchQuery("Query $i")
            }

            val history = repository.getSearchHistory()
            assertEquals(20, history.size)
            assertEquals("Query 25", history.first())
            assertEquals("Query 6", history.last())
            assertFalse(history.contains("Query 1"))
            assertFalse(history.contains("Query 5"))
        }

    @Test
    fun removeSearchHistory_removesItemAndReturnsUpdated() =
        runTest {
            repository.recordSearchQuery("Alpha")
            repository.recordSearchQuery("Beta")
            repository.recordSearchQuery("Gamma")

            val updated = repository.removeSearchHistory("Beta")
            assertEquals(listOf("Gamma", "Alpha"), updated)
            assertEquals(listOf("Gamma", "Alpha"), repository.getSearchHistory())

            // Removing non-existent query does not modify list
            val afterNonExistent = repository.removeSearchHistory("Delta")
            assertEquals(listOf("Gamma", "Alpha"), afterNonExistent)
        }

    @Test
    fun clearSearchHistory_removesAllHistory() =
        runTest {
            repository.recordSearchQuery("Alpha")
            repository.recordSearchQuery("Beta")
            assertEquals(2, repository.getSearchHistory().size)

            repository.clearSearchHistory()
            assertTrue(repository.getSearchHistory().isEmpty())
        }

    @Test
    fun searchHistory_persistsAcrossRepositoryInstancesWithSameContext() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            repository.recordSearchQuery("Persistent Query 1")
            repository.recordSearchQuery("Persistent Query 2")

            val newRepo =
                LibrarySearchRepositoryImpl(
                    directoryCacheDao = directoryCacheDao,
                    trackMetadataDao = trackMetadataDao,
                    context = context,
                )

            assertEquals(listOf("Persistent Query 2", "Persistent Query 1"), newRepo.getSearchHistory())
        }

    @Test
    fun search_withMediaTrackIndex_retrievesUnvisitedDeepTracksInstantly() =
        runTest {
            val deepTrack =
                MediaTrackIndexEntity(
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://media/external/audio/12345",
                    fileName = "01 - Pink Floyd - Comfortably Numb.flac",
                    fileSize = 52000000L,
                    lastModified = 1700000000L,
                    format = "FLAC",
                    parentPath = "/local/primary%3AMusic/Rock/PinkFloyd/TheWall/",
                    parsedTitle = "Comfortably Numb",
                    parsedArtist = "Pink Floyd",
                )
            mediaTrackIndexDao.insertOrUpdate(deepTrack)

            val startTime = System.currentTimeMillis()
            val result = repository.search("Comfortably").first()
            val elapsed = System.currentTimeMillis() - startTime

            assertTrue("Search completed in under 50ms (actual: ${elapsed}ms)", elapsed < 50)
            assertEquals(1, result.matchedTracks.size)
            val track = result.matchedTracks.first()
            assertEquals("Comfortably Numb", track.title)
            assertEquals("Pink Floyd", track.artist)
            assertEquals(LOCAL_STORAGE_ID, track.serverId)
            assertEquals("content://media/external/audio/12345", track.remotePath)
            assertEquals(AudioFormat.FLAC, track.format)
        }

    @Test
    fun search_withMediaTrackIndex_discoversDeepUnvisitedDirectories() =
        runTest {
            val deepTrack =
                MediaTrackIndexEntity(
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://media/external/audio/999",
                    fileName = "Symphony5.flac",
                    fileSize = 40000000L,
                    lastModified = 1700000000L,
                    format = "FLAC",
                    parentPath = "/local/primary%3AMusic/Classical/Beethoven/",
                    parsedTitle = "Symphony 5",
                    parsedArtist = "Beethoven",
                )
            mediaTrackIndexDao.insertOrUpdate(deepTrack)

            val result = repository.search("Beethoven").first()
            assertEquals(1, result.matchedDirectories.size)
            val dir = result.matchedDirectories.first()
            assertEquals("/local/primary%3AMusic/Classical/Beethoven/", dir.path)
            assertEquals("Beethoven", dir.name)
            assertEquals(LOCAL_STORAGE_ID, dir.serverId)
        }

    @Test
    fun search_withMediaTrackIndex_overlaysTrackMetadataWhenPresent() =
        runTest {
            val indexed =
                MediaTrackIndexEntity(
                    serverId = 1L,
                    remotePath = "/Music/song.mp3",
                    fileName = "01 - Artist - Song.mp3",
                    fileSize = 10000000L,
                    lastModified = 1000L,
                    format = "MP3",
                    parentPath = "/Music/",
                    parsedTitle = "Song",
                    parsedArtist = "Artist",
                )
            mediaTrackIndexDao.insertOrUpdate(indexed)

            // TrackMetadataDao contains rich ID3 tags
            trackMetadataDao.insertOrUpdate(
                TrackMetadataEntity(
                    serverId = 1L,
                    remotePath = "/Music/song.mp3",
                    title = "Enriched Song Title",
                    artist = "Enriched Artist",
                    album = "Enriched Album",
                    trackNumber = 1,
                    durationMs = 245000L,
                    coverThumbnailPath = "/thumbnails/cover.jpg",
                ),
            )

            val result = repository.search("Enriched").first()
            assertEquals(1, result.matchedTracks.size)
            val track = result.matchedTracks.first()
            assertEquals("Enriched Song Title", track.title)
            assertEquals("Enriched Artist", track.artist)
            assertEquals("Enriched Album", track.album)
            assertEquals(245000L, track.durationMs)
            assertEquals("/thumbnails/cover.jpg", track.coverThumbnailPath)
        }

    @Test
    fun search_withLocalStorage_filtersByLocalStorageId() =
        runTest {
            val localTrack =
                MediaTrackIndexEntity(
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://local/song.flac",
                    fileName = "song.flac",
                    fileSize = 20000000L,
                    lastModified = 1000L,
                    format = "FLAC",
                    parentPath = "/local/Music/",
                    parsedTitle = "Acoustic Melody",
                    parsedArtist = "Guitarist",
                )
            val remoteTrack =
                MediaTrackIndexEntity(
                    serverId = 10L,
                    remotePath = "/nas/song.flac",
                    fileName = "song.flac",
                    fileSize = 20000000L,
                    lastModified = 1000L,
                    format = "FLAC",
                    parentPath = "/nas/",
                    parsedTitle = "Acoustic Melody",
                    parsedArtist = "Guitarist",
                )
            mediaTrackIndexDao.insertAll(listOf(localTrack, remoteTrack))

            // Search local only
            val localOnly = repository.search("Acoustic", serverId = LOCAL_STORAGE_ID).first()
            assertEquals(1, localOnly.matchedTracks.size)
            assertEquals(LOCAL_STORAGE_ID, localOnly.matchedTracks.first().serverId)

            // Search remote only
            val remoteOnly = repository.search("Acoustic", serverId = 10L).first()
            assertEquals(1, remoteOnly.matchedTracks.size)
            assertEquals(10L, remoteOnly.matchedTracks.first().serverId)

            // Global search
            val global = repository.search("Acoustic", serverId = null).first()
            assertEquals(2, global.matchedTracks.size)
        }

    @Test
    fun search_withPhysicalTrackInMediaTrackIndex_returnsAudioTrackWithMetadata() =
        runTest {
            val physicalTrack =
                MediaTrackIndexEntity(
                    serverId = 1L,
                    remotePath = "/Music/Album.flac",
                    fileName = "Track One.flac",
                    fileSize = 120000000L,
                    lastModified = 1700000000L,
                    format = "FLAC",
                    parentPath = "/Music/",
                    parsedTitle = "Track One",
                    parsedArtist = "Band Artist",
                )
            mediaTrackIndexDao.insertOrUpdate(physicalTrack)

            // Parent audio metadata cached in TrackMetadataDao
            trackMetadataDao.insertOrUpdate(
                TrackMetadataEntity(
                    serverId = 1L,
                    remotePath = "/Music/Album.flac",
                    title = "Album Title",
                    artist = "Band Artist",
                    album = "Masterpiece Album",
                    trackNumber = 1,
                    durationMs = 2400000L,
                    coverThumbnailPath = "/thumbnails/masterpiece.jpg",
                ),
            )

            val result = repository.search("Track One").first()
            assertEquals(1, result.matchedTracks.size)
            val track = result.matchedTracks.first()

            assertEquals("1:/Music/Album.flac", track.id)
            assertEquals("/Music/Album.flac", track.remotePath)
            assertEquals("Album Title", track.title)
            assertEquals("Band Artist", track.artist)
            assertEquals("Masterpiece Album", track.album)
            assertEquals("/thumbnails/masterpiece.jpg", track.coverThumbnailPath)
            assertEquals(2400000L, track.durationMs)
        }

    @Test
    fun search_crossSourceGlobalSearch_combinesLocalStorageAndMultipleWebDavServers() =
        runTest {
            val localTrack =
                MediaTrackIndexEntity(
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://media/external/audio/501",
                    fileName = "Nocturne.mp3",
                    fileSize = 10000000L,
                    lastModified = 1000L,
                    format = "MP3",
                    parentPath = "/local/Classical/",
                    parsedTitle = "Nocturne in E-flat",
                    parsedArtist = "Chopin",
                )
            val remoteServer1Track =
                MediaTrackIndexEntity(
                    serverId = 1L,
                    remotePath = "/nas1/Music/Nocturne.flac",
                    fileName = "Nocturne.flac",
                    fileSize = 30000000L,
                    lastModified = 2000L,
                    format = "FLAC",
                    parentPath = "/nas1/Music/",
                    parsedTitle = "Nocturne in C-sharp minor",
                    parsedArtist = "Chopin",
                )
            val remoteServer2Track =
                MediaTrackIndexEntity(
                    serverId = 2L,
                    remotePath = "/nas2/Audio/Nocturne.wav",
                    fileName = "Nocturne.wav",
                    fileSize = 50000000L,
                    lastModified = 3000L,
                    format = "WAV",
                    parentPath = "/nas2/Audio/",
                    parsedTitle = "Nocturne Op. 9",
                    parsedArtist = "Chopin",
                )
            mediaTrackIndexDao.insertAll(listOf(localTrack, remoteServer1Track, remoteServer2Track))

            // Global search (serverId = null) combines all 3 sources
            val globalResult = repository.search("Nocturne", serverId = null).first()
            assertEquals(3, globalResult.matchedTracks.size)
            val serverIds = globalResult.matchedTracks.map { it.serverId }.toSet()
            assertEquals(setOf(LOCAL_STORAGE_ID, 1L, 2L), serverIds)

            // Local only search (serverId = LOCAL_STORAGE_ID)
            val localOnly = repository.search("Nocturne", serverId = LOCAL_STORAGE_ID).first()
            assertEquals(1, localOnly.matchedTracks.size)
            assertEquals(LOCAL_STORAGE_ID, localOnly.matchedTracks.first().serverId)

            // Remote server 1 only
            val server1Only = repository.search("Nocturne", serverId = 1L).first()
            assertEquals(1, server1Only.matchedTracks.size)
            assertEquals(1L, server1Only.matchedTracks.first().serverId)

            // Remote server 2 only
            val server2Only = repository.search("Nocturne", serverId = 2L).first()
            assertEquals(1, server2Only.matchedTracks.size)
            assertEquals(2L, server2Only.matchedTracks.first().serverId)
        }

    @Test
    fun search_metadataHarmonization_enrichesDurationCoverAlbumForIndexedTracks() =
        runTest {
            val regularTrack =
                MediaTrackIndexEntity(
                    serverId = 1L,
                    remotePath = "/Music/Ordinary.flac",
                    fileName = "Ordinary.flac",
                    fileSize = 25000000L,
                    lastModified = 1000L,
                    format = "FLAC",
                    parentPath = "/Music/",
                    parsedTitle = "Ordinary",
                    parsedArtist = "Indie Band",
                )
            val concertTrack =
                MediaTrackIndexEntity(
                    serverId = 1L,
                    remotePath = "/Music/Concert.flac",
                    fileName = "Concert.flac",
                    fileSize = 300000000L,
                    lastModified = 1000L,
                    format = "FLAC",
                    parentPath = "/Music/",
                    parsedTitle = "Concert Intro",
                    parsedArtist = "Live Band",
                )
            mediaTrackIndexDao.insertAll(listOf(regularTrack, concertTrack))

            // Enriched ID3 metadata in trackMetadataDao
            trackMetadataDao.insertOrUpdateAll(
                listOf(
                    TrackMetadataEntity(
                        serverId = 1L,
                        remotePath = "/Music/Ordinary.flac",
                        title = "Ordinary Song",
                        artist = "Indie Band",
                        album = "Studio Album",
                        trackNumber = 1,
                        durationMs = 210000L,
                        coverThumbnailPath = "/covers/studio.jpg",
                    ),
                    TrackMetadataEntity(
                        serverId = 1L,
                        remotePath = "/Music/Concert.flac",
                        title = "Full Concert",
                        artist = "Live Band",
                        album = "Live at Wembley",
                        trackNumber = 1,
                        durationMs = 5400000L,
                        coverThumbnailPath = "/covers/wembley.jpg",
                    ),
                ),
            )

            // Regular track harmonization
            val regularResult = repository.search("Ordinary").first()
            assertEquals(1, regularResult.matchedTracks.size)
            val matchedRegular = regularResult.matchedTracks.first()
            assertEquals("Ordinary Song", matchedRegular.title)
            assertEquals("Studio Album", matchedRegular.album)
            assertEquals(210000L, matchedRegular.durationMs)
            assertEquals("/covers/studio.jpg", matchedRegular.coverThumbnailPath)

            // Concert track harmonization
            val concertResult = repository.search("Concert Intro").first()
            assertEquals(1, concertResult.matchedTracks.size)
            val matchedConcert = concertResult.matchedTracks.first()
            assertEquals("Full Concert", matchedConcert.title)
            assertEquals("Live at Wembley", matchedConcert.album)
            assertEquals(5400000L, matchedConcert.durationMs)
            assertEquals("/covers/wembley.jpg", matchedConcert.coverThumbnailPath)
        }

    @Test
    fun search_withPhysicalAudioTrack_returnsMatchedTrack() =
        runTest {
            val audioTrack =
                MediaTrackIndexEntity(
                    serverId = 1L,
                    remotePath = "/Music/Symphony.flac",
                    fileName = "Symphony.flac",
                    fileSize = 100000000L,
                    lastModified = 1000L,
                    format = "FLAC",
                    parentPath = "/Music/",
                    parsedTitle = "Symphony No. 9",
                    parsedArtist = "Beethoven",
                )
            mediaTrackIndexDao.insertAll(listOf(audioTrack))

            val result = repository.search("Symphony").first()
            assertEquals(1, result.matchedTracks.size)
            val track = result.matchedTracks.first()
            assertEquals("1:/Music/Symphony.flac", track.id)
            assertEquals("/Music/Symphony.flac", track.remotePath)
        }

    @Test
    fun search_whenServerIdIsWebDav_neverReturnsLocalStorageTracksEvenIfMetadataHasServerId() =
        runTest {
            val localTrack =
                MediaTrackIndexEntity(
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://media/external/audio/101",
                    fileName = "Song.mp3",
                    fileSize = 5000000L,
                    lastModified = 1000L,
                    format = "MP3",
                    parentPath = "/local/content/",
                    parsedTitle = "Song Title",
                    parsedArtist = "Song Artist",
                )
            val webDavTrack =
                MediaTrackIndexEntity(
                    serverId = 1L,
                    remotePath = "/Music/RemoteSong.mp3",
                    fileName = "RemoteSong.mp3",
                    fileSize = 6000000L,
                    lastModified = 2000L,
                    format = "MP3",
                    parentPath = "/Music/",
                    parsedTitle = "Song Title",
                    parsedArtist = "Song Artist",
                )
            mediaTrackIndexDao.insertAll(listOf(localTrack, webDavTrack))

            // Even if a local content:// URI was mistakenly recorded with serverId = 1 in track_metadata
            trackMetadataDao.insertOrUpdate(
                TrackMetadataEntity(
                    serverId = 1L,
                    remotePath = "content://media/external/audio/101",
                    title = "Song Title",
                    artist = "Song Artist",
                    album = "Album",
                    trackNumber = 1,
                    durationMs = 200000L,
                    coverThumbnailPath = null,
                ),
            )

            // Search with serverId = 1L (WebDAV source)
            val result = repository.search("Song Title", serverId = 1L).first()
            assertEquals("Only WebDAV track should match; local track must be strictly excluded", 1, result.matchedTracks.size)
            val matched = result.matchedTracks.first()
            assertEquals(1L, matched.serverId)
            assertEquals("/Music/RemoteSong.mp3", matched.remotePath)
        }

    @Test
    fun search_inGlobalMode_deduplicatesTracksAcrossMediaTrackIndexDirectoryCacheAndMetadata() =
        runTest {
            val localDocUri = "content://media/external/audio/202"
            val localTrack =
                MediaTrackIndexEntity(
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = localDocUri,
                    fileName = "UniqueSong.flac",
                    fileSize = 10000000L,
                    lastModified = 1000L,
                    format = "FLAC",
                    parentPath = "/local/content/",
                    parsedTitle = "Unique Song",
                    parsedArtist = "Unique Artist",
                )
            mediaTrackIndexDao.insertOrUpdate(localTrack)

            // Same track also cached in directory_cache JSON
            val dirJson =
                """
                {
                    "path": "/local/content/",
                    "name": "content",
                    "subDirectories": [],
                    "files": [
                        {
                            "name": "UniqueSong.flac",
                            "path": "$localDocUri",
                            "size": 10000000,
                            "fileType": {"type": "Audio", "format": "FLAC"}
                        }
                    ]
                }
                """.trimIndent()
            directoryCacheDao.insertOrUpdate(
                DirectoryCacheEntity(
                    serverId = LOCAL_STORAGE_ID,
                    path = "/local/content/",
                    dataJson = dirJson,
                    lastUpdatedMs = 1000L,
                ),
            )

            // And also present in track_metadata
            trackMetadataDao.insertOrUpdate(
                TrackMetadataEntity(
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = localDocUri,
                    title = "Unique Song",
                    artist = "Unique Artist",
                    album = "Great Album",
                    trackNumber = 1,
                    durationMs = 180000L,
                    coverThumbnailPath = null,
                ),
            )

            // Search in global mode (serverId = null)
            val result = repository.search("Unique Song", serverId = null).first()
            assertEquals("Track must appear exactly once despite presence in multiple tables", 1, result.matchedTracks.size)
            assertEquals(localDocUri, result.matchedTracks.first().remotePath)
        }

    @Test
    fun observeSyncState_isolatesStateByServerId() =
        runTest {
            val localServer = WebDavServer.createLocalStorage()
            repository.syncServer(localServer, force = false)

            val localState = repository.observeSyncState(LOCAL_STORAGE_ID).first()
            val otherServerState = repository.observeSyncState(99L).first()
            val globalState = repository.observeSyncState(null).first()

            assertTrue(
                "Local storage sync state must be Completed",
                localState is com.webdav.player.domain.model.LibrarySyncState.Completed,
            )
            assertEquals("Other server sync state must remain Idle", com.webdav.player.domain.model.LibrarySyncState.Idle, otherServerState)
            assertTrue("Global sync state must reflect Completed", globalState is com.webdav.player.domain.model.LibrarySyncState.Completed)
        }
}
