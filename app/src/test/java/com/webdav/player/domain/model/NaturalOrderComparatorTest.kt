package com.webdav.player.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderComparatorTest {

    @Test
    fun sortsNumberedTracksNaturally() {
        val tracks = listOf("Track 10.flac", "Track 2.flac", "Track 1.flac", "Track 20.flac")
        val sorted = tracks.sortedWith(NaturalOrderComparator)
        assertEquals(
            listOf("Track 1.flac", "Track 2.flac", "Track 10.flac", "Track 20.flac"),
            sorted
        )
    }

    @Test
    fun handlesZeroPaddedAndUnpaddedNumbers() {
        val items = listOf("01 - Song", "2 - Song", "10 - Song", "02 - Song")
        val sorted = items.sortedWith(NaturalOrderComparator)
        // 01 and 1 have same numeric value, sorted by tie-breaker
        assertEquals("01 - Song", sorted[0])
        assertEquals("2 - Song", sorted[1])
        assertEquals("02 - Song", sorted[2])
        assertEquals("10 - Song", sorted[3])
    }

    @Test
    fun handlesCaseInsensitivity() {
        val items = listOf("track b", "Track A", "TRACK C")
        val sorted = items.sortedWith(NaturalOrderComparator)
        assertEquals(listOf("Track A", "track b", "TRACK C"), sorted)
    }

    @Test
    fun handlesMultiChunkNumbers() {
        val items = listOf("v1.10.2", "v1.2.1", "v1.2.10", "v1.2.2")
        val sorted = items.sortedWith(NaturalOrderComparator)
        assertEquals(listOf("v1.2.1", "v1.2.2", "v1.2.10", "v1.10.2"), sorted)
    }

    @Test
    fun handlesLargeNumbersWithoutOverflow() {
        val items = listOf("item99999999999999999999", "item100000000000000000000", "item2")
        val sorted = items.sortedWith(NaturalOrderComparator)
        assertEquals(listOf("item2", "item99999999999999999999", "item100000000000000000000"), sorted)
    }

    @Test
    fun handlesChineseAndUnicode() {
        val items = listOf("周杰伦 10.mp3", "周杰伦 2.mp3", "周杰伦 1.mp3")
        val sorted = items.sortedWith(NaturalOrderComparator)
        assertEquals(listOf("周杰伦 1.mp3", "周杰伦 2.mp3", "周杰伦 10.mp3"), sorted)
    }

    @Test
    fun handlesNullAndIdenticalStrings() {
        assertEquals(0, NaturalOrderComparator.compare("A", "A"))
        assertEquals(0, NaturalOrderComparator.compare(null, null))
        assertTrue(NaturalOrderComparator.compare(null, "A") < 0)
        assertTrue(NaturalOrderComparator.compare("A", null) > 0)
    }
}
