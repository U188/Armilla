package io.github.mangi.eta.agent.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDomScriptsTest {
    @Test
    fun `readable extraction is bounded and preserves absolute urls`() {
        val script = BrowserDomScripts.wrap(BrowserDomScripts.readable(offset = 0, maxChars = 8_000))

        assertTrue(script.contains("!visible(node)"))
        assertTrue(script.contains("remainingNodes: 8000"))
        assertTrue(script.contains("deadline: Date.now() + 750"))
        assertTrue(script.contains("return boundedString(parsed.href"))
        assertFalse(script.contains("parsed.protocol !== 'https:'"))
        assertFalse(script.contains("innerText"))
        assertFalse(script.contains("textContent"))
    }

    @Test
    fun `target resolution does not apply visibility or hit target guards`() {
        val script = BrowserDomScripts.wrap(
            BrowserDomScripts.click(selector = "#submit", x = null, y = null)
        )

        assertTrue(script.contains("document.querySelector(selector);"))
        assertFalse(script.contains("requireHitTarget"))
        assertFalse(script.contains("TARGET_OCCLUDED"))
    }

    @Test
    fun `hover dispatches mouseenter`() {
        val script = BrowserDomScripts.wrap(BrowserDomScripts.hover("#menu", null, null))
        assertTrue(script.contains("mouseenter"))
        assertTrue(script.contains("mouseover"))
    }

    @Test
    fun `backbone depth is bounded`() {
        val script = BrowserDomScripts.wrap(BrowserDomScripts.backbone(3))
        assertTrue(script.contains("MAX_DEPTH = 3"))
        assertTrue(script.contains("MAX_NODES = 80"))
    }
}
