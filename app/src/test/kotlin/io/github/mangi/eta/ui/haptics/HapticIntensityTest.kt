package io.github.mangi.eta.ui.haptics

import org.junit.Assert.assertEquals
import org.junit.Test

class HapticIntensityTest {
    @Test
    fun fromWireFallsBackToDefault() {
        assertEquals(HapticIntensity.DEFAULT, HapticIntensity.fromWire(null))
        assertEquals(HapticIntensity.DEFAULT, HapticIntensity.fromWire(""))
        assertEquals(HapticIntensity.DEFAULT, HapticIntensity.fromWire("unknown"))
        assertEquals(HapticIntensity.DEFAULT, HapticIntensity.fromWire("default"))
        assertEquals(HapticIntensity.LOW, HapticIntensity.fromWire("LOW"))
        assertEquals(HapticIntensity.MEDIUM, HapticIntensity.fromWire(" medium "))
        assertEquals(HapticIntensity.HIGH, HapticIntensity.fromWire("high"))
    }

    @Test
    fun primitiveScaleKeepsDefaultAndCapsHigh() {
        assertEquals(0.45f, HapticIntensity.DEFAULT.primitiveScale(0.45f), 0.0001f)
        assertEquals(0.1575f, HapticIntensity.LOW.primitiveScale(0.45f), 0.0001f)
        assertEquals(0.35f, HapticIntensity.MEDIUM.primitiveScale(0.45f), 0.0001f)
        assertEquals(1f, HapticIntensity.HIGH.primitiveScale(0.45f), 0.0001f)
        assertEquals(1f, HapticIntensity.HIGH.primitiveScale(1.2f), 0.0001f)
    }
}
