package com.webdav.player.data.storage

import com.webdav.player.data.remote.ConnectionResult
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.domain.model.InactiveReason
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.ListDirectoryResult
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.repository.ServerRepository
import com.webdav.player.domain.storage.TrackKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class StorageReachabilityTrackerTest {
    private class FakeProbeWebDavClient : WebDavClient {
        val testConnectionResults = mutableMapOf<Long, ConnectionResult>()
        val connectionProbeCount = AtomicInteger(0)
        var onTestConnection: (suspend (WebDavServer) -> ConnectionResult)? = null

        override suspend fun testConnection(server: WebDavServer): ConnectionResult {
            connectionProbeCount.incrementAndGet()
            onTestConnection?.let { return it(server) }
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

    private class FakeServerRepository(
        initialServers: List<WebDavServer> = emptyList(),
    ) : ServerRepository {
        private val serversMap = initialServers.associateBy { it.id }.toMutableMap()

        fun addServer(server: WebDavServer) {
            serversMap[server.id] = server
        }

        override fun getAllServers(): Flow<List<WebDavServer>> = flowOf(serversMap.values.toList())

        override fun getActiveServer(): Flow<WebDavServer?> = flowOf(serversMap.values.firstOrNull())

        override suspend fun getServerById(id: Long): WebDavServer? = serversMap[id]

        override suspend fun saveServer(server: WebDavServer): Long {
            serversMap[server.id] = server
            return server.id
        }

        override suspend fun deleteServer(id: Long) {
            serversMap.remove(id)
        }

        override suspend fun setActiveServer(id: Long) {}
    }

    @Test
    fun reportTrackUnavailable_updatesInactiveTracksStateFlow() {
        val tracker = StorageReachabilityTrackerImpl()

        assertTrue(tracker.inactiveTracks.value.isEmpty())

        tracker.reportTrackUnavailable(1L, "/music/track1.mp3", InactiveReason.FILE_NOT_FOUND)

        val inactive = tracker.inactiveTracks.value
        assertEquals(1, inactive.size)
        assertEquals(InactiveReason.FILE_NOT_FOUND, inactive[TrackKey(1L, "/music/track1.mp3")])

        tracker.reportTrackUnavailable(2L, "/other/track2.flac", InactiveReason.SERVER_UNREACHABLE)

        val updated = tracker.inactiveTracks.value
        assertEquals(2, updated.size)
        assertEquals(InactiveReason.FILE_NOT_FOUND, updated[TrackKey(1L, "/music/track1.mp3")])
        assertEquals(InactiveReason.SERVER_UNREACHABLE, updated[TrackKey(2L, "/other/track2.flac")])
    }

    @Test
    fun reportTrackUnavailable_overwritesPreviousReasonForSameTrack() {
        val tracker = StorageReachabilityTrackerImpl()

        tracker.reportTrackUnavailable(1L, "/music/track1.mp3", InactiveReason.FILE_NOT_FOUND)
        assertEquals(InactiveReason.FILE_NOT_FOUND, tracker.inactiveTracks.value[TrackKey(1L, "/music/track1.mp3")])

        tracker.reportTrackUnavailable(1L, "/music/track1.mp3", InactiveReason.SERVER_UNREACHABLE)
        assertEquals(InactiveReason.SERVER_UNREACHABLE, tracker.inactiveTracks.value[TrackKey(1L, "/music/track1.mp3")])
    }

    @Test
    fun reportServerUnavailable_updatesServerReachabilityAndInactiveTracks() {
        val tracker = StorageReachabilityTrackerImpl()

        tracker.reportTrackUnavailable(1L, "/song1.mp3", InactiveReason.SERVER_CREDENTIAL_INVALID)
        tracker.reportTrackUnavailable(1L, "/missing.mp3", InactiveReason.FILE_NOT_FOUND)
        tracker.reportTrackUnavailable(2L, "/song2.mp3", InactiveReason.SERVER_CREDENTIAL_INVALID)

        tracker.reportServerUnavailable(1L, InactiveReason.SERVER_UNREACHABLE)

        assertEquals(false, tracker.serverReachability.value[1L])
        assertNull(tracker.serverReachability.value[2L])

        val inactive = tracker.inactiveTracks.value
        assertEquals(InactiveReason.SERVER_UNREACHABLE, inactive[TrackKey(1L, "/song1.mp3")])
        assertEquals(InactiveReason.FILE_NOT_FOUND, inactive[TrackKey(1L, "/missing.mp3")])
        assertEquals(InactiveReason.SERVER_CREDENTIAL_INVALID, inactive[TrackKey(2L, "/song2.mp3")])
    }

    @Test
    fun reportServerReachable_clearsServerFailure_andClearsServerInactiveTracks_keepingFileNotFound() {
        val tracker = StorageReachabilityTrackerImpl()

        tracker.reportTrackUnavailable(1L, "/unreachable.mp3", InactiveReason.SERVER_UNREACHABLE)
        tracker.reportTrackUnavailable(1L, "/auth_fail.mp3", InactiveReason.SERVER_CREDENTIAL_INVALID)
        tracker.reportTrackUnavailable(1L, "/missing_404.mp3", InactiveReason.FILE_NOT_FOUND)
        tracker.reportTrackUnavailable(2L, "/other_server.mp3", InactiveReason.SERVER_UNREACHABLE)
        tracker.reportServerUnavailable(1L, InactiveReason.SERVER_UNREACHABLE)
        tracker.reportServerUnavailable(2L, InactiveReason.SERVER_UNREACHABLE)

        assertEquals(false, tracker.serverReachability.value[1L])
        assertEquals(false, tracker.serverReachability.value[2L])

        tracker.reportServerReachable(1L)

        assertEquals(true, tracker.serverReachability.value[1L])
        assertEquals(false, tracker.serverReachability.value[2L])

        val inactive = tracker.inactiveTracks.value
        assertNull("SERVER_UNREACHABLE track on server 1 must be cleared", inactive[TrackKey(1L, "/unreachable.mp3")])
        assertNull("SERVER_CREDENTIAL_INVALID track on server 1 must be cleared", inactive[TrackKey(1L, "/auth_fail.mp3")])
        assertEquals(
            "FILE_NOT_FOUND track on server 1 must be preserved",
            InactiveReason.FILE_NOT_FOUND,
            inactive[TrackKey(1L, "/missing_404.mp3")],
        )
        assertEquals(
            "Track on server 2 must remain unchanged",
            InactiveReason.SERVER_UNREACHABLE,
            inactive[TrackKey(2L, "/other_server.mp3")],
        )
    }

    @Test
    fun triggerReachabilitySelfHealing_testsUnreachableWebDavServers_andRestoresReachableOnes() =
        runBlocking {
            val server1 = WebDavServer(id = 1L, name = "Online NAS", url = "http://192.168.1.10")
            val server2 = WebDavServer(id = 2L, name = "Offline NAS", url = "http://192.168.1.20")
            val serverRepo = FakeServerRepository(listOf(server1, server2))

            val fakeClient = FakeProbeWebDavClient()
            fakeClient.testConnectionResults[1L] = ConnectionResult.Success
            fakeClient.testConnectionResults[2L] = ConnectionResult.Failure("Host unreachable")

            val tracker =
                StorageReachabilityTrackerImpl(
                    webDavClient = fakeClient,
                    serverRepository = serverRepo,
                    ioDispatcher = Dispatchers.Unconfined,
                )

            tracker.reportServerUnavailable(1L, InactiveReason.SERVER_UNREACHABLE)
            tracker.reportServerUnavailable(2L, InactiveReason.SERVER_UNREACHABLE)
            tracker.reportTrackUnavailable(1L, "/online.mp3", InactiveReason.SERVER_UNREACHABLE)
            tracker.reportTrackUnavailable(2L, "/offline.mp3", InactiveReason.SERVER_UNREACHABLE)

            tracker.triggerReachabilitySelfHealing()

            assertEquals(true, tracker.serverReachability.value[1L])
            assertEquals(false, tracker.serverReachability.value[2L])

            val inactive = tracker.inactiveTracks.value
            assertNull("Server 1 track must be restored", inactive[TrackKey(1L, "/online.mp3")])
            assertEquals(InactiveReason.SERVER_UNREACHABLE, inactive[TrackKey(2L, "/offline.mp3")])
        }

    @Test
    fun triggerReachabilitySelfHealing_restoresServerCredentialInvalidTracks() =
        runBlocking {
            val server1 = WebDavServer(id = 1L, name = "Online NAS", url = "http://192.168.1.10")
            val serverRepo = FakeServerRepository(listOf(server1))

            val fakeClient = FakeProbeWebDavClient()
            fakeClient.testConnectionResults[1L] = ConnectionResult.Success

            val tracker =
                StorageReachabilityTrackerImpl(
                    webDavClient = fakeClient,
                    serverRepository = serverRepo,
                    ioDispatcher = Dispatchers.Unconfined,
                )

            tracker.reportTrackUnavailable(1L, "/auth.mp3", InactiveReason.SERVER_CREDENTIAL_INVALID)

            tracker.triggerReachabilitySelfHealing()

            assertEquals(true, tracker.serverReachability.value[1L])
            assertNull(tracker.inactiveTracks.value[TrackKey(1L, "/auth.mp3")])
        }

    @Test
    fun triggerReachabilitySelfHealing_handlesClientExceptionsGracefully() =
        runBlocking {
            val server1 = WebDavServer(id = 1L, name = "Crashing NAS", url = "http://192.168.1.10")
            val serverRepo = FakeServerRepository(listOf(server1))

            val fakeClient = FakeProbeWebDavClient()
            fakeClient.onTestConnection = { throw IOException("Connection reset by peer") }

            val tracker =
                StorageReachabilityTrackerImpl(
                    webDavClient = fakeClient,
                    serverRepository = serverRepo,
                    ioDispatcher = Dispatchers.Unconfined,
                )

            tracker.reportServerUnavailable(1L, InactiveReason.SERVER_UNREACHABLE)
            tracker.reportTrackUnavailable(1L, "/crash.mp3", InactiveReason.SERVER_UNREACHABLE)

            tracker.triggerReachabilitySelfHealing()

            assertEquals(false, tracker.serverReachability.value[1L])
            assertEquals(InactiveReason.SERVER_UNREACHABLE, tracker.inactiveTracks.value[TrackKey(1L, "/crash.mp3")])
        }

    @Test
    fun triggerReachabilitySelfHealing_checksUnreachableLocalStorageTracks_andRestoresReachableOnes() =
        runBlocking {
            val accessibleUris = mutableSetOf("content://media/sdcard/song1.flac")

            val tracker =
                StorageReachabilityTrackerImpl(
                    localUriReachabilityChecker = { path -> accessibleUris.contains(path) },
                    ioDispatcher = Dispatchers.Unconfined,
                )

            tracker.reportTrackUnavailable(LOCAL_STORAGE_ID, "content://media/sdcard/song1.flac", InactiveReason.FILE_NOT_FOUND)
            tracker.reportTrackUnavailable(LOCAL_STORAGE_ID, "content://media/sdcard/missing.flac", InactiveReason.FILE_NOT_FOUND)

            assertEquals(2, tracker.inactiveTracks.value.size)

            tracker.triggerReachabilitySelfHealing()

            val inactive = tracker.inactiveTracks.value
            assertEquals(1, inactive.size)
            assertNull(
                "Song 1 is now accessible and should be restored",
                inactive[TrackKey(LOCAL_STORAGE_ID, "content://media/sdcard/song1.flac")],
            )
            assertEquals(
                "Missing song remains inaccessible",
                InactiveReason.FILE_NOT_FOUND,
                inactive[TrackKey(LOCAL_STORAGE_ID, "content://media/sdcard/missing.flac")],
            )
        }

    @Test
    fun triggerReachabilitySelfHealing_preventsConcurrentProbeStorms_viaReentrancyGuard() =
        runBlocking {
            val server1 = WebDavServer(id = 1L, name = "Slow NAS", url = "http://192.168.1.10")
            val serverRepo = FakeServerRepository(listOf(server1))

            val probeStarted = CompletableDeferred<Unit>()
            val probeProceed = CompletableDeferred<Unit>()

            val fakeClient = FakeProbeWebDavClient()
            fakeClient.onTestConnection = {
                probeStarted.complete(Unit)
                probeProceed.await()
                ConnectionResult.Success
            }

            val tracker =
                StorageReachabilityTrackerImpl(
                    webDavClient = fakeClient,
                    serverRepository = serverRepo,
                    ioDispatcher = Dispatchers.Default,
                )

            tracker.reportServerUnavailable(1L, InactiveReason.SERVER_UNREACHABLE)

            val job1 = launch { tracker.triggerReachabilitySelfHealing() }

            probeStarted.await()

            // Invoke second trigger concurrently while job1 is in-flight
            tracker.triggerReachabilitySelfHealing()

            // Let job1 complete
            probeProceed.complete(Unit)
            job1.join()

            // Exactly one probe should have been executed because the second was rejected by AtomicBoolean
            assertEquals(1, fakeClient.connectionProbeCount.get())

            // Subsequent trigger after job1 completes can execute normally
            fakeClient.onTestConnection = null
            fakeClient.testConnectionResults[1L] = ConnectionResult.Success

            tracker.reportServerUnavailable(1L, InactiveReason.SERVER_UNREACHABLE)
            tracker.triggerReachabilitySelfHealing()

            assertEquals(2, fakeClient.connectionProbeCount.get())
        }

    @Test
    fun triggerReachabilitySelfHealing_withNoUnreachableEntities_completesCleanlyWithoutProbing() =
        runBlocking {
            val fakeClient = FakeProbeWebDavClient()
            val serverRepo = FakeServerRepository()

            val tracker =
                StorageReachabilityTrackerImpl(
                    webDavClient = fakeClient,
                    serverRepository = serverRepo,
                    ioDispatcher = Dispatchers.Unconfined,
                )

            tracker.triggerReachabilitySelfHealing()

            assertEquals(0, fakeClient.connectionProbeCount.get())
            assertTrue(tracker.inactiveTracks.value.isEmpty())
            assertTrue(tracker.serverReachability.value.isEmpty())
        }

    @Test
    fun triggerReachabilitySelfHealing_handlesDeletedOrBlankUrlServersGracefully() =
        runBlocking {
            val serverWithBlankUrl = WebDavServer(id = 1L, name = "Blank URL", url = "")
            val serverRepo = FakeServerRepository(listOf(serverWithBlankUrl))
            val fakeClient = FakeProbeWebDavClient()

            val tracker =
                StorageReachabilityTrackerImpl(
                    webDavClient = fakeClient,
                    serverRepository = serverRepo,
                    ioDispatcher = Dispatchers.Unconfined,
                )

            tracker.reportServerUnavailable(1L, InactiveReason.SERVER_UNREACHABLE)
            tracker.reportServerUnavailable(999L, InactiveReason.SERVER_UNREACHABLE) // Missing server

            tracker.triggerReachabilitySelfHealing()

            assertEquals(0, fakeClient.connectionProbeCount.get())
            assertEquals(false, tracker.serverReachability.value[1L])
            assertEquals(false, tracker.serverReachability.value[999L])
        }

    @Test
    fun triggerReachabilitySelfHealing_withoutContextOrChecker_safelyKeepsLocalTracksInactive() =
        runBlocking {
            val tracker =
                StorageReachabilityTrackerImpl(
                    context = null,
                    localUriReachabilityChecker = null,
                    ioDispatcher = Dispatchers.Unconfined,
                )

            tracker.reportTrackUnavailable(LOCAL_STORAGE_ID, "content://media/sdcard/song.flac", InactiveReason.FILE_NOT_FOUND)

            tracker.triggerReachabilitySelfHealing()

            val inactive = tracker.inactiveTracks.value
            assertEquals(1, inactive.size)
            assertEquals(
                InactiveReason.FILE_NOT_FOUND,
                inactive[TrackKey(LOCAL_STORAGE_ID, "content://media/sdcard/song.flac")],
            )
        }
}
