package com.unciv.logic.front

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        testGame.gameInfo.gameParameters.frontMode = true
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
        unit.currentMovement = unit.getMaxMovement().toFloat()
        return unit
    }

    @Test
    fun `an attack hurts an enemy division standing on a tile that belongs to nobody`() {
        val attacker = division(civA, -1, 0, FrontStance.Defensive)
        val target = division(civB, 0, 0, FrontStance.Defensive)
        assertEquals(null, testGame.getTile(HexCoord(0, 0)).getOwner())
        val result = FrontResolver.attack(attacker, testGame.getTile(HexCoord(0, 0)), FrontStance.Aggressive)
        assertNotNull(result)
        assertTrue("the enemy division must lose troops", target.health < 100)
        assertTrue("the attacker pays too", attacker.health < 100)
    }

    @Test
    fun `an attack hurts an enemy division standing in the attacker's own territory`() {
        val attacker = division(civA, -3, 0, FrontStance.Defensive)
        val intruder = division(civB, -2, 0, FrontStance.Defensive)
        assertEquals(civA, testGame.getTile(HexCoord(-2, 0)).getOwner())
        FrontResolver.attack(attacker, testGame.getTile(HexCoord(-2, 0)), FrontStance.Aggressive)
        assertTrue("the intruder must lose troops", intruder.health < 100)
    }

    @Test
    fun `fighting an intruder does not give the attacker tiles it already owns`() {
        val attacker = division(civA, -3, 0, FrontStance.Defensive)
        division(civB, -2, 0, FrontStance.Defensive)
        val before = testGame.tileMap.values.count { it.getOwner() == civA }
        val result = FrontResolver.attack(attacker, testGame.getTile(HexCoord(-2, 0)), FrontStance.Aggressive)!!
        assertEquals(false, result.tileTaken)
        assertEquals(before, testGame.tileMap.values.count { it.getOwner() == civA })
    }

    @Test
    fun `enemy divisions come before empty enemy tiles in the targets`() {
        val attacker = division(civA, 0, 0, FrontStance.Defensive)
        division(civB, 2, 0, FrontStance.Defensive)
        val tiles = FrontResolver.attackTargets(attacker)
        assertTrue(tiles.isNotEmpty())
        assertEquals(testGame.getTile(HexCoord(2, 0)), tiles.first())
    }

    private fun cityOfB() = testGame.getTile(HexCoord(4, 0))

    private fun surround(civ: Civilization, count: Int): List<MapUnit> =
        listOf(HexCoord(3, 0), HexCoord(3, 1), HexCoord(4, -1)).take(count).map { division(civ, it.x, it.y, FrontStance.Defensive) }

    @Test
    fun `several divisions in contact take an empty city with a single attack`() {
        val attackers = surround(civA, 3)
        val result = FrontResolver.attack(attackers[0], cityOfB(), FrontStance.Aggressive)!!
        assertTrue(result.message, result.tileTaken)
        assertEquals(civA, cityOfB().getOwner())
    }

    @Test
    fun `a city held by a division resists a small assault`() {
        val attackers = surround(civA, 3)
        division(civB, 5, 0, FrontStance.Defensive)
        val result = FrontResolver.attack(attackers[0], cityOfB(), FrontStance.Aggressive)!!
        assertEquals(false, result.preview.succeeds)
        assertEquals(civB, cityOfB().getOwner())
    }

    @Test
    fun `a city falls once its defender is worn down by successive attacks`() {
        val attackers = surround(civA, 3)
        val defender = division(civB, 5, 0, FrontStance.Defensive)
        var attacks = 0
        // A fresh attacker leads each time, the others keep supporting
        while (cityOfB().getOwner() == civB && attacks < 3) {
            val leader = attackers.getOrNull(attacks) ?: break
            FrontResolver.attack(leader, cityOfB(), FrontStance.Aggressive)
            attacks++
        }
        assertTrue("the defender must have been worn down", defender.isDestroyed || defender.health < 100)
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
}
