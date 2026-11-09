package com.webdav.player.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.webdav.player.data.local.AppDatabase
import com.webdav.player.data.local.DirectorySyncCheckpointDao
import com.webdav.player.data.local.DirectorySyncCheckpointEntity
import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.local.MediaTrackIndexEntity
import com.webdav.player.data.local.TrackMetadataDao
import com.webdav.player.data.local.TrackMetadataEntity
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaLibrarySearchCoordinatorTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var mediaTrackIndexDao: MediaTrackIndexDao
    private lateinit var checkpointDao: DirectorySyncCheckpointDao
    private lateinit var trackMetadataDao: TrackMetadataDao
    private lateinit var coordinator: MediaLibrarySearchCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mediaTrackIndexDao = database.mediaTrackIndexDao()
        checkpointDao = database.directorySyncCheckpointDao()
        trackMetadataDao = database.trackMetadataDao()

        coordinator = MediaLibrarySearchCoordinator(
            mediaTrackIndexDao = mediaTrackIndexDao,
            directorySyncCheckpointDao = checkpointDao,
            trackMetadataDao = trackMetadataDao,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun blankOrEmptyQuery_returnsEmptyResultImmediately() = runTest {
        val resultBlank = coordinator.search("   ").first()
        assertTrue(resultBlank.matchedTracks.isEmpty())
        assertTrue(resultBlank.matchedDirectories.isEmpty())

        val resultEmpty = coordinator.search("").first()
        assertTrue(resultEmpty.matchedTracks.isEmpty())
        assertTrue(resultEmpty.matchedDirectories.isEmpty())
    }

    @Test
    fun search_combinesTracksAndDirectoriesAndMetadata() = runTest {
        mediaTrackIndexDao.upsertTracks(
            listOf(
                MediaTrackIndexEntity(
                    serverId = 1L,
                    remotePath = "/music/TrackA.mp3",
                    fileName = "TrackA.mp3",
                    fileSize = 1024L,
                    lastModified = 1000L,
                    format = "MP3",
                    parentPath = "/music/",
                    parsedTitle = "Song A",
                    parsedArtist = "Artist X",
                ),
            ),
        )
        checkpointDao.upsertCheckpoint(
            DirectorySyncCheckpointEntity(
                serverId = 1L,
                path = "/music/Rock/",
                lastModified = 1000L,
                etag = "etag1",
                lastScannedMs = System.currentTimeMillis(),
            ),
        )
        trackMetadataDao.insertOrUpdate(
            TrackMetadataEntity(
                serverId = 1L,
                remotePath = "/music/TrackB.flac",
                title = "Song B Rock",
                artist = "Artist Y",
                album = "Album Z",
                trackNumber = 1,
                durationMs = 200000L,
                coverThumbnailPath = null,
            ),
        )

        val result = coordinator.search("Rock").first()
        assertEquals(1, result.matchedDirectories.size)
        assertEquals("Rock", result.matchedDirectories[0].name)

        assertEquals(1, result.matchedTracks.size)
        assertEquals("Song B Rock", result.matchedTracks[0].title)
    }

    @Test
    fun search_sourceIsolation_localStorageVsRemote() = runTest {
        mediaTrackIndexDao.upsertTracks(
            listOf(
                MediaTrackIndexEntity(
                    serverId = 1L,
                    remotePath = "/music/song.mp3",
                    fileName = "song.mp3",
                    fileSize = 1024L,
                    lastModified = 1000L,
                    format = "MP3",
                    parentPath = "/music/",
                    parsedTitle = "Remote Song",
                    parsedArtist = "Queen",
                ),
                MediaTrackIndexEntity(
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://media/external/audio/media/1",
                    fileName = "local_song.mp3",
                    fileSize = 2048L,
                    lastModified = 1000L,
                    format = "MP3",
                    parentPath = "/local/Music/",
                    parsedTitle = "Local Queen Song",
                    parsedArtist = "Queen",
                ),
            ),
        )

        // Single remote server filter
        val remoteResult = coordinator.search("Queen", serverId = 1L).first()
        assertEquals(1, remoteResult.matchedTracks.size)
        assertEquals(1L, remoteResult.matchedTracks[0].serverId)
        assertEquals("Remote Song", remoteResult.matchedTracks[0].title)

        // Local storage filter
        val localResult = coordinator.search("Queen", serverId = LOCAL_STORAGE_ID).first()
        assertEquals(1, localResult.matchedTracks.size)
        assertEquals(LOCAL_STORAGE_ID, localResult.matchedTracks[0].serverId)
        assertEquals("Local Queen Song", localResult.matchedTracks[0].title)

        // Global search includes both
        val globalResult = coordinator.search("Queen", serverId = null).first()
        assertEquals(2, globalResult.matchedTracks.size)
    }

    @Test
    fun search_deduplicatesTracksBetweenIndexAndMetadata() = runTest {
        mediaTrackIndexDao.upsertTracks(
            listOf(
                MediaTrackIndexEntity(
                    serverId = 1L,
                    remotePath = "/music/track.mp3",
                    fileName = "track.mp3",
                    fileSize = 1024L,
                    lastModified = 1000L,
                    format = "MP3",
                    parentPath = "/music/",
                    parsedTitle = "Indexed Title",
                    parsedArtist = "Indexed Artist",
                ),
            ),
        )
        trackMetadataDao.insertOrUpdate(
            TrackMetadataEntity(
                serverId = 1L,
                remotePath = "/music/track.mp3",
                title = "Enriched Metadata Title",
                artist = "Enriched Artist",
                album = "Greatest Hits",
                trackNumber = 1,
                durationMs = 180000L,
                coverThumbnailPath = null,
            ),
        )

        val result = coordinator.search("Enriched").first()
        // Should only match once, enriched with metadata title
        assertEquals(1, result.matchedTracks.size)
        assertEquals("Enriched Metadata Title", result.matchedTracks[0].title)
    }
}
