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

/**
 * A monotonic clock.
 *
 * Implementations **must not** be derived from wall-clock time. The whole enforcement
 * mechanism is a commitment device, and wall-clock time is user-writable: if `System.currentTimeMillis`
 * backed this, ending a five minute block would be as easy as changing the date in Settings.
 * On Android the only correct implementation is `SystemClock.elapsedRealtime()`.
 */
public fun interface TimeSource {
    /** Milliseconds since an arbitrary fixed origin. Never decreases while the device is up. */
    public fun elapsedRealtimeMs(): Long
}
