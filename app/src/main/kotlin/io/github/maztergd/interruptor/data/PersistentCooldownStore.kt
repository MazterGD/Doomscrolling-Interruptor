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
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.maztergd.interruptor.core.domain.CooldownStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.cooldownDataStore: DataStore<Preferences> by preferencesDataStore(name = "interruptor_cooldowns")

/**
 * A [CooldownStore] that survives the service being restarted and the device rebooting.
 *
 * ### Why two timestamps are stored
 *
 * Enforcement runs on `SystemClock.elapsedRealtime`, which the user cannot alter — that is
 * what stops a block being ended by changing the date in Settings. But elapsed-realtime
 * values are meaningless after a reboot, since the origin moves. So each block records both
 * an elapsed-realtime deadline and a wall-clock one:
 *
 * - **Same boot** (the common case): the elapsed deadline is used, and the clock is irrelevant.
 * - **After a reboot**: the wall-clock deadline is converted into a fresh elapsed one.
 *
 * Rebooting to escape a block therefore requires also moving the system clock forward, which
 * is far more effort than waiting out the five minutes. The restored remainder is clamped so
 * that a clock moved *backwards* cannot manufacture a block of arbitrary length.
 *
 * All reads are served from memory, because the engine calls this on the service's main
 * thread and must never block; writes are mirrored to disk asynchronously.
 */
class PersistentCooldownStore(
    private val context: Context,
    private val scope: CoroutineScope,
) : CooldownStore {

    private val deadlines = LinkedHashMap<String, Long>()

    @Volatile
    private var hydrated = false

    /**
     * Loads persisted blocks. Must complete before the engine is allowed to make decisions.
     *
     * @param maxRestorableMs upper bound on a restored remainder, so that a system clock
     *   moved backwards cannot turn a five minute block into an indefinite one.
     */
    suspend fun hydrate(maxRestorableMs: Long) {
        val prefs = context.cooldownDataStore.data.first()
        val bootRef = prefs[KEY_BOOT_REFERENCE]
        val sameBoot = bootRef != null && kotlin.math.abs(bootRef - currentBootReference()) <= BOOT_TOLERANCE_MS

        val elapsedNow = SystemClock.elapsedRealtime()
        val wallNow = System.currentTimeMillis()

        deadlines.clear()
        prefs[KEY_APP_IDS].orEmpty().forEach { appId ->
            val elapsedExpiry = prefs[elapsedKey(appId)]
            val wallExpiry = prefs[wallKey(appId)]

            val remaining = when {
                sameBoot && elapsedExpiry != null -> elapsedExpiry - elapsedNow
                wallExpiry != null -> wallExpiry - wallNow
                else -> 0L
            }

            if (remaining > 0) {
                deadlines[appId] = elapsedNow + remaining.coerceAtMost(maxRestorableMs)
            }
        }
        hydrated = true
        persist()
    }

    /** Whether persisted state has been loaded. Enforcement stays inert until it has. */
    fun isHydrated(): Boolean = hydrated

    override fun expiryFor(appId: String): Long? = deadlines[appId]

    override fun put(appId: String, expiresAtElapsedMs: Long) {
        deadlines[appId] = expiresAtElapsedMs
        persist()
    }

    override fun remove(appId: String) {
        if (deadlines.remove(appId) != null) persist()
    }

    override fun snapshot(): Map<String, Long> = deadlines.toMap()

    private fun persist() {
        val elapsedNow = SystemClock.elapsedRealtime()
        val wallNow = System.currentTimeMillis()
        val entries = deadlines.toMap()
        val bootReference = currentBootReference()

        scope.launch {
            context.cooldownDataStore.edit { prefs ->
                prefs.clear()
                prefs[KEY_BOOT_REFERENCE] = bootReference
                prefs[KEY_APP_IDS] = entries.keys
                entries.forEach { (appId, elapsedExpiry) ->
                    prefs[elapsedKey(appId)] = elapsedExpiry
                    prefs[wallKey(appId)] = wallNow + (elapsedExpiry - elapsedNow)
                }
            }
        }
    }

    /**
     * An approximation of when the device booted, in wall-clock terms.
     *
     * Stable across a single boot to within clock drift, and different after a restart,
     * which is exactly what is needed to tell the two cases apart.
     */
    private fun currentBootReference(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private companion object {
        val KEY_APP_IDS = stringSetPreferencesKey("cooldown_app_ids")
        val KEY_BOOT_REFERENCE = longPreferencesKey("boot_reference")

        /** Allows for NTP corrections and drift without being mistaken for a reboot. */
        const val BOOT_TOLERANCE_MS = 10_000L

        fun elapsedKey(appId: String) = longPreferencesKey("cooldown_elapsed_$appId")
        fun wallKey(appId: String) = longPreferencesKey("cooldown_wall_$appId")
    }
}
