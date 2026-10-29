package com.starmano.splayer

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackLabelsTest {

    @Test
    fun commonMovieAudioFormatsHaveFamiliarNames() {
        assertEquals("Dolby Digital", audioCodecName(MimeTypes.AUDIO_AC3))
        assertEquals("Dolby Digital Plus", audioCodecName(MimeTypes.AUDIO_E_AC3))
        assertEquals("DTS-HD", audioCodecName(MimeTypes.AUDIO_DTS_HD))
        assertEquals("Dolby TrueHD", audioCodecName(MimeTypes.AUDIO_TRUEHD))
        assertEquals("AAC", audioCodecName(MimeTypes.AUDIO_AAC))
    }

    @Test
    fun unknownFormatsHaveNoName() {
        assertNull(audioCodecName("audio/x-something-new"))
        assertNull(audioCodecName(null))
    }

    @Test
    fun labelsSkipMissingDetails() {
        assertEquals(
            "English, 5.1 surround sound · Dolby Digital",
            trackLabel("English, 5.1 surround sound", "Dolby Digital", null, ""),
        )
        assertEquals("English", trackLabel("English"))
    }

    @Test
    fun labelsListEveryKnownDetail() {
        assertEquals(
            "German · Forced · Not supported on this phone",
            trackLabel("German", null, "Forced", "Not supported on this phone"),
        )
    }
}
