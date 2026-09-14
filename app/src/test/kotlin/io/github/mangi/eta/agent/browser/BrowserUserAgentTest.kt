package io.github.mangi.eta.agent.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUserAgentTest {
    @Test
    fun defaultIsDesktopChrome() {
        assertEquals(BrowserUserAgent.DESKTOP_CHROME, BrowserUserAgent.DEFAULT)
        assertTrue(BrowserUserAgent.DEFAULT.desktop)
        assertEquals(1280, BrowserUserAgent.DESKTOP_CHROME.viewportWidth)
    }

    @Test
    fun wireNamesRoundTrip() {
        assertEquals(BrowserUserAgent.DESKTOP_CHROME, BrowserUserAgent.fromWire("desktop_chrome"))
        assertEquals(BrowserUserAgent.MOBILE_CHROME, BrowserUserAgent.fromWire("mobile_chrome"))
        assertEquals(null, BrowserUserAgent.fromWire("safari"))
    }
}
