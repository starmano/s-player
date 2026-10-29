package com.starmano.splayer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLabelsTest {

    private fun audio(
        language: String?,
        label: String? = null,
        channels: Int = 6,
        mimeType: String = MimeTypes.AUDIO_AC3,
        roleFlags: Int = 0,
    ) = describeTrack(language, label, isAudio = true, channels, mimeType, roleFlags, selectionFlags = 0)

    private fun subtitle(language: String?, label: String? = null, roleFlags: Int = 0, selectionFlags: Int = 0) =
        describeTrack(language, label, isAudio = false, Format.NO_VALUE, null, roleFlags, selectionFlags)

    @Test
    fun dubbedAudioTracksAreTitledByLanguage() {
        assertEquals(TrackDescription("English", listOf("5.1", "Dolby Digital")), audio("en"))
        assertEquals(
            TrackDescription("Italian", listOf("Dub", "Stereo", "AAC")),
            audio("it", label = "Dub", channels = 2, mimeType = MimeTypes.AUDIO_AAC),
        )
        assertEquals("Portuguese (Brazil)", audio("pt-BR").title)
    }

    @Test
    fun trackNamesThatOnlyRepeatTheLanguageAreLeftOut() {
        assertEquals(listOf("5.1", "Dolby Digital"), audio("de", label = "German").details)
    }

    @Test
    fun tracksWithoutALanguageFallBackToTheirName() {
        assertEquals(TrackDescription("Director's cut", listOf("5.1", "Dolby Digital")), audio(null, "Director's cut"))
        assertEquals("Unknown language", audio("und").title)
        assertEquals("Unknown language", subtitle(null).title)
    }

    @Test
    fun specialTracksAreMarked() {
        assertEquals(
            listOf("Commentary", "Stereo", "AAC"),
            audio("en", channels = 2, mimeType = MimeTypes.AUDIO_AAC, roleFlags = C.ROLE_FLAG_COMMENTARY).details,
        )
        assertEquals(listOf("SDH"), subtitle("en", roleFlags = C.ROLE_FLAG_CAPTION).details)
        assertEquals(listOf("Forced"), subtitle("fr", selectionFlags = C.SELECTION_FLAG_FORCED).details)
        // Not repeated when the track's name already says so.
        assertEquals(listOf("Forced subs"), subtitle("fr", "Forced subs", selectionFlags = C.SELECTION_FLAG_FORCED).details)
    }

    @Test
    fun languageNamesAreInEnglish() {
        assertEquals("Slovenian", languageName("sl"))
        assertEquals("Spanish", languageName("es"))
        assertNull(languageName("und"))
        assertNull(languageName("zz"))
        assertNull(languageName(null))
    }

    @Test
    fun recognisesLanguageCodesUsedAsTrackNames() {
        assertTrue(looksLikeLanguageCode("eng"))
        assertTrue(looksLikeLanguageCode("pt-BR"))
        assertFalse(looksLikeLanguageCode("English SDH"))
        assertFalse(looksLikeLanguageCode("subs.srt"))
    }

    @Test
    fun channelLayoutsHaveShortNames() {
        assertEquals("Mono", channelLayout(1))
        assertEquals("7.1", channelLayout(8))
        assertEquals("3 channels", channelLayout(3))
        assertNull(channelLayout(Format.NO_VALUE))
    }

    @Test
    fun commonMovieAudioFormatsHaveFamiliarNames() {
        assertEquals("Dolby Digital Plus", audioCodecName(MimeTypes.AUDIO_E_AC3))
        assertEquals("DTS-HD", audioCodecName(MimeTypes.AUDIO_DTS_HD))
        assertEquals("Dolby TrueHD", audioCodecName(MimeTypes.AUDIO_TRUEHD))
        assertNull(audioCodecName("audio/x-something-new"))
        assertNull(audioCodecName(null))
    }
}
