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
package io.github.maztergd.interruptor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.maztergd.interruptor.core.model.InterruptPolicy
import io.github.maztergd.interruptor.core.model.InterruptorSettings
import io.github.maztergd.interruptor.core.model.TargetAppCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "interruptor_settings")

/**
 * Persists the user's configuration on device.
 *
 * Storage is a plain local DataStore. It is excluded from cloud and device-to-device backup
 * (see `data_extraction_rules.xml`), because the only thing worth saying about this data is
 * that it never leaves the handset.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<InterruptorSettings> = context.settingsDataStore.data.map { it.toSettings() }

    suspend fun setAppEnabled(appId: String, enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[KEY_ENABLED_APPS] ?: TargetAppCatalog.defaultEnabledIds
            prefs[KEY_ENABLED_APPS] = if (enabled) current + appId else current - appId
        }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.settingsDataStore.edit { it[KEY_ONBOARDED] = complete }
    }

    val onboardingComplete: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_ONBOARDED] ?: false }

    /**
     * Overwrites the timing rules.
     *
     * [InterruptPolicy] validates itself, so an incoherent combination cannot be written.
     */
    suspend fun setPolicy(policy: InterruptPolicy) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_THRESHOLD_MS] = policy.doomscrollThresholdMs
            prefs[KEY_COUNTDOWN_MS] = policy.countdownMs
            prefs[KEY_COOLDOWN_MS] = policy.cooldownMs
            prefs[KEY_SCROLL_IDLE_MS] = policy.scrollIdleTimeoutMs
            prefs[KEY_GRACE_MS] = policy.sessionGraceMs
        }
    }

    private fun Preferences.toSettings(): InterruptorSettings {
        val default = InterruptPolicy.DEFAULT
        // A stored policy that no longer validates — for example after a downgrade wrote
        // fields this build rejects — must not brick the service. Fall back to the default.
        val policy = runCatching {
            InterruptPolicy(
                doomscrollThresholdMs = this[KEY_THRESHOLD_MS] ?: default.doomscrollThresholdMs,
                countdownMs = this[KEY_COUNTDOWN_MS] ?: default.countdownMs,
                cooldownMs = this[KEY_COOLDOWN_MS] ?: default.cooldownMs,
                scrollIdleTimeoutMs = this[KEY_SCROLL_IDLE_MS] ?: default.scrollIdleTimeoutMs,
                sessionGraceMs = this[KEY_GRACE_MS] ?: default.sessionGraceMs,
            )
        }.getOrDefault(default)

        return InterruptorSettings(
            enabledAppIds = this[KEY_ENABLED_APPS] ?: TargetAppCatalog.defaultEnabledIds,
            policy = policy,
        )
    }

    private companion object {
        val KEY_ENABLED_APPS = stringSetPreferencesKey("enabled_app_ids")
        val KEY_ONBOARDED = booleanPreferencesKey("onboarding_complete")
        val KEY_THRESHOLD_MS = longPreferencesKey("threshold_ms")
        val KEY_COUNTDOWN_MS = longPreferencesKey("countdown_ms")
        val KEY_COOLDOWN_MS = longPreferencesKey("cooldown_ms")
        val KEY_SCROLL_IDLE_MS = longPreferencesKey("scroll_idle_ms")
        val KEY_GRACE_MS = longPreferencesKey("grace_ms")
    }
}
