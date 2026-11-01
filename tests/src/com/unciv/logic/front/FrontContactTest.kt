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

    private fun cityOfB() = testGame.getTile(HexCoord(4, 0))

    private fun surround(civ: Civilization, stance: FrontStance, count: Int) {
        listOf(HexCoord(3, 0), HexCoord(3, 1), HexCoord(4, -1)).take(count).forEach { division(civ, it.x, it.y, stance) }
    }

    @Test
    fun `several aggressive divisions in contact take a city by pressure on its centre`() {
        surround(civA, FrontStance.Aggressive, 3)
        repeat(80) { FrontResolver.resolveRound(testGame.gameInfo) }
        assertEquals(civA, cityOfB().getOwner())
    }

    @Test
    fun `a city held by a division resists a small assault`() {
        surround(civA, FrontStance.Aggressive, 3)
        division(civB, 5, 0, FrontStance.Defensive)
        repeat(30) { FrontResolver.resolveRound(testGame.gameInfo) }
        assertEquals(civB, cityOfB().getOwner())
    }

    @Test
    fun `the walls of a city add to its defenders and not to an empty city`() {
        val empty = FrontMath.tileResistance(0f, false, 0f, 0f, FrontMath.CITY_DEFENSE_BONUS)
        assertEquals(FrontMath.BASE_RESISTANCE, empty, 0.001f)
        val held = FrontMath.tileResistance(10f, true, 0f, 0f, FrontMath.CITY_DEFENSE_BONUS)
        assertEquals(30f, held, 0.001f)
    }

    @Test
    fun `divisions get stronger with the military technologies researched`() {
        val before = FrontResolver.militaryLevel(civA)
        val unlockers = testGame.ruleset.units.values.filter { it.isMilitary }.flatMap { it.requiredTechs().toList() }.toSet()
        unlockers.forEach { civA.tech.techsResearched.add(it) }
        val after = FrontResolver.militaryLevel(civA)
        assertTrue("level must rise from $before to $after", after > before)
        assertEquals(FrontMath.MAX_MILITARY_LEVEL, after, 0.001f)
    }

    @Test
    fun `designated tiles replace the automatic choice and concentrate the force`() {
        val attacker = division(civA, 1, 0, FrontStance.Aggressive)
        val automatic = FrontResolver.pressedTilesOf(attacker)
        assertTrue("automatic choice: $automatic", automatic.size > 1)
        val chosen = automatic.first()
        FrontResolver.toggleTarget(attacker, chosen)
        assertEquals(listOf(chosen), FrontResolver.pressedTilesOf(attacker))
        FrontResolver.toggleTarget(attacker, chosen)
        assertEquals(automatic, FrontResolver.pressedTilesOf(attacker))
    }

    @Test
    fun `a designated tile out of contact is refused`() {
        val attacker = division(civA, 0, 0, FrontStance.Aggressive)
        FrontResolver.toggleTarget(attacker, testGame.getTile(HexCoord(-3, 0)))
        assertTrue(attacker.frontTargets.isEmpty())
    }

    @Test
    fun `a single designated tile draws the whole pressure of the division`() {
        val attacker = division(civA, 1, 0, FrontStance.Aggressive)
        val chosen = FrontResolver.pressedTilesOf(attacker).first()
        FrontResolver.toggleTarget(attacker, chosen)
        FrontResolver.resolveRound(testGame.gameInfo)
        val pressed = testGame.tileMap.values.filter { it.frontProgress > 0f }
        assertTrue(pressed.all { it == chosen })
    }
}