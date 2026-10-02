package io.github.mangi.eta.ui

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.RemotePersonaCache
import io.github.mangi.eta.data.repository.RemotePersonaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object PersonaRefreshFeedback {
    fun cachedSummary(context: Context): String = listOf(
        cached(context, "小蝶", RemotePersonaStore.defaultSnapshot()),
        cached(context, "小枫", RemotePersonaStore.hackerSnapshot()),
    ).joinToString("\n")

    private fun cached(context: Context, name: String, snapshot: RemotePersonaCache.Snapshot?): String =
        if (snapshot == null) context.getString(R.string.persona_cache_builtin, name)
        else context.getString(R.string.persona_cache_local, name, snapshot.sha256.take(12))

    fun summary(context: Context, result: RemotePersonaStore.RefreshResult): String = listOf(
        status(context, "小蝶", result.default),
        status(context, "小枫", result.hacker),
    ).joinToString("\n")

    private fun status(context: Context, name: String, result: RemotePersonaStore.PersonaResult): String =
        when (result) {
            is RemotePersonaStore.PersonaResult.Success -> context.getString(
                if (result.changed) R.string.persona_status_updated else R.string.persona_status_unchanged,
                name,
            )
            is RemotePersonaStore.PersonaResult.Failure -> context.getString(R.string.persona_status_failed, name, result.reason)
        }

    fun details(context: Context, result: RemotePersonaStore.RefreshResult): String = listOf(
        details(context, "小蝶", result.default),
        details(context, "小枫", result.hacker),
        context.getString(R.string.persona_apply_note),
    ).joinToString("\n\n")

    private fun details(context: Context, name: String, result: RemotePersonaStore.PersonaResult): String {
        val snapshot = when (result) {
            is RemotePersonaStore.PersonaResult.Success -> result.snapshot
            is RemotePersonaStore.PersonaResult.Failure -> result.retained
        }
        return buildString {
            append(status(context, name, result))
            if (result is RemotePersonaStore.PersonaResult.Failure) {
                append('\n')
                append(context.getString(R.string.persona_failure_kept))
            }
            if (snapshot != null) {
                append('\n')
                append(context.getString(
                    R.string.persona_snapshot_details,
                    snapshot.source,
                    snapshot.revision.ifBlank { context.getString(R.string.persona_revision_unknown) },
                    snapshot.sha256,
                    if (snapshot.checkedAt == 0L) context.getString(R.string.persona_revision_unknown)
                    else SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date(snapshot.checkedAt)),
                    snapshot.text.length,
                ))
            }
        }
    }
}
