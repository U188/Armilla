package io.github.mangi.eta.agent.voice.doubao

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Independent ASR/clone credentials, in application-private preferences. Never logged. */
internal object DoubaoVoiceConfig {
    data class Config(val cloudAsr: Boolean = false, val asrKey: String = "", val resource: String = DoubaoAsrProtocol.resources.first(), val cloneKey: String = "")
    private val mutable = MutableStateFlow(Config())
    val state = mutable.asStateFlow()
    fun load(context: Context) {
        val p = context.getSharedPreferences("doubao_voice", Context.MODE_PRIVATE)
        mutable.value = Config(p.getBoolean("asr", false), p.getString("asr_key", "").orEmpty(),
            p.getString("resource", null)?.takeIf { it in DoubaoAsrProtocol.resources } ?: DoubaoAsrProtocol.resources.first(), p.getString("clone_key", "").orEmpty())
    }
    fun save(context: Context, value: Config) {
        context.getSharedPreferences("doubao_voice", Context.MODE_PRIVATE).edit().putBoolean("asr", value.cloudAsr)
            .putString("asr_key", value.asrKey.trim()).putString("resource", value.resource).putString("clone_key", value.cloneKey.trim()).apply()
        mutable.value = value.copy(asrKey = value.asrKey.trim(), cloneKey = value.cloneKey.trim())
    }
}
