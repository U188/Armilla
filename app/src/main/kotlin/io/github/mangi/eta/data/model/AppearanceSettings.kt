package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

const val MIN_INTERFACE_SCALE = 0.8f
const val MAX_INTERFACE_SCALE = 1.1f
const val DEFAULT_INTERFACE_SCALE = 1f
const val MIN_BACKGROUND_SCRIM = 0f
const val MAX_BACKGROUND_SCRIM = 0.85f
const val DEFAULT_BACKGROUND_SCRIM = 0.35f

@Serializable
data class AppearanceSettings(
    val themeMode: AppearanceThemeMode = AppearanceThemeMode.LIGHT,
    val monetEnabled: Boolean = true,
    val paletteStyle: AppearancePaletteStyle = AppearancePaletteStyle.NEUTRAL,
    val accentColor: AppearanceAccentColor = AppearanceAccentColor.SYSTEM,
    val pureBlackEnabled: Boolean = false,
    val blurEnabled: Boolean = true,
    val topBarBlurStyle: AppearanceTopBarBlurStyle = AppearanceTopBarBlurStyle.GAUSSIAN,
    val swipeDismissEnabled: Boolean = true,
    val predictiveBackEnabled: Boolean = false,
    val interfaceScale: Float = DEFAULT_INTERFACE_SCALE,
    val morphLoadingIndicator: Boolean = true,
    val morphLoadingBeforeResponseOnly: Boolean = false,
    val messageTimestampsEnabled: Boolean = false,
    /** 是否启用自定义背景图；关闭时使用纯主题背景。 */
    val backgroundImageEnabled: Boolean = false,
    /** 背景图在应用私有目录中的绝对路径；空表示未导入。 */
    val backgroundImagePath: String = "",
    /** 背景图之上的遮罩透明度（0f–1f），保证前景文字可读。 */
    val backgroundImageScrim: Float = DEFAULT_BACKGROUND_SCRIM,
) {
    fun normalized(): AppearanceSettings = copy(
        monetEnabled = true,
        interfaceScale = normalizeInterfaceScale(interfaceScale),
        backgroundImageScrim = normalizeBackgroundScrim(backgroundImageScrim),
        // 路径为空时强制关闭，避免残留启用态却无图。
        backgroundImageEnabled = backgroundImageEnabled && backgroundImagePath.isNotBlank(),
    )
}

@Serializable
enum class AppearanceThemeMode(val persistedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceThemeMode =
            entries.firstOrNull { it.persistedValue == value } ?: LIGHT
    }
}

@Serializable
enum class AppearancePaletteStyle(val persistedValue: String) {
    TONAL_SPOT("tonal_spot"),
    NEUTRAL("neutral"),
    VIBRANT("vibrant"),
    EXPRESSIVE("expressive"),
    RAINBOW("rainbow"),
    FRUIT_SALAD("fruit_salad"),
    MONOCHROME("monochrome"),
    FIDELITY("fidelity"),
    CONTENT("content");

    companion object {
        fun fromPersistedValue(value: String?): AppearancePaletteStyle =
            entries.firstOrNull { it.persistedValue == value } ?: NEUTRAL
    }
}

@Serializable
enum class AppearanceAccentColor(val persistedValue: String) {
    SYSTEM("system"),
    BLUE("blue"),
    PURPLE("purple"),
    PINK("pink"),
    RED("red"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    TEAL("teal");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceAccentColor =
            entries.firstOrNull { it.persistedValue == value } ?: SYSTEM
    }
}

@Serializable
enum class AppearanceTopBarBlurStyle(val persistedValue: String) {
    GAUSSIAN("gaussian"),
    PROGRESSIVE("progressive");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceTopBarBlurStyle =
            entries.firstOrNull { it.persistedValue == value } ?: GAUSSIAN
    }
}

fun normalizeInterfaceScale(value: Float): Float =
    if (value.isFinite()) {
        value.coerceIn(MIN_INTERFACE_SCALE, MAX_INTERFACE_SCALE)
    } else {
        DEFAULT_INTERFACE_SCALE
    }

fun normalizeBackgroundScrim(value: Float): Float =
    if (value.isFinite()) {
        value.coerceIn(MIN_BACKGROUND_SCRIM, MAX_BACKGROUND_SCRIM)
    } else {
        DEFAULT_BACKGROUND_SCRIM
    }
