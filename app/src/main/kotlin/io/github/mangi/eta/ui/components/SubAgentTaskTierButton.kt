package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SubAgentTaskTierButton(
    label: String,
    tier: SubAgentTaskTier?,
    enabled: Boolean,
    onTierSelected: (SubAgentTaskTier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val menu = rememberEtaMenuState()
    val view = LocalView.current
    val latestEnabled by rememberUpdatedState(enabled)
    val latestSelection by rememberUpdatedState(onTierSelected)
    LaunchedEffect(enabled) { if (!enabled) menu.dismiss() }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .squircleSurface(color = MiuixTheme.colorScheme.surfaceContainerHigh, cornerRadius = 12.dp)
                .semantics { contentDescription = "设置${label}任务分工" }
                .clickable(enabled = enabled, role = Role.Button) {
                    if (latestEnabled) { TouchHaptics.click(view); menu.onAnchorClick() }
                }.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MiuixTheme.textStyles.body2)
                Text(tier?.label ?: "未设置分工", style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        // Unmount immediately on run start so a fading popup cannot accept a stale selection.
        if (enabled) EtaDropdownMenu(
            expanded = menu.expanded,
            onDismissRequest = menu::dismiss,
            minWidth = 160.dp,
            maxWidth = 210.dp,
        ) {
            SubAgentTaskTier.entries.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(role = Role.Button) {
                            if (latestEnabled) {
                                TouchHaptics.click(view)
                                latestSelection(option)
                                menu.dismiss()
                            }
                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(option.label, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    if (option == tier) Icon(Icons.Rounded.Check, "当前分工", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
