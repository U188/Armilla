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
        listOf("执行代理 1", "执行代理 2", "执行代理 3", "审查／总结代理").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("单击选模型 · 长按调整思考深度", substring = true).assertExists()
        compose.onNodeWithText("最多两个只读子代理", substring = true).assertDoesNotExist()
        compose.onNodeWithText("自动委派").performClick()
        compose.runOnIdle { assertTrue(enabled.value) }
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { assertFalse(visible.value) }
    }
    @Test fun emptySlotClickOpensModelPickerAndLongPressDoesNotTriggerClick() {
        val slot = 0
        val saved = io.github.mangi.eta.agent.delegation.SubAgentPreferences.selection(slot)
        val savedEffort = io.github.mangi.eta.agent.delegation.SubAgentPreferences.reasoning(slot)
        try {
            io.github.mangi.eta.agent.delegation.SubAgentPreferences.save(slot,
                io.github.mangi.eta.agent.model.ModelFeatureSelection(true, "", ""))
            compose.setContent {
                MiuixTheme(colors = lightColorScheme()) {
                    ConversationCollaborationDialog(true, true, {}, {})
                }
            }
            compose.onNodeWithText("执行代理 1").performTouchInput { longClick() }
            compose.onNodeWithText("选择执行代理 1模型").assertDoesNotExist()
            compose.onNodeWithText("执行代理 1").performTouchInput { click() }
            compose.onNodeWithText("选择执行代理 1模型").assertExists()
            compose.onNodeWithText("无").performClick()
            compose.onNodeWithText("本会话协作").assertExists()
            compose.onNodeWithText("执行代理 1").performTouchInput { longClick() }
            compose.onNodeWithText("调整思考深度").assertDoesNotExist()
            compose.onNodeWithText("选择执行代理 1模型").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(io.github.mangi.eta.agent.delegation.SubAgentPreferences.selection(slot).modelId.isBlank())
            }
        } finally {
            io.github.mangi.eta.agent.delegation.SubAgentPreferences.save(slot, saved)
            io.github.mangi.eta.agent.delegation.SubAgentPreferences.saveReasoning(slot, savedEffort)
        }
    }

    @Test fun runningTaskDisablesAllControlsAndClosesOpenPicker() {
        val running = mutableStateOf(false)
        var changes = 0
        var dismissals = 0
        compose.setContent {
            MiuixTheme(colors = lightColorScheme()) {
                ConversationCollaborationDialog(true, true, { changes++ }, { dismissals++ }, taskRunning = running.value)
            }
        }
        compose.onNodeWithText("执行代理 1").performClick()
        compose.onNodeWithText("选择执行代理 1模型").assertExists()
        compose.runOnIdle { running.value = true }
        compose.onNodeWithText("选择执行代理 1模型").assertDoesNotExist()
        compose.onNodeWithText("完成").assertIsNotEnabled()
        compose.onNodeWithText("执行代理 1").assertIsNotEnabled()
        compose.onNodeWithText("执行代理 1").performTouchInput { click(); longClick() }
        compose.onNodeWithText("自动委派").performTouchInput { click() }
        compose.onNodeWithText("完成").performTouchInput { click() }
        compose.runOnIdle {
            org.junit.Assert.assertEquals(0, changes)
            org.junit.Assert.assertEquals(0, dismissals)
            running.value = false
        }
        compose.onNodeWithText("执行代理 1").assertIsEnabled()
        compose.onNodeWithText("完成").assertIsEnabled().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, dismissals) }
    }

}
