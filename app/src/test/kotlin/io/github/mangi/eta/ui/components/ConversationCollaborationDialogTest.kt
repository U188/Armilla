package io.github.mangi.eta.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationCollaborationDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsRolesAndToggleWithoutObsoleteReadOnlyParagraph() {
        val enabled = mutableStateOf(false)
        val visible = mutableStateOf(true)
        compose.setContent {
            MiuixTheme(colors = lightColorScheme()) {
                ConversationCollaborationDialog(visible.value, enabled.value,
                    { enabled.value = it }, { visible.value = false })
            }
        }
        compose.onNodeWithText("本会话协作").assertExists()
        compose.onNodeWithText("实现").assertExists()
        compose.onNodeWithText("审查 / 总结").assertExists()
        compose.onNodeWithText("最多两个只读子代理", substring = true).assertDoesNotExist()
        compose.onNodeWithText("自动委派").performClick()
        compose.runOnIdle { assertTrue(enabled.value) }
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { assertFalse(visible.value) }
    }
}
