package io.github.mangi.eta.agent.voice.doubao

import android.content.Context

/** Only the user-requested key ID and project; never the secret access key. */
internal object VoiceCatalogPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("voice_catalog_id", Context.MODE_PRIVATE)
    fun keyId(context: Context) = prefs(context).getString("key_id", "").orEmpty()
    fun project(context: Context) = prefs(context).getString("project", "default").orEmpty()
    fun save(context: Context, keyId: String, project: String) {
        prefs(context).edit().putString("key_id", keyId.trim()).putString("project", project.trim().ifBlank { "default" }).apply()
    }
    fun clear(context: Context) { prefs(context).edit().clear().apply() }
}
