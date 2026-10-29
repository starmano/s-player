package com.starmano.splayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {

    @Test
    fun readsTheBuildNumberFromTheSite() {
        assertEquals(12L, parseBuildNumber("""{"build":12,"apk":"/s-player.apk"}"""))
        assertEquals(7L, parseBuildNumber("""{ "apk": "/s-player.apk", "build" : 7 }"""))
    }

    @Test
    fun anythingElseHasNoBuildNumber() {
        assertNull(parseBuildNumber("""{"error":"unavailable"}"""))
        assertNull(parseBuildNumber("<html>Server error</html>"))
        assertNull(parseBuildNumber(""))
    }
}
