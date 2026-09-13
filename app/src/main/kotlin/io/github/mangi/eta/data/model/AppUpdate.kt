package io.github.mangi.eta.data.model

internal data class AppUpdateOffer(
    val versionName: String,
    val tagName: String,
    val notes: String,
    val htmlUrl: String,
    val apkUrl: String? = null,
    val apkName: String? = null,
)

internal object AppVersion {
    fun normalize(value: String): String =
        value.trim()
            .removePrefix("v")
            .removePrefix("V")
            .takeWhile { it.isDigit() || it == '.' }
            .trim('.')

    fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = parts(remote)
        val localParts = parts(local)
        if (remoteParts.isEmpty()) return false
        if (localParts.isEmpty()) return true
        val size = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until size) {
            val remotePart = remoteParts.getOrElse(index) { 0 }
            val localPart = localParts.getOrElse(index) { 0 }
            if (remotePart != localPart) return remotePart > localPart
        }
        return false
    }

    private fun parts(value: String): List<Int> =
        normalize(value)
            .split('.')
            .filter { it.isNotEmpty() }
            .map { it.toIntOrNull() ?: 0 }
}
