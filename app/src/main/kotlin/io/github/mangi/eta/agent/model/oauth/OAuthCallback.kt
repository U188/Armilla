package io.github.mangi.eta.agent.model.oauth

import android.net.Uri

internal object OAuthCallback {
    fun isRedirect(uri: Uri): Boolean {
        val host = uri.host.orEmpty().trim().lowercase()
            .removePrefix("[").removeSuffix("]")
        if (host != "localhost" && host != "127.0.0.1" && host != "::1") return false
        val path = uri.path.orEmpty()
        return path.contains("callback")
    }

    fun parse(uri: Uri): Pair<String, String?> {
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val description = uri.getQueryParameter("error_description")
                ?.takeIf { it.isNotBlank() }
            error(description ?: error)
        }
        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
            ?: error("登录回调缺少授权码")
        return code to uri.getQueryParameter("state")
    }

    fun parseUrl(url: String): Pair<String, String?>? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (!isRedirect(uri)) return null
        return parse(uri)
    }
}
