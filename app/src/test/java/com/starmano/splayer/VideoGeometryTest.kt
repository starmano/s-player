package com.starmano.splayer

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGeometryTest {

    @Test
    fun regularVideoKeepsItsAspectRatio() {
        assertEquals(1920 to 1080, pipAspectRatio(1920, 1080))
        assertEquals(1080 to 1920, pipAspectRatio(1080, 1920))
    }

    @Test
    fun extremeAspectRatiosAreClampedToWhatAndroidAccepts() {
        assertEquals(239 to 100, pipAspectRatio(3840, 1080))
        assertEquals(100 to 239, pipAspectRatio(400, 1920))
    }

    @Test
    fun unknownVideoSizeFallsBackTo16By9() {
        assertEquals(16 to 9, pipAspectRatio(0, 0))
    }

    @Test
    fun wideVideoIsLetterboxedInATallContainer() {
        assertEquals(Box(0, 150, 400, 375), fitCenter(400, 525, 16f / 9f))
    }

    @Test
    fun narrowVideoIsPillarboxedInAWideContainer() {
        assertEquals(Box(480, 0, 1920, 1080), fitCenter(2400, 1080, 4f / 3f))
    }
}
