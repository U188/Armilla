package io.github.mangi.eta.ui.haptics

import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Main-thread, frame-synchronous feedback. No network-event queue or delayed replay. */
internal object StreamingHaptics {
    private var owner: Any? = null
    private var visibleGate: ((View) -> Boolean)? = null

    fun onVisibleAdvance(view: View) {
        if (visibleGate?.invoke(view) == true) TouchHaptics.generationTick(view)
    }

    @Composable
    fun Observe(enabled: Boolean) {
        val view = LocalView.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val active by rememberUpdatedState(enabled)
        DisposableEffect(view, lifecycle) {
            val token = Any()
            owner = token
            visibleGate = { candidate ->
                candidate === view && active &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && view.hasWindowFocus()
            }
            onDispose {
                if (owner === token) { owner = null; visibleGate = null }
            }
        }
    }
}
