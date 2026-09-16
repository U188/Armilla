package io.github.mangi.eta.data.model

internal object ProviderAuthMode {
    const val API_KEY = "api_key"
    const val OAUTH = "oauth"

    const val DEFAULT = API_KEY

    fun parse(value: String?): String =
        if (value?.trim().equals(OAUTH, ignoreCase = true) == true) OAUTH else API_KEY

    fun isOAuth(value: String?): Boolean = parse(value) == OAUTH
}

internal val ProviderSetting.usesOAuth: Boolean
    get() = ProviderAuthMode.isOAuth(authMode)
