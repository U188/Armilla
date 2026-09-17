package io.github.mangi.eta.agent.browser.ported.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserThemeParityTest {
    @Test fun lightPaletteKeepsUpstreamBlueAndNeutralSurfaces() {
        assertEquals(Color(0xFF528AD2), LightColorScheme.primary)
        assertEquals(Color.White, LightChatPalette.background)
        assertEquals(Color(0xFFF2F2F7), LightColorScheme.background)
        assertEquals(Color.White, LightColorScheme.surfaceContainer)
        assertEquals(Color(0xFFF7F7FA), LightColorScheme.surfaceContainerHigh)
        assertEquals(Color(0xFFD1D1D6), LightColorScheme.outline)
        assertEquals(Color.Black, LightChatPalette.primaryText)
    }

    @Test fun darkPaletteKeepsUpstreamLayers() {
        assertEquals(Color(0xFF6A94CE), DarkColorScheme.primary)
        assertEquals(Color.Black, DarkChatPalette.background)
        assertEquals(Color(0xFF1C1C1E), DarkColorScheme.surfaceContainer)
        assertEquals(Color(0xFF2C2C2E), DarkColorScheme.surfaceContainerHigh)
        assertEquals(Color.White, DarkChatPalette.primaryText)
    }

    @Test fun bottomSheetsAndDialogsUseUpstream28DpCorners() {
        val shape = MinisShapes.extraLarge as RoundedCornerShape
        val outline = shape.createOutline(Size(400f, 800f), LayoutDirection.Ltr, Density(1f))
            as androidx.compose.ui.graphics.Outline.Rounded
        assertEquals(28f, outline.roundRect.topLeftCornerRadius.x, 0f)
        assertEquals(28f, outline.roundRect.topRightCornerRadius.x, 0f)
    }
}
