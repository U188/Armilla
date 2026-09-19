package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatImageSourceCacheTest {
    @Test fun streamingTextDoesNotReparseHistoricalImages() {
        var parses = 0
        val cache = ChatImageSourceCache { parses++; listOf("https://example.com/image.png") }
        repeat(100) { index ->
            cache.sources("history", "![image](https://example.com/image.png)")
            cache.sources("live", "streaming text $index")
        }
        assertEquals(1, parses)
    }

    @Test fun editsWithSameIdAndLengthInvalidateSources() {
        var parses = 0
        val cache = ChatImageSourceCache { parses++; listOf(it) }
        val first = "![a](https://example.com/a.png)"
        val edited = "![b](https://example.com/b.png)"
        cache.sources("m", first)
        assertEquals(listOf(edited), cache.sources("m", edited))
        assertEquals(emptyList<String>(), cache.sources("m", "image removed"))
        assertEquals(2, parses)
    }

    @Test fun cacheIsBounded() {
        var parses = 0
        val cache = ChatImageSourceCache(capacity = 2, parse = { parses++; emptyList() })
        cache.sources("a", "![a](a)")
        cache.sources("b", "![b](b)")
        cache.sources("c", "![c](c)")
        cache.sources("a", "![a](a)")
        assertEquals(4, parses)
    }

    @Test fun referenceImagesAndCodeFenceSemanticsArePreserved() {
        val cache = ChatImageSourceCache()
        assertEquals(listOf("https://example.com/a.png"), cache.sources("a",
            "![caption][ref]\n\n[ref]: https://example.com/a.png"))
        assertEquals(emptyList<String>(), cache.sources("a",
            "```\n![caption](https://example.com/a.png)\n```"))
    }
}
