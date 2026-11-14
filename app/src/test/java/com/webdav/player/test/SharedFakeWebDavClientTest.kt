package com.webdav.player.test

import com.webdav.player.data.remote.ConnectionResult
import com.webdav.player.domain.model.ListDirectoryResult
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.WebDavServer
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SharedFakeWebDavClientTest {
    private lateinit var client: SharedFakeWebDavClient
    private val testServer =
        WebDavServer(
            id = 1L,
            name = "Test NAS",
            url = "http://192.168.1.100:5005",
            port = 5005,
            pathPrefix = "/dav",
            username = "admin",
            password = "password"
        )

    @Before
    fun setUp() {
        client = SharedFakeWebDavClient()
    }

    @Test
    fun testConnection_returnsDefaultSuccess_andIncrementsProbeCount() =
        runTest {
            val result = client.testConnection(testServer)
            assertEquals(ConnectionResult.Success, result)
            assertEquals(1, client.connectionProbeCount.get())
        }

    @Test
    fun testConnection_withCustomPerServerResult_returnsConfiguredResult() =
        runTest {
            client.testConnectionResults[testServer.id] =
                ConnectionResult.Failure("Unauthorized", statusCode = 401)
            val result = client.testConnection(testServer)
            assertTrue(result is ConnectionResult.Failure)
            assertEquals(401, (result as ConnectionResult.Failure).statusCode)
            assertEquals(1, client.connectionProbeCount.get())
        }

    @Test
    fun testConnection_withHook_invokesHookAndReturnsCustomResult() =
        runTest {
            var interceptedServer: WebDavServer? = null
            client.onTestConnection = { server ->
                interceptedServer = server
                ConnectionResult.Failure("Custom Hook Failure", statusCode = 503)
            }
            val result = client.testConnection(testServer)
            assertEquals(testServer, interceptedServer)
            assertTrue(result is ConnectionResult.Failure)
            assertEquals(503, (result as ConnectionResult.Failure).statusCode)
        }

    @Test
    fun listDirectory_emptyTree_returnsDefaultSyntheticDirectory() =
        runTest {
            val result = client.listDirectory(testServer, "/Music/Rock/")
            assertTrue(result is ListDirectoryResult.Success)
            val dir = (result as ListDirectoryResult.Success).directory
            assertEquals("/Music/Rock/", dir.path)
            assertEquals("Rock", dir.name)
            assertTrue(client.visitedPaths.contains("/Music/Rock/"))
        }

    @Test
    fun listDirectory_withDirectoryTree_normalizesPathAndReturnsSuccess() =
        runTest {
            val stubDir =
                RemoteDirectory(
                    path = "/Music/Jazz/",
                    name = "Jazz",
                    files = listOf(RemoteFile(path = "/Music/Jazz/track.flac", name = "track.flac", size = 1024L))
                )
            client.stubDirectory("/Music/Jazz", stubDir)

            val result = client.listDirectory(testServer, "Music/Jazz/")
            assertTrue(result is ListDirectoryResult.Success)
            assertEquals(stubDir, (result as ListDirectoryResult.Success).directory)
        }

    @Test
    fun listDirectory_withDirectoryTree_missingSubdirectory_returns404Failure() =
        runTest {
            client.stubDirectory("/", RemoteDirectory(path = "/", name = "root"))
            val result = client.listDirectory(testServer, "/NonExistent/")
            assertTrue(result is ListDirectoryResult.Failure)
            val failure = result as ListDirectoryResult.Failure
            assertEquals(404, failure.statusCode)
            assertTrue(failure.message.contains("Directory not found"))
        }

    @Test
    fun listDirectory_withExplicitDirectoryResult_overridesTree() =
        runTest {
            val treeDir = RemoteDirectory(path = "/Music/", name = "Music")
            client.stubDirectory("/Music/", treeDir)
            val explicitFailure = ListDirectoryResult.Failure("Network timeout", statusCode = 504)
            client.results["/Music/"] = explicitFailure

            val result = client.listDirectory(testServer, "/Music/")
            assertSame(explicitFailure, result)
        }

    @Test
    fun listDirectory_withFailureMap_returnsFailure() =
        runTest {
            client.failureMap["/Corrupted/"] = "Disk I/O error"
            val result = client.listDirectory(testServer, "/Corrupted/")
            assertTrue(result is ListDirectoryResult.Failure)
            assertEquals("Disk I/O error", (result as ListDirectoryResult.Failure).message)
        }

    @Test
    fun listDirectory_withCancellation_throwsCancellationException() =
        runTest {
            client.shouldThrowCancellation = true
            try {
                client.listDirectory(testServer, "/Music/")
                fail("Expected CancellationException")
            } catch (e: CancellationException) {
                assertTrue(e.message?.contains("Simulated cancellation") == true)
            }
        }

    @Test
    fun listDirectory_withException_throwsConfiguredException() =
        runTest {
            client.listDirectoryException = IOException("Simulated socket closed")
            try {
                client.listDirectory(testServer, "/Music/")
                fail("Expected IOException")
            } catch (e: IOException) {
                assertEquals("Simulated socket closed", e.message)
            }
        }

    @Test
    fun listDirectory_tracksCallCountsAndVisitedPaths() =
        runTest {
            client.listDirectory(testServer, "/Music/")
            client.listDirectory(testServer, "/Music/")
            assertEquals(2, client.callCount["/Music/"])
            assertEquals(2, client.visitedPaths.size)
        }

    @Test
    fun listDirectory_withDelay_incrementsActiveAndMaxConcurrentCalls() =
        runTest {
            client.delayMs = 50L
            val job1 = async { client.listDirectory(testServer, "/dir1/") }
            val job2 = async { client.listDirectory(testServer, "/dir2/") }
            job1.await()
            job2.await()

            assertTrue(client.maxConcurrentCalls.get() >= 2)
            assertEquals(0, client.activeCalls.get())
        }

    @Test
    fun fetchRange_readsCorrectSliceOfBytes() =
        runTest {
            val payload = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
            client.stubFileBytes("/test.bin", payload)

            val slice = client.fetchRange(testServer, "/test.bin", startByte = 2L, endByte = 5L)
            assertNotNull(slice)
            assertArrayEquals(byteArrayOf(2, 3, 4, 5), slice)
            assertEquals(1, client.fetchCount.get())
            assertEquals(1, client.requestedRanges.size)
            assertEquals(Triple("/test.bin", 2L, 5L), client.requestedRanges.first())
        }

    @Test
    fun fetchRange_withThrowOnPaths_throwsIOException() =
        runTest {
            client.throwOnPaths.add("/timeout.flac")
            try {
                client.fetchRange(testServer, "/timeout.flac", 0L, 1024L)
                fail("Expected IOException")
            } catch (e: IOException) {
                assertTrue(e.message?.contains("Simulated network timeout") == true)
            }
        }

    @Test
    fun fetchRange_withIgnoreRangeOnSecondaryFetch_returnsFullFile() =
        runTest {
            val fullData = byteArrayOf(10, 20, 30, 40)
            client.stubFileBytes("/special.mp3", fullData)
            client.ignoreRangeOnSecondaryFetchPaths.add("/special.mp3")

            val result = client.fetchRange(testServer, "/special.mp3", startByte = 2L, endByte = 3L)
            assertArrayEquals(fullData, result)
        }

    @Test
    fun fetchRange_withPartialChunk_returnsTruncatedChunk() =
        runTest {
            val fullData = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
            client.stubFileBytes("/chunk.mp3", fullData)
            client.partialChunkPaths["/chunk.mp3"] = 2

            val result = client.fetchRange(testServer, "/chunk.mp3", startByte = 4L, endByte = 8L)
            assertNotNull(result)
            assertArrayEquals(byteArrayOf(4, 5), result)
        }

    @Test
    fun fetchText_returnsStubbedText_andIncrementsCount() =
        runTest {
            client.stubText("/lyrics.lrc", "[00:01.00]Hello world")
            val text = client.fetchText(testServer, "/lyrics.lrc")
            assertEquals("[00:01.00]Hello world", text)
            assertEquals(1, client.fetchTextCount.get())
        }

    @Test
    fun fetchText_withThrowOnTextPaths_throwsIOException() =
        runTest {
            client.throwOnTextPaths.add("/broken.cue")
            try {
                client.fetchText(testServer, "/broken.cue")
                fail("Expected IOException")
            } catch (e: IOException) {
                assertTrue(e.message?.contains("Simulated network error") == true)
            }
        }

    @Test
    fun invalidateServer_recordsServerId() {
        client.invalidateServer(42L)
        assertTrue(client.wasServerInvalidated(42L))
        assertFalse(client.wasServerInvalidated(99L))
    }

    @Test
    fun getStreamingClientForServer_returnsOkHttpClientWithConfiguredConnectionPool() {
        val streamingClient = client.getStreamingClientForServer(testServer)
        assertNotNull(streamingClient)
        assertSame(client.streamingConnectionPool, streamingClient.connectionPool)
        assertEquals(1, client.streamingClientRequests.get())

        val customClient = OkHttpClient.Builder().build()
        client.streamingClients[testServer.id] = customClient
        assertSame(customClient, client.getStreamingClientForServer(testServer))
    }

    @Test
    fun clear_resetsAllStateAndCounters() {
        client.testConnectionResults[1L] = ConnectionResult.Failure("err")
        client.stubFileBytes("/f", byteArrayOf(1))
        client.stubText("/t", "txt")
        client.invalidateServer(1L)
        client.delayMs = 100L

        client.clear()

        assertEquals(0, client.testConnectionResults.size)
        assertEquals(0, client.files.size)
        assertEquals(0, client.textFiles.size)
        assertEquals(0, client.invalidatedServerIds.size)
        assertEquals(0L, client.delayMs)
    }
}
