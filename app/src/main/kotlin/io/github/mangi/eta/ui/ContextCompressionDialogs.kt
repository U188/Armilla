package io.github.mangi.eta.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.ViewTreeObserver
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentCompressionStrategy
import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.defaultExpandedModelProviderIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog


private val CompressDialogChrome = 200.dp

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun rememberActivityImeBottomDp(): Dp {
    val density = LocalDensity.current
    val context = LocalContext.current
    val composeImePx = WindowInsets.ime.getBottom(density)
    var viewImePx by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val target = context.findActivity()?.window?.decorView
        if (target == null) {
            return@DisposableEffect onDispose { }
        }
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val insets = ViewCompat.getRootWindowInsets(target) ?: return@OnGlobalLayoutListener
            viewImePx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        }
        target.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose {
            target.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }
    return with(density) { maxOf(composeImePx, viewImePx).toDp() }
}


private fun currentCompressionStrategy(): AgentCompressionStrategy =
    AgentCompressionStrategy.parse(Prefs.getString(Prefs.Keys.AGENT_COMPRESSION_STRATEGY))

private fun storedManualCompressionStrategy(): AgentCompressionStrategy {
    val stored = Prefs.getString(Prefs.Keys.AGENT_MANUAL_COMPRESS_STRATEGY)
    if (!stored.isNullOrBlank() &&
        AgentCompressionStrategy.entries.any { it.wireValue == stored }
    ) {
        return AgentCompressionStrategy.parse(stored)
    }
    return currentCompressionStrategy()
}


