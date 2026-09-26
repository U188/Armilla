package io.github.mangi.eta.ui.components

import androidx.compose.runtime.compositionLocalOf
import io.github.mangi.eta.agent.delegation.ConversationSubAgentConfig
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentParallelModel
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import java.util.UUID

/** Capture this object when opening a picker: callbacks must never retarget the currently selected owner. */
internal class ConversationSubAgentEditor(
    val owner: SubAgentConfigKey,
    val repository: ConversationSubAgentPreferences,
    val canEdit: () -> Boolean,
) {
    private class LostOwner : RuntimeException()
    val enabled: Boolean get() = canEdit()
    fun update(change: (ConversationSubAgentConfig) -> ConversationSubAgentConfig): ConversationSubAgentPreferences.WriteResult {
        if (!canEdit()) return ConversationSubAgentPreferences.WriteResult.Rejected
        return try {
            repository.update(owner) { old ->
                if (!canEdit()) throw LostOwner()
                change(old)
            }
        } catch (_: LostOwner) { ConversationSubAgentPreferences.WriteResult.Rejected }
    }
    fun add(): ConversationSubAgentPreferences.WriteResult = update { old ->
        var number = old.profiles.size + 1
        while (old.profiles.any { it.name == "子代理 $number" }) number++
        old.copy(profiles = old.profiles + SubAgentProfile(UUID.randomUUID().toString(), "子代理 $number"))
    }
    fun updateProfile(id: String, change: (SubAgentProfile) -> SubAgentProfile): ConversationSubAgentPreferences.WriteResult = update { old ->
        if (old.profiles.none { it.id == id }) throw LostOwner()
        old.copy(profiles = old.profiles.map { profile ->
            if (profile.id != id) profile else change(profile).normalizedTaskTier().also { require(it.id == id) }
        })
    }
    fun remove(id: String) = update { old ->
        if (old.profiles.none { it.id == id }) throw LostOwner()
        old.copy(profiles = old.profiles.filterNot { profile -> profile.id == id })
    }
    fun setEnabled(value: Boolean) = update { it.copy(enabled = value) }
    fun setDiagnosticsEnabled(value: Boolean) = update { it.copy(diagnosticsEnabled = value) }
    fun saveParallelLimit(id: String, providerId: String, modelId: String, apiModel: String, limit: Int) = update { old ->
        if (limit < 0 || providerId.isBlank() || apiModel.isBlank() || old.profiles.none {
                it.id == id && it.providerId == providerId && it.modelId == modelId
            }) throw LostOwner()
        old.copy(parallelLimits = old.parallelLimits + (SubAgentParallelModel(providerId, apiModel) to limit))
    }
    fun saveModel(id: String, selection: ModelFeatureSelection, expected: SubAgentProfile? = null) = updateProfile(id) { old ->
        if (expected != null && (old.role != expected.role || old.providerId != expected.providerId || old.modelId != expected.modelId))
            throw LostOwner()
        val sameModel = old.providerId == selection.providerId && old.modelId == selection.modelId
        val memory = old.reasoningByModel.toMutableMap()
        if (old.providerId.isNotBlank() && old.modelId.isNotBlank() && old.reasoning != null)
            memory[SubAgentProfile.modelReasoningKey(old.providerId, old.modelId)] = old.reasoning
        val restored = when {
            sameModel -> old.reasoning
            selection.providerId.isBlank() || selection.modelId.isBlank() -> null
            else -> memory[SubAgentProfile.modelReasoningKey(selection.providerId, selection.modelId)]
        }
        old.copy(providerId = selection.providerId, modelId = selection.modelId,
            imageResolution = if (sameModel) old.imageResolution else null,
            reasoning = restored, reasoningByModel = memory)
    }
}

internal val LocalConversationSubAgentEditor = compositionLocalOf<ConversationSubAgentEditor?> { null }
