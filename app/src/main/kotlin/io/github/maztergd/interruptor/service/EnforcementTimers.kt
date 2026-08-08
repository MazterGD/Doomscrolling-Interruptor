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

import io.github.maztergd.interruptor.core.domain.DoomscrollEngine
import io.github.maztergd.interruptor.core.model.AppTimeLeft

/**
 * The one link between the running service and the settings screen.
 *
 * The screen shows live countdowns, and the only place those exist is the engine inside the
 * accessibility service. An enabled accessibility service is hosted by the app's own process,
 * so a plain handle is enough: no binder, no broadcast, and — the point of doing it this way —
 * no second copy of the timing rules that could drift out of step with the one enforcing them.
 *
 * The engine holds no `Activity` or view, so parking a reference here leaks nothing; the
 * service still hands it back on teardown, so a torn-down instance is never read from.
 *
 * [snapshot] returning `null` means the service is not running. That is deliberately distinct
 * from an empty map: with the accessibility permission withdrawn nothing is being timed at all,
 * and the screen says so rather than displaying timers that would never move.
 */
object EnforcementTimers {

    @Volatile
    private var engine: DoomscrollEngine? = null

    fun attach(engine: DoomscrollEngine) {
        this.engine = engine
    }

    /**
     * Releases [engine], if it is still the live one.
     *
     * The identity check matters during a teardown/rebind race: the replacement service can
     * connect before the outgoing one is destroyed, and an unconditional clear would drop the
     * handle to the engine that is now doing the work.
     */
    fun detach(engine: DoomscrollEngine) {
        if (this.engine === engine) this.engine = null
    }

    /** Time left per app id, or `null` when no service is running to count it. */
    fun snapshot(): Map<String, AppTimeLeft>? = engine?.timeLeftByApp()
}
