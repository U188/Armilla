package io.github.mangi.eta.ui.screens.browser

import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.browser.ported.browser.BrowserTabPool
import io.github.mangi.eta.agent.browser.ported.ui.browser.BrowserSheet
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Full multi-tab browser UI; the pool is the same one used by browser_use. */
@Composable
internal fun AgentBrowserScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = remember { Any() }
    var pool by remember { mutableStateOf<BrowserTabPool?>(null) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { AgentBrowserSession.releaseUserControl(owner) }
    }
    LaunchedEffect(context.applicationContext) {
        try {
            pool = AgentBrowserSession.acquireUserControl(context.applicationContext, owner)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val colors = MiuixTheme.colorScheme
    val scheme = (if (colors.surface.luminance() < 0.5f) darkColorScheme() else lightColorScheme()).copy(
        primary = colors.primary, onPrimary = colors.onPrimary,
        surface = colors.surface, background = colors.surface,
        onSurface = colors.onSurface, onBackground = colors.onSurface,
        surfaceContainer = colors.surfaceContainer,
        onSurfaceVariant = colors.onSurfaceVariantSummary,
        outline = colors.outline,
    )
    MaterialTheme(colorScheme = scheme) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val ready = pool
            when {
                failed -> Text("浏览器初始化失败，请退出此页后重试")
                ready == null -> InfiniteProgressIndicator()
                else -> BrowserSheet(tabPool = ready, embedded = true, onDismiss = {})
            }
        }
    }
}
