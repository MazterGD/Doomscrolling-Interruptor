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

import kotlin.math.abs

/**
 * The parts of [InterruptPolicy] a user may change, and what they may change them to.
 *
 * Each rule carries its own list of offerable values, so the settings screen renders itself
 * from this table rather than hard-coding four sets of bounds. Adding a knob means adding an
 * entry here and a label in the UI; no arithmetic anywhere has to change.
 *
 * ### Why a list of choices rather than a range and a step
 *
 * A linear one-minute step from one minute to ninety would put a hundred-odd stops under a
 * finger-width of slider, which is unusable on a phone and offers precision nobody wants —
 * the difference between an eleven and a twelve minute allowance is not a decision anyone is
 * making. Round numbers, spaced more widely as they grow, are both easier to hit and the only
 * values a user would actually pick.
 *
 * The shipped defaults in [InterruptPolicy.DEFAULT] all appear in their rule's list, so a
 * fresh install starts on a stop rather than between two.
 *
 * ### Staying coherent
 *
 * [InterruptPolicy] rejects a warning at least as long as the trigger it precedes, so the two
 * cannot be adjusted independently. Rather than let the screen offer a choice its own model
 * would throw on, [choicesIn] hides the values that do not currently fit and [applyTo] pulls
 * the counterpart along when one is moved past the other. Every value this class hands out is
 * therefore constructible, which keeps the `require` in [InterruptPolicy] an assertion about
 * the code rather than a validation step the UI has to duplicate.
 *
 * @param choicesMs offerable values, ascending, in milliseconds.
 * @param mustStayBelow the field this rule has to remain strictly under, if any.
 */
public enum class TimingRule(
    public val choicesMs: List<Long>,
    private val read: (InterruptPolicy) -> Long,
    private val write: (InterruptPolicy, Long) -> InterruptPolicy,
    private val mustStayBelow: ((InterruptPolicy) -> Long)? = null,
) {

    /** How much active scrolling a feed allows before it pauses. */
    Trigger(
        choicesMs = minutes(1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90),
        read = InterruptPolicy::doomscrollThresholdMs,
        write = { policy, value -> policy.copy(doomscrollThresholdMs = value) },
    ),

    /** How long the countdown runs before the pause lands. */
    Warning(
        choicesMs = seconds(5, 10, 15, 20, 30, 45, 60),
        read = InterruptPolicy::countdownMs,
        write = { policy, value -> policy.copy(countdownMs = value) },
        mustStayBelow = InterruptPolicy::doomscrollThresholdMs,
    ),

    /** How long the feed stays paused afterwards. */
    Block(
        choicesMs = minutes(1, 2, 3, 5, 10, 15, 20, 30, 45, 60),
        read = InterruptPolicy::cooldownMs,
        write = { policy, value -> policy.copy(cooldownMs = value) },
    ),

    /**
     * How long after a swipe still counts as scrolling.
     *
     * Raising it keeps the timer running through longer videos; lowering it means only rapid
     * swiping accrues. It is the one rule that changes what the app *considers* doomscrolling
     * rather than how much of it is allowed.
     */
    ActiveWindow(
        choicesMs = seconds(5, 10, 15, 20, 30, 45, 60, 90, 120),
        read = InterruptPolicy::scrollIdleTimeoutMs,
        write = { policy, value -> policy.copy(scrollIdleTimeoutMs = value) },
    );

    /** This rule's current value in [policy]. */
    public fun valueIn(policy: InterruptPolicy): Long = read(policy)

    /**
     * The values offerable for this rule, given what the rest of [policy] is set to.
     *
     * Usually the whole list. A rule with a [mustStayBelow] counterpart loses the choices that
     * no longer fit under it — the warning cannot be offered at a minute while the trigger is
     * one minute, because a countdown starting before the session is meaningless.
     */
    public fun choicesIn(policy: InterruptPolicy): List<Long> {
        val ceiling = mustStayBelow?.invoke(policy) ?: return choicesMs
        // A stored policy from another build could in principle sit below even the shortest
        // choice. Offering one value the user cannot move off beats offering none at all.
        return choicesMs.filter { it < ceiling }.ifEmpty { choicesMs.take(1) }
    }

    /**
     * Where [policy] currently sits in [choicesIn], as an index.
     *
     * The *nearest* choice, not an exact match: a value written by an older build may fall
     * between two stops, and a slider still has to be somewhere.
     */
    public fun selectedIndexIn(policy: InterruptPolicy): Int {
        val choices = choicesIn(policy)
        val current = valueIn(policy)
        return choices.indices.minByOrNull { abs(choices[it] - current) } ?: 0
    }

    /**
     * [policy] with this rule moved to the offerable choice nearest [valueMs].
     *
     * Snapping rather than rejecting means a caller may pass a raw slider position without
     * knowing the table. The result is always a valid policy.
     */
    public fun applyTo(policy: InterruptPolicy, valueMs: Long): InterruptPolicy {
        val choice = choicesIn(policy).minByOrNull { abs(it - valueMs) } ?: return policy
        if (this != Trigger || policy.countdownMs < choice) return write(policy, choice)

        // Shortening the trigger past the warning already set. Refusing would strand the user
        // — the slider would stop responding at its lower end — so the warning gives way
        // instead, to the longest one that still fits. It has to give way *first*: an
        // InterruptPolicy holding both old values at once cannot be constructed, so there is
        // no intermediate copy to repair afterwards.
        val warning = Warning.choicesMs.lastOrNull { it < choice } ?: return policy
        return write(policy.copy(countdownMs = warning), choice)
    }
}

private fun seconds(vararg values: Int): List<Long> = values.map { it * 1_000L }

private fun minutes(vararg values: Int): List<Long> = values.map { it * 60_000L }
