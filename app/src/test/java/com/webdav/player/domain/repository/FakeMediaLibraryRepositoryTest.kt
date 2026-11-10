package com.webdav.player.domain.repository

import com.webdav.player.data.repository.LocalStorageIndexScanner
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FakeMediaLibraryRepositoryTest {

    private lateinit var fakeRepository: FakeMediaLibraryRepository

    @Before
    fun setUp() {
        fakeRepository = FakeMediaLibraryRepository()
    }

    @Test
    fun returnsRootByDefault_forUnvisitedServer() {
        assertEquals("/", fakeRepository.getLastBrowsingPath(1L))
        assertEquals("/", fakeRepository.getLastBrowsingPath(LOCAL_STORAGE_ID))
    }

    @Test
    fun setLastBrowsingPath_recordsAndUpdatesCursor() {
        fakeRepository.setLastBrowsingPath(1L, "/Music/Rock")
        assertEquals("/Music/Rock/", fakeRepository.getLastBrowsingPath(1L))
        assertEquals(listOf(1L to "/Music/Rock/"), fakeRepository.setBrowsingPathCalls)
    }

    @Test
    fun maintainsServerIsolation() {
        fakeRepository.setLastBrowsingPath(1L, "/Server1/Path/")
        fakeRepository.setLastBrowsingPath(2L, "/Server2/Path/")
        fakeRepository.setLastBrowsingPath(LOCAL_STORAGE_ID, "/local/Path/")

        assertEquals("/Server1/Path/", fakeRepository.getLastBrowsingPath(1L))
        assertEquals("/Server2/Path/", fakeRepository.getLastBrowsingPath(2L))
        assertEquals("/local/Path/", fakeRepository.getLastBrowsingPath(LOCAL_STORAGE_ID))
    }

    @Test
    fun clearStorageData_purgesBrowsingCursor() = runTest {
        fakeRepository.setLastBrowsingPath(1L, "/Music/Rock/")
        fakeRepository.setLastBrowsingPath(2L, "/Music/Jazz/")

        fakeRepository.clearStorageData(1L)

        assertEquals("/", fakeRepository.getLastBrowsingPath(1L))
        assertEquals("/Music/Jazz/", fakeRepository.getLastBrowsingPath(2L))
        assertEquals(listOf(1L), fakeRepository.clearedStorageServerIds)
    }

    @Test
    fun clearLocalFolderData_resetsLocalCursorWhenInsideUnmountedTree() = runTest {
        val uriString = "content://test.storage/tree/Music"
        val localRoot = LocalStorageIndexScanner.buildLocalRootPath(uriString)
        val insidePath = "${localRoot}Classical/"

        fakeRepository.setLastBrowsingPath(LOCAL_STORAGE_ID, insidePath)
        fakeRepository.clearLocalFolderData(uriString)

        assertEquals("/", fakeRepository.getLastBrowsingPath(LOCAL_STORAGE_ID))
        assertEquals(listOf(uriString), fakeRepository.clearedLocalFolderUris)
    }

    @Test
    fun clearLocalFolderData_doesNotResetLocalCursorWhenOutsideUnmountedTree() = runTest {
        val unmountedUri = "content://test.storage/tree/Music"
        val otherUri = "content://test.storage/tree/Other"
        val otherRoot = LocalStorageIndexScanner.buildLocalRootPath(otherUri)
        val otherPath = "${otherRoot}Classical/"

        fakeRepository.setLastBrowsingPath(LOCAL_STORAGE_ID, otherPath)
        fakeRepository.clearLocalFolderData(unmountedUri)

        assertEquals(otherPath, fakeRepository.getLastBrowsingPath(LOCAL_STORAGE_ID))
    }
}
