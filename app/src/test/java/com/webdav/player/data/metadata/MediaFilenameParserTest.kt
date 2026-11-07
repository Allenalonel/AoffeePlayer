package com.webdav.player.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFilenameParserTest {
    @Test
    fun parse_standardArtistAndTitle_extractsBoth() {
        val result = MediaFilenameParser.parse("Artist - Title.flac")
        assertEquals("Title", result.title)
        assertEquals("Artist", result.artist)
    }

    @Test
    fun parse_leadingTrackNumberAndTitle_extractsTitleOnly() {
        val r1 = MediaFilenameParser.parse("01. Title.mp3")
        assertEquals("Title", r1.title)
        assertNull(r1.artist)

        val r2 = MediaFilenameParser.parse("01 - Title.mp3")
        assertEquals("Title", r2.title)
        assertNull(r2.artist)

        val r3 = MediaFilenameParser.parse("01 Title.mp3")
        assertEquals("Title", r3.title)
        assertNull(r3.artist)
    }

    @Test
    fun parse_trackPrefixArtistAndTitle_extractsArtistAndTitle() {
        val r1 = MediaFilenameParser.parse("Track - Artist - Title.mp3")
        assertEquals("Title", r1.title)
        assertEquals("Artist", r1.artist)

        val r2 = MediaFilenameParser.parse("01 - Artist - Title.mp3")
        assertEquals("Title", r2.title)
        assertEquals("Artist", r2.artist)

        val r3 = MediaFilenameParser.parse("Track 02 - Artist - Title.flac")
        assertEquals("Title", r3.title)
        assertEquals("Artist", r3.artist)
    }

    @Test
    fun parse_trackDottedArtistAndTitle_extractsCleanArtistAndTitle() {
        val result = MediaFilenameParser.parse("01. Artist - Title.flac")
        assertEquals("Title", result.title)
        assertEquals("Artist", result.artist)
    }

    @Test
    fun parse_artistAlbumTitle_extractsArtistAndTitle() {
        val result = MediaFilenameParser.parse("Queen - A Night at the Opera - Bohemian Rhapsody.mp3")
        assertEquals("Bohemian Rhapsody", result.title)
        assertEquals("Queen", result.artist)
    }

    @Test
    fun parse_plainFileName_extractsTitleWithNoArtist() {
        val result = MediaFilenameParser.parse("BohemianRhapsody.wav")
        assertEquals("BohemianRhapsody", result.title)
        assertNull(result.artist)
    }

    @Test
    fun parse_surroundingWhitespace_isProperlyTrimmed() {
        val result = MediaFilenameParser.parse("   Eagles   -   Hotel California   .flac")
        assertEquals("Hotel California", result.title)
        assertEquals("Eagles", result.artist)
    }

    @Test
    fun parse_numericOnlyTitles_preservesNumber() {
        val r1 = MediaFilenameParser.parse("1999.mp3")
        assertEquals("1999", r1.title)
        assertNull(r1.artist)

        val r2 = MediaFilenameParser.parse("01.mp3")
        assertEquals("01", r2.title)
        assertNull(r2.artist)
    }

    @Test
    fun parse_complexMultiSegmentFileName_handlesTrackAndSegments() {
        val result = MediaFilenameParser.parse("01 - Pink Floyd - The Dark Side of the Moon - Time.flac")
        assertEquals("Pink Floyd", result.artist)
        assertEquals("The Dark Side of the Moon - Time", result.title)
    }
}
