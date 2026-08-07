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
package io.github.maztergd.interruptor.core.domain

import io.github.maztergd.interruptor.core.model.EnforcementState
import io.github.maztergd.interruptor.core.model.InterruptPolicy
import io.github.maztergd.interruptor.core.model.InterruptorSettings
import io.github.maztergd.interruptor.core.model.TargetApp

/**
 * Decides when scrolling has become doomscrolling, and enforces the block that follows.
 *
 * The engine is the single source of truth for timing. It is driven by three inputs — a
 * surface change, a scroll, and a periodic tick — and answers each with the
 * [EnforcementState] the screen should reflect. Nothing above it is allowed to decide
 * when to block, which is what keeps the rules testable without a device.
 *
 * ### What counts as doomscrolling
 *
 * Time accrues only while the user is **on a short-video surface and actively scrolling**.
 * "Actively" means within [InterruptPolicy.scrollIdleTimeoutMs] of the last swipe, so
 * stopping to watch one long video does not march the user toward a block.
 *
 * ### Why sessions survive leaving the surface
 *
 * Accrued time is kept for [InterruptPolicy.sessionGraceMs] after the user navigates away.
 * Without this, the threshold would be defeated by flicking to the home feed and back.
 * Sessions are tracked per app, so time spent in Instagram never pushes YouTube toward a
 * block, and each app's block is independent.
 *
 * ### Threading
 *
 * Callers on Android are the accessibility service's main thread, but every entry point
 * is guarded anyway: a service can be torn down and rebuilt while a tick is in flight,
 * and correctness here is worth an uncontended lock.
 */
