package io.github.mangi.eta.ui.markdown

import org.intellij.markdown.ast.ASTNode

/** Structural equality across full parser snapshots, without retaining their ASTs.
 * References can depend on definitions outside this node: conservatively retain the
 * full source dependency for bracket-bearing text, including incomplete references.
 */
internal data class MarkdownRenderCacheKey(
    val start: Int,
    val source: String,
    val structure: String,
    val referenceSource: String?,
)

internal fun markdownRenderCacheKey(content: String, node: ASTNode): MarkdownRenderCacheKey {
    val text = content.substring(node.startOffset, node.endOffset)
    val structure = buildString {
        fun appendNode(current: ASTNode) {
            append(current.type.name).append(':')
            append(current.startOffset - node.startOffset).append(':')
            append(current.endOffset - node.startOffset).append('[')
            current.children.forEach(::appendNode)
            append(']')
        }
        appendNode(node)
    }
    return MarkdownRenderCacheKey(
        start = node.startOffset,
        source = text,
        structure = structure,
        referenceSource = content.takeIf { '[' in text },
    )
}
