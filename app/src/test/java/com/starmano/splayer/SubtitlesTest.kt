package com.starmano.splayer

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitlesTest {

    private val srt = "1\n00:00:01,000 --> 00:00:02,500\nHello\n"

    @Test
    fun detectsTheFormatFromTheFileItself() {
        assertEquals(SubtitleFileFormat.SUBRIP, detectSubtitleFormat(srt))
        assertEquals(SubtitleFileFormat.WEBVTT, detectSubtitleFormat("﻿WEBVTT\n\n00:01.000 --> 00:02.000\nHi"))
        assertEquals(SubtitleFileFormat.SSA, detectSubtitleFormat("[Script Info]\nTitle: x\n\n[Events]\n"))
        assertEquals(SubtitleFileFormat.SSA, detectSubtitleFormat("; comment\n[V4+ Styles]\n[Events]\n"))
    }

    @Test
    fun fixesSubripTimestampsWrittenWithDots() {
        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,5\nAt 10.30.00.5 sharp\n",
            normalizeSubripTimestamps("1\n00:00:01.000 --> 00:00:02.5\nAt 10.30.00.5 sharp\n"),
        )
        assertEquals(srt, normalizeSubripTimestamps(srt))
    }

    @Test
    fun readsUtf8AndUtf16() {
        val text = "Žaba čaka šefa"
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val legacy = legacySubtitleCharset("sl")
        assertEquals(text, decodeSubtitleText(utf8, 0, utf8.size, legacy))

        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8
        assertEquals(text, decodeSubtitleText(withBom, 0, withBom.size, legacy))

        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, decodeSubtitleText(utf16, 0, utf16.size, legacy))
    }

    @Test
    fun readsOldWindowsEncodingsForTheSubtitleLanguage() {
        val text = "Žaba čaka šefa"
        val windows1250 = text.toByteArray(charset("windows-1250"))
        assertEquals(text, decodeSubtitleText(windows1250, 0, windows1250.size, legacySubtitleCharset("sl")))

        val russian = "Привет"
        val windows1251 = russian.toByteArray(charset("windows-1251"))
        assertEquals(russian, decodeSubtitleText(windows1251, 0, windows1251.size, legacySubtitleCharset("ru")))
    }

    @Test
    fun picksTheLegacyEncodingByLanguage() {
        assertEquals("windows-1250", legacySubtitleCharset("sl").name())
        assertEquals("windows-1250", legacySubtitleCharset("hr-HR").name())
        assertEquals("windows-1251", legacySubtitleCharset("ru").name())
        assertEquals("windows-1252", legacySubtitleCharset("en").name())
        assertEquals("windows-1252", legacySubtitleCharset(null).name())
    }
}
