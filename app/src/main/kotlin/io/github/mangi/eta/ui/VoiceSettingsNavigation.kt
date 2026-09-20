package io.github.mangi.eta.ui

internal enum class VoiceSettingsBackTarget {
    SYNC_PARENT, ACCOUNT_PARENT, PREVIOUS_STEP, HOME, EXIT,
}

// Nested forms close first without discarding the in-progress voice draft.
internal fun voiceSettingsBackTarget(
    showSync: Boolean,
    showAccount: Boolean,
    mode: String?,
    step: Int,
): VoiceSettingsBackTarget = when {
    showSync -> VoiceSettingsBackTarget.SYNC_PARENT
    showAccount -> VoiceSettingsBackTarget.ACCOUNT_PARENT
    mode != null && step > 0 -> VoiceSettingsBackTarget.PREVIOUS_STEP
    mode != null -> VoiceSettingsBackTarget.HOME
    else -> VoiceSettingsBackTarget.EXIT
}
