package com.webdav.player.domain.model

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Domain engine responsible for sorting RemoteDirectory subdirectories and files
 * according to a DirectorySortOrder while preserving the invariant that subdirectories
 * are always pinned at the top.
 */
object RemoteDirectorySorter {

    fun sort(directory: RemoteDirectory, sortOrder: DirectorySortOrder): RemoteDirectory {
        val sortedSubDirs = sortSubDirectories(directory.subDirectories, sortOrder)
        val sortedFiles = sortFiles(directory.files, sortOrder)
        return directory.copy(
            subDirectories = sortedSubDirs,
            files = sortedFiles,
        )
    }

    fun sortSubDirectories(
        subDirectories: List<RemoteDirectory>,
        sortOrder: DirectorySortOrder,
    ): List<RemoteDirectory> {
        if (subDirectories.size <= 1) return subDirectories

        val baseComparator: Comparator<RemoteDirectory> = when (sortOrder.field) {
            SortField.NAME, SortField.SIZE -> {
                Comparator { d1, d2 -> NaturalOrderComparator.compare(d1.name, d2.name) }
            }
            SortField.LAST_MODIFIED -> {
                Comparator { d1, d2 ->
                    val diff = d1.lastModified.compareTo(d2.lastModified)
                    if (diff != 0) diff else NaturalOrderComparator.compare(d1.name, d2.name)
                }
            }
        }

        val comparator = when (sortOrder.direction) {
            SortDirection.ASCENDING -> baseComparator
            SortDirection.DESCENDING -> baseComparator.reversed()
        }

        return subDirectories.sortedWith(comparator)
    }

    fun sortFiles(
        files: List<RemoteFile>,
        sortOrder: DirectorySortOrder,
    ): List<RemoteFile> {
        if (files.size <= 1) return files

        val baseComparator: Comparator<RemoteFile> = when (sortOrder.field) {
            SortField.NAME -> {
                Comparator { f1, f2 ->
                    val diff = NaturalOrderComparator.compare(f1.name, f2.name)
                    if (diff != 0) diff else f1.path.compareTo(f2.path)
                }
            }
            SortField.LAST_MODIFIED -> {
                val timestamps = HashMap<String, Long>(files.size)
                for (f in files) {
                    timestamps[f.path] = parseLastModifiedToMillis(f.lastModified)
                }
                Comparator { f1, f2 ->
                    val t1 = timestamps[f1.path] ?: 0L
                    val t2 = timestamps[f2.path] ?: 0L
                    val diff = t1.compareTo(t2)
                    if (diff != 0) diff else NaturalOrderComparator.compare(f1.name, f2.name)
                }
            }
            SortField.SIZE -> {
                Comparator { f1, f2 ->
                    val diff = f1.size.compareTo(f2.size)
                    if (diff != 0) diff else NaturalOrderComparator.compare(f1.name, f2.name)
                }
            }
        }

        val comparator = when (sortOrder.direction) {
            SortDirection.ASCENDING -> baseComparator
            SortDirection.DESCENDING -> baseComparator.reversed()
        }

        return files.sortedWith(comparator)
    }

    fun parseLastModifiedToMillis(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        val trimmed = dateStr.trim()
        trimmed.toLongOrNull()?.let { return it }
        if (trimmed.contains("GMT", ignoreCase = true) || trimmed.contains(",")) {
            try {
                return ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            } catch (_: Exception) {}
        }
        if (trimmed.contains("T") || trimmed.contains("-")) {
            try {
                return Instant.parse(trimmed).toEpochMilli()
            } catch (_: Exception) {}
        }
        try {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            return sdf.parse(trimmed)?.time ?: 0L
        } catch (_: Exception) {}
        return 0L
    }
}

/**
 * Extension function to return a sorted copy of RemoteDirectory.
 */
fun RemoteDirectory.withSortOrder(sortOrder: DirectorySortOrder): RemoteDirectory =
    RemoteDirectorySorter.sort(this, sortOrder)
