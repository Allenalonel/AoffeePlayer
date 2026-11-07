package com.webdav.player.data.cue

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.webdav.player.data.local.AppDatabase
import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.remote.ConnectionResult
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.ListDirectoryResult
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.RemoteFileType
import com.webdav.player.domain.model.WebDavServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CueVirtualTrackIndexerTest {
    private lateinit var database: AppDatabase
    private lateinit var mediaTrackIndexDao: MediaTrackIndexDao
    private lateinit var indexer: CueVirtualTrackIndexer

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

    private val testServer =
        WebDavServer(
            id = 5L,
            name = "Test NAS",
            url = "http://nas.local:5005",
            port = 5005,
        )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        mediaTrackIndexDao = database.mediaTrackIndexDao()
        fakeWebDavClient.textResponses.clear()

        val cueSheetResolver = DefaultCueSheetResolver(fakeWebDavClient)
        indexer = CueVirtualTrackIndexer(cueSheetResolver, mediaTrackIndexDao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun indexDirectoryCues_whenNoCueOrAudioFiles_returnsEmptyListAndNoDaoUpsert() =
        runTest {
            // Only audio files, no .cue
            val dirOnlyAudio =
                RemoteDirectory(
                    path = "/Music/Solo/",
                    name = "Solo",
                    files =
                        listOf(
                            RemoteFile(
                                name = "song.mp3",
                                path = "/Music/Solo/song.mp3",
                                size = 5000000L,
                                fileType = RemoteFileType.Audio(AudioFormat.MP3),
                            ),
                        ),
                )
            val result1 = indexer.indexDirectoryCues(testServer, dirOnlyAudio)
            assertTrue(result1.isEmpty())
            assertEquals(0, mediaTrackIndexDao.countByServerId(testServer.id))

            // Only .cue file, no audio
            val dirOnlyCue =
                RemoteDirectory(
                    path = "/Music/OrphanCue/",
                    name = "OrphanCue",
                    files =
                        listOf(
                            RemoteFile(
                                name = "album.cue",
                                path = "/Music/OrphanCue/album.cue",
                                size = 1000L,
                                fileType = RemoteFileType.Cue,
                            ),
                        ),
                )
            val result2 = indexer.indexDirectoryCues(testServer, dirOnlyCue)
            assertTrue(result2.isEmpty())
            assertEquals(0, mediaTrackIndexDao.countByServerId(testServer.id))
        }

    @Test
    fun indexDirectoryCues_resolvesVirtualTracks_persistsToDao_andReturnsMappedEntities() =
        runTest {
            val cueFile =
                RemoteFile(
                    name = "TheWall.cue",
                    path = "/Music/PinkFloyd/TheWall.cue",
                    size = 2048L,
                    fileType = RemoteFileType.Cue,
                )
            val audioFile =
                RemoteFile(
                    name = "TheWall.flac",
                    path = "/Music/PinkFloyd/TheWall.flac",
                    size = 500000000L,
                    lastModified = "1700000000000",
                    fileType = RemoteFileType.Audio(AudioFormat.FLAC),
                )
            val directory =
                RemoteDirectory(
                    path = "/Music/PinkFloyd/",
                    name = "PinkFloyd",
                    files = listOf(cueFile, audioFile),
                )

            val cueContent =
                """
                PERFORMER "Pink Floyd"
                TITLE "The Wall"
                FILE "TheWall.flac" WAVE
                  TRACK 01 AUDIO
                    TITLE "In the Flesh?"
                    INDEX 01 00:00:00
                  TRACK 02 AUDIO
                    TITLE "The Thin Ice"
                    PERFORMER "Pink Floyd ft. Guest"
                    INDEX 01 03:19:00
                  TRACK 03 AUDIO
                    TITLE "Another Brick in the Wall, Pt. 1"
                    INDEX 01 05:46:00
                """.trimIndent()
            fakeWebDavClient.textResponses["/Music/PinkFloyd/TheWall.cue"] = cueContent

            val indexed = indexer.indexDirectoryCues(testServer, directory)
            assertEquals(3, indexed.size)

            // Verify Track 01 mapping
            val track1 = indexed[0]
            assertEquals(testServer.id, track1.serverId)
            assertEquals("/Music/PinkFloyd/TheWall.flac#cue_1", track1.remotePath)
            assertEquals("In the Flesh?", track1.fileName)
            assertEquals("In the Flesh?", track1.parsedTitle)
            assertEquals("Pink Floyd", track1.parsedArtist)
            assertEquals(500000000L, track1.fileSize)
            assertEquals(1700000000000L, track1.lastModified)
            assertEquals("FLAC", track1.format)
            assertTrue(track1.isCue)
            assertEquals("/Music/PinkFloyd/", track1.parentPath)

            // Verify Track 02 mapping (overridden performer)
            val track2 = indexed[1]
            assertEquals("/Music/PinkFloyd/TheWall.flac#cue_2", track2.remotePath)
            assertEquals("The Thin Ice", track2.parsedTitle)
            assertEquals("Pink Floyd ft. Guest", track2.parsedArtist)

            // Verify DAO upsert
            assertEquals(3, mediaTrackIndexDao.countByServerId(testServer.id))
            val daoTrack = mediaTrackIndexDao.getTrack(testServer.id, "/Music/PinkFloyd/TheWall.flac#cue_2")
            assertNotNull(daoTrack)
            assertEquals("The Thin Ice", daoTrack?.parsedTitle)
            assertEquals("Pink Floyd ft. Guest", daoTrack?.parsedArtist)
            assertTrue(daoTrack?.isCue == true)
        }

    @Test
    fun indexDirectoryCues_withFallbackArtistFromParentFilename_whenCuePerformerMissing() =
        runTest {
            val cueFile =
                RemoteFile(
                    name = "album.cue",
                    path = "/Classical/album.cue",
                    size = 1000L,
                    fileType = RemoteFileType.Cue,
                )
            val audioFile =
                RemoteFile(
                    name = "Beethoven - Symphony No. 5.flac",
                    path = "/Classical/Beethoven - Symphony No. 5.flac",
                    size = 150000000L,
                    fileType = RemoteFileType.Audio(AudioFormat.FLAC),
                )
            val directory =
                RemoteDirectory(
                    path = "/Classical/",
                    name = "Classical",
                    files = listOf(cueFile, audioFile),
                )

            val cueContent =
                """
                TITLE "Symphony No. 5"
                FILE "Beethoven - Symphony No. 5.flac" WAVE
                  TRACK 01 AUDIO
                    TITLE "I. Allegro con brio"
                    INDEX 01 00:00:00
                """.trimIndent()
            fakeWebDavClient.textResponses["/Classical/album.cue"] = cueContent

            val indexed = indexer.indexDirectoryCues(testServer, directory)
            assertEquals(1, indexed.size)
            val track = indexed.first()
            assertEquals("I. Allegro con brio", track.parsedTitle)
            // Artist fell back to parent file name "Beethoven"
            assertEquals("Beethoven", track.parsedArtist)
        }

    @Test
    fun indexDirectoryCues_withHttpDateLastModified_parsesMillisProperly() =
        runTest {
            val cueFile =
                RemoteFile(
                    name = "disc.cue",
                    path = "/Music/disc.cue",
                    size = 1000L,
                    fileType = RemoteFileType.Cue,
                )
            val audioFile =
                RemoteFile(
                    name = "disc.flac",
                    path = "/Music/disc.flac",
                    size = 200000000L,
                    lastModified = "Thu, 15 Jun 2023 12:00:00 GMT",
                    fileType = RemoteFileType.Audio(AudioFormat.FLAC),
                )
            val directory =
                RemoteDirectory(
                    path = "/Music/",
                    name = "Music",
                    files = listOf(cueFile, audioFile),
                )

            val cueContent =
                """
                FILE "disc.flac" WAVE
                  TRACK 01 AUDIO
                    TITLE "Track One"
                    INDEX 01 00:00:00
                """.trimIndent()
            fakeWebDavClient.textResponses["/Music/disc.cue"] = cueContent

            val indexed = indexer.indexDirectoryCues(testServer, directory)
            assertEquals(1, indexed.size)
            assertTrue("lastModified should be parsed as positive timestamp", indexed.first().lastModified > 0L)
        }
}
