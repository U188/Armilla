package io.github.mangi.eta.ui.screens.stats

import java.text.NumberFormat
import java.util.Locale

internal fun formatCacheHitRate(cachedTokens: Long, inputTokens: Long, locale: Locale = Locale.getDefault()): String {
    if (inputTokens <= 0L) return "—"
    return NumberFormat.getPercentInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(cachedTokens.coerceAtLeast(0L).toDouble() / inputTokens.toDouble())
}
