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
package io.github.maztergd.interruptor.core.model

/**
 * What the accessibility service should be showing right now.
 *
 * The engine emits this and nothing else. The service's only job is to make the screen
 * match the latest value, which keeps all timing decisions in one testable place and
 * leaves no room for the UI layer to invent policy of its own.
 */
public sealed interface EnforcementState {

    /** Nothing to display. The foreground app is untracked, disabled, or off its video surface. */
    public data object Idle : EnforcementState

    /**
     * A short-video session is accruing time, but the user is not close to the threshold.
     * Nothing is drawn on screen; this exists so the UI can show live session stats.
     */
    public data class Monitoring(
        val appId: String,
        val activeMs: Long,
    ) : EnforcementState

    /**
     * The final seconds before the interrupt. A small, non-interactive warning is shown and
     * the user can still scroll — the point is to give notice, not to block yet.
     */
    public data class Countdown(
        val appId: String,
        val millisRemaining: Long,
    ) : EnforcementState

    /**
     * The short-video surface is blocked. A full-screen overlay is shown with no exit
     * affordance; the user leaves via the system home or recents gesture.
     */
    public data class Blocked(
        val appId: String,
        val cooldownRemainingMs: Long,
    ) : EnforcementState
}
