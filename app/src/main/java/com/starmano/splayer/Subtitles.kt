package com.starmano.splayer

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.ssa.SsaParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Format given to subtitle files handed over by other apps (Stremio's subtitles). Their URLs
 * rarely say what format they are, and Media3 silently shows nothing when it's told the wrong
 * one, so their real format is detected from the file itself instead.
 */
internal const val SIDELOADED_SUBTITLE_MIME_TYPE = "application/x-splayer-sideloaded-subtitles"

internal enum class SubtitleFileFormat { SUBRIP, WEBVTT, SSA }

internal fun detectSubtitleFormat(text: String): SubtitleFileFormat {
    val start = text.trimStart('﻿', ' ', '\t', '\r', '\n')
    return when {
        start.startsWith("WEBVTT") -> SubtitleFileFormat.WEBVTT
        start.startsWith("[Script Info]", ignoreCase = true) || "[Events]" in text -> SubtitleFileFormat.SSA
        else -> SubtitleFileFormat.SUBRIP
    }
}

/** Some SubRip files write "00:01:02.500" instead of "00:01:02,500", which Media3 rejects. */
internal fun normalizeSubripTimestamps(text: String): String {
    val dotMillis = Regex("""(\d{1,2}:\d{2}:\d{2})\.(\d{1,3})""")
    return text.lineSequence()
        .joinToString("\n") { line -> if ("-->" in line) dotMillis.replace(line, "$1,$2") else line }
}

/**
 * Reads a subtitle file as text: UTF-8 or UTF-16 when it says so or is valid UTF-8, otherwise
 * the legacy encoding such files usually use for their language (e.g. windows-1250 for Slovenian).
 */
internal fun decodeSubtitleText(data: ByteArray, offset: Int, length: Int, legacyCharset: Charset): String {
    fun startsWith(vararg bytes: Int) =
        length >= bytes.size && bytes.indices.all { data[offset + it] == bytes[it].toByte() }
    return when {
        startsWith(0xEF, 0xBB, 0xBF) -> String(data, offset + 3, length - 3, Charsets.UTF_8)
        startsWith(0xFF, 0xFE) -> String(data, offset + 2, length - 2, Charsets.UTF_16LE)
        startsWith(0xFE, 0xFF) -> String(data, offset + 2, length - 2, Charsets.UTF_16BE)
        else -> try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, offset, length))
                .toString()
        } catch (e: CharacterCodingException) {
            String(data, offset, length, legacyCharset)
        }
    }
}

/** The pre-Unicode Windows encoding subtitles in [language] were usually saved in. */
internal fun legacySubtitleCharset(language: String?): Charset = Charset.forName(
    when (language?.substringBefore('-')?.lowercase()) {
        "sl", "hr", "bs", "cs", "sk", "pl", "hu", "ro", "sq" -> "windows-1250"
        "ru", "uk", "be", "bg", "mk", "sr" -> "windows-1251"
        "el" -> "windows-1253"
        "tr", "az" -> "windows-1254"
        "he", "iw" -> "windows-1255"
        "ar", "fa", "ur" -> "windows-1256"
        else -> "windows-1252"
    },
)

/** Media3's subtitle parsers, plus format detection for subtitle files handed over by other apps. */
@OptIn(UnstableApi::class)
class SubtitleParserFactory : SubtitleParser.Factory {
    private val defaultFactory = DefaultSubtitleParserFactory()

    override fun supportsFormat(format: Format): Boolean =
        isSideloaded(format) || defaultFactory.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int =
        if (isSideloaded(format)) {
            Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
        } else {
            defaultFactory.getCueReplacementBehavior(format)
        }

    override fun create(format: Format): SubtitleParser =
        if (isSideloaded(format)) {
            DetectingSubtitleParser(legacySubtitleCharset(format.language ?: Locale.getDefault().language))
        } else {
            defaultFactory.create(format)
        }

    private fun isSideloaded(format: Format) = format.sampleMimeType == SIDELOADED_SUBTITLE_MIME_TYPE
}

/** Works out a subtitle file's format and encoding from its contents, then parses it with Media3. */
@OptIn(UnstableApi::class)
private class DetectingSubtitleParser(private val legacyCharset: Charset) : SubtitleParser {
    private var parser: SubtitleParser? = null

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        val text = decodeSubtitleText(data, offset, length, legacyCharset)
        val format = detectSubtitleFormat(text)
        val parser = when (format) {
            SubtitleFileFormat.WEBVTT -> WebvttParser()
            SubtitleFileFormat.SSA -> SsaParser()
            SubtitleFileFormat.SUBRIP -> SubripParser()
        }
        this.parser = parser
        val utf8 = (if (format == SubtitleFileFormat.SUBRIP) normalizeSubripTimestamps(text) else text)
            .toByteArray(Charsets.UTF_8)
        parser.parse(utf8, 0, utf8.size, outputOptions, output)
    }

    override fun reset() {
        parser?.reset()
    }

    // SubRip, WebVTT and SSA all merge overlapping cues.
    override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
}
