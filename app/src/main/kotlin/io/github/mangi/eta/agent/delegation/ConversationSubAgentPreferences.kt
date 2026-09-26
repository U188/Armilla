package io.github.mangi.eta.agent.delegation

import android.content.SharedPreferences
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ReasoningEffort
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject

internal sealed class SubAgentConfigKey {
    abstract val value: String
    data class Conversation(override val value: String) : SubAgentConfigKey() { init { require(value.isNotBlank()) } }
    data class Draft(override val value: String) : SubAgentConfigKey() { init { require(value.isNotBlank()) } }
}

/** The legacy hash and new pool key both use provider ID + API model (not model selection ID). */
internal data class SubAgentParallelModel(val providerId: String, val apiModel: String) {
    init { require(providerId.isNotBlank() && apiModel.isNotBlank()) }
    fun legacyKey(): String = "agent_model_parallel_" + MessageDigest.getInstance("SHA-256")
        .digest((providerId + "\u0000" + apiModel).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal data class ConversationSubAgentConfig(
    val profiles: List<SubAgentProfile>,
    val enabled: Boolean = true,
    val parallelLimits: Map<SubAgentParallelModel, Int> = emptyMap(),
    val diagnosticsEnabled: Boolean = false,
    /** Unresolved legacy hashes are frozen into the seed and lazily resolved with the actual API name. */
    val legacyParallelLimits: Map<String, Int> = emptyMap(),
) {
    fun detached(): ConversationSubAgentConfig = copy(
        profiles = profiles.map { it.copy(reasoningByModel = it.reasoningByModel.toMap()) }.toList(),
        parallelLimits = parallelLimits.toMap(), legacyParallelLimits = legacyParallelLimits.toMap())
    fun parallelLimit(model: SubAgentParallelModel): Int =
        parallelLimits[model] ?: legacyParallelLimits[model.legacyKey()] ?: 1
    fun validate() {
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Duplicate profile IDs" }
        profiles.forEach { require(it.role == "implementation" || it.tier == null) }
        require(parallelLimits.values.all { it >= 0 })
        require(legacyParallelLimits.all { (key, limit) -> key.matches(Regex("agent_model_parallel_[0-9a-f]{64}")) && limit >= 0 })
    }
    fun poolKey(owner: SubAgentConfigKey, model: SubAgentParallelModel): String =
        "subagent:v1:" + (if (owner is SubAgentConfigKey.Conversation) "c:" else "d:") +
            Base64.getUrlEncoder().withoutPadding().encodeToString(owner.value.toByteArray(Charsets.UTF_8)) + ":" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(model.providerId.toByteArray(Charsets.UTF_8)) + ":" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(model.apiModel.toByteArray(Charsets.UTF_8))
}

/** Explicit-owner commits. All repository instances in the process share both write lock and revision. */
internal class ConversationSubAgentPreferences(
    private val preferences: SharedPreferences = requireNotNull(Prefs.localAgentPreferences()) { "Initialize Prefs first" },
    private val canEdit: (SubAgentConfigKey) -> Boolean = { true },
) {
    companion object {
        internal const val SEED_KEY = "agent_conversation_child_seed_v1"
        internal const val OWNER_PREFIX = "agent_conversation_child_owner_v1_"
        private const val BIND_PREFIX = "agent_conversation_child_binding_v1_"
        private const val VERSION = 1
        private val lock = Any()
        private val revisions = java.util.WeakHashMap<SharedPreferences, MutableStateFlow<Long>>()
        private fun revisionFor(preferences: SharedPreferences): MutableStateFlow<Long> = synchronized(lock) {
            revisions.getOrPut(preferences) { MutableStateFlow(0L) }
        }
    }
    sealed class WriteResult {
        data class Saved(val revision: Long, val config: ConversationSubAgentConfig) : WriteResult()
        object Rejected : WriteResult()
    }
    private val changes = revisionFor(preferences)
    val revision: StateFlow<Long> get() = changes
    fun flow(owner: SubAgentConfigKey) = changes.map { snapshot(owner) }.distinctUntilChanged()
    private fun key(owner: SubAgentConfigKey): String = OWNER_PREFIX +
        (if (owner is SubAgentConfigKey.Conversation) "c_" else "d_") +
        Base64.getUrlEncoder().withoutPadding().encodeToString(owner.value.toByteArray(Charsets.UTF_8))
    private fun binding(conversation: SubAgentConfigKey.Conversation) = BIND_PREFIX + key(conversation).removePrefix(OWNER_PREFIX)
    private fun commit(name: String, data: String) {
        check(preferences.edit().putString(name, data).commit()) { "Sub-agent config commit failed: $name" }
        changes.value = changes.value + 1
    }
    private fun stored(name: String): String? {
        if (!preferences.contains(name)) return null
        return preferences.getString(name, null) ?: error("Invalid config value type: $name")
    }
    private fun seed(): ConversationSubAgentConfig {
        stored(SEED_KEY)?.let { return decode(it) }
        val profiles = if (preferences.contains(SubAgentPreferences.PROFILES_KEY)) {
            val json = JSONObject(stored(SubAgentPreferences.PROFILES_KEY)!!)
            require(json.getInt("version") == 1)
            val array = json.getJSONArray("agents")
            (0 until array.length()).map { SubAgentProfile.fromJson(array.getJSONObject(it)) }
        } else listOf(0, 2, 3, 1).map { slot ->
            SubAgentProfile("legacy-$slot", when (slot) {
                0 -> "执行代理 1"; 1 -> "审查／总结代理"; 2 -> "执行代理 2"; else -> "执行代理 3"
            }, if (slot == 1) "review" else "implementation",
                providerId = preferences.getString("agent_child_${slot}_provider", "").orEmpty(),
                modelId = preferences.getString("agent_child_${slot}_model", "").orEmpty(),
                reasoning = ReasoningEffort.fromWireValue(preferences.getString("agent_child_${slot}_reasoning", "")),
                tier = if (slot == 1) null else SubAgentTaskTier.fromWireValue(preferences.getString("agent_child_${slot}_task_tier", "")))
        }
        // Preserve every legacy value even when its selection ID cannot be resolved to an API model here.
        val legacy = preferences.all.filterKeys { it.matches(Regex("agent_model_parallel_[0-9a-f]{64}")) }
            .mapValues { (_, raw) -> when (raw) {
                is Int -> raw
                is String -> raw.toIntOrNull() ?: error("Invalid legacy parallel limit")
                else -> error("Invalid legacy parallel limit type")
            }.also { require(it >= 0) } }
        val result = ConversationSubAgentConfig(profiles, legacyParallelLimits = legacy)
        result.validate()
        commit(SEED_KEY, encode(result))
        return result.detached()
    }
    private fun initial(owner: SubAgentConfigKey): ConversationSubAgentConfig {
        val base = seed().detached()
        if (owner is SubAgentConfigKey.Conversation) {
            val old = stored("agent_collaboration_${owner.value}")
            if (old != null) {
                require(old == "true" || old == "false") { "Invalid legacy collaboration switch" }
                return base.copy(enabled = old == "true")
            }
        }
        return base
    }
    private fun read(owner: SubAgentConfigKey): ConversationSubAgentConfig = stored(key(owner))?.let(::decode) ?: initial(owner)
    fun snapshot(owner: SubAgentConfigKey): ConversationSubAgentConfig = synchronized(lock) { read(owner).detached() }
    fun createConversation(owner: SubAgentConfigKey.Conversation, source: SubAgentConfigKey? = null): ConversationSubAgentConfig = synchronized(lock) {
        stored(key(owner))?.let { return@synchronized decode(it).detached() }
        val config = (source?.let { read(it) } ?: initial(owner)).detached()
        commit(key(owner), encode(config))
        config.detached()
    }
    fun createDraft(source: SubAgentConfigKey? = null): SubAgentConfigKey.Draft = synchronized(lock) {
        val config = (source?.let { read(it) } ?: seed()).detached()
        val owner = SubAgentConfigKey.Draft(UUID.randomUUID().toString())
        commit(key(owner), encode(config))
        owner
    }
    /** Persist owner and association atomically; only caller may confirm after Room commit. */
    fun bindDraft(draft: SubAgentConfigKey.Draft, conversation: SubAgentConfigKey.Conversation): ConversationSubAgentConfig = synchronized(lock) {
        val existingBinding = stored(binding(conversation))
        if (existingBinding != null) require(existingBinding == draft.value) { "Conversation bound to another draft" }
        stored(key(conversation))?.let {
            require(existingBinding == draft.value) { "Existing conversation is not bound to this draft" }
            return@synchronized decode(it).detached()
        }
        val data = stored(key(draft)) ?: error("Draft is absent: ${draft.value}")
        val config = decode(data)
        check(preferences.edit().putString(key(conversation), encode(config))
            .putString(binding(conversation), draft.value).commit()) { "Sub-agent draft bind failed" }
        changes.value = changes.value + 1
        config.detached()
    }
    fun confirmBoundDraft(draft: SubAgentConfigKey.Draft, conversation: SubAgentConfigKey.Conversation): Boolean = synchronized(lock) {
        if (stored(binding(conversation)) != draft.value || !preferences.contains(key(draft))) return@synchronized false
        decode(stored(key(conversation)) ?: error("Bound conversation config missing"))
        decode(stored(key(draft)) ?: error("Draft config missing"))
        check(preferences.edit().remove(key(draft)).remove(binding(conversation)).commit()) { "Sub-agent draft confirmation failed" }
        changes.value = changes.value + 1
        true
    }
    fun update(owner: SubAgentConfigKey, change: (ConversationSubAgentConfig) -> ConversationSubAgentConfig): WriteResult = synchronized(lock) {
        if (!canEdit(owner)) return@synchronized WriteResult.Rejected
        val next = change(read(owner).detached()).detached()
        next.validate()
        commit(key(owner), encode(next))
        WriteResult.Saved(changes.value, next.detached())
    }
    fun delete(owner: SubAgentConfigKey): Boolean = synchronized(lock) {
        if (!preferences.contains(key(owner))) return@synchronized false
        val edit = preferences.edit().remove(key(owner))
        if (owner is SubAgentConfigKey.Conversation) edit.remove(binding(owner))
        check(edit.commit()) { "Sub-agent config removal failed" }
        changes.value = changes.value + 1
        true
    }
    fun export(owner: SubAgentConfigKey): String = synchronized(lock) { encode(read(owner)) }
    fun importOwner(owner: SubAgentConfigKey, archive: String, overwrite: Boolean = false): Boolean = synchronized(lock) {
        val config = decode(archive)
        if (!overwrite && preferences.contains(key(owner))) {
            decode(stored(key(owner))!!)
            return@synchronized false
        }
        commit(key(owner), encode(config.detached()))
        true
    }
    fun validateRestoredPreferences() = synchronized(lock) {
        stored(SEED_KEY)?.let(::decode)
        preferences.all.keys.filter { it.startsWith(OWNER_PREFIX) }.forEach { name ->
            decode(stored(name) ?: error("Missing owner payload: $name"))
        }
        preferences.all.keys.filter { it.startsWith(BIND_PREFIX) }.forEach { name ->
            require(!stored(name).isNullOrBlank()) { "Invalid bound draft association" }
        }
    }
    fun refreshAfterRestore() = synchronized(lock) {
        validateRestoredPreferences()
        changes.value = changes.value + 1
    }
    private fun encode(config: ConversationSubAgentConfig): String {
        config.validate()
        return JSONObject().put("version", VERSION).put("enabled", config.enabled)
            .put("diagnostics_enabled", config.diagnosticsEnabled)
            .put("agents", JSONArray(config.profiles.map { it.toJson() }))
            .put("parallel_limits", JSONArray(config.parallelLimits.map { (model, limit) ->
                JSONObject().put("provider", model.providerId).put("api_model", model.apiModel).put("limit", limit)
            }))
            .put("legacy_parallel_limits", JSONArray(config.legacyParallelLimits.map { (hash, limit) ->
                JSONObject().put("hash", hash).put("limit", limit)
            })).toString()
    }
    private fun decode(raw: String): ConversationSubAgentConfig {
        val json = JSONObject(raw)
        require(json.getInt("version") == VERSION) { "Unsupported sub-agent config version" }
        val agents = json.getJSONArray("agents")
        val limits = json.getJSONArray("parallel_limits")
        val models = (0 until limits.length()).map { index ->
            val item = limits.getJSONObject(index)
            SubAgentParallelModel(item.getString("provider"), item.getString("api_model")) to item.getInt("limit")
        }
        require(models.map { it.first }.distinct().size == models.size)
        val legacy = json.optJSONArray("legacy_parallel_limits") ?: JSONArray()
        val hashes = (0 until legacy.length()).map { index ->
            val item = legacy.getJSONObject(index)
            item.getString("hash") to item.getInt("limit")
        }
        require(hashes.map { it.first }.distinct().size == hashes.size)
        val config = ConversationSubAgentConfig(
            profiles = (0 until agents.length()).map { index -> SubAgentProfile.fromJson(agents.getJSONObject(index)) },
            enabled = json.getBoolean("enabled"), parallelLimits = models.toMap(),
            diagnosticsEnabled = json.getBoolean("diagnostics_enabled"), legacyParallelLimits = hashes.toMap())
        config.validate()
        return config.detached()
    }
}
