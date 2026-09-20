package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.config.Prefs

/** Only model references and conversation switches; never duplicate provider credentials. */
internal object SubAgentPreferences {
    fun selection(slot: Int) = ModelFeatureSelection(true,
        Prefs.getString("agent_child_${slot}_provider"), Prefs.getString("agent_child_${slot}_model"))

    fun save(slot: Int, selection: ModelFeatureSelection) {
        require(slot in 0..1)
        Prefs.putString("agent_child_${slot}_provider", selection.providerId)
        Prefs.putString("agent_child_${slot}_model", selection.modelId)
    }

    private fun key(conversation: String?) = "agent_collaboration_${conversation ?: "draft"}"
    fun enabled(conversation: String?) = Prefs.getString(key(conversation), "true") != "false"
    fun setEnabled(conversation: String?, enabled: Boolean) = Prefs.putString(key(conversation), enabled.toString())
    fun promote(conversation: String) {
        setEnabled(conversation, enabled(null))
        setEnabled(null, true)
    }
}
