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

/** Attacks launched during the turn of their owner, resolved on the spot, and the upkeep of the front. */
@RunWith(BaseTestRunner::class)
class FrontResolverTest {
    private val testGame = TestGame()
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization
    private lateinit var tileA: Tile   // city centre of A
    private lateinit var tileB: Tile   // city centre of B

    /** Two civilizations at war, each owning the half of the map closest to its city. */
    private class Duel(val game: TestGame, val a: Civilization, val b: Civilization, val centreA: Tile, val centreB: Tile)

    private fun duel(game: TestGame): Duel {
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
            val owner = if (tile.aerialDistanceTo(centreA) < tile.aerialDistanceTo(centreB)) cityA else cityB
            owner.expansion.takeOwnership(tile)
        }
        return Duel(game, a, b, centreA, centreB)
    }

    @Before
    fun setUp() {
        val d = duel(testGame)
        civA = d.a; civB = d.b; tileA = d.centreA; tileB = d.centreB
    }

    private fun division(game: TestGame, civ: Civilization, x: Int, y: Int, stance: FrontStance, health: Int = 100): MapUnit {
        val unit = game.addUnit(FrontResolver.DIVISION_UNIT_NAME, civ, game.getTile(HexCoord(x, y)))
        unit.frontStance = stance.name
        unit.currentMovement = unit.getMaxMovement().toFloat()
        unit.health = health
        return unit
    }

    private fun division(civ: Civilization, x: Int, y: Int, stance: FrontStance, health: Int = 100) =
        division(testGame, civ, x, y, stance, health)

    private fun ownedBy(civ: Civilization) = testGame.tileMap.values.count { it.getOwner() == civ }

    @Test
    fun `the division is a unit of the ruleset and cannot attack in the old way`() {
        val unit = division(civA, -1, 0, FrontStance.Defensive)
        assertTrue(FrontResolver.isDivision(unit))
        assertTrue(unit.hasUnique(com.unciv.models.ruleset.unique.UniqueType.CannotAttack))
    }

    @Test
    fun `an attack on an enemy tile is resolved at once and the tile changes hands`() {
        val attacker = division(civA, -1, 0, FrontStance.Defensive)
        val target = FrontResolver.attackTargets(attacker).first()
        assertEquals(civB, target.getOwner())
        val result = FrontResolver.attack(attacker, target, FrontStance.Moderate)
        assertNotNull(result)
        assertTrue(result!!.tileTaken)
        assertEquals(civA, target.getOwner())
        assertTrue(result.message.contains("Victory"))
    }

    @Test
    fun `a victorious division advances onto an adjacent tile it has taken`() {
        val attacker = division(civA, -1, 0, FrontStance.Defensive)
        val target = FrontResolver.attackTargets(attacker).first { it.aerialDistanceTo(attacker.currentTile) == 1 }
        FrontResolver.attack(attacker, target, FrontStance.Moderate)
        assertEquals(target, attacker.currentTile)
    }

    @Test
    fun `one attack per turn, the division has no movement left afterwards`() {
        val attacker = division(civA, -1, 0, FrontStance.Defensive)
        val target = FrontResolver.attackTargets(attacker).first()
        assertTrue(FrontResolver.canAttack(attacker))
        FrontResolver.attack(attacker, target, FrontStance.Moderate)
        assertEquals(0f, attacker.currentMovement, 0f)
        assertFalse(FrontResolver.canAttack(attacker))
        assertNull(FrontResolver.attack(attacker, target, FrontStance.Moderate))
    }

    @Test
    fun `a tile out of reach or out of contact cannot be attacked`() {
        val attacker = division(civA, -1, 0, FrontStance.Defensive)
        assertNull(FrontResolver.previewAttack(attacker, testGame.getTile(HexCoord(-4, 0)), FrontStance.Moderate))
        assertNull(FrontResolver.attack(attacker, testGame.getTile(HexCoord(4, 0)), FrontStance.Moderate))
    }

    @Test
    fun `a defended tile repels a single moderate attack and both sides lose health`() {
        val attacker = division(civA, -1, 0, FrontStance.Defensive)
        val defender = division(civB, 1, 0, FrontStance.Defensive)
        val target = testGame.getTile(HexCoord(0, 0))
        val result = FrontResolver.attack(attacker, target, FrontStance.Moderate)
        assertNotNull(result)
        assertFalse(result!!.preview.succeeds)
        assertEquals(civB, target.getOwner())
        assertTrue(attacker.health < 100)
        assertTrue(defender.health < 100)
    }

    @Test
    fun `an aggressive attack hits harder and costs more than a moderate one`() {
        val attacker = division(civA, -1, 0, FrontStance.Defensive)
        division(civB, 1, 0, FrontStance.Defensive)
        val target = testGame.getTile(HexCoord(0, 0))
        val moderate = FrontResolver.previewAttack(attacker, target, FrontStance.Moderate)!!
        val aggressive = FrontResolver.previewAttack(attacker, target, FrontStance.Aggressive)!!
        assertTrue(aggressive.pressure > moderate.pressure)
        assertTrue(aggressive.attackerLoss > moderate.attackerLoss)
    }

    @Test
    fun `divisions of the attacker in the zone of the target support the attack`() {
        val attacker = division(civA, -1, 0, FrontStance.Defensive)
        division(civB, 1, 0, FrontStance.Defensive)
        val target = testGame.getTile(HexCoord(0, 0))
        val alone = FrontResolver.previewAttack(attacker, target, FrontStance.Moderate)!!
        division(civA, -1, 1, FrontStance.Defensive)
        val supported = FrontResolver.previewAttack(attacker, target, FrontStance.Moderate)!!
        assertEquals(1, supported.supporters)
        assertTrue(supported.pressure > alone.pressure)
    }

    @Test
    fun `holding the line resists more than withdrawing, and a withdrawing defender falls back`() {
        val attacker = division(civA, -1, 0, FrontStance.Defensive)
        val defender = division(civB, 1, 0, FrontStance.Defensive)
        val target = testGame.getTile(HexCoord(0, 0))
        val holding = FrontResolver.previewAttack(attacker, target, FrontStance.Moderate)!!
        defender.frontStance = FrontStance.Withdrawal.name
        val withdrawing = FrontResolver.previewAttack(attacker, target, FrontStance.Moderate)!!
        assertTrue(withdrawing.resistance < holding.resistance)

        val before = defender.currentTile.aerialDistanceTo(tileB)
        FrontResolver.attack(attacker, target, FrontStance.Moderate)
        assertTrue("the defender must fall back toward its city", defender.currentTile.aerialDistanceTo(tileB) < before)
    }

    @Test
    fun `the posture of the defender is the one that counts, not an old attack intensity`() {
        val defender = division(civB, 1, 0, FrontStance.Aggressive)
        assertEquals(FrontStance.Defensive, FrontResolver.reactionOf(defender))
        defender.frontStance = FrontStance.Withdrawal.name
        assertEquals(FrontStance.Withdrawal, FrontResolver.reactionOf(defender))
    }

    @Test
    fun `an enemy division on the target is pushed back when the attack succeeds`() {
        val attackers = listOf(division(civA, -1, 0, FrontStance.Defensive), division(civA, -1, 1, FrontStance.Defensive),
            division(civA, 0, -1, FrontStance.Defensive))
        val defender = division(civB, 0, 0, FrontStance.Defensive, health = 40)
        val target = testGame.getTile(HexCoord(0, 0))
        val result = FrontResolver.attack(attackers[0], target, FrontStance.Aggressive)!!
        assertTrue(result.preview.succeeds)
        assertTrue("the defender no longer stands on the target", defender.isDestroyed || defender.currentTile != target)
    }

    @Test
    fun `a flipped tile goes to the nearest city of the winner`() {
        val attacker = division(civA, -1, 0, FrontStance.Defensive)
        val target = FrontResolver.attackTargets(attacker).first()
        FrontResolver.attack(attacker, target, FrontStance.Moderate)
        assertEquals(civA, target.getOwner())
        assertEquals(civA, target.getCity()?.civ)
    }

    @Test
    fun `peace stops the attacks`() {
        civA.diplomacy[civB.civName]?.makePeace()
        assertFalse(civA.isAtWarWith(civB))
        val attacker = division(civA, -1, 0, FrontStance.Defensive)
        assertFalse(FrontResolver.canAttack(attacker))
        assertTrue(FrontResolver.attackTargets(attacker).isEmpty())
    }

    @Test
    fun `a division at ten health or less breaks up at the end of the round`() {
        val unit = division(civA, -1, 0, FrontStance.Defensive, health = 10)
        FrontResolver.endOfRound(testGame.gameInfo)
        assertTrue(unit.isDestroyed)
    }

    @Test
    fun `a division that an attack leaves at ten health or less breaks up at once`() {
        val attacker = division(civA, -1, 0, FrontStance.Defensive, health = 22)
        division(civB, 1, 0, FrontStance.Defensive)
        FrontResolver.attack(attacker, testGame.getTile(HexCoord(0, 0)), FrontStance.Aggressive)
        assertTrue(attacker.isDestroyed)
    }

    @Test
    fun `entrenchment grows while holding still and resets after a move`() {
        val unit = division(civA, -2, 0, FrontStance.Defensive)
        repeat(3) { FrontResolver.endOfRound(testGame.gameInfo) }
        assertTrue(unit.frontEntrenchment > 0f)

        unit.removeFromTile()
        unit.putInTile(testGame.getTile(HexCoord(-2, 1)))
        FrontResolver.endOfRound(testGame.gameInfo)
        assertEquals(0f, unit.frontEntrenchment, 0.0001f)
    }

    @Test
    fun `an attack resets the entrenchment of the attacker`() {
        val attacker = division(civA, -1, 0, FrontStance.Defensive)
        attacker.frontEntrenchment = 0.3f
        FrontResolver.attack(attacker, FrontResolver.attackTargets(attacker).first(), FrontStance.Moderate)
        assertEquals(0f, attacker.frontEntrenchment, 0f)
    }

    @Test
    fun `a wounded division in supplied own territory is reinforced against gold`() {
        civA.addGold(500)
        val unit = division(civA, -3, 0, FrontStance.Defensive, health = 50)
        val goldBefore = civA.gold
        FrontResolver.endOfRound(testGame.gameInfo)
        assertEquals(60, unit.health)
        assertTrue(civA.gold < goldBefore)
    }

    @Test
    fun `no gold means no reinforcement`() {
        civA.addGold(-civA.gold)
        val unit = division(civA, -3, 0, FrontStance.Defensive, health = 50)
        FrontResolver.endOfRound(testGame.gameInfo)
        assertEquals(50, unit.health)
    }

    @Test
    fun `the end of a round does not attack on its own`() {
        division(civA, -1, 0, FrontStance.Defensive)
        val before = ownedBy(civB)
        repeat(5) { FrontResolver.endOfRound(testGame.gameInfo) }
        assertEquals(before, ownedBy(civB))
    }

    @Test
    fun `the same setup gives the same result`() {
        fun run(): List<String> {
            val d = duel(TestGame())
            val ua = division(d.game, d.a, -1, 0, FrontStance.Defensive)
            val ub = division(d.game, d.b, 1, 0, FrontStance.Defensive)
            division(d.game, d.a, -1, 1, FrontStance.Defensive)
            val target = d.game.getTile(HexCoord(0, 0))
            FrontResolver.attack(ua, target, FrontStance.Aggressive)
            repeat(3) { FrontResolver.endOfRound(d.game.gameInfo) }
            return d.game.tileMap.values.map { "${it.position}:${it.getOwner()?.civName}" } + listOf("${ua.health}", "${ub.health}")
        }
        assertEquals(run(), run())
    }
}
