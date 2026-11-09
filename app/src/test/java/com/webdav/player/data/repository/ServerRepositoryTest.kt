package com.webdav.player.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.webdav.player.data.local.AppDatabase
import com.webdav.player.data.local.CoverArtStorageImpl
import com.webdav.player.data.local.DirectoryCacheEntity
import com.webdav.player.data.local.TrackMetadataEntity
import com.webdav.player.data.remote.OkHttpWebDavClient
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.model.isLocal
import com.webdav.player.domain.repository.FakeMediaLibraryRepository
import com.webdav.player.domain.repository.ServerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServerRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var coverArtStorage: CoverArtStorageImpl
    private lateinit var repository: ServerRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        coverArtStorage = CoverArtStorageImpl(context)
        val mediaLibraryRepository =
            MediaLibraryRepositoryImpl(
                webDavClient = OkHttpWebDavClient(),
                directoryCacheDao = database.directoryCacheDao(),
                trackMetadataDao = database.trackMetadataDao(),
                mediaTrackIndexDao = database.mediaTrackIndexDao(),
                directorySyncCheckpointDao = database.directorySyncCheckpointDao(),
                localStorageFolderDao = database.localStorageFolderDao(),
                context = context,
            )
        repository =
            ServerRepositoryImpl(
                serverDao = database.webDavServerDao(),
                coverArtStorage = coverArtStorage,
                trackMetadataDao = database.trackMetadataDao(),
                localStorageFolderDao = database.localStorageFolderDao(),
                context = context,
                cascadeInvalidator = mediaLibraryRepository,
            )
    }

    @After
    fun tearDown() {
        database.close()
        File(context.cacheDir, "covers").deleteRecursively()
    }

    @Test
    fun initially_getAllServers_hasPinnedLocalStorage_andActiveServer_isNull() =
        runTest {
            val servers = repository.getAllServers().first()
            val active = repository.getActiveServer().first()
            assertEquals(1, servers.size)
            assertEquals(LOCAL_STORAGE_ID, servers.first().id)
            assertEquals("本地存储", servers.first().name)
            assertFalse(servers.first().isDefault)
            assertNull(active)
        }

    @Test
    fun saveServer_insertsNewServer_andRetrievesById() =
        runTest {
            val server =
                WebDavServer(
                    name = "NAS",
                    url = "http://192.168.1.100",
                    port = 5005,
                    pathPrefix = "/dav",
                    username = "admin",
                    password = "pwd",
                    allowSelfSigned = true,
                )

            val id = repository.saveServer(server)
            assertTrue(id > 0)

            val saved = repository.getServerById(id)
            assertNotNull(saved)
            assertEquals("NAS", saved?.name)
            assertEquals("http://192.168.1.100", saved?.url)
            assertEquals(5005, saved?.port)
            assertEquals("/dav", saved?.pathPrefix)
            assertEquals("admin", saved?.username)
            assertEquals("pwd", saved?.password)
            assertTrue(saved?.allowSelfSigned == true)
            // If it was the first server, it should automatically be active
            assertTrue(saved?.isDefault == true)
        }

    @Test
    fun setActiveServer_switchesActiveServerCorrectly() =
        runTest {
            val id1 = repository.saveServer(WebDavServer(name = "Server 1", url = "http://srv1"))
            val id2 = repository.saveServer(WebDavServer(name = "Server 2", url = "http://srv2"))

            // Set server 2 as active
            repository.setActiveServer(id2)

            val active = repository.getActiveServer().first()
            assertNotNull(active)
            assertEquals(id2, active?.id)
            assertEquals("Server 2", active?.name)

            val s1 = repository.getServerById(id1)
            val s2 = repository.getServerById(id2)
            assertFalse(s1?.isDefault == true)
            assertTrue(s2?.isDefault == true)
        }

    @Test
    fun updateServer_modifiesExistingRecord() =
        runTest {
            val id = repository.saveServer(WebDavServer(name = "Original", url = "http://original"))
            repository.saveServer(WebDavServer(id = id, name = "Updated", url = "http://updated", port = 8080))

            val updated = repository.getServerById(id)
            assertEquals("Updated", updated?.name)
            assertEquals("http://updated", updated?.url)
            assertEquals(8080, updated?.port)
        }

    @Test
    fun deleteServer_removesServerFromDatabase() =
        runTest {
            val id = repository.saveServer(WebDavServer(name = "To Delete", url = "http://delete-me"))
            assertEquals(2, repository.getAllServers().first().size)

            repository.deleteServer(id)

            val servers = repository.getAllServers().first()
            assertEquals(1, servers.size)
            assertEquals(LOCAL_STORAGE_ID, servers.first().id)
            assertNull(repository.getServerById(id))
        }

    @Test
    fun deleteServer_withLocalStorageId_doesNothing() =
        runTest {
            repository.deleteServer(LOCAL_STORAGE_ID)
            val servers = repository.getAllServers().first()
            assertEquals(1, servers.size)
            assertEquals(LOCAL_STORAGE_ID, servers.first().id)
        }

    @Test
    fun getServerById_withLocalStorageId_returnsLocalStorageNode() =
        runTest {
            val local = repository.getServerById(LOCAL_STORAGE_ID)
            assertNotNull(local)
            assertEquals(LOCAL_STORAGE_ID, local?.id)
            assertEquals("本地存储", local?.name)
        }

    @Test
    fun setActiveServer_withLocalStorageId_activatesLocalAndDeactivatesRemote() =
        runTest {
            val id = repository.saveServer(WebDavServer(name = "NAS", url = "http://nas"))
            var active = repository.getActiveServer().first()
            assertEquals(id, active?.id)

            // Switch to local storage
            repository.setActiveServer(LOCAL_STORAGE_ID)

            active = repository.getActiveServer().first()
            assertNotNull(active)
            assertEquals(LOCAL_STORAGE_ID, active?.id)
            assertTrue(active?.isLocal == true)
            assertTrue(active?.isDefault == true)

            val remote = repository.getServerById(id)
            assertFalse(remote?.isDefault == true)

            val servers = repository.getAllServers().first()
            assertTrue(servers.first().isDefault)
            assertFalse(servers[1].isDefault)

            // Switch back to remote
            repository.setActiveServer(id)
            active = repository.getActiveServer().first()
            assertEquals(id, active?.id)
            assertFalse(active?.isLocal == true)
        }

    @Test
    fun mountedFolders_addGetRemove_worksCorrectly() =
        runTest {
            var folders = repository.getMountedLocalFolders().first()
            assertTrue(folders.isEmpty())

            repository.addMountedLocalFolder("content://tree/music", "My Music")
            folders = repository.getMountedLocalFolders().first()
            assertEquals(1, folders.size)
            assertEquals("content://tree/music", folders.first().uriString)
            assertEquals("My Music", folders.first().displayName)

            repository.removeMountedLocalFolder("content://tree/music")
            folders = repository.getMountedLocalFolders().first()
            assertTrue(folders.isEmpty())
        }

    @Test
    fun deleteServer_cascadesRemovalOfCoverArtFromDisk() =
        runTest {
            val s1 = WebDavServer(name = "Server 1", url = "http://srv1")
            val s2 = WebDavServer(name = "Server 2", url = "http://srv2")
            val id1 = repository.saveServer(s1)
            val id2 = repository.saveServer(s2)

            // Save cover files for both servers
            val dummyBytes = ByteArray(32) { 0x11 }
            coverArtStorage.saveThumbnail(id1, "/track1.mp3", dummyBytes)
            coverArtStorage.saveThumbnail(id2, "/track2.mp3", dummyBytes)

            assertNotNull(coverArtStorage.getThumbnailFile(id1, "/track1.mp3"))
            assertNotNull(coverArtStorage.getThumbnailFile(id2, "/track2.mp3"))

            // Delete server 1
            repository.deleteServer(id1)

            // Server 1 should be gone from DB and its covers physically gone from disk
            assertNull(repository.getServerById(id1))
            assertNull(coverArtStorage.getThumbnailFile(id1, "/track1.mp3"))

            // Server 2 and its covers should still exist
            assertNotNull(repository.getServerById(id2))
            assertNotNull(coverArtStorage.getThumbnailFile(id2, "/track2.mp3"))
        }

    @Test
    fun saveServer_and_deleteServer_invalidatesWebDavClientCache() =
        runTest {
            val webDavClient = OkHttpWebDavClient()
            val repoWithClient =
                ServerRepositoryImpl(
                    serverDao = database.webDavServerDao(),
                    coverArtStorage = coverArtStorage,
                    webDavClient = webDavClient,
                )

            val server1 = WebDavServer(name = "Cached Server", url = "http://cached-srv", port = 80)
            val id = repoWithClient.saveServer(server1)
            val savedServer = repoWithClient.getServerById(id)!!

            val clientA = webDavClient.buildClientForServer(savedServer)
            assertEquals(1, webDavClient.cachedClientCount)

            // Updating server invalidates cache
            val updatedServer = savedServer.copy(port = 8080)
            repoWithClient.saveServer(updatedServer)
            assertEquals(0, webDavClient.cachedClientCount)

            // Re-cache and delete
            val clientB = webDavClient.buildClientForServer(updatedServer)
            assertEquals(1, webDavClient.cachedClientCount)
            assertNotSame(clientA, clientB)

            repoWithClient.deleteServer(id)
            assertEquals(0, webDavClient.cachedClientCount)
        }

    @Test
    fun deleteServer_cascadesRemovalOfDirectoryCacheAndTrackMetadata() =
        runTest {
            val s1 = WebDavServer(name = "Server 1", url = "http://srv1")
            val id1 = repository.saveServer(s1)

            val dirDao = database.directoryCacheDao()
            val metaDao = database.trackMetadataDao()

            dirDao.insertOrUpdate(
                DirectoryCacheEntity(serverId = id1, path = "/Music/", dataJson = "{}", lastUpdatedMs = System.currentTimeMillis()),
            )
            metaDao.insertOrUpdate(
                TrackMetadataEntity(
                    serverId = id1,
                    remotePath = "/Music/song.mp3",
                    title = "Song",
                    artist = null,
                    album = null,
                    trackNumber = null,
                    durationMs = 0L,
                    coverThumbnailPath = null,
                ),
            )

            assertNotNull(dirDao.getCache(id1, "/Music/"))
            assertNotNull(metaDao.getMetadata(id1, "/Music/song.mp3"))

            repository.deleteServer(id1)

            assertNull(dirDao.getCache(id1, "/Music/"))
            assertNull(metaDao.getMetadata(id1, "/Music/song.mp3"))
        }

    @Test
    fun setActiveServer_localStorage_persistsAcrossColdStart() =
        runTest {
            val s1 = WebDavServer(name = "Server 1", url = "http://srv1")
            val id1 = repository.saveServer(s1)
            assertEquals(id1, repository.getActiveServer().first()?.id)

            // Switch to local storage
            repository.setActiveServer(LOCAL_STORAGE_ID)
            val activeLocal = repository.getActiveServer().first()
            assertNotNull(activeLocal)
            assertEquals(LOCAL_STORAGE_ID, activeLocal?.id)
            assertTrue(activeLocal?.isDefault == true)

            // Simulate cold restart with a new repository instance sharing the same context and DB
            val restartedRepo =
                ServerRepositoryImpl(
                    serverDao = database.webDavServerDao(),
                    coverArtStorage = coverArtStorage,
                    trackMetadataDao = database.trackMetadataDao(),
                    localStorageFolderDao = database.localStorageFolderDao(),
                    context = context,
                )

            val coldStartActive = restartedRepo.getActiveServer().first()
            assertNotNull(coldStartActive)
            assertEquals(LOCAL_STORAGE_ID, coldStartActive?.id)
            assertTrue(coldStartActive?.isDefault == true)
        }

    @Test
    fun setActiveServer_remoteServer_clearsLocalStoragePersistence() =
        runTest {
            val s1 = WebDavServer(name = "Server 1", url = "http://srv1")
            val id1 = repository.saveServer(s1)

            // First activate local storage
            repository.setActiveServer(LOCAL_STORAGE_ID)
            assertEquals(LOCAL_STORAGE_ID, repository.getActiveServer().first()?.id)

            // Switch back to remote server
            repository.setActiveServer(id1)
            assertEquals(id1, repository.getActiveServer().first()?.id)

            // Simulate cold restart
            val restartedRepo =
                ServerRepositoryImpl(
                    serverDao = database.webDavServerDao(),
                    coverArtStorage = coverArtStorage,
                    trackMetadataDao = database.trackMetadataDao(),
                    localStorageFolderDao = database.localStorageFolderDao(),
                    context = context,
                )

            val coldStartActive = restartedRepo.getActiveServer().first()
            assertNotNull(coldStartActive)
            assertEquals(id1, coldStartActive?.id)
        }

    @Test
    fun deleteServer_and_removeMountedLocalFolder_delegateToMediaLibraryRepository() =
        runTest {
            val fakeMediaLibraryRepo = FakeMediaLibraryRepository()
            val repoWithFake =
                ServerRepositoryImpl(
                    serverDao = database.webDavServerDao(),
                    coverArtStorage = coverArtStorage,
                    trackMetadataDao = database.trackMetadataDao(),
                    localStorageFolderDao = database.localStorageFolderDao(),
                    context = context,
                    cascadeInvalidator = fakeMediaLibraryRepo,
                )

            val serverId = repoWithFake.saveServer(WebDavServer(name = "Cascade NAS", url = "http://nas"))
            repoWithFake.deleteServer(serverId)

            assertEquals(listOf(serverId), fakeMediaLibraryRepo.clearedStorageServerIds)

            val folderUri = "content://tree/primary%3AMusic"
            repoWithFake.addMountedLocalFolder(folderUri, "Music")
            repoWithFake.removeMountedLocalFolder(folderUri)

            assertEquals(listOf(folderUri), fakeMediaLibraryRepo.clearedLocalFolderUris)
        }
}
