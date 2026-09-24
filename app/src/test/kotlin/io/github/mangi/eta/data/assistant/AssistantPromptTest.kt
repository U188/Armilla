package io.github.mangi.eta.data.assistant

import io.github.mangi.eta.data.model.AssistantPrompt
import io.github.mangi.eta.data.model.AssistantProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPromptTest {
    @Test
    fun emptyPromptUsesAssistantName() {
        val prompt = AssistantPrompt.build("Minis", "")
        assertTrue(prompt.startsWith("你是 Minis"))
        assertFalse(prompt.contains("人格设定"))
        assertFalse(prompt.contains("Armilla"))
    }

    @Test
    fun blankNameFallsBackToDefaultName() {
        val prompt = AssistantPrompt.build("  ", "")
        assertTrue(prompt.startsWith("你是 晚枫"))
        assertFalse(prompt.contains("人格设定"))
    }

    @Test
    fun personalityBodyIsAppended() {
        val prompt = AssistantPrompt.build("Armilla", "先做事，少客套。")
        assertTrue(prompt.startsWith("你是 Armilla"))
        assertTrue(prompt.contains("人格设定："))
        assertTrue(prompt.contains("先做事，少客套。"))
        assertEquals(2, prompt.split("人格设定：").size)
    }

    @Test
    fun builtInAssistantAppendsUserPromptAfterFixedPersona() {
        val profile = AssistantProfile(
            id = AssistantPrompt.DEFAULT_ID,
            name = AssistantPrompt.DEFAULT_NAME,
            prompt = "只回答天气。",
        )
        val prompt = AssistantPrompt.build(profile)
        assertTrue(prompt.startsWith("你是 晚枫"))
        assertTrue(prompt.contains(AssistantPrompt.DEFAULT_PERSONA))
        assertTrue(prompt.endsWith("只回答天气。"))
        assertTrue(prompt.indexOf(AssistantPrompt.DEFAULT_PERSONA) < prompt.indexOf("只回答天气。"))
    }

    @Test
    fun builtInAssistantWithoutUserPromptUsesOnlyFixedPersona() {
        val profile = AssistantProfile(
            id = AssistantPrompt.DEFAULT_ID,
            name = AssistantPrompt.DEFAULT_NAME,
            prompt = "  ",
        )
        assertEquals(
            AssistantPrompt.build(AssistantPrompt.DEFAULT_NAME, AssistantPrompt.DEFAULT_PERSONA),
            AssistantPrompt.build(profile),
        )
    }

    @Test
    fun otherAssistantsUseTheirOwnPersonaPrompt() {
        val profile = AssistantProfile(id = "custom", name = "小助手", prompt = "只回答天气。")
        val prompt = AssistantPrompt.build(profile)
        assertTrue(prompt.startsWith("你是 小助手"))
        assertTrue(prompt.endsWith("只回答天气。"))
    }

}
