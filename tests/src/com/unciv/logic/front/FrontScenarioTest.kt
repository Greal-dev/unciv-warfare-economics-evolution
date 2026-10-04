package com.unciv.logic.front

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Front mode: a scenario run, two civilizations with three cities each and divisions driven by the AI v0,
 * over 150 rounds. It prints the measures of the spec (tiles flipped, divisions lost, rounds to the first
 * collapse of a front) so the constants can be calibrated, and checks that the dynamics stay sane.
 */
@RunWith(BaseTestRunner::class)
class FrontScenarioTest {
    private class Result(
        val tilesA: List<Int>, val tilesB: List<Int>,
        val divisionsA: Int, val divisionsB: Int,
        val dissolved: Int, val flipped: Int, val firstFlipRound: Int
    ) {
        val signature get() = "$tilesA|$tilesB|$divisionsA|$divisionsB|$dissolved|$flipped"
    }

    private fun run(divisionsOfA: Int, divisionsOfB: Int, rounds: Int = 150): Result {
        val testGame = TestGame()
        testGame.makeHexagonalMap(10)
        val civA = testGame.addCiv()
        val civB = testGame.addCiv()
        civA.diplomacyFunctions.makeCivilizationsMeet(civB)
        civA.diplomacy[civB.civName]?.declareWar()
        civA.addGold(5000)
        civB.addGold(5000)

        val centresA = listOf(HexCoord(-5, 0), HexCoord(-5, 4), HexCoord(-5, -4)).map { testGame.getTile(it) }
        val centresB = listOf(HexCoord(5, 0), HexCoord(5, -4), HexCoord(5, 4)).map { testGame.getTile(it) }
        val citiesA = centresA.map { testGame.addCity(civA, it) }
        val citiesB = centresB.map { testGame.addCity(civB, it) }
        for (tile in testGame.tileMap.values) {
            if (tile in centresA || tile in centresB) continue
            val a = centresA.minOf { it.aerialDistanceTo(tile) }
            val b = centresB.minOf { it.aerialDistanceTo(tile) }
            val city = if (a <= b) citiesA.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }!!
                else citiesB.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }!!
            city.expansion.takeOwnership(tile)
        }

        fun spawn(civ: Civilization, count: Int, x: Int) {
            val ys = listOf(0, 2, -2, 4, -4, 1, -1, 3, -3)
            repeat(count) { testGame.addUnit(FrontResolver.DIVISION_UNIT_NAME, civ, testGame.getTile(HexCoord(x, ys[it]))) }
        }
        spawn(civA, divisionsOfA, -3)
        spawn(civB, divisionsOfB, 3)

        val startA = testGame.tileMap.values.count { it.getOwner() == civA }
        val tilesA = ArrayList<Int>()
        val tilesB = ArrayList<Int>()
        var flipped = 0
        var firstFlip = -1
        var previousA = startA
        var created = divisionsOfA + divisionsOfB
        for (round in 1..rounds) {
            for (civ in listOf(civA, civB))
                for (unit in civ.units.getCivUnits().filter { FrontResolver.isDivision(it) }.toList()) {
                    unit.currentMovement = unit.getMaxMovement().toFloat()
                    FrontAutomation.automate(unit)
                }
            FrontResolver.resolveRound(testGame.gameInfo)
            val a = testGame.tileMap.values.count { it.getOwner() == civA }
            val b = testGame.tileMap.values.count { it.getOwner() == civB }
            if (a != previousA) { flipped += kotlin.math.abs(a - previousA); if (firstFlip < 0) firstFlip = round }
            previousA = a
            if (round % 25 == 0) { tilesA += a; tilesB += b }
            if (round == 10 || round == 40) {
                val dump = (civA.units.getCivUnits() + civB.units.getCivUnits()).filter { FrontResolver.isDivision(it) }
                    .joinToString(" ") { "${it.civ.civName.take(1)}@${it.currentTile.position.x},${it.currentTile.position.y}:${it.frontStance.take(3)}:${it.health}" }
                java.io.File(System.getProperty("java.io.tmpdir"), "front-scenario.txt").appendText("  r$round $dump\n")
            }
        }
        val aliveA = civA.units.getCivUnits().count { FrontResolver.isDivision(it) }
        val aliveB = civB.units.getCivUnits().count { FrontResolver.isDivision(it) }
        val result = Result(tilesA, tilesB, aliveA, aliveB, created - aliveA - aliveB, flipped, firstFlip)
        // The test runner swallows standard output, so the measures go to a file as well
        val file = java.io.File(System.getProperty("java.io.tmpdir"), "front-scenario.txt")
        file.appendText(
            "${divisionsOfA}v$divisionsOfB ($rounds rounds): start A=$startA, A per 25 rounds=$tilesA, B=$tilesB, " +
            "divisions left A=$aliveA B=$aliveB, dissolved=${result.dissolved}, tiles flipped=$flipped, first flip at round $firstFlip\n")
        return result
    }

    @Test
    fun `the same scenario twice gives the same result`() {
        assertEquals(run(4, 4, 60).signature, run(4, 4, 60).signature)
    }

    @Test
    fun `a stronger army gains ground over the weaker one`() {
        val r = run(6, 3)
        assertTrue("some tiles must change hands", r.flipped > 0)
        assertTrue("the stronger side must end with more tiles than it started with a fair split",
            r.tilesA.last() > r.tilesB.last())
    }

    @Test
    fun `equal armies do not annihilate each other at once`() {
        val r = run(4, 4)
        assertTrue("at least a division must survive on each side", r.divisionsA > 0 && r.divisionsB > 0)
    }

    @Test
    fun `without divisions nothing happens`() {
        val r = run(0, 0, 30)
        assertEquals(0, r.flipped)
    }
}
