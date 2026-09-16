package io.github.mangi.eta.agent.model.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object OAuthPkce {
    fun generate(byteLength: Int = 64): Pair<String, String> {
        val bytes = ByteArray(byteLength.coerceAtLeast(32))
        SecureRandom().nextBytes(bytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
        return verifier to challenge
    }

    fun generateState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
