package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ContainedMorphIdleTest {
    @Test fun stoppedFramesAlwaysReturnToTheOriginalCircle() {
        for (progress in listOf(0f, 0.4f, 1f, 1.1f)) {
            assertEquals(1f, idleBlendedRadius(0.55f, 0.92f, progress, 0f), 0.0001f)
        }
    }

    @Test fun activeAnimationKeepsItsExistingShape() {
        assertEquals(0.75f, idleBlendedRadius(0.5f, 1f, 0.5f, 1f), 0.0001f)
    }

    @Test fun returningToIdleBlendsInsteadOfJumping() {
        assertEquals(0.875f, idleBlendedRadius(0.5f, 1f, 0.5f, 0.5f), 0.0001f)
    }
}