public class DoomscrollEngine(
    private val timeSource: TimeSource,
    private val cooldownStore: CooldownStore = InMemoryCooldownStore(),
    initialSettings: InterruptorSettings = InterruptorSettings.DEFAULT,
) {

    private val lock = Any()

    private var settings: InterruptorSettings = initialSettings

    /** Per-app accrual state. Bounded by the size of the catalog, and pruned once stale. */
    private val sessions = LinkedHashMap<String, Session>()

    private var currentAppId: String? = null
    private var onReels: Boolean = false

    private val policy: InterruptPolicy
        get() = settings.policy

    /** Replaces the active configuration. Accrued sessions are preserved. */
    public fun updateSettings(newSettings: InterruptorSettings) {
        synchronized(lock) {
            settings = newSettings
            // An app switched off mid-session must stop enforcing immediately.
            val active = currentAppId
            if (active != null && sessions.containsKey(active) && !isEnabled(active)) {
                sessions.remove(active)
                currentAppId = null
                onReels = false
            }
        }
    }

    /**
     * Reports what is on screen.
     *
     * @param app the foreground app if the catalog recognises it, otherwise `null`.
     * @param showingReels whether that app's short-video surface is currently displayed.
     */
    public fun onSurface(app: TargetApp?, showingReels: Boolean): EnforcementState =
        synchronized(lock) {
            val now = timeSource.elapsedRealtimeMs()
            val tracked = app?.takeIf { settings.isEnabled(it) }

            // Settle time accrued under the *previous* surface before switching.
            accrue(now)

            val previousAppId = currentAppId
            val nextAppId = tracked?.id
            val nextOnReels = tracked != null && showingReels

            if (previousAppId != null && previousAppId != nextAppId) {
                // Left the app entirely: start its grace window.
                sessions[previousAppId]?.markLeftReels(now)
            }

            currentAppId = nextAppId
            onReels = nextOnReels

            if (nextAppId != null) {
                if (nextOnReels) {
                    enterReels(nextAppId, now)
                } else {
                    sessions[nextAppId]?.markLeftReels(now)
                }
            }

            evaluate(now)
        }

    /**
     * Reports a scroll gesture on the current surface. Ignored unless a short-video surface
     * is showing, so scrolling a message list or a profile never counts.
     */
    public fun onScroll(): EnforcementState = synchronized(lock) {
        val now = timeSource.elapsedRealtimeMs()
        accrue(now)
        val appId = currentAppId
        // Scrolls arriving while a block is in force are ignored outright. The overlay makes
        // them impossible in practice, but recording one would re-open the scrolling window
        // and hand the user free credit the instant the block lifted.
        if (onReels && appId != null && remainingCooldown(appId, now) <= 0) {
            sessions[appId]?.lastScrollMs = now
        }
        evaluate(now)
    }

    /** Advances time. The service calls this on a fixed cadence while a session is live. */
    public fun onTick(): EnforcementState = synchronized(lock) {
        val now = timeSource.elapsedRealtimeMs()
        accrue(now)
        pruneStaleSessions(now)
        evaluate(now)
    }

    /** Milliseconds left on [appId]'s block, or `0` when it is not blocked. */
    public fun cooldownRemainingFor(appId: String): Long = synchronized(lock) {
        remainingCooldown(appId, timeSource.elapsedRealtimeMs())
    }

    /** Active blocks by app id, for display in the app's own UI. */
    public fun activeCooldowns(): Map<String, Long> = synchronized(lock) {
        val now = timeSource.elapsedRealtimeMs()
        cooldownStore.snapshot()
            .mapValues { (_, expiry) -> expiry - now }
            .filterValues { it > 0 }
    }

    /**
     * Ends every block. Exposed for the settings screen's "reset" affordance and for tests —
     * it is deliberately *not* reachable from the block overlay, which has no exit path.
     */
    public fun clearAllCooldowns() {
        synchronized(lock) {
            cooldownStore.snapshot().keys.forEach(cooldownStore::remove)
        }
    }

    // ---------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------

    private fun isEnabled(appId: String): Boolean = appId in settings.enabledAppIds

    /**
     * Credits elapsed time to the current session.
     *
     * Only the part of `[lastSample, now]` that falls inside the active-scrolling window is
     * counted, rather than the whole interval when the window happens to be open. That makes
     * the result independent of how often [onTick] fires, so the service is free to slow its
     * cadence — or miss ticks entirely under load — without changing when a user is blocked.
     */
    private fun accrue(now: Long) {
        val appId = currentAppId ?: return
        val session = sessions[appId] ?: return

        val from = session.lastSampleMs
        session.lastSampleMs = now

        if (!onReels) return
        // A blocked surface cannot be scrolled, so it must not accrue toward the next block.
        if (remainingCooldown(appId, now) > 0) return

        // No open scrolling window: the user has not swiped since the session was cleared.
        val lastScroll = session.lastScrollMs ?: return

        val activeUntil = lastScroll + policy.scrollIdleTimeoutMs
        val creditedEnd = minOf(now, activeUntil)
        if (creditedEnd > from) {
            session.activeMs += creditedEnd - from
        }
    }

    /**
     * Begins or resumes a session on [appId]'s short-video surface.
     *
     * Returning within the grace window continues where the user left off; returning later
     * starts fresh. Entering always refreshes the scroll timestamp, because navigating onto
     * the surface is itself an act of intent.
     */
    private fun enterReels(appId: String, now: Long) {
        val existing = sessions[appId]
        if (existing == null) {
            sessions[appId] = Session(startedAtMs = now)
            return
        }
        // Already on the surface. Accessibility events arrive continuously while a feed plays,
        // so treating each one as a fresh entry would keep resetting the scroll timestamp and
        // silently disable the idle timeout.
        val leftAt = existing.leftReelsAtMs ?: return
        if (now - leftAt > policy.sessionGraceMs) existing.restartOnEntry(now) else existing.resume(now)
    }

    /**
     * Derives the state to display, and fires the interrupt when the threshold is crossed.
     *
     * This is where the block is created, so it is intentionally the only place that writes
     * to [cooldownStore].
     */
    private fun evaluate(now: Long): EnforcementState {
        val appId = currentAppId ?: return EnforcementState.Idle

        val remaining = remainingCooldown(appId, now)
        if (remaining > 0) {
            // A block confines itself to the short-video surface. Messages, the main feed and
            // every other app stay reachable, which is the entire point of blocking a section
            // rather than an application.
            return if (onReels) EnforcementState.Blocked(appId, remaining) else EnforcementState.Idle
        }

        if (!onReels) return EnforcementState.Idle
        val session = sessions[appId] ?: return EnforcementState.Idle

        if (session.activeMs >= policy.doomscrollThresholdMs) {
            cooldownStore.put(appId, now + policy.cooldownMs)
            // Clear accrual so the user gets a full allowance once the block lifts, and close
            // the scrolling window: minutes spent facing the overlay are not scrolling, and
            // must not be credited the moment the block expires.
            session.clearAfterInterrupt(now)
            return EnforcementState.Blocked(appId, policy.cooldownMs)
        }

        if (session.activeMs >= policy.countdownStartsAtMs) {
            return EnforcementState.Countdown(
                appId = appId,
                millisRemaining = policy.doomscrollThresholdMs - session.activeMs,
            )
        }

        return EnforcementState.Monitoring(appId, session.activeMs)
    }

    /** Remaining block time for [appId], forgetting the record once it has lapsed. */
    private fun remainingCooldown(appId: String, now: Long): Long {
        val expiry = cooldownStore.expiryFor(appId) ?: return 0
        val remaining = expiry - now
        if (remaining <= 0) {
            cooldownStore.remove(appId)
            return 0
        }
        return remaining
    }

    /**
     * Drops sessions whose grace window has closed.
     *
     * Equivalent to resetting them on return, but it also stops the map growing for a user
     * who opens many apps in one boot.
     */
    private fun pruneStaleSessions(now: Long) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val (appId, session) = iterator.next()
            if (appId == currentAppId) continue
            val leftAt = session.leftReelsAtMs ?: continue
            if (now - leftAt > policy.sessionGraceMs) iterator.remove()
        }
    }

    /**
     * Accrual state for one app.
     *
     * @param leftReelsAtMs when the user navigated off the short-video surface, or `null`
     *   while they are on it. Doubles as the grace-window anchor.
     */
    private class Session(startedAtMs: Long) {
        var activeMs: Long = 0
        var lastSampleMs: Long = startedAtMs

        /**
         * When the user last swiped, or `null` when no scrolling window is open.
         *
         * Arriving on the surface counts as a swipe — navigating there is itself intent — so
         * a new or resumed session starts with the window open.
         */
        var lastScrollMs: Long? = startedAtMs
        var leftReelsAtMs: Long? = null

        fun markLeftReels(now: Long) {
            if (leftReelsAtMs == null) leftReelsAtMs = now
            lastSampleMs = now
        }

        /** Continue an existing session after a short absence. */
        fun resume(now: Long) {
            leftReelsAtMs = null
            lastSampleMs = now
            lastScrollMs = now
        }

        /** Discard accrued time and treat the user's arrival as a fresh start. */
        fun restartOnEntry(now: Long) {
            activeMs = 0
            lastSampleMs = now
            lastScrollMs = now
            leftReelsAtMs = null
        }

        /**
         * Discard accrued time after a block was imposed, leaving no scrolling window open.
         * The user must swipe again before any time counts toward the next block.
         */
        fun clearAfterInterrupt(now: Long) {
            activeMs = 0
            lastSampleMs = now
            lastScrollMs = null
            leftReelsAtMs = null
        }
    }
}
