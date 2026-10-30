package com.starmano.splayer

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRequestTest {

    @Test
    fun headersArePairedUp() {
        val headers = parseHeaders(arrayOf("Referer", "https://example.com", "Cookie", "a=1"))
        assertEquals(mapOf("Referer" to "https://example.com", "Cookie" to "a=1"), headers)
    }

    @Test
    fun danglingAndBlankHeaderNamesAreDropped() {
        assertEquals(mapOf("A" to "1"), parseHeaders(arrayOf("A", "1", " ", "2", "B")))
        assertEquals(emptyMap<String, String>(), parseHeaders(null))
    }

    @Test
    fun adaptiveStreamsKeepTheirMimeType() {
        assertEquals(MimeTypes.APPLICATION_M3U8, streamMimeType("application/x-mpegURL"))
        assertEquals(MimeTypes.APPLICATION_M3U8, streamMimeType("application/vnd.apple.mpegurl"))
        assertEquals(MimeTypes.APPLICATION_MPD, streamMimeType("application/dash+xml"))
    }

    @Test
    fun progressiveStreamsAreLeftForMedia3ToSniff() {
        assertNull(streamMimeType("video/*"))
        assertNull(streamMimeType("video/mp4"))
        assertNull(streamMimeType(null))
    }

    @Test
    fun fileNamesBecomeTitles() {
        assertEquals("Big.Buck.Bunny.1080p.mkv", titleFromPathSegment("Big.Buck.Bunny.1080p.mkv"))
    }

    @Test
    fun torrentFileIndexesAreNotTitles() {
        assertNull(titleFromPathSegment("0"))
        assertNull(titleFromPathSegment(null))
    }
}
