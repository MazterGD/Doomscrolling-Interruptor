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
package io.github.maztergd.interruptor.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.maztergd.interruptor.core.domain.DoomscrollEngine
import io.github.maztergd.interruptor.core.domain.TimeSource
import io.github.maztergd.interruptor.core.domain.matches
import io.github.maztergd.interruptor.core.model.EnforcementState
import io.github.maztergd.interruptor.core.model.TargetApp
import io.github.maztergd.interruptor.core.model.TargetAppCatalog
import io.github.maztergd.interruptor.data.PersistentCooldownStore
import io.github.maztergd.interruptor.data.SettingsRepository
import io.github.maztergd.interruptor.service.overlay.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The always-on component. Watches the supported apps, times short-video sessions, and puts
 * the block on screen when one runs too long.
 *
 * An accessibility service is used rather than a foreground service because it is the only
 * API that can tell *which part of an app* is on screen. Usage-stats APIs report that
 * Instagram is open, not that Reels is; without that distinction the app could only limit
 * whole applications, which is precisely what this project set out not to do.
 *
 * ### How the work is divided
 *
 * - **Events** drive the accurate path. A window change triggers a bounded scan of the
 *   element tree to decide whether the short-video surface is showing. Scans are throttled
 *   and coalesced, because a playing feed emits content-change events continuously.
 * - **A timer** drives the cheap path. It advances the clock and checks only the foreground
 *   package. This matters most while a block is displayed: the manifest restricts events to
 *   the supported apps, so leaving for the launcher produces no event at all, and polling is
 *   what takes the overlay down.
 *
 * Everything runs on the main thread, which is where accessibility callbacks arrive. The
 * scan is bounded precisely so that staying here is safe.
 */
class DoomscrollAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var overlays: OverlayController
    private lateinit var cooldownStore: PersistentCooldownStore
    private lateinit var engine: DoomscrollEngine

    private var lastScanUptimeMs = 0L
    private var scanScheduled = false

    /** Foreground package as of the last full scan, used to detect app switches on the tick path. */
    private var lastScannedPackage: String? = null

    private val tickRunnable = Runnable { onTick() }
    private val coalescedScanRunnable = Runnable {
        scanScheduled = false
        scanSurface()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        overlays = OverlayController(this)
        val settingsRepository = SettingsRepository(applicationContext)
        cooldownStore = PersistentCooldownStore(applicationContext, scope)
        engine = DoomscrollEngine(
            timeSource = TimeSource { SystemClock.elapsedRealtime() },
            cooldownStore = cooldownStore,
        )

        scope.launch {
            // Settings first: restoring a block needs the configured cooldown length to clamp
            // against, and enforcing anything before both are loaded risks acting on defaults
            // the user has overridden.
            val initial = settingsRepository.settings.first()
            engine.updateSettings(initial)
            cooldownStore.hydrate(maxRestorableMs = initial.policy.cooldownMs)
            scanSurface()

            settingsRepository.settings.collect { settings ->
                engine.updateSettings(settings)
                scanSurface()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString() ?: return
        // Our own overlay windows generate events too. Treating one as a foreground change
        // would make the block conclude the user had left, and dismiss itself.
        if (eventPackage == packageName) return
        // onServiceConnected always precedes event delivery, but an event arriving during a
        // teardown/rebind race must not hit an uninitialised field.
        if (!::cooldownStore.isInitialized || !cooldownStore.isHydrated()) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (TargetAppCatalog.forPackage(eventPackage) != null) render(engine.onScroll())
            }

            else -> requestScan()
        }
    }

    override fun onInterrupt() {
        overlays.hideAll()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        handler.removeCallbacksAndMessages(null)
        if (::overlays.isInitialized) overlays.hideAll()
        scope.cancel()
    }

    // ---------------------------------------------------------------------------------
    // Surface evaluation
    // ---------------------------------------------------------------------------------

    /**
     * Requests a scan, rate-limited.
     *
     * A burst of events collapses into one scan now and, if more arrive during the window,
     * exactly one more afterwards — so the final state of a burst is never the one dropped.
     */
    private fun requestScan() {
        val now = SystemClock.uptimeMillis()
        if (now - lastScanUptimeMs >= SCAN_THROTTLE_MS) {
            scanSurface()
            return
        }
        if (!scanScheduled) {
            scanScheduled = true
            handler.postDelayed(coalescedScanRunnable, SCAN_THROTTLE_MS)
        }
    }

    /** Reads what is on screen and hands it to the engine. */
    private fun scanSurface() {
        if (!cooldownStore.isHydrated()) return
        lastScanUptimeMs = SystemClock.uptimeMillis()

        val root = activeApplicationRoot()
        val foregroundPackage = root?.packageName?.toString()
        val app = foregroundPackage?.let(TargetAppCatalog::forPackage)
        lastScannedPackage = foregroundPackage

        val state = if (root == null || app == null) {
            engine.onSurface(app = null, showingReels = false)
        } else {
            val snapshot = ScreenSnapshotFactory.capture(
                root = root,
                screenHeightPx = resources.displayMetrics.heightPixels,
            )
            engine.onSurface(app = app, showingReels = app.reelsSignal.matches(snapshot))
        }
        render(state)
    }

    /**
     * The root of the topmost application window that is not our own.
     *
     * `rootInActiveWindow` alone is not enough: while the block is displayed, the focused
     * window belongs to this app, and trusting it would mean the service could no longer see
     * the app it is blocking. Falling back to an explicit window scan keeps the underlying
     * surface observable — accessibility services may introspect windows beneath a touchable
     * overlay for exactly this reason.
     */
    private fun activeApplicationRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName?.toString() != packageName) return it }

        return windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() != packageName }
    }

    // ---------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------

    private fun render(state: EnforcementState) {
        when (state) {
            EnforcementState.Idle, is EnforcementState.Monitoring -> overlays.hideAll()

            is EnforcementState.Countdown ->
                overlays.showCountdown(displayNameOf(state.appId), state.millisRemaining)

            is EnforcementState.Blocked ->
                overlays.showBlock(displayNameOf(state.appId), state.cooldownRemainingMs)
        }
        scheduleNextTick(state)
    }

    private fun displayNameOf(appId: String): String =
        TargetAppCatalog.forId(appId)?.displayName ?: appId

    /**
     * The cheap periodic path: advance time, and notice if the user has left.
     *
     * A full tree scan is not repeated here. Staying on a video feed generates a steady
     * stream of content-change events, which keep the accurate path warm; the timer only has
     * to catch the case that produces no events at all — the user leaving for another app.
     */
    private fun onTick() {
        if (!cooldownStore.isHydrated()) return

        val foregroundPackage = activeApplicationRoot()?.packageName?.toString()

        // A different app is in front than the one the engine is timing. Which surface of it
        // is showing is unknown, so fall back to the accurate path rather than crediting the
        // previous app for time spent here.
        if (foregroundPackage != lastScannedPackage) {
            scanSurface()
            return
        }

        val app: TargetApp? = foregroundPackage?.let(TargetAppCatalog::forPackage)
        val state = if (app == null) engine.onSurface(app = null, showingReels = false) else engine.onTick()
        render(state)
    }

    /**
     * Ticks only while there is something to do, at a cadence matched to what is on screen.
     * An idle device schedules nothing at all.
     */
    private fun scheduleNextTick(state: EnforcementState) {
        handler.removeCallbacks(tickRunnable)
        val delay = when (state) {
            EnforcementState.Idle -> return
            is EnforcementState.Monitoring -> MONITORING_TICK_MS
            is EnforcementState.Countdown -> COUNTDOWN_TICK_MS
            is EnforcementState.Blocked -> BLOCKED_TICK_MS
        }
        handler.postDelayed(tickRunnable, delay)
    }

    private companion object {
        /** Upper bound on how often the element tree is walked. */
        const val SCAN_THROTTLE_MS = 250L

        const val MONITORING_TICK_MS = 1_000L

        /** Fast enough that the displayed second is never visibly stale. */
        const val COUNTDOWN_TICK_MS = 200L

        const val BLOCKED_TICK_MS = 500L
    }
}
