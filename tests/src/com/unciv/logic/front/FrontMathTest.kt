package com.unciv.logic.front

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontMathTest {
    private val delta = 0.001f

    @Test
    fun `base strength grows with the military technologies`() {
        assertEquals(20f, FrontMath.baseStrength(0f), delta)
        assertEquals(30f, FrontMath.baseStrength(2f), delta)
        assertEquals(20f, FrontMath.baseStrength(FrontMath.militaryLevel(0, 40)), delta)
        assertEquals(55f, FrontMath.baseStrength(FrontMath.militaryLevel(40, 40)), delta)
        assertEquals(37.5f, FrontMath.baseStrength(FrontMath.militaryLevel(20, 40)), delta)
        assertEquals(FrontMath.MAX_MILITARY_LEVEL, FrontMath.militaryLevel(99, 40), delta)
        assertEquals(0f, FrontMath.militaryLevel(3, 0), delta)
    }

    @Test
    fun `equal forces with moderate coefficients each lose the base loss in an attack`() {
        val loss = FrontMath.loss(opposingForce = 10f, ownForce = 10f, share = 1f,
            taken = FrontStance.Moderate.taken, dealtByOpponent = FrontStance.Moderate.dealt)
        assertEquals(FrontMath.BASE_LOSS, loss, delta)
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
        val full = FrontMath.pressure(100, 0f, FrontStance.Moderate, 1f)
        assertEquals(20f, full, delta)
        assertEquals(10f, FrontMath.pressure(50, 0f, FrontStance.Moderate, 1f), delta)
        assertEquals(30f, FrontMath.pressure(100, 0f, FrontStance.Aggressive, 1f), delta)
        assertEquals(0f, FrontMath.pressure(100, 0f, FrontStance.Defensive, 1f), delta)
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
