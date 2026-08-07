package io.github.maztergd.interruptor.core.domain

import io.github.maztergd.interruptor.core.model.EnforcementState
import io.github.maztergd.interruptor.core.model.InterruptPolicy
import io.github.maztergd.interruptor.core.model.InterruptorSettings
import io.github.maztergd.interruptor.core.model.TargetAppCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for the enforcement rules.
 *
 * These run against the real policy shape but with short durations, so a "10 minute"
 * threshold is expressed as 10 seconds and the whole suite finishes instantly.
 */
class DoomscrollEngineTest {

    private val instagram = TargetAppCatalog.instagram
    private val youtube = TargetAppCatalog.youtube

    private val policy = InterruptPolicy(
        doomscrollThresholdMs = 10_000,
        countdownMs = 1_000,
        cooldownMs = 5_000,
        scrollIdleTimeoutMs = 2_000,
        sessionGraceMs = 3_000,
    )

    private val time = FakeTimeSource()
    private val cooldowns = InMemoryCooldownStore()
    private val engine = DoomscrollEngine(
        timeSource = time,
        cooldownStore = cooldowns,
        initialSettings = InterruptorSettings(
            enabledAppIds = setOf(instagram.id, youtube.id),
            policy = policy,
        ),
    )

    /** Simulates a user scrolling continuously for [durationMs], swiping every second. */
    private fun scrollFor(durationMs: Long): EnforcementState {
        var state: EnforcementState = engine.onTick()
        var elapsed = 0L
        while (elapsed < durationMs) {
            time.advance(1_000)
            elapsed += 1_000
            state = engine.onScroll()
        }
        return state
    }

    // -- accrual -----------------------------------------------------------------------

    @Test
    fun `untracked app produces no state`() {
        assertEquals(EnforcementState.Idle, engine.onSurface(app = null, showingReels = false))
    }

    @Test
    fun `being in a tracked app but off the video surface is idle`() {
        assertEquals(EnforcementState.Idle, engine.onSurface(instagram, showingReels = false))
    }

    @Test
    fun `scrolling the video surface accrues time`() {
        engine.onSurface(instagram, showingReels = true)
        val state = scrollFor(4_000)
        val monitoring = state as EnforcementState.Monitoring
        assertEquals(instagram.id, monitoring.appId)
        assertEquals(4_000, monitoring.activeMs)
    }

    @Test
    fun `pausing on a single video stops accrual after the idle timeout`() {
        engine.onSurface(instagram, showingReels = true)
        scrollFor(3_000)

        // Sit on one video for far longer than the idle timeout without swiping.
        time.advance(30_000)
        val state = engine.onTick()

        // Only the 2s idle grace after the final swipe is credited, not the full 30s.
        assertEquals(5_000, (state as EnforcementState.Monitoring).activeMs)
    }

    @Test
    fun `accrual does not depend on tick frequency`() {
        engine.onSurface(instagram, showingReels = true)
        engine.onScroll()
        // One huge gap with no intermediate ticks: only the active window may be credited.
        time.advance(60_000)
        val state = engine.onTick()
        assertEquals(policy.scrollIdleTimeoutMs, (state as EnforcementState.Monitoring).activeMs)
    }

    // -- countdown and interrupt -------------------------------------------------------

    @Test
    fun `countdown appears in the final stretch before the threshold`() {
        engine.onSurface(instagram, showingReels = true)
        val state = scrollFor(9_000)
        val countdown = state as EnforcementState.Countdown
        assertEquals(instagram.id, countdown.appId)
        assertEquals(1_000, countdown.millisRemaining)
    }

    @Test
    fun `crossing the threshold blocks the app`() {
        engine.onSurface(instagram, showingReels = true)
        val state = scrollFor(10_000)
        val blocked = state as EnforcementState.Blocked
        assertEquals(instagram.id, blocked.appId)
        assertEquals(policy.cooldownMs, blocked.cooldownRemainingMs)
    }

    @Test
    fun `block persists across leaving and re-entering the video surface`() {
        engine.onSurface(instagram, showingReels = true)
        scrollFor(10_000)

        engine.onSurface(instagram, showingReels = false)
        time.advance(2_000)
        val state = engine.onSurface(instagram, showingReels = true)

        val blocked = state as EnforcementState.Blocked
        assertEquals(3_000, blocked.cooldownRemainingMs)
    }

    @Test
    fun `block expires after the cooldown and a fresh allowance begins`() {
        engine.onSurface(instagram, showingReels = true)
        scrollFor(10_000)

        time.advance(policy.cooldownMs + 1)
        val afterCooldown = engine.onTick()

        // Still on the surface, but the user must scroll again before time accrues.
        assertTrue("expected monitoring, was $afterCooldown", afterCooldown is EnforcementState.Monitoring)
        assertEquals(0, (afterCooldown as EnforcementState.Monitoring).activeMs)

        // The full threshold is required again, not the remainder of the last session. The
        // first swipe only re-opens the scrolling window, so it takes one interval longer.
        val nearThreshold = scrollFor(9_000)
        assertTrue("expected still monitoring, was $nearThreshold", nearThreshold is EnforcementState.Monitoring)

        val next = scrollFor(2_000)
        assertTrue("expected a second block, was $next", next is EnforcementState.Blocked)
    }

    @Test
    fun `no time accrues while blocked`() {
        engine.onSurface(instagram, showingReels = true)
        scrollFor(10_000)

        // Even if scroll events somehow arrive under the overlay, they must not count.
        repeat(3) {
            time.advance(1_000)
            engine.onScroll()
        }
        time.advance(policy.cooldownMs)
        val state = engine.onTick()
        assertEquals(0, (state as EnforcementState.Monitoring).activeMs)
    }

