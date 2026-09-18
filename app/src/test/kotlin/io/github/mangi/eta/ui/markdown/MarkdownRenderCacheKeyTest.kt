package io.github.mangi.eta.ui.markdown

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MarkdownRenderCacheKeyTest {
    private fun snapshot(source: String) = StreamingGfmParserSession().parse(source, isComplete = true)
    private fun firstKey(source: String): MarkdownRenderCacheKey {
        val parsed = snapshot(source)
        return markdownRenderCacheKey(parsed.renderedSource, parsed.state.node.children.first())
    }

    @Test fun completedParagraphReusesKeyWhenLaterTextGrows() {
        assertEquals(firstKey("Stable **paragraph**.\n\nTail"), firstKey("Stable **paragraph**.\n\nTail grows longer"))
    }

    @Test fun changedTextAndFormattingInvalidateKey() {
        assertNotEquals(firstKey("Old paragraph"), firstKey("New paragraph"))
        assertNotEquals(firstKey("**bold**"), firstKey("__bold__"))
    }

    @Test fun referenceDefinitionsRemainADocumentDependency() {
        assertNotEquals(firstKey("[link][target]\n\n[target]: https://a.example"),
            firstKey("[link][target]\n\n[target]: https://b.example"))
    }

    @Test fun existingTableCellsReuseKeysAsRowsArrive() {
        fun cells(source: String): List<MarkdownRenderCacheKey> {
            val parsed = snapshot(source)
            val result = mutableListOf<MarkdownRenderCacheKey>()
            fun visit(node: ASTNode) {
                if (node.type == CELL) {
                    result.add(markdownRenderCacheKey(parsed.renderedSource, node))
                }
                node.children.forEach(::visit)
            }
            visit(parsed.state.node)
            return result
        }
        val initial = "| A | B |\n| --- | --- |\n| one | two |\n"
        val before = cells(initial)
        val after = cells(initial + "| three | four |\n")
        assertEquals(4, before.size)
        assertEquals(before, after.take(before.size))
    }
}
