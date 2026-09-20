package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationTurnJumpTest {
    @get:Rule val compose = createComposeRule()

    @Test fun upwardJumpDoesNotVisitSupplementOrIntermediateReply() = assertDirectJump(12, 0)
    @Test fun downwardJumpDoesNotVisitSupplementOrIntermediateReply() = assertDirectJump(0, 12)

    private fun assertDirectJump(start: Int, target: Int) {
        val state = LazyListState(firstVisibleItemIndex = start)
        val observed = mutableListOf<Int>()
        lateinit var scope: CoroutineScope
        compose.setContent {
            val rememberedScope = rememberCoroutineScope()
            SideEffect { scope = rememberedScope }
            LaunchedEffect(state) {
                snapshotFlow { state.firstVisibleItemIndex }.collect { observed += it }
            }
            LazyColumn(state = state, modifier = Modifier.size(300.dp, 400.dp)) {
                items(18) { index ->
                    // Oversized replies alternate with short supplement bubbles.
                    Box(Modifier.height(if (index % 3 == 1) 1800.dp else 80.dp))
                }
            }
        }
        compose.runOnIdle {
            assertEquals(start, state.firstVisibleItemIndex)
            observed.clear()
            scope.launch { state.jumpToConversationTurn(target) }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(target, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
            assertTrue("Unexpected intermediate messages: $observed", observed.isNotEmpty())
            assertTrue("Unexpected intermediate messages: $observed", observed.all { it == target })
        }
    }
}
