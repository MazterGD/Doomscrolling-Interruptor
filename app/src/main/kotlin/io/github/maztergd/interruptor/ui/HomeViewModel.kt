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

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.maztergd.interruptor.core.model.AppTimeLeft
import io.github.maztergd.interruptor.core.model.InterruptorSettings
import io.github.maztergd.interruptor.data.SettingsRepository
import io.github.maztergd.interruptor.service.EnforcementTimers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the single settings screen. */
class HomeViewModel(private val repository: SettingsRepository) : ViewModel() {

    val settings: StateFlow<InterruptorSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = InterruptorSettings.DEFAULT,
    )

    /**
     * What each watched app is counting down to, refreshed while this screen is on show.
     *
     * Polled rather than pushed. The engine only ticks while a short-video session is live, so
     * there is nothing to subscribe to at the moment this screen is open — the user is looking
     * at us, not at a feed. Re-reading is cheap and cannot drift: every value is derived from a
     * monotonic clock at the instant it is asked for.
     *
     * Empty means nothing is being timed, which on this screen is either "the accessibility
     * service is off" or "no app is watched". Both are already visible elsewhere on it.
     */
    val timeLeft: StateFlow<Map<String, AppTimeLeft>> = flow {
        while (true) {
            emit(EnforcementTimers.snapshot().orEmpty())
            delay(REFRESH_INTERVAL_MS)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyMap(),
    )

    fun setAppEnabled(appId: String, enabled: Boolean) {
        viewModelScope.launch { repository.setAppEnabled(appId, enabled) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * Twice a second. The countdowns are displayed to the second, and sampling at exactly
         * one second would let the displayed value visibly skip whenever the two cadences drift
         * apart.
         */
        private const val REFRESH_INTERVAL_MS = 500L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer { HomeViewModel(SettingsRepository(appContext)) }
            }
        }
    }
}
