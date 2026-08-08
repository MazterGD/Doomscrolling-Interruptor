package io.github.maztergd.interruptor.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the user-adjustable timing rules.
 *
 * The point of most of these is that no sequence of adjustments can produce a policy
 * [InterruptPolicy] would refuse to construct — the settings screen has no validation of its
 * own and is not supposed to need any.
 */
class TimingRuleTest {

    private val default = InterruptPolicy.DEFAULT

    // -- the table itself ----------------------------------------------------------------

    @Test
    fun `every rule offers its shipped default, so a fresh install sits on a stop`() {
        TimingRule.entries.forEach { rule ->
            assertTrue(
                "${rule.name} default ${rule.valueIn(default)} is not one of its choices",
                rule.valueIn(default) in rule.choicesMs,
            )
        }
    }

    @Test
    fun `choices are ascending and free of duplicates`() {
        TimingRule.entries.forEach { rule ->
            assertEquals("${rule.name} is not sorted", rule.choicesMs.sorted(), rule.choicesMs)
            assertEquals("${rule.name} repeats a value", rule.choicesMs.distinct(), rule.choicesMs)
        }
    }

    @Test
    fun `the documented minimums are what the rules actually offer`() {
        assertEquals(60_000, TimingRule.Trigger.choicesMs.first())
        assertEquals(5_000, TimingRule.Warning.choicesMs.first())
        assertEquals(60_000, TimingRule.Block.choicesMs.first())
    }

    @Test
    fun `the active-scrolling window can be raised well past its default`() {
        val choices = TimingRule.ActiveWindow.choicesMs
        assertEquals(20_000, TimingRule.ActiveWindow.valueIn(default))
        assertTrue("expected room to raise it, got $choices", choices.last() >= 120_000)
    }

    // -- applying a value ----------------------------------------------------------------

    @Test
    fun `a rule writes only its own field`() {
        val updated = TimingRule.Block.applyTo(default, 60_000)
        assertEquals(60_000, updated.cooldownMs)
        assertEquals(default.copy(cooldownMs = 60_000), updated)
    }

    @Test
    fun `a value between two stops snaps to the nearer one`() {
        assertEquals(
            15_000,
            TimingRule.ActiveWindow.applyTo(default, 16_000).scrollIdleTimeoutMs,
        )
    }

    @Test
    fun `a value beyond the ends is clamped to them`() {
        assertEquals(
            TimingRule.Trigger.choicesMs.last(),
            TimingRule.Trigger.applyTo(default, Long.MAX_VALUE / 2).doomscrollThresholdMs,
        )
        assertEquals(
            TimingRule.Trigger.choicesMs.first(),
            TimingRule.Trigger.applyTo(default, 0).doomscrollThresholdMs,
        )
    }

    @Test
    fun `every choice of every rule produces a policy that constructs`() {
        TimingRule.entries.forEach { rule ->
            rule.choicesMs.forEach { value ->
                // Constructing at all is the assertion: InterruptPolicy throws on an
                // incoherent combination, so an exception here is the failure.
                rule.applyTo(default, value)
            }
        }
    }

    // -- the warning cannot outlast the trigger ------------------------------------------

    @Test
    fun `the warning is not offered at lengths the trigger cannot accommodate`() {
        val shortTrigger = TimingRule.Trigger.applyTo(default, 60_000)
        val offered = TimingRule.Warning.choicesIn(shortTrigger)

        assertTrue("expected some choices, got none", offered.isNotEmpty())
        assertTrue(
            "every offered warning must fit inside a 1 minute trigger, got $offered",
            offered.all { it < shortTrigger.doomscrollThresholdMs },
        )
    }

    @Test
    fun `shortening the trigger past the warning pulls the warning down with it`() {
        val longWarning = TimingRule.Warning.applyTo(default, 60_000)
        assertEquals(60_000, longWarning.countdownMs)

        val shortened = TimingRule.Trigger.applyTo(longWarning, 60_000)

        assertEquals(60_000, shortened.doomscrollThresholdMs)
        assertTrue(
            "warning ${shortened.countdownMs} must fit inside the trigger",
            shortened.countdownMs < shortened.doomscrollThresholdMs,
        )
        // The longest that still fits, rather than a reset to the shipped default.
        assertEquals(45_000, shortened.countdownMs)
    }

    @Test
    fun `the trigger can always be taken to its shortest, whatever the warning is`() {
        // No dead end: the warning slider being at its top must not lock the trigger slider.
        val maxWarning = TimingRule.Warning.applyTo(default, TimingRule.Warning.choicesMs.last())
        val shortest = TimingRule.Trigger.applyTo(maxWarning, TimingRule.Trigger.choicesMs.first())

        assertEquals(TimingRule.Trigger.choicesMs.first(), shortest.doomscrollThresholdMs)
    }

    // -- reading a position back ---------------------------------------------------------

    @Test
    fun `the selected index round-trips through every choice`() {
        TimingRule.entries.forEach { rule ->
            rule.choicesMs.forEachIndexed { index, value ->
                val updated = rule.applyTo(default, value)
                assertEquals(
                    "${rule.name} at $value",
                    index,
                    rule.selectedIndexIn(updated),
                )
            }
        }
    }

    @Test
    fun `a value written by another build still resolves to a position`() {
        // 7 minutes is not on the trigger's list; the slider still has to land somewhere.
        val offGrid = default.copy(doomscrollThresholdMs = 7 * 60_000L)
        val index = TimingRule.Trigger.selectedIndexIn(offGrid)

        assertTrue("index $index is out of bounds", index in TimingRule.Trigger.choicesMs.indices)
        assertEquals(5 * 60_000L, TimingRule.Trigger.choicesMs[index])
    }
}
