package com.starmano.splayer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/** A track as the audio & subtitles panel shows it: its language, with the other details below. */
internal data class TrackDescription(val title: String, val details: List<String>)

/**
 * Describes an audio or subtitle track. Dubbed movies have one audio track per language, so the
 * language is the title; the track's own name (often "Dub", "Original" or "Commentary"), its kind,
 * channels and format follow as details.
 */
internal fun describeTrack(
    language: String?,
    label: String?,
    isAudio: Boolean,
    channelCount: Int,
    mimeType: String?,
    codecs: String?,
    roleFlags: Int,
    selectionFlags: Int,
): TrackDescription {
    val languageName = languageName(language)
    val name = label?.trim()?.takeIf { it.isNotEmpty() }
    val title = languageName ?: name ?: "Unknown language"
    val details = mutableListOf<String>()
    if (name != null && !name.equals(title, ignoreCase = true)) details += name

    fun addUnlessNamed(detail: String) {
        if (name == null || !name.contains(detail, ignoreCase = true)) details += detail
    }
    if ((roleFlags and C.ROLE_FLAG_COMMENTARY) != 0) addUnlessNamed("Commentary")
    if ((roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) addUnlessNamed("Audio description")
    if (!isAudio && (roleFlags and (C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)) != 0) {
        addUnlessNamed("SDH")
    }
    if ((selectionFlags and C.SELECTION_FLAG_FORCED) != 0) addUnlessNamed("Forced")
    if (isAudio) {
        channelLayout(channelCount)?.let(details::add)
        audioCodecName(mimeType)?.let(details::add)
    } else {
        subtitleFormatName(mimeType, codecs)?.let(details::add)
    }
    return TrackDescription(title, details)
}

/** The English name of a language code such as "it" or "pt-BR", or null if it isn't known. */
internal fun languageName(code: String?): String? {
    if (code.isNullOrBlank() || code == C.LANGUAGE_UNDETERMINED) return null
    val name = Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH)
    if (name.isBlank() || name.equals(code, ignoreCase = true)) return null
    return name.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
}

/** Whether a track name is really a language code, as side-loaded subtitles often have ("eng"). */
internal fun looksLikeLanguageCode(text: String): Boolean =
    Regex("[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*").matches(text.trim())

internal fun channelLayout(channelCount: Int): String? = when {
    channelCount == 1 -> "Mono"
    channelCount == 2 -> "Stereo"
    channelCount == 6 -> "5.1"
    channelCount == 8 -> "7.1"
    channelCount > 2 -> "$channelCount channels"
    else -> null
}

/**
 * Names for subtitle formats. Text subtitles are converted while the file is read, after which
 * the original format is kept in the track's codecs.
 */
@OptIn(UnstableApi::class)
internal fun subtitleFormatName(mimeType: String?, codecs: String?): String? =
    when (if (mimeType == MimeTypes.APPLICATION_MEDIA3_CUES) codecs else mimeType) {
        SIDELOADED_SUBTITLE_MIME_TYPE -> "External"
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_VTT -> "WebVTT"
        MimeTypes.TEXT_SSA -> "SSA"
        MimeTypes.APPLICATION_TTML -> "TTML"
        MimeTypes.APPLICATION_TX3G -> "TX3G"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VobSub"
        MimeTypes.APPLICATION_DVBSUBS -> "DVB"
        MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_MP4CEA608, MimeTypes.APPLICATION_CEA708 ->
            "Closed captions"
        else -> null
    }

/** Familiar names for the audio formats found in movie files. */
@OptIn(UnstableApi::class)
internal fun audioCodecName(mimeType: String?): String? = when (mimeType) {
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_AC3 -> "Dolby Digital"
    MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
    MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
    MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
    MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
    MimeTypes.AUDIO_DTS_X -> "DTS:X"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_ALAC -> "ALAC"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_MPEG_L2 -> "MP2"
    MimeTypes.AUDIO_RAW -> "PCM"
    else -> null
}
