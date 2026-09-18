package io.github.mangi.eta.ui.haptics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionLongPressReleaseTest {
    @Test fun shortTapStillOpensLinks() {
        assertFalse(isSelectionLongPressRelease(100L, 500L))
        assertFalse(isSelectionLongPressRelease(499L, 500L))
    }

    @Test fun longPressReleaseMustNotOpenLinks() {
        assertTrue(isSelectionLongPressRelease(500L, 500L))
        assertTrue(isSelectionLongPressRelease(1500L, 500L))
    }

    @Test fun followsSystemLongPressTimeout() {
        assertFalse(isSelectionLongPressRelease(500L, 1000L))
        assertTrue(isSelectionLongPressRelease(1000L, 1000L))
    }
}
