package io.github.mangi.eta.agent.browser

import android.content.SharedPreferences
import io.github.mangi.eta.config.Prefs

internal enum class BrowserUserAgent(
    val wireName: String,
    val userAgent: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
) {
    DESKTOP_CHROME(
        wireName = "desktop_chrome",
        userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        viewportWidth = 1280,
        viewportHeight = 800,
    ),
    MOBILE_CHROME(
        wireName = "mobile_chrome",
        userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36",
        viewportWidth = 412,
        viewportHeight = 915,
    ),
    ;

    val desktop: Boolean get() = this == DESKTOP_CHROME

    companion object {
        const val PREF_KEY = "browser_user_agent_profile"
        val DEFAULT = DESKTOP_CHROME

        fun fromWire(value: String?): BrowserUserAgent? =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) }

        fun load(preferences: SharedPreferences? = Prefs.localAgentPreferences()): BrowserUserAgent =
            fromWire(preferences?.getString(PREF_KEY, null)) ?: DEFAULT

        fun persist(
            profile: BrowserUserAgent,
            preferences: SharedPreferences? = Prefs.localAgentPreferences(),
        ) {
            preferences?.edit()?.putString(PREF_KEY, profile.wireName)?.apply()
        }
    }
}