    // -- the block is scoped to the video surface only ---------------------------------

    @Test
    fun `messages and the rest of the app stay reachable while blocked`() {
        engine.onSurface(instagram, showingReels = true)
        scrollFor(10_000)

        // Navigating to direct messages: same app, not the video surface.
        val state = engine.onSurface(instagram, showingReels = false)
        assertEquals(EnforcementState.Idle, state)
    }

    @Test
    fun `other apps stay reachable while one app is blocked`() {
        engine.onSurface(instagram, showingReels = true)
        scrollFor(10_000)

        assertEquals(EnforcementState.Idle, engine.onSurface(app = null, showingReels = false))

        val onYoutube = engine.onSurface(youtube, showingReels = true)
        assertTrue("YouTube must not inherit Instagram's block, was $onYoutube", onYoutube is EnforcementState.Monitoring)
    }

    @Test
    fun `blocks are tracked per app`() {
        engine.onSurface(instagram, showingReels = true)
        scrollFor(10_000)

        assertTrue(engine.cooldownRemainingFor(instagram.id) > 0)
        assertEquals(0, engine.cooldownRemainingFor(youtube.id))
    }

    @Test
    fun `time in one app does not push another toward its threshold`() {
        engine.onSurface(instagram, showingReels = true)
        scrollFor(9_000)

        val state = engine.onSurface(youtube, showingReels = true)
        assertEquals(0, (state as EnforcementState.Monitoring).activeMs)
    }

    // -- grace window ------------------------------------------------------------------

    @Test
    fun `a brief detour off the surface preserves accrued time`() {
        engine.onSurface(instagram, showingReels = true)
        scrollFor(9_000)

        engine.onSurface(instagram, showingReels = false)
        time.advance(policy.sessionGraceMs - 500)
        val state = engine.onSurface(instagram, showingReels = true)

        // Progress survives, so bouncing to the feed and back cannot reset the timer.
        assertEquals(9_000, (state as EnforcementState.Countdown).let { policy.doomscrollThresholdMs - it.millisRemaining })
    }

    @Test
    fun `a long absence resets the session`() {
        engine.onSurface(instagram, showingReels = true)
        scrollFor(9_000)

        engine.onSurface(instagram, showingReels = false)
        time.advance(policy.sessionGraceMs + 1)
        val state = engine.onSurface(instagram, showingReels = true)

        assertEquals(0, (state as EnforcementState.Monitoring).activeMs)
    }

    @Test
    fun `switching apps and returning within grace preserves progress`() {
        engine.onSurface(instagram, showingReels = true)
        scrollFor(9_000)

        engine.onSurface(youtube, showingReels = true)
        time.advance(1_000)
        engine.onScroll()

        val back = engine.onSurface(instagram, showingReels = true)
        assertTrue("expected countdown, was $back", back is EnforcementState.Countdown)
    }

    // -- settings ----------------------------------------------------------------------

    @Test
    fun `disabled apps are not monitored`() {
        engine.updateSettings(
            InterruptorSettings(enabledAppIds = setOf(youtube.id), policy = policy),
        )
        engine.onSurface(instagram, showingReels = true)
        val state = scrollFor(15_000)
        assertEquals(EnforcementState.Idle, state)
    }

    @Test
    fun `disabling an app mid-session stops enforcement immediately`() {
        engine.onSurface(instagram, showingReels = true)
        scrollFor(9_000)

        engine.updateSettings(
            InterruptorSettings(enabledAppIds = setOf(youtube.id), policy = policy),
        )
        assertEquals(EnforcementState.Idle, engine.onTick())
    }

    @Test
    fun `clearing cooldowns unblocks every app`() {
        engine.onSurface(instagram, showingReels = true)
        scrollFor(10_000)
        assertTrue(engine.cooldownRemainingFor(instagram.id) > 0)

        engine.clearAllCooldowns()
        assertEquals(0, engine.cooldownRemainingFor(instagram.id))
    }

    @Test
    fun `active cooldowns are reported for the UI`() {
        engine.onSurface(instagram, showingReels = true)
        scrollFor(10_000)

        val active = engine.activeCooldowns()
        assertEquals(setOf(instagram.id), active.keys)
        assertTrue(active.getValue(instagram.id) in 1..policy.cooldownMs)
    }

    // -- persistence port --------------------------------------------------------------

    @Test
    fun `a block restored from storage is enforced on the next session`() {
        val restored = InMemoryCooldownStore(mapOf(instagram.id to 4_000L))
        val restoredEngine = DoomscrollEngine(
            timeSource = time,
            cooldownStore = restored,
            initialSettings = InterruptorSettings(setOf(instagram.id), policy),
        )
        val state = restoredEngine.onSurface(instagram, showingReels = true)
        assertEquals(4_000, (state as EnforcementState.Blocked).cooldownRemainingMs)
    }

    @Test
    fun `a lapsed block is forgotten rather than enforced`() {
        val stale = InMemoryCooldownStore(mapOf(instagram.id to 0L))
        val restoredEngine = DoomscrollEngine(
            timeSource = FakeTimeSource(nowMs = 10_000),
            cooldownStore = stale,
            initialSettings = InterruptorSettings(setOf(instagram.id), policy),
        )
        val state = restoredEngine.onSurface(instagram, showingReels = true)
        assertTrue("expected monitoring, was $state", state is EnforcementState.Monitoring)
        assertTrue("stale entry should be evicted", stale.snapshot().isEmpty())
    }
}
