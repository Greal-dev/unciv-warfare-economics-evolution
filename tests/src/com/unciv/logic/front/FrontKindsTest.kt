package com.unciv.logic.front

import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class FrontKindsTest {
    private val testGame = TestGame()

    @Test
    fun `cavalry and armour strike harder than infantry in the aggressive posture`() {
        val infantry = FrontMath.pressure(100, 0f, FrontStance.Aggressive, 1f, DivisionKind.Infantry)
        val cavalry = FrontMath.pressure(100, 0f, FrontStance.Aggressive, 1f, DivisionKind.Cavalry)
        val armored = FrontMath.pressure(100, 0f, FrontStance.Aggressive, 1f, DivisionKind.Armored)
        assertTrue(cavalry > infantry)
        assertTrue(armored > cavalry)
    }

    @Test
    fun `cavalry and armour hold a line worse than infantry but fall back better`() {
        for (kind in listOf(DivisionKind.Cavalry, DivisionKind.Armored)) {
            assertTrue(FrontMath.resistance(100, 0f, FrontStance.Defensive, 0f, kind) <
                FrontMath.resistance(100, 0f, FrontStance.Defensive, 0f, DivisionKind.Infantry))
            assertTrue(FrontMath.resistance(100, 0f, FrontStance.Withdrawal, 0f, kind) >
                FrontMath.resistance(100, 0f, FrontStance.Withdrawal, 0f, DivisionKind.Infantry))
        }
    }

    @Test
    fun `the three kinds are told apart and take two, three and four turns`() {
        assertEquals(DivisionKind.Infantry, DivisionKind.fromUnitName("Division"))
        assertEquals(DivisionKind.Cavalry, DivisionKind.fromUnitName("Cavalry Division"))
        assertEquals(DivisionKind.Armored, DivisionKind.fromUnitName("Armored Division"))
        assertEquals(null, DivisionKind.fromUnitName("Warrior"))
        assertEquals(listOf(2, 3, 4), DivisionKind.entries.map { it.buildTurns })
    }

    @Test
    fun `the ruleset defines the three divisions with their technology and resource`() {
        val units = testGame.ruleset.units
        assertEquals("Horseback Riding", units["Cavalry Division"]!!.requiredTech)
        assertEquals("Horses", units["Cavalry Division"]!!.requiredResource)
        assertEquals("Oil", units["Armored Division"]!!.requiredResource)
        val firstArmouredTech = units.values.filter { it.type.name == "Armored" && !FrontResolver.isDivisionName(it.name) }
            .mapNotNull { it.requiredTech }.toSet()
        assertTrue("the armoured division comes with the first armoured unit", units["Armored Division"]!!.requiredTech in firstArmouredTech)
        assertNotNull(units["Division"])
        assertEquals(null, units["Division"]!!.requiredResource)
    }

    @Test
    fun `a division is produced in the same number of turns whatever the production of the city`() {
        testGame.makeHexagonalMap(3)
        testGame.gameInfo.gameParameters.frontMode = true
        val civ = testGame.addCiv()
        val city = testGame.addCity(civ, testGame.getTile(HexCoord(0, 0)))
        for (production in listOf(3f, 12f)) {
            city.cityStats.currentCityStats.production = production
            for (kind in DivisionKind.entries) {
                val cost = testGame.ruleset.units[kind.unitName]!!.getProductionCost(civ, city)
                assertEquals("${kind.name} at $production", (production * kind.buildTurns).toInt(), cost)
            }
        }
    }

    @Test
    fun `a mobile division falls back where infantry holds`() {
        assertEquals(FrontStance.Defensive, FrontAutomation.chooseReaction(0.5f, 100, true, DivisionKind.Infantry))
        assertEquals(FrontStance.Withdrawal, FrontAutomation.chooseReaction(0.5f, 100, true, DivisionKind.Cavalry))
        assertEquals(FrontStance.Defensive, FrontAutomation.chooseReaction(1.6f, 100, true, DivisionKind.Armored))
        assertFalse(DivisionKind.Infantry.isMobile)
    }

    @Test
    fun `cavalry and armour hit harder in an aggressive attack and resist less when they hold`() {
        assertTrue(DivisionKind.Armored.offenseFactor(FrontStance.Aggressive) > DivisionKind.Cavalry.offenseFactor(FrontStance.Aggressive))
        assertTrue(DivisionKind.Cavalry.offenseFactor(FrontStance.Aggressive) > DivisionKind.Infantry.offenseFactor(FrontStance.Aggressive))
        assertTrue(DivisionKind.Cavalry.defenseFactor(FrontStance.Defensive) < DivisionKind.Infantry.defenseFactor(FrontStance.Defensive))
        assertTrue(DivisionKind.Armored.defenseFactor(FrontStance.Withdrawal) > DivisionKind.Infantry.defenseFactor(FrontStance.Withdrawal))
    }
}
