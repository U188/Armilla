package io.github.mangi.eta.ui.components

import androidx.compose.foundation.lazy.LazyListState

/** A turn switch is one positioning operation, not an animated tour of intermediate messages. */
internal suspend fun LazyListState.jumpToConversationTurn(targetIndex: Int) {
    scrollToItem(targetIndex)
}
