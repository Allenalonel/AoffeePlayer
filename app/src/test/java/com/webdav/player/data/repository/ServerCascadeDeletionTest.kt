package com.webdav.player.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.webdav.player.data.local.AppDatabase
import com.webdav.player.data.local.CoverArtStorageImpl
import com.webdav.player.data.local.DirectorySyncCheckpointDao
import com.webdav.player.data.local.DirectorySyncCheckpointEntity
import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.local.MediaTrackIndexEntity
import com.webdav.player.data.remote.OkHttpWebDavClient
import com.webdav.player.data.repository.MediaLibraryRepositoryImpl
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.repository.ServerRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLEncoder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServerCascadeDeletionTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var mediaTrackIndexDao: MediaTrackIndexDao
    private lateinit var checkpointDao: DirectorySyncCheckpointDao
    private lateinit var repository: ServerRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        mediaTrackIndexDao = database.mediaTrackIndexDao()
        checkpointDao = database.directorySyncCheckpointDao()

        val mediaLibraryRepository =
            MediaLibraryRepositoryImpl(
                webDavClient = OkHttpWebDavClient(),
                directoryCacheDao = database.directoryCacheDao(),
                trackMetadataDao = database.trackMetadataDao(),
                mediaTrackIndexDao = mediaTrackIndexDao,
                directorySyncCheckpointDao = checkpointDao,
                localStorageFolderDao = database.localStorageFolderDao(),
                context = context,
            )

        repository =
            ServerRepositoryImpl(
                serverDao = database.webDavServerDao(),
                coverArtStorage = CoverArtStorageImpl(context),
                trackMetadataDao = database.trackMetadataDao(),
                localStorageFolderDao = database.localStorageFolderDao(),
                context = context,
                cascadeInvalidator = mediaLibraryRepository,
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun deleteServer_cascadesDeletionOfIndexedTracksAndCheckpoints() =
        runTest {
            val serverId =
                repository.saveServer(
                    WebDavServer(
                        id = 0L,
                        name = "Test Server",
                        url = "http://192.168.1.100:5005",
                        port = 5005,
                    ),
                )
            val otherServerId =
                repository.saveServer(
                    WebDavServer(
                        id = 0L,
                        name = "Other Server",
                        url = "http://192.168.1.200:5005",
                        port = 5005,
                    ),
                )

            // Seed index and checkpoints for target server
            val targetTrack =
                MediaTrackIndexEntity(
                    serverId = serverId,
                    remotePath = "/Music/song1.flac",
                    fileName = "song1.flac",
                    fileSize = 1000L,
                    lastModified = 1000L,
                    format = "FLAC",
                    parentPath = "/Music/",
                    parsedTitle = "Song 1",
                    parsedArtist = "Artist 1",
                )
            val otherTrack =
                MediaTrackIndexEntity(
                    serverId = otherServerId,
                    remotePath = "/Audio/song2.flac",
                    fileName = "song2.flac",
                    fileSize = 2000L,
                    lastModified = 2000L,
                    format = "FLAC",
                    parentPath = "/Audio/",
                    parsedTitle = "Song 2",
                    parsedArtist = "Artist 2",
                )
            mediaTrackIndexDao.insertAll(listOf(targetTrack, otherTrack))

            val targetCheckpoint =
                DirectorySyncCheckpointEntity(
                    serverId = serverId,
                    path = "/Music/",
                    lastModified = 1000L,
                    etag = "etag-1",
                    lastScannedMs = 1000L,
                )
            val otherCheckpoint =
                DirectorySyncCheckpointEntity(
                    serverId = otherServerId,
                    path = "/Audio/",
                    lastModified = 2000L,
                    etag = "etag-2",
                    lastScannedMs = 2000L,
                )
            checkpointDao.insertAll(listOf(targetCheckpoint, otherCheckpoint))

            // Delete target server
            repository.deleteServer(serverId)

            // Verify target server data is removed
            assertEquals(0, mediaTrackIndexDao.countByServerId(serverId))
            assertNull(checkpointDao.getCheckpoint(serverId, "/Music/"))

            // Verify other server data remains intact
            assertEquals(1, mediaTrackIndexDao.countByServerId(otherServerId))
            assertNotNull(checkpointDao.getCheckpoint(otherServerId, "/Audio/"))
        }

    @Test
    fun removeMountedLocalFolder_cascadesDeletionOfLocalIndexTracksAndCheckpoints() =
        runTest {
            val uri1 = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
            val uri2 = "content://com.android.externalstorage.documents/tree/primary%3APodcasts"

            repository.addMountedLocalFolder(uri1, "Music")
            repository.addMountedLocalFolder(uri2, "Podcasts")

            val encodedUri1 = URLEncoder.encode(uri1, "UTF-8")
            val encodedUri2 = URLEncoder.encode(uri2, "UTF-8")
            val root1 = "/local/$encodedUri1/"
            val root2 = "/local/$encodedUri2/"

            val localTrack1 =
                MediaTrackIndexEntity(
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "$root1/track1.mp3",
                    fileName = "track1.mp3",
                    fileSize = 1000L,
                    lastModified = 1000L,
                    format = "MP3",
                    parentPath = root1,
                    parsedTitle = "Track 1",
                    parsedArtist = "Local Artist",
                )
            val localTrack2 =
                MediaTrackIndexEntity(
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "$root2/track2.mp3",
                    fileName = "track2.mp3",
                    fileSize = 2000L,
                    lastModified = 2000L,
                    format = "MP3",
                    parentPath = root2,
                    parsedTitle = "Track 2",
                    parsedArtist = "Local Artist",
                )
            mediaTrackIndexDao.insertAll(listOf(localTrack1, localTrack2))

            val checkpoint1 =
                DirectorySyncCheckpointEntity(
                    serverId = LOCAL_STORAGE_ID,
                    path = root1,
                    lastModified = 1000L,
                    etag = null,
                    lastScannedMs = 1000L,
                )
            val checkpoint2 =
                DirectorySyncCheckpointEntity(
                    serverId = LOCAL_STORAGE_ID,
                    path = root2,
                    lastModified = 2000L,
                    etag = null,
                    lastScannedMs = 2000L,
                )
            checkpointDao.insertAll(listOf(checkpoint1, checkpoint2))

            // Remove mounted local folder 1
            repository.removeMountedLocalFolder(uri1)

            // Verify folder 1 index tracks and checkpoints deleted
            val remainingTracks = mediaTrackIndexDao.getTracksByParentPath(LOCAL_STORAGE_ID, root1)
            assertEquals(0, remainingTracks.size)
            assertNull(checkpointDao.getCheckpoint(LOCAL_STORAGE_ID, root1))

            // Verify folder 2 remains intact
            val folder2Tracks = mediaTrackIndexDao.getTracksByParentPath(LOCAL_STORAGE_ID, root2)
            assertEquals(1, folder2Tracks.size)
            assertNotNull(checkpointDao.getCheckpoint(LOCAL_STORAGE_ID, root2))
        }

    @Test
    fun removeMountedLocalFolder_clearsLocalDirectoryCache_andDeleteDefaultServer_promotesRemainingServer() =
        runTest {
            val directoryCacheDao = database.directoryCacheDao()
            directoryCacheDao.insertOrUpdate(
                com.webdav.player.data.local.DirectoryCacheEntity(
                    serverId = LOCAL_STORAGE_ID,
                    path = "/",
                    dataJson = "{}",
                    lastUpdatedMs = 1000L,
                ),
            )
            assertNotNull(directoryCacheDao.getCache(LOCAL_STORAGE_ID, "/"))

            repository.removeMountedLocalFolder("content://com.android.externalstorage.documents/tree/primary%3AMusic")
            assertNull(
                "Local directory cache must be invalidated when folder is unmounted",
                directoryCacheDao.getCache(LOCAL_STORAGE_ID, "/"),
            )

            val s1Id = repository.saveServer(WebDavServer(name = "Primary NAS", url = "http://nas1.local", isDefault = true))
            val s2Id = repository.saveServer(WebDavServer(name = "Backup NAS", url = "http://nas2.local", isDefault = false))

            repository.deleteServer(s1Id)
            val promoted = repository.getServerById(s2Id)
            assertNotNull(promoted)
            assertEquals(true, promoted?.isDefault)
        }
}
