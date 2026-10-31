package com.unciv.logic.front

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Where a division can hit: on enemy tiles, but also on an enemy division wherever it stands. */
@RunWith(BaseTestRunner::class)
class FrontContactTest {
    private val testGame = TestGame()
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(6)
        civA = testGame.addCiv()
        civB = testGame.addCiv()
        civA.diplomacyFunctions.makeCivilizationsMeet(civB)
        civA.diplomacy[civB.civName]?.declareWar()
        val tileA = testGame.getTile(HexCoord(-4, 0))
        val tileB = testGame.getTile(HexCoord(4, 0))
        val cityA = testGame.addCity(civA, tileA)
        val cityB = testGame.addCity(civB, tileB)
        for (tile in testGame.tileMap.values) {
            if (tile == tileA || tile == tileB) continue
            val a = tile.aerialDistanceTo(tileA)
            val b = tile.aerialDistanceTo(tileB)
            // a strip of tiles between the two realms belongs to nobody
            when {
                a < b - 1 -> cityA.expansion.takeOwnership(tile)
                b < a - 1 -> cityB.expansion.takeOwnership(tile)
            }
        }
    }

    private fun division(civ: Civilization, x: Int, y: Int, stance: FrontStance): MapUnit {
        val unit = testGame.addUnit(FrontResolver.DIVISION_UNIT_NAME, civ, testGame.getTile(HexCoord(x, y)))
        unit.frontStance = stance.name
        return unit
    }

    @Test
    fun `an aggressive division hurts an enemy division standing on a tile that belongs to nobody`() {
        val attacker = division(civA, -1, 0, FrontStance.Aggressive)
        val target = division(civB, 0, 0, FrontStance.Defensive)
        assertEquals(null, testGame.getTile(HexCoord(0, 0)).getOwner())
        repeat(3) { FrontResolver.resolveRound(testGame.gameInfo) }
        assertTrue("the enemy division must lose troops", target.health < 100)
        assertTrue("the attacker pays too", attacker.health < 100)
    }

    @Test
    fun `an aggressive division hurts an enemy division standing in its own territory`() {
        val attacker = division(civA, -3, 0, FrontStance.Aggressive)
        val intruder = division(civB, -2, 0, FrontStance.Defensive)
        assertEquals(civA, testGame.getTile(HexCoord(-2, 0)).getOwner())
        repeat(3) { FrontResolver.resolveRound(testGame.gameInfo) }
        assertTrue("the intruder must lose troops", intruder.health < 100)
        assertTrue(attacker.health <= 100)
    }

    @Test
    fun `fighting an intruder does not give the attacker tiles it already owns`() {
        division(civA, -3, 0, FrontStance.Aggressive)
        division(civB, -2, 0, FrontStance.Defensive)
        repeat(5) { FrontResolver.resolveRound(testGame.gameInfo) }
        assertTrue(testGame.tileMap.values.none { it.getOwner() == civA && it.frontProgress > 0f })
    }

    @Test
    fun `enemy divisions are pressed before empty enemy tiles`() {
        val attacker = division(civA, 0, 0, FrontStance.Aggressive)
        division(civB, 2, 0, FrontStance.Defensive)
        val tiles = FrontResolver.pressedTilesOf(attacker)
        assertTrue(tiles.isNotEmpty())
        assertEquals(testGame.getTile(HexCoord(2, 0)), tiles.first())
    }
}
