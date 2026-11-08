package com.webdav.player.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
class MediaTrackIndexDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: MediaTrackIndexDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.mediaTrackIndexDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndRetrieve_preservesAllIndexedFields() =
        runTest {
            val track =
                MediaTrackIndexEntity(
                    serverId = 0L,
                    remotePath = "content://media/external/audio/1",
                    fileName = "01 - Queen - Bohemian Rhapsody.flac",
                    fileSize = 45000000L,
                    lastModified = 1700000000L,
                    format = "FLAC",
                    isCue = false,
                    parentPath = "/local/primary%3AMusic/Rock/",
                    parsedTitle = "Bohemian Rhapsody",
                    parsedArtist = "Queen",
                )

            dao.insertOrUpdate(track)

            val retrieved = dao.getTrack(0L, "content://media/external/audio/1")
            assertNotNull(retrieved)
            assertEquals("Bohemian Rhapsody", retrieved?.parsedTitle)
            assertEquals("Queen", retrieved?.parsedArtist)
            assertEquals("FLAC", retrieved?.format)
            assertEquals(45000000L, retrieved?.fileSize)
            assertEquals("/local/primary%3AMusic/Rock/", retrieved?.parentPath)
            assertEquals(1, dao.countByServerId(0L))
        }

    @Test
    fun search_matchesByTitleArtistFileNameAndParentPath_caseInsensitive() =
        runTest {
            val tracks =
                listOf(
                    MediaTrackIndexEntity(
                        serverId = 0L,
                        remotePath = "content://music/track1.flac",
                        fileName = "Hotel California.flac",
                        fileSize = 30000000L,
                        lastModified = 1000L,
                        format = "FLAC",
                        isCue = false,
                        parentPath = "/local/Music/Eagles/",
                        parsedTitle = "Hotel California",
                        parsedArtist = "Eagles",
                    ),
                    MediaTrackIndexEntity(
                        serverId = 1L,
                        remotePath = "/WebDAV/Pop/BillieJean.mp3",
                        fileName = "BillieJean.mp3",
                        fileSize = 8000000L,
                        lastModified = 2000L,
                        format = "MP3",
                        isCue = false,
                        parentPath = "/WebDAV/Pop/",
                        parsedTitle = "Billie Jean",
                        parsedArtist = "Michael Jackson",
                    ),
                )
            dao.insertAll(tracks)

            // Search by title
            val byTitle = dao.search("hotel").first()
            assertEquals(1, byTitle.size)
            assertEquals("Hotel California", byTitle.first().parsedTitle)

            // Search by artist
            val byArtist = dao.search("jackson").first()
            assertEquals(1, byArtist.size)
            assertEquals("Billie Jean", byArtist.first().parsedTitle)

            // Search by file name
            val byFileName = dao.search("billiejean").first()
            assertEquals(1, byFileName.size)

            // Search by parent path
            val byPath = dao.search("Eagles").first()
            assertEquals(1, byPath.size)
            assertEquals("Hotel California", byPath.first().parsedTitle)
        }

    @Test
    fun search_withServerIdFilter_restrictsResultsToTargetServer() =
        runTest {
            val t1 =
                MediaTrackIndexEntity(
                    serverId = 0L,
                    remotePath = "content://music/song.mp3",
                    fileName = "song.mp3",
                    fileSize = 5000L,
                    lastModified = 1000L,
                    format = "MP3",
                    isCue = false,
                    parentPath = "/local/music/",
                    parsedTitle = "Common Song",
                    parsedArtist = "Common Artist",
                )
            val t2 =
                MediaTrackIndexEntity(
                    serverId = 2L,
                    remotePath = "/nas/song.mp3",
                    fileName = "song.mp3",
                    fileSize = 5000L,
                    lastModified = 1000L,
                    format = "MP3",
                    isCue = false,
                    parentPath = "/nas/",
                    parsedTitle = "Common Song",
                    parsedArtist = "Common Artist",
                )
            dao.insertAll(listOf(t1, t2))

            // Query server 0L only (local storage)
            val localResult = dao.search("Common", serverId = 0L).first()
            assertEquals(1, localResult.size)
            assertEquals(0L, localResult.first().serverId)

            // Query server 2L only (remote NAS)
            val remoteResult = dao.search("Common", serverId = 2L).first()
            assertEquals(1, remoteResult.size)
            assertEquals(2L, remoteResult.first().serverId)

            // Query global (null serverId)
            val globalResult = dao.search("Common", serverId = null).first()
            assertEquals(2, globalResult.size)
        }

    @Test
    fun deleteByServerAndPathPrefix_removesSubtreeTracksOnly() =
        runTest {
            val tracks =
                listOf(
                    MediaTrackIndexEntity(
                        serverId = 0L,
                        remotePath = "content://tree1/song1.mp3",
                        fileName = "song1.mp3",
                        fileSize = 1000L,
                        lastModified = 1000L,
                        format = "MP3",
                        isCue = false,
                        parentPath = "/local/tree1/Album1/",
                        parsedTitle = "Song 1",
                        parsedArtist = null,
                    ),
                    MediaTrackIndexEntity(
                        serverId = 0L,
                        remotePath = "content://tree1/song2.mp3",
                        fileName = "song2.mp3",
                        fileSize = 1000L,
                        lastModified = 1000L,
                        format = "MP3",
                        isCue = false,
                        parentPath = "/local/tree1/Album2/",
                        parsedTitle = "Song 2",
                        parsedArtist = null,
                    ),
                    MediaTrackIndexEntity(
                        serverId = 0L,
                        remotePath = "content://tree2/song3.mp3",
                        fileName = "song3.mp3",
                        fileSize = 1000L,
                        lastModified = 1000L,
                        format = "MP3",
                        isCue = false,
                        parentPath = "/local/tree2/",
                        parsedTitle = "Song 3",
                        parsedArtist = null,
                    ),
                )
            dao.insertAll(tracks)
            assertEquals(3, dao.countByServerId(0L))

            // Delete tree1 only
            dao.deleteByServerAndPathPrefix(0L, "/local/tree1/")
            val remaining = dao.getTracksByServerId(0L)
            assertEquals(1, remaining.size)
            assertEquals("Song 3", remaining.first().parsedTitle)
        }

    @Test
    fun deleteByServerId_purgesAllTracksForServer() =
        runTest {
            dao.insertOrUpdate(
                MediaTrackIndexEntity(
                    serverId = 1L,
                    remotePath = "/file1.flac",
                    fileName = "file1.flac",
                    fileSize = 100L,
                    lastModified = 100L,
                    format = "FLAC",
                    isCue = false,
                    parentPath = "/",
                    parsedTitle = "Title",
                    parsedArtist = null,
                ),
            )
            assertEquals(1, dao.countByServerId(1L))

            dao.deleteByServerId(1L)
            assertEquals(0, dao.countByServerId(1L))
        }
}
