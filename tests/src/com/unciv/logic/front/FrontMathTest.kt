package com.unciv.logic.front

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontMathTest {
    private val delta = 0.001f

    @Test
    fun `base strength and width grow with the era`() {
        assertEquals(20f, FrontMath.baseStrength(0), delta)
        assertEquals(30f, FrontMath.baseStrength(2), delta)
        assertEquals(3, FrontMath.frontWidth(0))
        assertEquals(4, FrontMath.frontWidth(2))
        assertEquals(6, FrontMath.frontWidth(6))
    }

    @Test
    fun `progress needs a ratio above the threshold`() {
        assertEquals(0f, FrontMath.progressGain(0.5f), delta)
        assertEquals(0f, FrontMath.progressGain(0.7f), delta)
        assertEquals(7.5f, FrontMath.progressGain(1.0f), delta)
        assertEquals(25f, FrontMath.progressGain(1.7f), delta)
    }

    @Test
    fun `a ratio of one point seven takes four rounds per tile`() {
        var progress = 0f
        var rounds = 0
        while (progress < FrontMath.FLIP_PROGRESS) { progress += FrontMath.progressGain(1.7f); rounds++ }
        assertEquals(4, rounds)
    }

    @Test
    fun `idle progress fades and then disappears`() {
        assertEquals(40f, FrontMath.idleProgress(50f), delta)
        assertEquals(0f, FrontMath.idleProgress(1.2f), delta)
        assertEquals(0f, FrontMath.idleProgress(0f), delta)
    }

    @Test
    fun `equal forces in moderate postures each lose four points on a tile`() {
        val loss = FrontMath.loss(opposingForce = 10f, ownForce = 10f, share = 1f,
            taken = FrontStance.Moderate.taken, dealtByOpponent = FrontStance.Moderate.dealt)
        assertEquals(4f, loss, delta)
    }

    @Test
    fun `the weaker side loses more`() {
        val strong = FrontMath.loss(opposingForce = 10f, ownForce = 30f, share = 1f, taken = 1f, dealtByOpponent = 1f)
        val weak = FrontMath.loss(opposingForce = 30f, ownForce = 10f, share = 1f, taken = 1f, dealtByOpponent = 1f)
        assertTrue(weak > strong)
    }

    @Test
    fun `an aggressive posture suffers and inflicts more than a moderate one`() {
        val moderate = FrontMath.loss(10f, 10f, 1f, FrontStance.Moderate.taken, 1f)
        val aggressive = FrontMath.loss(10f, 10f, 1f, FrontStance.Aggressive.taken, 1f)
        assertTrue(aggressive > moderate)
        val hitByAggressive = FrontMath.loss(10f, 10f, 1f, 1f, FrontStance.Aggressive.dealt)
        assertTrue(hitByAggressive > moderate)
    }

    @Test
    fun `holding the line suffers least and withdrawing preserves the most`() {
        val taken = FrontStance.entries.associateWith { it.taken }
        assertTrue(taken[FrontStance.Withdrawal]!! < taken[FrontStance.Defensive]!!)
        assertTrue(taken[FrontStance.Defensive]!! < taken[FrontStance.Moderate]!!)
        assertTrue(taken[FrontStance.Moderate]!! < taken[FrontStance.Aggressive]!!)
    }

    @Test
    fun `only the offensive postures press`() {
        assertTrue(FrontStance.Moderate.presses)
        assertTrue(FrontStance.Aggressive.presses)
        assertFalse(FrontStance.Defensive.presses)
        assertFalse(FrontStance.Withdrawal.presses)
    }

    @Test
    fun `entrenchment grows to its cap and resets when the division moves`() {
        var e = 0f
        repeat(20) { e = FrontMath.nextEntrenchment(e, FrontStance.Defensive, moved = false) }
        assertEquals(FrontStance.Defensive.entrenchmentCap, e, delta)
        assertEquals(0f, FrontMath.nextEntrenchment(e, FrontStance.Defensive, moved = true), delta)
        assertEquals(0f, FrontMath.nextEntrenchment(e, FrontStance.Moderate, moved = false), delta)
    }

    @Test
    fun `an uncovered tile resists as a militia, adjusted by terrain and population`() {
        val plain = FrontMath.tileResistance(0f, hasDefenders = false, tileDefenseBonus = 0f, culturalSympathy = 0f)
        assertEquals(FrontMath.BASE_RESISTANCE, plain, delta)
        val hills = FrontMath.tileResistance(0f, hasDefenders = false, tileDefenseBonus = 0.25f, culturalSympathy = 0f)
        assertEquals(FrontMath.BASE_RESISTANCE * 1.25f, hills, delta)
        val defended = FrontMath.tileResistance(40f, hasDefenders = true, tileDefenseBonus = 0f, culturalSympathy = 0.3f)
        assertEquals(52f, defended, delta)
    }

    @Test
    fun `cultural sympathy uses the fork bands`() {
        assertEquals(0.3f, FrontMath.culturalSympathy(0.9f), delta)
        assertEquals(0f, FrontMath.culturalSympathy(0.5f), delta)
        assertEquals(-0.3f, FrontMath.culturalSympathy(0.1f), delta)
    }

    @Test
    fun `supply weakens pressure with distance`() {
        assertEquals(1f, FrontMath.supplyFactor(2f), delta)
        assertTrue(FrontMath.supplyFactor(6f) < 1f)
        assertTrue(FrontMath.supplyFactor(10f) < FrontMath.supplyFactor(6f))
        assertTrue(FrontMath.supplyFactor(20f) < FrontMath.supplyFactor(10f))
    }

    @Test
    fun `pressure scales with health, stance and supply`() {
        val full = FrontMath.pressure(100, 0, FrontStance.Moderate, 1f)
        assertEquals(20f, full, delta)
        assertEquals(10f, FrontMath.pressure(50, 0, FrontStance.Moderate, 1f), delta)
        assertEquals(30f, FrontMath.pressure(100, 0, FrontStance.Aggressive, 1f), delta)
        assertEquals(0f, FrontMath.pressure(100, 0, FrontStance.Defensive, 1f), delta)
    }

    @Test
    fun `the army the AI keeps follows the number of cities`() {
        assertEquals(3, FrontMath.maxDivisions(1))
        assertEquals(9, FrontMath.maxDivisions(4))
        assertTrue(FrontMath.maxDivisions(0) > 0)
    }

    @Test
    fun `unknown stance names fall back to holding the line`() {
        assertEquals(FrontStance.Defensive, FrontStance.fromName(null))
        assertEquals(FrontStance.Defensive, FrontStance.fromName("nonsense"))
        assertEquals(FrontStance.Aggressive, FrontStance.fromName("Aggressive"))
    }
}
