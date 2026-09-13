package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun normalizesTagAndDottedName() {
        assertEquals("5.2.3", AppVersion.normalize("v5.2.3"))
        assertEquals("5.2.3", AppVersion.normalize("5.2.3"))
        assertEquals("5.2.3", AppVersion.normalize("  V5.2.3-release  "))
    }

    @Test
    fun comparesDottedVersions() {
        assertTrue(AppVersion.isNewer("5.2.3", "5.2.2"))
        assertTrue(AppVersion.isNewer("v5.3.0", "5.2.9"))
        assertTrue(AppVersion.isNewer("5.2.10", "5.2.9"))
        assertFalse(AppVersion.isNewer("5.2.2", "5.2.2"))
        assertFalse(AppVersion.isNewer("5.2.1", "5.2.2"))
        assertTrue(AppVersion.isNewer("5.2.2", ""))
        assertFalse(AppVersion.isNewer("", "5.2.2"))
    }
}
