package com.webdav.player.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.webdav.player.data.local.AppDatabase
import com.webdav.player.data.local.DirectorySyncCheckpointDao
import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.local.MediaTrackIndexEntity
import com.webdav.player.data.remote.ConnectionResult
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.LibrarySyncResult
import com.webdav.player.domain.model.LibrarySyncState
import com.webdav.player.domain.model.ListDirectoryResult
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.RemoteFileType
import com.webdav.player.domain.model.WebDavServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebDavSyncScannerTest {
    private class FakeWebDavClient : WebDavClient {
        val directoryTree = mutableMapOf<String, RemoteDirectory>()
        val visitedPaths = Collections.synchronizedList(mutableListOf<String>())
        val activeCalls = AtomicInteger(0)
        val maxConcurrentCalls = AtomicInteger(0)
        var delayMs: Long = 0L
        val failureMap = mutableMapOf<String, String>()

        override suspend fun listDirectory(
            server: WebDavServer,
            path: String,
        ): ListDirectoryResult {
            val normalizedPath = RemoteDirectory.normalizePath(path)
            visitedPaths.add(normalizedPath)
            val current = activeCalls.incrementAndGet()
            maxConcurrentCalls.updateAndGet { maxOf(it, current) }

            try {
                if (delayMs > 0L) {
                    delay(delayMs)
                }
                val failure = failureMap[normalizedPath]
                if (failure != null) {
                    return ListDirectoryResult.Failure(failure)
                }
                val dir =
                    directoryTree[normalizedPath]
                        ?: return ListDirectoryResult.Failure("Directory not found: $normalizedPath", statusCode = 404)
                return ListDirectoryResult.Success(dir)
            } finally {
                activeCalls.decrementAndGet()
            }
        }

        override suspend fun testConnection(server: WebDavServer): ConnectionResult = ConnectionResult.Success

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

    private lateinit var database: AppDatabase
    private lateinit var mediaTrackIndexDao: MediaTrackIndexDao
    private lateinit var checkpointDao: DirectorySyncCheckpointDao
    private lateinit var fakeClient: FakeWebDavClient
    private lateinit var scanner: WebDavSyncScanner

    private val testServer =
        WebDavServer(
            id = 101L,
            name = "Test NAS",
            url = "http://192.168.1.100:5005",
            port = 5005,
            pathPrefix = "/",
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
        checkpointDao = database.directorySyncCheckpointDao()
        fakeClient = FakeWebDavClient()

        scanner =
            WebDavSyncScanner(
                webDavClient = fakeClient,
                mediaTrackIndexDao = mediaTrackIndexDao,
                directorySyncCheckpointDao = checkpointDao,
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sync_performsBfsTraversalAndExcludesNoiseDirectories() =
        runTest {
            // Root contains: Music, and various noise directories
            val rootDir =
                RemoteDirectory(
                    path = "/",
                    name = "/",
                    subDirectories =
                        listOf(
                            RemoteDirectory(path = "/Music/", name = "Music"),
                            RemoteDirectory(path = "/.git/", name = ".git"),
                            RemoteDirectory(path = "/#recycle/", name = "#recycle"),
                            RemoteDirectory(path = "/.@eaDir/", name = ".@eaDir"),
                            RemoteDirectory(path = "/LOST.DIR/", name = "LOST.DIR"),
                            RemoteDirectory(path = "/.thumbnails/", name = ".thumbnails"),
                            RemoteDirectory(path = "/.cache/", name = ".cache"),
                            RemoteDirectory(path = "/Android/", name = "Android"),
                        ),
                    files = listOf(RemoteFile(name = "readme.txt", path = "/readme.txt", size = 100L)),
                )

            // /Music/ contains /Music/Rock/ and a nested noise directory .Trash-1000
            val musicDir =
                RemoteDirectory(
                    path = "/Music/",
                    name = "Music",
                    subDirectories =
                        listOf(
                            RemoteDirectory(path = "/Music/Rock/", name = "Rock"),
                            RemoteDirectory(path = "/Music/.Trash-1000/", name = ".Trash-1000"),
                        ),
                    files =
                        listOf(
                            RemoteFile(
                                name = "01 - Queen - Bohemian Rhapsody.flac",
                                path = "/Music/01 - Queen - Bohemian Rhapsody.flac",
                                size = 45000000L,
                                lastModified = "Wed, 14 Jan 2026 08:30:00 GMT",
                                fileType = RemoteFileType.Audio(AudioFormat.FLAC),
                            ),
                        ),
                )

            // /Music/Rock/ contains Stairway to Heaven
            val rockDir =
                RemoteDirectory(
                    path = "/Music/Rock/",
                    name = "Rock",
                    subDirectories = emptyList(),
                    files =
                        listOf(
                            RemoteFile(
                                name = "Led Zeppelin - Stairway to Heaven.mp3",
                                path = "/Music/Rock/Led Zeppelin - Stairway to Heaven.mp3",
                                size = 12000000L,
                                lastModified = "Wed, 14 Jan 2026 09:00:00 GMT",
                                fileType = RemoteFileType.Audio(AudioFormat.MP3),
                            ),
                        ),
                )

            fakeClient.directoryTree["/"] = rootDir
            fakeClient.directoryTree["/Music/"] = musicDir
            fakeClient.directoryTree["/Music/Rock/"] = rockDir

            val result = scanner.sync(testServer)

            assertTrue(result is LibrarySyncResult.Success)
            val success = result as LibrarySyncResult.Success
            assertEquals(2, success.totalTracksIndexed)
            assertEquals(3, success.directoriesScanned)

            // Verify noise directories were never listed
            assertFalse(fakeClient.visitedPaths.contains("/.git/"))
            assertFalse(fakeClient.visitedPaths.contains("/#recycle/"))
            assertFalse(fakeClient.visitedPaths.contains("/.@eaDir/"))
            assertFalse(fakeClient.visitedPaths.contains("/LOST.DIR/"))
            assertFalse(fakeClient.visitedPaths.contains("/.thumbnails/"))
            assertFalse(fakeClient.visitedPaths.contains("/.cache/"))
            assertFalse(fakeClient.visitedPaths.contains("/Android/"))
            assertFalse(fakeClient.visitedPaths.contains("/Music/.Trash-1000/"))

            // Verify valid paths were visited
            assertTrue(fakeClient.visitedPaths.contains("/"))
            assertTrue(fakeClient.visitedPaths.contains("/Music/"))
            assertTrue(fakeClient.visitedPaths.contains("/Music/Rock/"))

            // Verify indexed tracks in DAO
            val tracks = mediaTrackIndexDao.getTracksByServerId(testServer.id)
            assertEquals(2, tracks.size)

            val track1 = tracks.first { it.fileName.contains("Queen") }
            assertEquals("Bohemian Rhapsody", track1.parsedTitle)
            assertEquals("Queen", track1.parsedArtist)
            assertEquals("/Music/", track1.parentPath)
            assertEquals("FLAC", track1.format)
            assertEquals(45000000L, track1.fileSize)

            val track2 = tracks.first { it.fileName.contains("Led Zeppelin") }
            assertEquals("Stairway to Heaven", track2.parsedTitle)
            assertEquals("Led Zeppelin", track2.parsedArtist)
            assertEquals("/Music/Rock/", track2.parentPath)
            assertEquals("MP3", track2.format)

            // Verify checkpoints created
            assertNotNull(checkpointDao.getCheckpoint(testServer.id, "/"))
            assertNotNull(checkpointDao.getCheckpoint(testServer.id, "/Music/"))
            assertNotNull(checkpointDao.getCheckpoint(testServer.id, "/Music/Rock/"))
        }

    @Test
    fun sync_respectsStrictConcurrencyCeiling() =
        runTest {
            val maxConcurrency = 3
            val customScanner =
                WebDavSyncScanner(
                    webDavClient = fakeClient,
                    mediaTrackIndexDao = mediaTrackIndexDao,
                    directorySyncCheckpointDao = checkpointDao,
                    maxConcurrency = maxConcurrency,
                )

            fakeClient.delayMs = 30L

            val subDirs =
                (1..12).map { i ->
                    RemoteDirectory(path = "/Dir$i/", name = "Dir$i")
                }
            val rootDir = RemoteDirectory(path = "/", name = "/", subDirectories = subDirs)
            fakeClient.directoryTree["/"] = rootDir

            for (i in 1..12) {
                fakeClient.directoryTree["/Dir$i/"] =
                    RemoteDirectory(
                        path = "/Dir$i/",
                        name = "Dir$i",
                        files =
                            listOf(
                                RemoteFile(
                                    name = "Track$i.mp3",
                                    path = "/Dir$i/Track$i.mp3",
                                    size = 1000L,
                                    fileType = RemoteFileType.Audio(AudioFormat.MP3),
                                ),
                            ),
                    )
            }

            val result = customScanner.sync(testServer)
            assertTrue(result is LibrarySyncResult.Success)

            val maxObserved = fakeClient.maxConcurrentCalls.get()
            assertTrue(
                "Observed concurrency ($maxObserved) must not exceed ceiling ($maxConcurrency)",
                maxObserved <= maxConcurrency,
            )
            assertTrue(
                "Observed concurrency ($maxObserved) should achieve parallel overlap (>= 2)",
                maxObserved >= 2,
            )

            val tracks = mediaTrackIndexDao.getTracksByServerId(testServer.id)
            assertEquals(12, tracks.size)
        }

    @Test
    fun sync_incrementallySkipsUnchangedDirectoriesBasedOnCheckpoints() =
        runTest {
            // Initial sync: /Album1/ (etag: tag1, mod: 1000) and /Album2/ (etag: tag2, mod: 2000)
            val album1 =
                RemoteDirectory(
                    path = "/Album1/",
                    name = "Album1",
                    lastModified = 1000L,
                    etag = "tag1",
                    files =
                        listOf(
                            RemoteFile(
                                name = "Song1.flac",
                                path = "/Album1/Song1.flac",
                                size = 1000L,
                                fileType = RemoteFileType.Audio(AudioFormat.FLAC),
                            ),
                        ),
                )
            val album2 =
                RemoteDirectory(
                    path = "/Album2/",
                    name = "Album2",
                    lastModified = 2000L,
                    etag = "tag2",
                    files =
                        listOf(
                            RemoteFile(
                                name = "Song2.mp3",
                                path = "/Album2/Song2.mp3",
                                size = 2000L,
                                fileType = RemoteFileType.Audio(AudioFormat.MP3),
                            ),
                        ),
                )
            val rootDir =
                RemoteDirectory(
                    path = "/",
                    name = "/",
                    lastModified = 500L,
                    etag = "rootTag",
                    subDirectories = listOf(album1, album2),
                )

            fakeClient.directoryTree["/"] = rootDir
            fakeClient.directoryTree["/Album1/"] = album1
            fakeClient.directoryTree["/Album2/"] = album2

            val firstResult = scanner.sync(testServer)
            assertTrue(firstResult is LibrarySyncResult.Success)
            assertEquals(2, (firstResult as LibrarySyncResult.Success).totalTracksIndexed)
            assertEquals(3, firstResult.directoriesScanned)
            assertEquals(0, firstResult.directoriesSkipped)

            // Rescan with unchanged Album1, and modified Album2 (etag changed to tag2_mod, new track added)
            fakeClient.visitedPaths.clear()

            val album2Modified =
                RemoteDirectory(
                    path = "/Album2/",
                    name = "Album2",
                    lastModified = 3000L,
                    etag = "tag2_mod",
                    files =
                        listOf(
                            RemoteFile(
                                name = "Song2.mp3",
                                path = "/Album2/Song2.mp3",
                                size = 2000L,
                                fileType = RemoteFileType.Audio(AudioFormat.MP3),
                            ),
                            RemoteFile(
                                name = "Song3.mp3",
                                path = "/Album2/Song3.mp3",
                                size = 3000L,
                                fileType = RemoteFileType.Audio(AudioFormat.MP3),
                            ),
                        ),
                )
            // Root updated with Album2's new metadata, Album1 still has tag1 & 1000L
            val rootDirUpdated =
                RemoteDirectory(
                    path = "/",
                    name = "/",
                    lastModified = 600L,
                    etag = "rootTag2",
                    subDirectories = listOf(album1, album2Modified),
                )

            fakeClient.directoryTree["/"] = rootDirUpdated
            fakeClient.directoryTree["/Album2/"] = album2Modified

            val secondResult = scanner.sync(testServer, force = false)
            assertTrue(secondResult is LibrarySyncResult.Success)
            val secondSuccess = secondResult as LibrarySyncResult.Success
            assertEquals(1, secondSuccess.directoriesSkipped) // Album1 was skipped!
            assertEquals(2, secondSuccess.totalTracksIndexed) // Album2 indexed 2 tracks

            // Verify Album1 was skipped and not queried
            assertFalse(fakeClient.visitedPaths.contains("/Album1/"))
            assertTrue(fakeClient.visitedPaths.contains("/Album2/"))

            // Verify all 3 tracks are in DB (Song1 was preserved, Song2 & Song3 present)
            val allTracks = mediaTrackIndexDao.getTracksByServerId(testServer.id)
            assertEquals(3, allTracks.size)
            assertTrue(allTracks.any { it.fileName == "Song1.flac" })
            assertTrue(allTracks.any { it.fileName == "Song2.mp3" })
            assertTrue(allTracks.any { it.fileName == "Song3.mp3" })

            // Test force rescan: when force = true, even unchanged folders are re-traversed
            fakeClient.visitedPaths.clear()
            val forceResult = scanner.sync(testServer, force = true)
            assertTrue(forceResult is LibrarySyncResult.Success)
            assertEquals(0, (forceResult as LibrarySyncResult.Success).directoriesSkipped)
            assertTrue(fakeClient.visitedPaths.contains("/Album1/"))
            assertTrue(fakeClient.visitedPaths.contains("/Album2/"))
        }

    @Test
    fun sync_handlesTransientNetworkDropsGracefullyWithoutCrashingOrErasingData() =
        runTest {
            val goodDir =
                RemoteDirectory(
                    path = "/Good/",
                    name = "Good",
                    files =
                        listOf(
                            RemoteFile(
                                name = "GoodSong.mp3",
                                path = "/Good/GoodSong.mp3",
                                size = 1000L,
                                fileType = RemoteFileType.Audio(AudioFormat.MP3),
                            ),
                        ),
                )
            val rootDir =
                RemoteDirectory(
                    path = "/",
                    name = "/",
                    subDirectories =
                        listOf(
                            goodDir,
                            RemoteDirectory(path = "/Flaky/", name = "Flaky"),
                        ),
                )

            fakeClient.directoryTree["/"] = rootDir
            fakeClient.directoryTree["/Good/"] = goodDir
            fakeClient.failureMap["/Flaky/"] = "500 Internal Server Error"

            val result = scanner.sync(testServer)
            assertTrue("Scan should succeed partially despite subfolder failure", result is LibrarySyncResult.Success)

            // Good song was indexed
            val tracks = mediaTrackIndexDao.getTracksByServerId(testServer.id)
            assertEquals(1, tracks.size)
            assertEquals("GoodSong.mp3", tracks[0].fileName)

            // Now simulate complete server failure on rescan (root failure)
            fakeClient.failureMap["/"] = "Connection refused"
            val failedResult = scanner.sync(testServer)
            assertTrue("Root failure should return LibrarySyncResult.Error", failedResult is LibrarySyncResult.Error)

            // Crucial: previously indexed tracks must NOT be erased!
            val tracksAfterFailure = mediaTrackIndexDao.getTracksByServerId(testServer.id)
            assertEquals("Previously indexed tracks must remain safe", 1, tracksAfterFailure.size)
            assertEquals("GoodSong.mp3", tracksAfterFailure[0].fileName)
        }

    @Test
    fun sync_cleansUpDeletedTracksInScannedDirectories() =
        runTest {
            val dirWithTwoTracks =
                RemoteDirectory(
                    path = "/Album/",
                    name = "Album",
                    files =
                        listOf(
                            RemoteFile(
                                name = "TrackA.mp3",
                                path = "/Album/TrackA.mp3",
                                size = 1000L,
                                fileType = RemoteFileType.Audio(AudioFormat.MP3),
                            ),
                            RemoteFile(
                                name = "TrackB.mp3",
                                path = "/Album/TrackB.mp3",
                                size = 2000L,
                                fileType = RemoteFileType.Audio(AudioFormat.MP3),
                            ),
                        ),
                )
            val rootDir = RemoteDirectory(path = "/", name = "/", subDirectories = listOf(dirWithTwoTracks))
            fakeClient.directoryTree["/"] = rootDir
            fakeClient.directoryTree["/Album/"] = dirWithTwoTracks

            scanner.sync(testServer)
            assertEquals(2, mediaTrackIndexDao.getTracksByServerId(testServer.id).size)

            // Now TrackB is deleted from remote server
            val dirWithOneTrack =
                RemoteDirectory(
                    path = "/Album/",
                    name = "Album",
                    files =
                        listOf(
                            RemoteFile(
                                name = "TrackA.mp3",
                                path = "/Album/TrackA.mp3",
                                size = 1000L,
                                fileType = RemoteFileType.Audio(AudioFormat.MP3),
                            ),
                        ),
                )
            fakeClient.directoryTree["/Album/"] = dirWithOneTrack

            scanner.sync(testServer, force = true)

            val updatedTracks = mediaTrackIndexDao.getTracksByServerId(testServer.id)
            assertEquals(1, updatedTracks.size)
            assertEquals("TrackA.mp3", updatedTracks[0].fileName)
        }

    @Test
    fun sync_emitsReactiveLibrarySyncStateTransitions() =
        runTest {
            val rootDir =
                RemoteDirectory(
                    path = "/",
                    name = "/",
                    subDirectories = listOf(RemoteDirectory(path = "/Pop/", name = "Pop")),
                )
            val popDir =
                RemoteDirectory(
                    path = "/Pop/",
                    name = "Pop",
                    files =
                        listOf(
                            RemoteFile(
                                name = "Song.mp3",
                                path = "/Pop/Song.mp3",
                                size = 1000L,
                                fileType = RemoteFileType.Audio(AudioFormat.MP3),
                            ),
                        ),
                )
            fakeClient.directoryTree["/"] = rootDir
            fakeClient.directoryTree["/Pop/"] = popDir

            val statesEmitted = mutableListOf<LibrarySyncState>()
            scanner.sync(testServer, onProgress = { statesEmitted.add(it) })

            assertTrue(statesEmitted.any { it is LibrarySyncState.Scanning })
            val lastState = statesEmitted.last()
            assertTrue(lastState is LibrarySyncState.Completed)
            assertEquals(1, (lastState as LibrarySyncState.Completed).totalTracks)
        }

    @Test
    fun sync_withCueSheet_indexesOnlyGenuineAudioFilesAndIgnoresCueFile() =
        runTest {
            val dirWithCue =
                RemoteDirectory(
                    path = "/Album/",
                    name = "Album",
                    files =
                        listOf(
                            RemoteFile(
                                name = "album.cue",
                                path = "/Album/album.cue",
                                size = 500L,
                            ),
                            RemoteFile(
                                name = "album.flac",
                                path = "/Album/album.flac",
                                size = 50000000L,
                                fileType = RemoteFileType.Audio(AudioFormat.FLAC),
                            ),
                        ),
                )
            fakeClient.directoryTree["/"] = RemoteDirectory(path = "/", name = "/", subDirectories = listOf(dirWithCue))
            fakeClient.directoryTree["/Album/"] = dirWithCue

            val result = scanner.sync(testServer)
            assertTrue(result is LibrarySyncResult.Success)

            val tracks = mediaTrackIndexDao.getTracksByServerId(testServer.id)
            assertEquals(1, tracks.size)
            assertEquals("album.flac", tracks.first().fileName)
            assertEquals("/Album/album.flac", tracks.first().remotePath)
        }

    @Test
    fun sync_whenServerHasPathPrefix_scansFromRootAndDoesNotDuplicatePrefix() =
        runTest {
            val serverWithPrefix = testServer.copy(pathPrefix = "/dav")
            val rootDir =
                RemoteDirectory(
                    path = "/",
                    name = "/",
                    subDirectories = listOf(RemoteDirectory(path = "/Pop/", name = "Pop")),
                )
            val popDir =
                RemoteDirectory(
                    path = "/Pop/",
                    name = "Pop",
                    files = listOf(RemoteFile(name = "Song.mp3", path = "/Pop/Song.mp3", size = 1000L)),
                )
            fakeClient.directoryTree["/"] = rootDir
            fakeClient.directoryTree["/Pop/"] = popDir

            val result = scanner.sync(serverWithPrefix)
            assertTrue(result is LibrarySyncResult.Success)
            assertTrue("Must visit '/' as root", fakeClient.visitedPaths.contains("/"))
            assertTrue("Must visit '/Pop/'", fakeClient.visitedPaths.contains("/Pop/"))
            assertFalse("Must never visit '/dav/' as root", fakeClient.visitedPaths.contains("/dav/"))
        }
}
