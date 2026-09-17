package io.github.mangi.eta.data.model

internal object ProviderAuthMode {
    const val API_KEY = "api_key"
    const val OAUTH = "oauth"
    // Retain a tombstone when reading old backups; never reinterpret as Codex OAuth.
    const val REMOVED = "removed_oauth"

    const val DEFAULT = API_KEY

    fun parse(value: String?): String {
        val normalized = value?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            OAUTH -> OAUTH
            "oauth_antigravity", REMOVED -> REMOVED
            else -> API_KEY
        }
    }

    fun isOAuth(value: String?): Boolean = parse(value) == OAUTH
}

internal val ProviderSetting.usesOAuth: Boolean
    get() = ProviderAuthMode.isOAuth(authMode)