@Composable
internal fun CompressionStrategyOptions(
    selected: AgentCompressionStrategy,
    enabled: Boolean,
    onSelect: (AgentCompressionStrategy) -> Unit,
    showNote: Boolean = false,
    compact: Boolean = false,
) {
    val view = LocalView.current
    val horizontal = if (compact) 0.dp else 16.dp
    Column(modifier = Modifier.fillMaxWidth()) {
        AgentCompressionStrategy.entries.forEach { option ->
            val title = if (option == AgentCompressionStrategy.CONTINUE_TASK) {
                stringResource(R.string.ui_compress_strategy_continue_title)
            } else {
                stringResource(R.string.ui_compress_strategy_preserve_title)
            }
            val summary = if (option == AgentCompressionStrategy.CONTINUE_TASK) {
                stringResource(R.string.ui_compress_strategy_continue_summary)
            } else {
                stringResource(R.string.ui_compress_strategy_preserve_summary)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) {
                        TouchHaptics.click(view)
                        onSelect(option)
                    }
                    .padding(horizontal = horizontal, vertical = if (compact) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(summary, style = MaterialTheme.typography.bodySmall)
                }
                androidx.compose.material3.RadioButton(
                    selected = selected == option,
                    enabled = enabled,
                    onClick = {
                        TouchHaptics.click(view)
                        onSelect(option)
                    },
                )
            }
        }
        if (showNote) {
            Text(
                stringResource(R.string.ui_compress_strategy_note),
                modifier = Modifier.padding(start = horizontal, end = horizontal, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompressConversationDialog(
    show: Boolean,
    isCompressing: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (
        providerId: String?,
        modelId: String?,
        strategy: AgentCompressionStrategy,
        onFinished: (Boolean) -> Unit,
    ) -> Unit,
) {
    val prefs = remember { Prefs.localAgentPreferences() }
    val scope = rememberCoroutineScope()
    var strategy by remember { mutableStateOf(AgentCompressionStrategy.DEFAULT) }
    var selectedModel by remember { mutableStateOf<AgentModelOptionUi?>(null) }
    var customModelEnabled by remember { mutableStateOf(false) }
    var modelPickerState by remember { mutableStateOf(AgentModelPickerUiState()) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showMissingWindowDialog by remember { mutableStateOf(false) }
    var isLoadingModels by remember { mutableStateOf(false) }
    val imeBottom = rememberActivityImeBottomDp()
    val configuration = LocalConfiguration.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val dialogImeOffset = -(imeBottom / 2)
    val maxBodyHeight = (configuration.screenHeightDp.dp - imeBottom - CompressDialogChrome)
        .coerceIn(140.dp, 360.dp)

    LaunchedEffect(show, prefs) {
        if (!show) {
            showModelDialog = false
            return@LaunchedEffect
        }
        strategy = storedManualCompressionStrategy()
        isLoadingModels = true
        customModelEnabled = Prefs.isCustomCompressModelEnabled(prefs)
        val pickerState = withContext(Dispatchers.IO) {
            buildManualCompressModelPickerState()
        }
        modelPickerState = pickerState
        selectedModel = pickerState.selectedModel.takeIf { customModelEnabled }
        isLoadingModels = false
    }

    WindowDialog(
        show = show,
        title = stringResource(R.string.action_compress_conversation),
        modifier = Modifier.offset(y = dialogImeOffset),
        onDismissRequest = onDismiss,
    ) {
        val scrollState = rememberScrollState()
        val dialogFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            dialogFocus.requestFocus()
            keyboard?.hide()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(dialogFocus)
                .focusable(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBodyHeight)
                    .verticalScroll(scrollState)
                    .alpha(if (isCompressing) 0.42f else 1f),
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ui_custom_compress_model_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = customModelEnabled,
                    enabled = !isCompressing,
                    onCheckedChange = { value ->
                        TouchHaptics.click(view)
                        customModelEnabled = value
                        Prefs.putBoolean(Prefs.Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED, value)
                        if (value && selectedModel == null) {
                            selectedModel = modelPickerState.selectedModel
                        }
                        if (value && !selectedModel.hasCompressContextWindow() && selectedModel != null) {
                            showMissingWindowDialog = true
                        }
                    },
                )
            }
            if (customModelEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isCompressing && !isLoadingModels) {
                            TouchHaptics.click(view)
                            showModelDialog = true
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selectedModel?.displayName
                            ?: stringResource(R.string.model_not_selected),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Text(
                text = stringResource(R.string.ui_compress_strategy_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            CompressionStrategyOptions(
                selected = strategy,
                enabled = !isCompressing,
                compact = true,
                onSelect = { option ->
                    strategy = option
                    Prefs.putString(Prefs.Keys.AGENT_MANUAL_COMPRESS_STRATEGY, option.wireValue)
                    Prefs.putInt(
                        Prefs.Keys.AGENT_MANUAL_COMPRESS_KEEP_RECENT,
                        AgentContextCompactor.keepRecentFor(option),
                    )
                },
            )

            }
            if (isCompressing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.compress_conversation_in_progress),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            MiuixDialogActions(
                confirmText = stringResource(R.string.compress_conversation_confirm),
                cancelText = stringResource(R.string.action_cancel),
                confirmEnabled = !isCompressing,
                cancelEnabled = !isCompressing,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .alpha(if (isCompressing) 0.42f else 1f),
                onCancel = onDismiss,
                onConfirm = {
                    if (isCompressing) return@MiuixDialogActions
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onConfirm(
                        selectedModel?.providerId.takeIf { customModelEnabled },
                        selectedModel?.id.takeIf { customModelEnabled },
                        strategy,
                    ) { ok ->
                        if (ok) onDismiss()
                    }
                },
            )
        }
    }

    CompressModelPickerDialog(
        state = modelPickerState,
        show = show && showModelDialog,
        isLoading = isLoadingModels,
        onDismiss = { showModelDialog = false },
        onModelSelected = { providerId, modelId ->
            showModelDialog = false
            Prefs.putString(Prefs.Keys.AGENT_MANUAL_COMPRESS_MODEL_PROVIDER_ID, providerId)
            Prefs.putString(Prefs.Keys.AGENT_MANUAL_COMPRESS_MODEL_ID, modelId)
            val picked = modelPickerState.findCompressModel(providerId, modelId)
            if (!picked.hasCompressContextWindow()) {
                showMissingWindowDialog = true
            }
            scope.launch {
                val pickerState = withContext(Dispatchers.IO) {
                    buildCompressModelPickerState(providerId, modelId)
                }
                modelPickerState = pickerState
                selectedModel = pickerState.selectedModel
            }
        },
    )

    CompressModelMissingWindowDialog(
        show = show && showMissingWindowDialog,
        onDismiss = { showMissingWindowDialog = false },
    )
}


internal fun AgentModelOptionUi?.hasCompressContextWindow(): Boolean =
    this?.contextWindow?.let { it > 0 } == true

internal fun AgentModelPickerUiState.findCompressModel(providerId: String, modelId: String): AgentModelOptionUi? =
    providerGroups.firstOrNull { it.providerId == providerId }?.models?.firstOrNull { it.id == modelId }

@Composable
internal fun CompressModelMissingWindowDialog(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    if (!show) return
    WindowDialog(
        show = true,
        title = stringResource(R.string.compress_model_missing_window_title),
        summary = stringResource(R.string.compress_model_missing_window_summary),
        onDismissRequest = onDismiss,
    ) {
        TextButton(
            text = stringResource(R.string.ui_knew_cb63c6),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
internal fun CompressModelPickerDialog(
    state: AgentModelPickerUiState,
    show: Boolean,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onModelSelected: (String, String) -> Unit,
) {
    val view = LocalView.current
    var expandedProviderIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(show, state.selectedModel?.providerId, state.providerGroups) {
        if (!show) return@LaunchedEffect
        expandedProviderIds = defaultExpandedModelProviderIds(state.selectedModel).ifEmpty {
            state.providerGroups.singleOrNull()?.providerId?.let(::setOf).orEmpty()
        }
    }
    WindowDialog(
        show = show,
        title = stringResource(R.string.ui_compress_model_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (isLoading && state.providerGroups.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(size = 22.dp, strokeWidth = 2.dp)
                }
            } else if (state.providerGroups.isEmpty()) {
                MiuixText(
                    text = stringResource(R.string.provider_empty),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            } else {
                state.providerGroups.forEachIndexed { index, group ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    val expanded = group.providerId in expandedProviderIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                TouchHaptics.click(view)
                                expandedProviderIds = if (expanded) {
                                    expandedProviderIds - group.providerId
                                } else {
                                    expandedProviderIds + group.providerId
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = group.providerName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (expanded) {
                        group.models.forEach { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        TouchHaptics.click(view)
                                        onModelSelected(model.providerId, model.id)
                                    }
                                    .padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = model.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (model.id == state.selectedModel?.id) {
                                    Icon(imageVector = Icons.Rounded.Check, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal suspend fun buildCompressModelPickerState(
    prefs: SharedPreferences?,
): AgentModelPickerUiState {
    val providerId = prefs?.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID, null)
    val modelId = prefs?.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_ID, null)
    return buildCompressModelPickerState(providerId, modelId)
}

internal suspend fun buildManualCompressModelPickerState(): AgentModelPickerUiState {
    val prefs = Prefs.localAgentPreferences()
    val manualProviderId = prefs?.getString(Prefs.Keys.AGENT_MANUAL_COMPRESS_MODEL_PROVIDER_ID, null)
    val manualModelId = prefs?.getString(Prefs.Keys.AGENT_MANUAL_COMPRESS_MODEL_ID, null)
    if (!manualProviderId.isNullOrBlank() && !manualModelId.isNullOrBlank()) {
        return buildCompressModelPickerState(manualProviderId, manualModelId)
    }
    val settings = SettingsDataStore.settings()
    return buildCompressModelPickerState(settings.selectedProviderId, settings.selectedModelId)
}

internal suspend fun buildCompressModelPickerState(
    selectedProviderId: String?,
    selectedModelId: String?,
): AgentModelPickerUiState {
    val providers = runCatching { ProviderRepository.allProviders() }.getOrNull() ?: emptyList()
    return AgentModelPickerProjector.project(
        providers = providers,
        selectedProviderId = selectedProviderId,
        selectedModelId = selectedModelId,
    )
}

internal suspend fun readCompressModelSelection(prefs: SharedPreferences): AgentModelOptionUi? {
    return buildCompressModelPickerState(prefs).selectedModel
}

