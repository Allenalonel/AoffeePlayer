package com.webdav.player.ui.browser

import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.Breadcrumb
import com.webdav.player.domain.model.DirectoryContent
import com.webdav.player.domain.model.LibrarySearchResult
import com.webdav.player.domain.model.LibrarySyncResult
import com.webdav.player.domain.model.LibrarySyncState
import com.webdav.player.domain.model.ListDirectoryResult
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.repository.DirectoryRepository
import com.webdav.player.domain.repository.LibrarySearchRepository
import com.webdav.player.domain.repository.ServerRepository
import com.webdav.player.domain.session.FakeMusicPlayerAppSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectoryBrowserSyncTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeMusicPlayerAppSession: FakeMusicPlayerAppSession
    private lateinit var fakeLibrarySearchRepository: FakeSyncLibrarySearchRepository
    private lateinit var fakeDirectoryRepository: FakeSyncDirectoryRepository
    private lateinit var fakeServerRepository: FakeSyncServerRepository
    private lateinit var viewModel: DirectoryBrowserViewModel

    private val sampleServer =
        WebDavServer(
            id = 1L,
            name = "Test NAS",
            url = "http://nas.local",
            port = 5005,
            isDefault = true,
        )

    private class FakeSyncLibrarySearchRepository : LibrarySearchRepository {
        val syncStateFlow = MutableStateFlow<LibrarySyncState>(LibrarySyncState.Idle)
        var syncServerCalls = mutableListOf<Pair<WebDavServer, Boolean>>()

        override fun search(
            query: String,
            serverId: Long?,
        ): Flow<LibrarySearchResult> = flowOf(LibrarySearchResult())

        override fun observeSyncState(serverId: Long?): Flow<LibrarySyncState> = syncStateFlow.asStateFlow()

        override suspend fun syncServer(
            server: WebDavServer,
            force: Boolean,
        ): LibrarySyncResult {
            syncServerCalls.add(server to force)
            syncStateFlow.value = LibrarySyncState.Scanning(progress = 0.5f, currentPath = "/Music/", tracksFound = 42)
            syncStateFlow.value = LibrarySyncState.Completed(totalTracks = 42)
            return LibrarySyncResult.Success(totalTracksIndexed = 42)
        }
    }

    private class FakeSyncDirectoryRepository : DirectoryRepository {
        override fun observeDirectoryContent(
            server: WebDavServer,
            path: String,
            forceRefresh: Boolean,
        ): Flow<DirectoryContent> =
            flowOf(
                DirectoryContent(
                    directory = RemoteDirectory(path = path, name = path.trimEnd('/').substringAfterLast('/')),
                ),
            )

        override suspend fun clearCache() {}
    }

    private class FakeSyncServerRepository(
        private val activeServer: WebDavServer,
    ) : ServerRepository {
        override fun getAllServers(): Flow<List<WebDavServer>> = flowOf(listOf(activeServer))

        override fun getActiveServer(): Flow<WebDavServer?> = flowOf(activeServer)

        override suspend fun getServerById(id: Long): WebDavServer? = if (id == activeServer.id) activeServer else null

        override suspend fun saveServer(server: WebDavServer): Long = server.id

        override suspend fun deleteServer(id: Long) {}

        override suspend fun setActiveServer(id: Long) {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeMusicPlayerAppSession = FakeMusicPlayerAppSession()
        fakeMusicPlayerAppSession.setActiveServer(sampleServer)
        fakeLibrarySearchRepository = FakeSyncLibrarySearchRepository()
        fakeDirectoryRepository = FakeSyncDirectoryRepository()
        fakeServerRepository = FakeSyncServerRepository(sampleServer)

        viewModel =
            DirectoryBrowserViewModel(
                serverRepository = fakeServerRepository,
                directoryRepository = fakeDirectoryRepository,
                musicPlayerAppSession = fakeMusicPlayerAppSession,
                librarySearchRepository = fakeLibrarySearchRepository,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun syncState_projectsReactiveStateFromRepository() =
        runTest {
            advanceUntilIdle()
            assertEquals(LibrarySyncState.Idle, viewModel.uiState.value.syncState)

            // Repository transitions to Scanning
            fakeLibrarySearchRepository.syncStateFlow.value =
                LibrarySyncState.Scanning(progress = 0.2f, currentPath = "/Flac/", tracksFound = 10)
            advanceUntilIdle()

            val state = viewModel.uiState.value.syncState
            assertTrue(state is LibrarySyncState.Scanning)
            assertEquals(10, (state as LibrarySyncState.Scanning).tracksFound)
            assertEquals("/Flac/", state.currentPath)

            // Repository transitions to Completed
            fakeLibrarySearchRepository.syncStateFlow.value =
                LibrarySyncState.Completed(totalTracks = 50)
            advanceUntilIdle()

            val completed = viewModel.uiState.value.syncState
            assertTrue(completed is LibrarySyncState.Completed)
            assertEquals(50, (completed as LibrarySyncState.Completed).totalTracks)
        }

    @Test
    fun onSyncLibrary_triggersSyncServerOnActiveServer() =
        runTest {
            advanceUntilIdle()
            assertEquals(sampleServer, viewModel.uiState.value.activeServer)

            viewModel.onSyncLibrary()
            advanceUntilIdle()

            assertEquals(1, fakeLibrarySearchRepository.syncServerCalls.size)
            val (calledServer, force) = fakeLibrarySearchRepository.syncServerCalls.first()
            assertEquals(sampleServer.id, calledServer.id)
            assertTrue(force)
        }

    @Test
    fun onSearchResultTrackClicked_insertsTrackNextAndAdvancesPlayback() =
        runTest {
            advanceUntilIdle()
            val track =
                AudioTrack(
                    id = "1:/Music/hit.flac",
                    serverId = 1L,
                    remotePath = "/Music/hit.flac",
                    title = "Hit Song",
                    format = AudioFormat.FLAC,
                )

            viewModel.onSearchActiveChanged(true)
            viewModel.onSearchQueryChanged("Hit")
            assertTrue(viewModel.uiState.value.isSearchActive)

            viewModel.onSearchResultTrackClicked(track)
            advanceUntilIdle()

            // Search bar closed
            assertFalse(viewModel.uiState.value.isSearchActive)

            // Track inserted next and advanced via playNextAndAdvance
            assertEquals("1:/Music/hit.flac", fakeMusicPlayerAppSession.lastPlayNextAndAdvanceTrack?.id)
        }

    @Test
    fun onSearchResultDirectoryClicked_navigatesDirectlyToDirectoryAndUpdatesBreadcrumbs() =
        runTest {
            advanceUntilIdle()
            val targetDir =
                RemoteDirectory(
                    path = "/Music/Rock/",
                    name = "Rock",
                    serverId = sampleServer.id,
                )

            viewModel.onSearchActiveChanged(true)
            viewModel.onSearchResultDirectoryClicked(targetDir)
            advanceUntilIdle()

            // Search bar closed and currentPath updated
            assertFalse(viewModel.uiState.value.isSearchActive)
            assertEquals("/Music/Rock/", viewModel.uiState.value.currentPath)
            val breadcrumbs = viewModel.uiState.value.breadcrumbs
            assertEquals(
                listOf(
                    Breadcrumb(name = sampleServer.name, path = "/"),
                    Breadcrumb(name = "Music", path = "/Music/"),
                    Breadcrumb(name = "Rock", path = "/Music/Rock/"),
                ),
                breadcrumbs,
            )
        }
}
