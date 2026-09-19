package io.github.mangi.eta.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.agent.voice.VoiceModeController

/** Lives above the keyed message list: sending the first message must not end voice mode. */
@Composable
internal fun rememberChatVoiceController(conversationId: String?, submit: (String) -> Unit): VoiceModeController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestSubmit by rememberUpdatedState(submit)
    val controller = remember(context, scope) {
        VoiceModeController(context, scope) { latestSubmit(it) }
    }
    var previousId by remember { mutableStateOf(conversationId) }
    LaunchedEffect(conversationId) {
        // A draft acquiring its ID is not navigation; switching existing chats is.
        if (previousId != null && previousId != conversationId) controller.stop()
        previousId = conversationId
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.stop()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.stop()
        }
    }
    return controller
}
