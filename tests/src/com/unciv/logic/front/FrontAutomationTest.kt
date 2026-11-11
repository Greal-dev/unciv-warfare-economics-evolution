package com.unciv.logic.front

import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class FrontAutomationTest {
    @Test
    fun `a healthy division holds the line whatever the balance`() {
        assertEquals(FrontStance.Defensive, FrontAutomation.chooseReaction(2.5f, 100, true))
        assertEquals(FrontStance.Defensive, FrontAutomation.chooseReaction(0.5f, 100, true))
    }

    @Test
    fun `a wounded division in contact is relieved by withdrawal`() {
        assertEquals(FrontStance.Withdrawal, FrontAutomation.chooseReaction(3f, FrontAutomation.RELIEVE_BELOW - 1, true))
        assertEquals(FrontStance.Defensive, FrontAutomation.chooseReaction(3f, FrontAutomation.RELIEVE_BELOW + 1, true))
    }

    @Test
    fun `out of contact a division simply holds`() {
        assertEquals(FrontStance.Defensive, FrontAutomation.chooseReaction(3f, 30, false))
    }

    private fun duel(): Triple<TestGame, com.unciv.logic.civilization.Civilization, com.unciv.logic.civilization.Civilization> {
        val game = TestGame()
        game.makeHexagonalMap(6)
        game.gameInfo.gameParameters.frontMode = true
        val a = game.addCiv()
        val b = game.addCiv()
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacy[b.civName]?.declareWar()
        val centreA = game.getTile(HexCoord(-3, 0))
        val centreB = game.getTile(HexCoord(3, 0))
        val cityA = game.addCity(a, centreA)
        val cityB = game.addCity(b, centreB)
        for (tile in game.tileMap.values) {
            if (tile == centreA || tile == centreB) continue
            (if (tile.aerialDistanceTo(centreA) < tile.aerialDistanceTo(centreB)) cityA else cityB).expansion.takeOwnership(tile)
        }
        return Triple(game, a, b)
    }

    @Test
    fun `the AI attacks an open enemy tile during its turn and the tile changes hands`() {
        val (game, a, b) = duel()
        val unit = game.addUnit(FrontResolver.DIVISION_UNIT_NAME, a, game.getTile(HexCoord(-1, 0)))
        unit.currentMovement = unit.getMaxMovement().toFloat()
        val before = game.tileMap.values.count { it.getOwner() == b }
        FrontAutomation.automate(unit)
        assertTrue("the AI must have taken a tile", game.tileMap.values.count { it.getOwner() == b } < before)
        assertEquals(0f, unit.currentMovement, 0f)
    }

    @Test
    fun `the AI does not launch an attack it is sure to lose`() {
        val (game, a, b) = duel()
        val unit = game.addUnit(FrontResolver.DIVISION_UNIT_NAME, a, game.getTile(HexCoord(-1, 0)))
        unit.currentMovement = unit.getMaxMovement().toFloat()
        game.addUnit(FrontResolver.DIVISION_UNIT_NAME, b, game.getTile(HexCoord(1, 0)))
        game.addUnit(FrontResolver.DIVISION_UNIT_NAME, b, game.getTile(HexCoord(1, 1)))
        val defended = game.getTile(HexCoord(0, 0))
        assertTrue(!FrontResolver.previewAttack(unit, defended, FrontStance.Aggressive)!!.succeeds)
        val best = FrontAutomation.bestAttack(unit)
        assertTrue("the AI must not pick the defended tile", best == null || best.tile != defended)
        FrontAutomation.automate(unit)
        assertTrue("the AI keeps its division above the safety margin", unit.health > FrontMath.DISSOLVE_HEALTH + 10)
    }

    @Test
    fun `the AI picks the cheaper intensity that is sure to succeed`() {
        val (game, a, _) = duel()
        val unit = game.addUnit(FrontResolver.DIVISION_UNIT_NAME, a, game.getTile(HexCoord(-1, 0)))
        unit.currentMovement = unit.getMaxMovement().toFloat()
        val best = FrontAutomation.bestAttack(unit)
        assertNotNull(best)
        assertEquals(FrontStance.Moderate, best!!.intensity)
    }

    @Test
    fun `a wounded division does not attack`() {
        val (game, a, _) = duel()
        val unit = game.addUnit(FrontResolver.DIVISION_UNIT_NAME, a, game.getTile(HexCoord(-1, 0)))
        unit.currentMovement = unit.getMaxMovement().toFloat()
        unit.health = FrontAutomation.FIT_FOR_OFFENSIVE - 1
        assertNull(FrontAutomation.bestAttack(unit))
    }
}
