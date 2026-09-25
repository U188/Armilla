package io.github.mangi.eta.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceTopBarBlurStyle
import io.github.mangi.eta.data.model.MAX_INTERFACE_SCALE
import io.github.mangi.eta.data.model.MIN_INTERFACE_SCALE
import io.github.mangi.eta.data.model.normalizeInterfaceScale
import io.github.mangi.eta.data.model.MAX_BACKGROUND_CARD_ALPHA
import io.github.mangi.eta.data.model.MAX_BACKGROUND_SCRIM
import io.github.mangi.eta.data.model.MIN_BACKGROUND_CARD_ALPHA
import io.github.mangi.eta.data.model.MIN_BACKGROUND_SCRIM
import io.github.mangi.eta.data.model.normalizeBackgroundCardAlpha
import io.github.mangi.eta.data.model.normalizeBackgroundScrim
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import io.github.mangi.eta.ui.components.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import io.github.mangi.eta.ui.components.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

@Composable
internal fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val appearance = LocalAppearanceSettings.current
    val coroutineScope = rememberCoroutineScope()
    var scaleDraft by remember(appearance.interfaceScale) {
        mutableFloatStateOf(appearance.interfaceScale * 100f)
    }
    var showScaleDialog by remember { mutableStateOf(false) }
    var scaleInput by remember { mutableStateOf("") }
    var morphLoadingExpanded by remember { mutableStateOf(false) }
    val blurSupported = isRuntimeShaderSupported()
    val context = LocalContext.current
    val backgroundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            runCatching { AppearanceSettingsRepository.importBackgroundImage(context, uri) }
                .onFailure { failure ->
                    Toast.makeText(
                        context,
                        failure.message ?: context.getString(R.string.appearance_background_import_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        coroutineScope.launch {
            AppearanceSettingsRepository.update(transform)
        }
    }

    fun commitScale(percent: Float) {
        val scale = normalizeInterfaceScale(percent.roundToInt() / 100f)
        scaleDraft = scale * 100f
        update { current -> current.copy(interfaceScale = scale) }
    }

    val themeModes = AppearanceThemeMode.entries
    val themeModeLabels = listOf(
        stringResource(R.string.appearance_theme_system),
        stringResource(R.string.appearance_theme_light),
        stringResource(R.string.appearance_theme_dark),
    )
    val paletteStyles = AppearancePaletteStyle.entries
    val paletteLabels = listOf(
        stringResource(R.string.appearance_palette_tonal_spot),
        stringResource(R.string.appearance_palette_neutral),
        stringResource(R.string.appearance_palette_vibrant),
        stringResource(R.string.appearance_palette_expressive),
        stringResource(R.string.appearance_palette_rainbow),
        stringResource(R.string.appearance_palette_fruit_salad),
        stringResource(R.string.appearance_palette_monochrome),
        stringResource(R.string.appearance_palette_fidelity),
        stringResource(R.string.appearance_palette_content),
    )
    val accentColors = AppearanceAccentColor.entries
    val accentLabels = listOf(
        stringResource(R.string.appearance_accent_system),
        stringResource(R.string.appearance_accent_blue),
        stringResource(R.string.appearance_accent_purple),
        stringResource(R.string.appearance_accent_pink),
        stringResource(R.string.appearance_accent_red),
        stringResource(R.string.appearance_accent_orange),
        stringResource(R.string.appearance_accent_yellow),
        stringResource(R.string.appearance_accent_green),
        stringResource(R.string.appearance_accent_teal),
    )
    val blurStyles = AppearanceTopBarBlurStyle.entries
    val blurStyleLabels = listOf(
        stringResource(R.string.appearance_blur_style_gaussian),
        stringResource(R.string.appearance_blur_style_progressive),
    )
    val blurStyleSummaries = listOf(
        stringResource(R.string.appearance_blur_style_gaussian_summary),
        stringResource(R.string.appearance_blur_style_progressive_summary),
    )

    MiuixScaffoldPage(
        title = stringResource(R.string.appearance_title),
        onBack = onBack,
    ) {
        item(key = "appearance_color_title") {
            SmallTitle(text = stringResource(R.string.appearance_group_color))
        }
        item(key = "appearance_color_card") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.appearance_theme_mode),
                    summary = themeModeLabels[appearance.themeMode.ordinal],
                    items = themeModeLabels,
                    selectedIndex = appearance.themeMode.ordinal,
                    onSelectedIndexChange = { index ->
                        themeModes.getOrNull(index)?.let { mode ->
                            update { current -> current.copy(themeMode = mode) }
                        }
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.appearance_palette_style),
                    summary = paletteLabels[appearance.paletteStyle.ordinal],
                    items = paletteLabels,
                    selectedIndex = appearance.paletteStyle.ordinal,
                    onSelectedIndexChange = { index ->
                        paletteStyles.getOrNull(index)?.let { style ->
                            update { current -> current.copy(paletteStyle = style) }
                        }
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.appearance_accent_color),
                    summary = accentLabels[appearance.accentColor.ordinal],
                    items = accentLabels,
                    selectedIndex = appearance.accentColor.ordinal,
                    onSelectedIndexChange = { index ->
                        accentColors.getOrNull(index)?.let { accent ->
                            update { current -> current.copy(accentColor = accent) }
                        }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.appearance_pure_black),
                    summary = stringResource(R.string.appearance_pure_black_summary),
                    checked = appearance.pureBlackEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(pureBlackEnabled = enabled) }
                    },
                )
            }
        }

        item(key = "appearance_background_title") {
            SmallTitle(text = stringResource(R.string.appearance_group_background))
        }
        item(key = "appearance_background_card") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.appearance_background_enabled),
                    summary = stringResource(R.string.appearance_background_enabled_summary),
                    checked = appearance.backgroundImageEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && appearance.backgroundImagePath.isBlank()) {
                            backgroundPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        } else {
                            update { current -> current.copy(backgroundImageEnabled = enabled) }
                        }
                    },
                )
                ArrowPreference(
                    title = stringResource(
                        if (appearance.backgroundImagePath.isBlank()) {
                            R.string.appearance_background_pick
                        } else {
                            R.string.appearance_background_replace
                        },
                    ),
                    summary = stringResource(R.string.appearance_background_pick_summary),
                    onClick = {
                        backgroundPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
                AnimatedVisibility(
                    visible = appearance.backgroundImagePath.isNotBlank(),
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Column {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                text = stringResource(R.string.appearance_background_scrim),
                                style = MiuixTheme.textStyles.body1,
                            )
                            Text(
                                text = "${(appearance.backgroundImageScrim * 100).roundToInt()}%",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Slider(
                                value = appearance.backgroundImageScrim.coerceIn(
                                    MIN_BACKGROUND_SCRIM,
                                    MAX_BACKGROUND_SCRIM,
                                ),
                                onValueChange = { value ->
                                    update { current ->
                                        current.copy(backgroundImageScrim = normalizeBackgroundScrim(value))
                                    }
                                },
                                valueRange = MIN_BACKGROUND_SCRIM..MAX_BACKGROUND_SCRIM,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                            Text(
                                text = stringResource(R.string.appearance_background_card_alpha),
                                style = MiuixTheme.textStyles.body1,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                            Text(
                                text = "${(appearance.backgroundCardAlpha * 100).roundToInt()}%",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Slider(
                                value = appearance.backgroundCardAlpha.coerceIn(
                                    MIN_BACKGROUND_CARD_ALPHA,
                                    MAX_BACKGROUND_CARD_ALPHA,
                                ),
                                onValueChange = { value ->
                                    update { current ->
                                        current.copy(backgroundCardAlpha = normalizeBackgroundCardAlpha(value))
                                    }
                                },
                                valueRange = MIN_BACKGROUND_CARD_ALPHA..MAX_BACKGROUND_CARD_ALPHA,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                        ArrowPreference(
                            title = stringResource(R.string.appearance_background_remove),
                            onClick = {
                                coroutineScope.launch {
                                    AppearanceSettingsRepository.clearBackgroundImage(context)
                                }
                            },
                        )
                    }
                }
            }
        }
        item(key = "appearance_interface_title") {
            SmallTitle(text = stringResource(R.string.appearance_group_interface))
        }
        item(key = "appearance_interface_card") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.appearance_message_timestamps),
                    summary = stringResource(R.string.appearance_message_timestamps_summary),
                    checked = appearance.messageTimestampsEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(messageTimestampsEnabled = enabled) }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.appearance_blur),
                    summary = stringResource(R.string.appearance_blur_summary),
                    checked = appearance.blurEnabled && blurSupported,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(blurEnabled = enabled) }
                    },
                    enabled = blurSupported,
                )
                AnimatedVisibility(
                    visible = appearance.blurEnabled && blurSupported,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.appearance_blur_style),
                        summary = blurStyleSummaries[appearance.topBarBlurStyle.ordinal],
                        items = blurStyleLabels,
                        selectedIndex = appearance.topBarBlurStyle.ordinal,
                        onSelectedIndexChange = { index ->
                            blurStyles.getOrNull(index)?.let { style ->
                                update { current -> current.copy(topBarBlurStyle = style) }
                            }
                        },
                    )
                }
                BasicComponent(
                    title = stringResource(R.string.appearance_morph_loading),
                    summary = when {
                        !appearance.morphLoadingIndicator ->
                            stringResource(R.string.appearance_morph_loading_summary)
                        appearance.morphLoadingBeforeResponseOnly ->
                            stringResource(R.string.appearance_morph_loading_before_response)
                        else ->
                            stringResource(R.string.appearance_morph_loading_during_generation)
                    },
                    onClick = {
                        if (appearance.morphLoadingIndicator) {
                            morphLoadingExpanded = !morphLoadingExpanded
                        } else {
                            update { current -> current.copy(morphLoadingIndicator = true) }
                            morphLoadingExpanded = true
                        }
                    },
                    holdDownState = appearance.morphLoadingIndicator && morphLoadingExpanded,
                    endActions = {
                        if (appearance.morphLoadingIndicator) {
                            Icon(
                                imageVector = if (morphLoadingExpanded) {
                                    Icons.Rounded.ExpandMore
                                } else {
                                    Icons.Rounded.ChevronRight
                                },
                                contentDescription = stringResource(
                                    if (morphLoadingExpanded) {
                                        R.string.appearance_morph_loading_collapse
                                    } else {
                                        R.string.appearance_morph_loading_expand
                                    },
                                ),
                                modifier = Modifier
                                    .align(Alignment.CenterVertically)
                                    .padding(end = 6.dp)
                                    .size(16.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                        Switch(
                            checked = appearance.morphLoadingIndicator,
                            onCheckedChange = { enabled ->
                                update { current -> current.copy(morphLoadingIndicator = enabled) }
                                morphLoadingExpanded = enabled
                            },
                        )
                    },
                    bottomAction = if (appearance.morphLoadingIndicator && morphLoadingExpanded) {
                        {
                            SwitchPreference(
                                title = stringResource(R.string.appearance_morph_loading_before_response),
                                summary = stringResource(R.string.appearance_morph_loading_before_response_summary),
                                checked = appearance.morphLoadingBeforeResponseOnly,
                                onCheckedChange = { enabled ->
                                    update { current ->
                                        current.copy(morphLoadingBeforeResponseOnly = enabled)
                                    }
                                },
                                insideMargin = PaddingValues(0.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.appearance_swipe_dismiss),
                    summary = stringResource(R.string.appearance_swipe_dismiss_summary),
                    checked = appearance.swipeDismissEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(swipeDismissEnabled = enabled) }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.appearance_predictive_back),
                    summary = stringResource(R.string.appearance_predictive_back_summary),
                    checked = appearance.predictiveBackEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(predictiveBackEnabled = enabled) }
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.appearance_interface_scale),
                    summary = stringResource(R.string.appearance_interface_scale_summary),
                    endActions = {
                        Text(
                            text = "${scaleDraft.roundToInt()}%",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    },
                    bottomAction = {
                        Slider(
                            value = scaleDraft.coerceIn(
                                MIN_INTERFACE_SCALE * 100f,
                                MAX_INTERFACE_SCALE * 100f,
                            ),
                            onValueChange = { scaleDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            valueRange = (MIN_INTERFACE_SCALE * 100f)..(MAX_INTERFACE_SCALE * 100f),
                            onValueChangeFinished = { commitScale(scaleDraft) },
                            showKeyPoints = true,
                            keyPoints = listOf(80f, 90f, 100f, 110f),
                            magnetThreshold = 0.01f,
                            hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                        )
                    },
                    onClick = {
                        scaleInput = scaleDraft.roundToInt().toString()
                        showScaleDialog = true
                    },
                    holdDownState = showScaleDialog,
                )
            }
        }
    }

    val parsedScale = scaleInput.toIntOrNull()
    WindowDialog(
        show = showScaleDialog,
        title = stringResource(R.string.appearance_interface_scale_dialog_title),
        summary = stringResource(R.string.appearance_interface_scale_dialog_summary),
        onDismissRequest = { showScaleDialog = false },
    ) {
        Column {
            TextField(
                value = scaleInput,
                onValueChange = { value -> scaleInput = value.filter(Char::isDigit).take(3) },
                label = stringResource(R.string.appearance_interface_scale_input_label),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_confirm),
                confirmEnabled = parsedScale != null && parsedScale in 80..110,
                onCancel = { showScaleDialog = false },
                onConfirm = {
                    parsedScale?.let { commitScale(it.toFloat()) }
                    showScaleDialog = false
                },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
