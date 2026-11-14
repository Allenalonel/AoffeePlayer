package com.webdav.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteDirectorySorterTest {

    private val subDirA = RemoteDirectory(
        path = "/music/Folder A/",
        name = "Folder A",
        lastModified = 1000L
    )
    private val subDirB = RemoteDirectory(
        path = "/music/Folder 10/",
        name = "Folder 10",
        lastModified = 3000L
    )
    private val subDirC = RemoteDirectory(
        path = "/music/Folder 2/",
        name = "Folder 2",
        lastModified = 2000L
    )

    private val file1 = RemoteFile(
        name = "01 - Track 1.flac",
        path = "/music/01 - Track 1.flac",
        size = 10_000_000L,
        lastModified = "Wed, 14 Jan 2026 08:00:00 GMT"
    )
    private val file2 = RemoteFile(
        name = "02 - Track 2.flac",
        path = "/music/02 - Track 2.flac",
        size = 50_000_000L,
        lastModified = "1768464600000" // newer timestamp
    )
    private val file10 = RemoteFile(
        name = "10 - Track 10.flac",
        path = "/music/10 - Track 10.flac",
        size = 5_000_000L,
        lastModified = "2026-01-14T09:00:00Z"
    )
    private val fileNoDate = RemoteFile(
        name = "Unknown.flac",
        path = "/music/Unknown.flac",
        size = 20_000_000L,
        lastModified = null
    )

    private val sampleDirectory = RemoteDirectory(
        path = "/music/",
        name = "music",
        subDirectories = listOf(subDirB, subDirA, subDirC),
        files = listOf(file10, file2, fileNoDate, file1)
    )

    @Test
    fun sortByNameAscending_preservesFolderPinningAndNaturalOrder() {
        val sorted = sampleDirectory.withSortOrder(
            DirectorySortOrder(field = SortField.NAME, direction = SortDirection.ASCENDING)
        )

        // Subdirectories stay on top, naturally sorted
        assertEquals(listOf("Folder 2", "Folder 10", "Folder A"), sorted.subDirectories.map { it.name })

        // Files follow, naturally sorted
        assertEquals(
            listOf("01 - Track 1.flac", "02 - Track 2.flac", "10 - Track 10.flac", "Unknown.flac"),
            sorted.files.map { it.name }
        )
    }

    @Test
    fun sortByNameDescending_preservesFolderPinningAndReversesNaturalOrder() {
        val sorted = sampleDirectory.withSortOrder(
            DirectorySortOrder(field = SortField.NAME, direction = SortDirection.DESCENDING)
        )

        // Subdirectories stay on top, reverse sorted
        assertEquals(listOf("Folder A", "Folder 10", "Folder 2"), sorted.subDirectories.map { it.name })

        // Files follow, reverse sorted
        assertEquals(
            listOf("Unknown.flac", "10 - Track 10.flac", "02 - Track 2.flac", "01 - Track 1.flac"),
            sorted.files.map { it.name }
        )
    }

    @Test
    fun sortByLastModified_ordersByParsedTime() {
        // Ascending (oldest first)
        val sortedAsc = sampleDirectory.withSortOrder(
            DirectorySortOrder(field = SortField.LAST_MODIFIED, direction = SortDirection.ASCENDING)
        )
        assertEquals(listOf("Folder A", "Folder 2", "Folder 10"), sortedAsc.subDirectories.map { it.name })
        assertEquals("Unknown.flac", sortedAsc.files.first().name) // null date = 0L is oldest

        // Descending (newest first)
        val sortedDesc = sampleDirectory.withSortOrder(
            DirectorySortOrder(field = SortField.LAST_MODIFIED, direction = SortDirection.DESCENDING)
        )
        assertEquals(listOf("Folder 10", "Folder 2", "Folder A"), sortedDesc.subDirectories.map { it.name })
        assertEquals("Unknown.flac", sortedDesc.files.last().name) // null date = 0L is at bottom when descending
    }

    @Test
    fun sortBySize_ordersByFileSize() {
        // Ascending (smallest file first)
        val sortedAsc = sampleDirectory.withSortOrder(
            DirectorySortOrder(field = SortField.SIZE, direction = SortDirection.ASCENDING)
        )
        assertEquals(
            listOf(5_000_000L, 10_000_000L, 20_000_000L, 50_000_000L),
            sortedAsc.files.map { it.size }
        )

        // Descending (largest file first)
        val sortedDesc = sampleDirectory.withSortOrder(
            DirectorySortOrder(field = SortField.SIZE, direction = SortDirection.DESCENDING)
        )
        assertEquals(
            listOf(50_000_000L, 20_000_000L, 10_000_000L, 5_000_000L),
            sortedDesc.files.map { it.size }
        )
    }

    @Test
    fun handlesEmptyDirectory() {
        val emptyDir = RemoteDirectory(path = "/", name = "root")
        val sorted = emptyDir.withSortOrder(DirectorySortOrder())
        assertEquals(0, sorted.subDirectories.size)
        assertEquals(0, sorted.files.size)
    }
}
