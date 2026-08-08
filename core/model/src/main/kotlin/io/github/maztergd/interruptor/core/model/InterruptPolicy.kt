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
 * The timing rules that decide when scrolling becomes an interruption.
 *
 * Every value is a duration in milliseconds so the engine never has to convert units.
 * The constructor rejects incoherent combinations, which means an invalid policy cannot
 * be persisted, restored, or reach the engine.
 *
 * Most of these are user-adjustable; [TimingRule] describes which, and what each may be set
 * to. Change one through that rather than by hand, so the constraints below stay satisfied.
 *
 * @param doomscrollThresholdMs how much *active* scrolling triggers the interrupt.
 * @param countdownMs how long the warning countdown runs immediately before the interrupt.
 * @param cooldownMs how long the short-video surface stays blocked afterwards.
 * @param scrollIdleTimeoutMs how long after the last swipe the session still counts as
 *   active. This is what stops a single long video, or a paused screen, from accruing time.
 * @param sessionGraceMs how long a user may be away from the short-video surface before
 *   accrued time is discarded. Short absences — replying to a message, checking the feed —
 *   must not reset progress, otherwise the threshold is trivially evaded.
 */
public data class InterruptPolicy(
    val doomscrollThresholdMs: Long,
    val countdownMs: Long,
    val cooldownMs: Long,
    val scrollIdleTimeoutMs: Long,
    val sessionGraceMs: Long,
) {
    init {
        require(doomscrollThresholdMs > 0) { "doomscrollThresholdMs must be positive" }
        require(countdownMs > 0) { "countdownMs must be positive" }
        require(cooldownMs > 0) { "cooldownMs must be positive" }
        require(scrollIdleTimeoutMs > 0) { "scrollIdleTimeoutMs must be positive" }
        require(sessionGraceMs >= 0) { "sessionGraceMs must not be negative" }
        require(countdownMs < doomscrollThresholdMs) {
            "countdownMs ($countdownMs) must be shorter than doomscrollThresholdMs " +
                "($doomscrollThresholdMs), otherwise the countdown would start before the session"
        }
    }

    /** Accrued time at which the countdown becomes visible. */
    public val countdownStartsAtMs: Long
        get() = doomscrollThresholdMs - countdownMs

    public companion object {
        /** The behaviour described in the project brief: 10 minutes, 10 second warning, 5 minute block. */
        public val DEFAULT: InterruptPolicy = InterruptPolicy(
            doomscrollThresholdMs = 10 * 60 * 1_000L,
            countdownMs = 10 * 1_000L,
            cooldownMs = 5 * 60 * 1_000L,
            scrollIdleTimeoutMs = 20 * 1_000L,
            sessionGraceMs = 2 * 60 * 1_000L,
        )
    }
}

/**
 * User-controlled configuration.
 *
 * @param enabledAppIds ids from [TargetAppCatalog]. Ids of apps this build no longer knows
 *   are retained rather than dropped, so downgrading and re-upgrading does not silently
 *   turn protection off.
 */
public data class InterruptorSettings(
    val enabledAppIds: Set<String>,
    val policy: InterruptPolicy,
) {
    /** Whether [app] should be monitored. */
    public fun isEnabled(app: TargetApp): Boolean = app.id in enabledAppIds

    public companion object {
        public val DEFAULT: InterruptorSettings = InterruptorSettings(
            enabledAppIds = TargetAppCatalog.defaultEnabledIds,
            policy = InterruptPolicy.DEFAULT,
        )
    }
}
