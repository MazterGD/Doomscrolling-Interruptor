/*
 * Doomscrolling-Interruptor
 * Copyright (C) 2026 the Doomscrolling-Interruptor contributors
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.maztergd.interruptor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.maztergd.interruptor.R
import io.github.maztergd.interruptor.core.model.AppTimeLeft
import io.github.maztergd.interruptor.core.model.TargetApp
import io.github.maztergd.interruptor.core.model.TargetAppCatalog
import java.util.Locale

@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val timeLeft by viewModel.timeLeft.collectAsStateWithLifecycle()

    var serviceEnabled by remember { mutableStateOf(AccessibilityPermission.isServiceEnabled(context)) }
    // The grant happens in system Settings, so the only reliable moment to re-check is when
    // this screen comes back to the foreground.
    LifecycleResumeEffect(Unit) {
        serviceEnabled = AccessibilityPermission.isServiceEnabled(context)
        onPauseOrDispose { }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        item {
            ServiceStatusCard(
                enabled = serviceEnabled,
                onOpenSettings = { context.startActivity(AccessibilityPermission.settingsIntent()) },
            )
        }

        item { SectionHeader(stringResource(R.string.apps_section)) }

        items(TargetAppCatalog.all, key = TargetApp::id) { app ->
            AppToggleRow(
                app = app,
                watched = app.id in settings.enabledAppIds,
                timeLeft = timeLeft[app.id],
                onToggle = { viewModel.setAppEnabled(app.id, it) },
            )
        }

        item { SectionHeader(stringResource(R.string.rules_section)) }

        item {
            val policy = settings.policy
            InfoCard(
                lines = listOf(
                    stringResource(R.string.rule_threshold, policy.doomscrollThresholdMs / 60_000),
                    stringResource(R.string.rule_countdown, policy.countdownMs / 1_000),
                    stringResource(R.string.rule_cooldown, policy.cooldownMs / 60_000),
                    stringResource(R.string.rule_idle),
                    stringResource(R.string.rule_scope),
                ),
            )
        }

        item { SectionHeader(stringResource(R.string.privacy_section)) }

        item { InfoCard(lines = listOf(stringResource(R.string.privacy_body))) }

        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.source_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ServiceStatusCard(enabled: Boolean, onOpenSettings: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = stringResource(if (enabled) R.string.service_enabled else R.string.service_disabled),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    if (enabled) R.string.service_enabled_detail else R.string.service_disabled_detail,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!enabled) {
                Spacer(Modifier.height(14.dp))
                Button(onClick = onOpenSettings) {
                    Text(stringResource(R.string.open_accessibility_settings))
                }
            }
        }
    }
}

/**
 * One app, its switch, and what it is counting down to.
 *
 * @param timeLeft the app's live countdown, or `null` when nothing is timing it — the service
 *   is off, or the app is not watched.
 */
@Composable
private fun AppToggleRow(
    app: TargetApp,
    watched: Boolean,
    timeLeft: AppTimeLeft?,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = app.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                val caption = timeLeftCaption(watched, timeLeft)
                if (caption != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodySmall,
                        // A paused app is the one thing on this screen the user may be waiting
                        // on, so it is the only row that earns the accent colour.
                        color = if (timeLeft is AppTimeLeft.UntilUnlock) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Switch(checked = watched, onCheckedChange = onToggle)
        }
    }
}

/**
 * The line under an app's name, or `null` when there is nothing truthful to put there.
 *
 * A watched app with no countdown means the accessibility service is not running, and no
 * number belongs on the row in that case: the status card above already explains that nothing
 * is being enforced, and a frozen timer would suggest otherwise.
 */
@Composable
private fun timeLeftCaption(watched: Boolean, timeLeft: AppTimeLeft?): String? = when {
    !watched -> stringResource(R.string.app_status_not_watched)
    timeLeft is AppTimeLeft.UntilUnlock ->
        stringResource(R.string.app_status_paused, formatDuration(timeLeft.millisRemaining))
    timeLeft is AppTimeLeft.UntilPause ->
        stringResource(R.string.app_status_time_left, formatDuration(timeLeft.millisRemaining))
    else -> null
}

/**
 * `m:ss`.
 *
 * Rounded up, so a countdown reads 0:00 only once it has genuinely run out rather than for the
 * whole of its final second.
 */
private fun formatDuration(millisRemaining: Long): String {
    val seconds = (millisRemaining.coerceAtLeast(0) + 999) / 1_000
    return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}

@Composable
private fun InfoCard(lines: List<String>) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
