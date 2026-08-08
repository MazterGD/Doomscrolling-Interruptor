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
 * What one app is counting down to, for display on the settings screen.
 *
 * Two clocks matter to a user and only one applies at a time: a paused app is counting down
 * to unlocking, and every other watched app is counting down to its next interrupt. Both are
 * a duration in milliseconds, so the distinction is carried by the type rather than by a flag
 * the caller could forget to read.
 *
 * This is a *view* of the engine's state, not an input to it. Nothing here decides anything;
 * [EnforcementState] remains the only thing the service acts on.
 */
public sealed interface AppTimeLeft {

    public val millisRemaining: Long

    /**
     * How much more *active scrolling* the app allows before its short-video feed pauses.
     *
     * Wall-clock time is not what is being counted: this shrinks only while the user is
     * swiping, so a figure that sits still is telling the truth.
     */
    public data class UntilPause(override val millisRemaining: Long) : AppTimeLeft

    /** The feed is paused; how long until it unlocks. This one does run down on its own. */
    public data class UntilUnlock(override val millisRemaining: Long) : AppTimeLeft
}
