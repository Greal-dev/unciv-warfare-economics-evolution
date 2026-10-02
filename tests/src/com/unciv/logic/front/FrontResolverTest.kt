package com.unciv.logic.front

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class FrontResolverTest {
    private val testGame = TestGame()
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization
    private lateinit var tileA: Tile   // city centre of A
    private lateinit var tileB: Tile   // city centre of B

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(6)
        civA = testGame.addCiv()
        civB = testGame.addCiv()
        civA.diplomacyFunctions.makeCivilizationsMeet(civB)
        civA.diplomacy[civB.civName]?.declareWar()

        tileA = testGame.getTile(HexCoord(-3, 0))
        tileB = testGame.getTile(HexCoord(3, 0))
        val cityA = testGame.addCity(civA, tileA)
        val cityB = testGame.addCity(civB, tileB)

        // Split the map along the middle: each side owns what is closer to its own city centre
        for (tile in testGame.tileMap.values) {
            if (tile == tileA || tile == tileB) continue
            val owner = if (tile.aerialDistanceTo(tileA) < tile.aerialDistanceTo(tileB)) cityA else cityB
            owner.expansion.takeOwnership(tile)
        }
    }

    private fun division(civ: Civilization, x: Int, y: Int, stance: FrontStance, health: Int = 100): MapUnit {
        val unit = testGame.addUnit(FrontResolver.DIVISION_UNIT_NAME, civ, testGame.getTile(HexCoord(x, y)))
        unit.frontStance = stance.name
        unit.health = health
        return unit
    }

    private fun ownedBy(civ: Civilization) = testGame.tileMap.values.count { it.getOwner() == civ }

    @Test
    fun `the division is a unit of the ruleset and cannot attack in the old way`() {
        val unit = division(civA, -1, 0, FrontStance.Moderate)
        assertTrue(FrontResolver.isDivision(unit))
        assertTrue(unit.hasUnique(com.unciv.models.ruleset.unique.UniqueType.CannotAttack))
    }

    @Test
    fun `a moderate offensive builds progress on the enemy tiles in its zone`() {
        division(civA, -1, 0, FrontStance.Moderate)
        FrontResolver.resolveRound(testGame.gameInfo)
        val pressed = testGame.tileMap.values.filter { it.frontProgress > 0f }
        assertTrue("some enemy tile must progress", pressed.isNotEmpty())
        assertTrue(pressed.all { it.getOwner() == civB && it.frontAttacker == civA.civName })
    }

    @Test
    fun `no more tiles are pressed than the front width allows`() {
        division(civA, -1, 0, FrontStance.Moderate)
        FrontResolver.resolveRound(testGame.gameInfo)
        val pressed = testGame.tileMap.values.count { it.frontProgress > 0f }
        assertTrue(pressed <= FrontMath.frontWidth(civA.getEraNumber()))
    }

    @Test
    fun `holding the line does not press`() {
        division(civA, -1, 0, FrontStance.Defensive)
        FrontResolver.resolveRound(testGame.gameInfo)
        assertTrue(testGame.tileMap.values.none { it.frontProgress > 0f })
    }

    @Test
    fun `a territory with no divisions falls to a sustained offensive but a city centre never flips directly`() {
        division(civA, -1, 0, FrontStance.Aggressive)
        val before = ownedBy(civB)
        repeat(40) { FrontResolver.resolveRound(testGame.gameInfo) }
        assertTrue("B must have lost tiles", ownedBy(civB) < before)
        assertEquals(civB, tileB.getOwner())
    }

    @Test
    fun `a flipped tile goes to the nearest city of the winner`() {
        division(civA, -1, 0, FrontStance.Aggressive)
        repeat(40) { FrontResolver.resolveRound(testGame.gameInfo) }
        val taken = testGame.tileMap.values.filter { it.getOwner() == civA && it.aerialDistanceTo(tileB) < it.aerialDistanceTo(tileA) }
        assertTrue("A must have crossed the middle", taken.isNotEmpty())
        assertTrue(taken.all { it.getCity()?.civ == civA })
    }

    @Test
    fun `a defended tile resists far longer than an empty one`() {
        // Same attacker, same rounds: first against an empty territory, then against a defending division
        division(civA, -1, 0, FrontStance.Aggressive)
        repeat(20) { FrontResolver.resolveRound(testGame.gameInfo) }
        val openLoss = ownedBy(civB)

        val other = TestGame()
        other.makeHexagonalMap(6)
        val a = other.addCiv(); val b = other.addCiv()
        a.diplomacyFunctions.makeCivilizationsMeet(b); a.diplomacy[b.civName]?.declareWar()
        val centreA = other.getTile(HexCoord(-3, 0)); val centreB = other.getTile(HexCoord(3, 0))
        val cA = other.addCity(a, centreA); val cB = other.addCity(b, centreB)
        for (tile in other.tileMap.values) {
            if (tile == centreA || tile == centreB) continue
            (if (tile.aerialDistanceTo(centreA) < tile.aerialDistanceTo(centreB)) cA else cB).expansion.takeOwnership(tile)
        }
        other.addUnit(FrontResolver.DIVISION_UNIT_NAME, a, other.getTile(HexCoord(-1, 0))).frontStance = FrontStance.Aggressive.name
        other.addUnit(FrontResolver.DIVISION_UNIT_NAME, b, other.getTile(HexCoord(1, 0))).frontStance = FrontStance.Defensive.name
        repeat(20) { FrontResolver.resolveRound(other.gameInfo) }
        val defendedLoss = other.tileMap.values.count { it.getOwner() == b }

        assertTrue("defended territory must hold more tiles ($defendedLoss) than the open one ($openLoss)", defendedLoss > openLoss)
    }

    @Test
    fun `both sides lose health when two offensives meet`() {
        val a = division(civA, -1, 0, FrontStance.Moderate)
        val b = division(civB, 1, 0, FrontStance.Moderate)
        repeat(3) { FrontResolver.resolveRound(testGame.gameInfo) }
        assertTrue(a.health < 100)
        assertTrue(b.health < 100)
    }

    @Test
    fun `an aggressive division wears out faster than a moderate one`() {
        val aggressive = division(civA, -1, 0, FrontStance.Aggressive)
        division(civB, 1, 0, FrontStance.Defensive)
        repeat(4) { FrontResolver.resolveRound(testGame.gameInfo) }

        val other = TestGame()
        other.makeHexagonalMap(6)
        val a = other.addCiv(); val b = other.addCiv()
        a.diplomacyFunctions.makeCivilizationsMeet(b); a.diplomacy[b.civName]?.declareWar()
        val centreA = other.getTile(HexCoord(-3, 0)); val centreB = other.getTile(HexCoord(3, 0))
        val cA = other.addCity(a, centreA); val cB = other.addCity(b, centreB)
        for (tile in other.tileMap.values) {
            if (tile == centreA || tile == centreB) continue
            (if (tile.aerialDistanceTo(centreA) < tile.aerialDistanceTo(centreB)) cA else cB).expansion.takeOwnership(tile)
        }
        val moderate = other.addUnit(FrontResolver.DIVISION_UNIT_NAME, a, other.getTile(HexCoord(-1, 0)))
        moderate.frontStance = FrontStance.Moderate.name
        other.addUnit(FrontResolver.DIVISION_UNIT_NAME, b, other.getTile(HexCoord(1, 0))).frontStance = FrontStance.Defensive.name
        repeat(4) { FrontResolver.resolveRound(other.gameInfo) }

        assertTrue("aggressive ${aggressive.health} must be below moderate ${moderate.health}", aggressive.health < moderate.health)
    }

    @Test
    fun `withdrawal steps the token back toward its city`() {
        val unit = division(civA, -1, 0, FrontStance.Withdrawal)
        val before = unit.currentTile.aerialDistanceTo(tileA)
        FrontResolver.resolveRound(testGame.gameInfo)
        assertTrue(unit.currentTile.aerialDistanceTo(tileA) < before)
    }

    @Test
    fun `a division at ten health or less breaks up`() {
        val unit = division(civA, -1, 0, FrontStance.Defensive, health = 10)
        FrontResolver.resolveRound(testGame.gameInfo)
        assertTrue(unit.isDestroyed)
    }

    @Test
    fun `entrenchment grows while holding still and resets after a move`() {
        val unit = division(civA, -2, 0, FrontStance.Defensive)
        repeat(3) { FrontResolver.resolveRound(testGame.gameInfo) }
        assertTrue(unit.frontEntrenchment > 0f)

        unit.removeFromTile()
        unit.putInTile(testGame.getTile(HexCoord(-2, 1)))
        FrontResolver.resolveRound(testGame.gameInfo)
        assertEquals(0f, unit.frontEntrenchment, 0.0001f)
    }

    @Test
    fun `a wounded division in supplied own territory is reinforced against gold`() {
        civA.addGold(500)
        val unit = division(civA, -2, 0, FrontStance.Defensive, health = 50)
        val goldBefore = civA.gold
        FrontResolver.resolveRound(testGame.gameInfo)
        assertEquals(60, unit.health)
        assertTrue(civA.gold < goldBefore)
    }

    @Test
    fun `no gold means no reinforcement`() {
        civA.addGold(-civA.gold)
        val unit = division(civA, -2, 0, FrontStance.Defensive, health = 50)
        FrontResolver.resolveRound(testGame.gameInfo)
        assertEquals(50, unit.health)
    }

    @Test
    fun `progress fades when the pressure stops`() {
        val unit = division(civA, -1, 0, FrontStance.Moderate)
        FrontResolver.resolveRound(testGame.gameInfo)
        val before = testGame.tileMap.values.sumOf { it.frontProgress.toDouble() }
        assertTrue(before > 0.0)
        unit.frontStance = FrontStance.Defensive.name
        repeat(3) { FrontResolver.resolveRound(testGame.gameInfo) }
        val after = testGame.tileMap.values.sumOf { it.frontProgress.toDouble() }
        assertTrue(after < before)
    }

    @Test
    fun `peace stops the pressure`() {
        civA.diplomacy[civB.civName]?.makePeace()
        assertFalse(civA.isAtWarWith(civB))
        division(civA, -1, 0, FrontStance.Aggressive)
        FrontResolver.resolveRound(testGame.gameInfo)
        assertTrue(testGame.tileMap.values.none { it.frontProgress > 0f })
    }

    @Test
    fun `the same setup gives the same result`() {
        fun run(): List<String> {
            val g = TestGame()
            g.makeHexagonalMap(6)
            val a = g.addCiv(); val b = g.addCiv()
            a.diplomacyFunctions.makeCivilizationsMeet(b); a.diplomacy[b.civName]?.declareWar()
            val centreA = g.getTile(HexCoord(-3, 0)); val centreB = g.getTile(HexCoord(3, 0))
            val cA = g.addCity(a, centreA); val cB = g.addCity(b, centreB)
            for (tile in g.tileMap.values) {
                if (tile == centreA || tile == centreB) continue
                (if (tile.aerialDistanceTo(centreA) < tile.aerialDistanceTo(centreB)) cA else cB).expansion.takeOwnership(tile)
            }
            val ua = g.addUnit(FrontResolver.DIVISION_UNIT_NAME, a, g.getTile(HexCoord(-1, 0))); ua.frontStance = FrontStance.Aggressive.name
            val ub = g.addUnit(FrontResolver.DIVISION_UNIT_NAME, b, g.getTile(HexCoord(1, 0))); ub.frontStance = FrontStance.Moderate.name
            repeat(15) { FrontResolver.resolveRound(g.gameInfo) }
            return g.tileMap.values.map { "${it.position}:${it.getOwner()?.civName}:${it.frontProgress}" } + listOf("${ua.health}", "${ub.health}")
        }
        assertEquals(run(), run())
    }

    @Test
    fun `a pressed tile has an attacker recorded and a flipped one is cleared`() {
        division(civA, -1, 0, FrontStance.Aggressive)
        FrontResolver.resolveRound(testGame.gameInfo)
        val pressed = testGame.tileMap.values.first { it.frontProgress > 0f }
        assertNotNull(pressed.frontAttacker)
        repeat(40) { FrontResolver.resolveRound(testGame.gameInfo) }
        val flipped = testGame.tileMap.values.filter { it.getOwner() == civA && it.frontProgress == 0f }
        assertTrue(flipped.all { it.frontAttacker == null })
        assertNull(testGame.tileMap.values.firstOrNull { it.frontProgress == 0f && it.frontAttacker != null })
    }
}
