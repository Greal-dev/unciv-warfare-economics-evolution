package com.unciv.logic.front

import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class FrontModeOptionTest {
    private val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(3)
    }

    private lateinit var civ: com.unciv.logic.civilization.Civilization
    private lateinit var city: com.unciv.logic.city.City

    @Before
    fun setUpCity() {
        civ = testGame.addCiv()
        city = testGame.addCity(civ, testGame.getTile(HexCoord(0, 0)))
    }

    private fun unbuildable(name: String): Boolean {
        return testGame.ruleset.units[name]!!.getRejectionReasons(civ, city).any { it.type == RejectionReasonType.Unbuildable }
    }

    @Test
    fun `without front mode the division cannot be built and land units can`() {
        testGame.gameInfo.gameParameters.frontMode = false
        assertTrue(unbuildable(FrontResolver.DIVISION_UNIT_NAME))
        assertFalse(unbuildable("Warrior"))
    }

    @Test
    fun `in front mode the division replaces the land units that fight`() {
        testGame.gameInfo.gameParameters.frontMode = true
        assertFalse(unbuildable(FrontResolver.DIVISION_UNIT_NAME))
        assertTrue(unbuildable("Warrior"))
    }

    @Test
    fun `the option survives a clone of the game parameters`() {
        val parameters = com.unciv.models.metadata.GameParameters().apply { frontMode = true }
        assertTrue(parameters.clone().frontMode)
    }

    @Test
    fun `the divisions of a civilization without cities disband`() {
        val landless = testGame.addCiv()
        val unit = testGame.addUnit(FrontResolver.DIVISION_UNIT_NAME, landless, testGame.getTile(HexCoord(2, 0)))
        val kept = testGame.addUnit(FrontResolver.DIVISION_UNIT_NAME, civ, testGame.getTile(HexCoord(-2, 0)))
        FrontResolver.resolveRound(testGame.gameInfo)
        assertFalse(unit.civ.units.getCivUnits().any { FrontResolver.isDivision(it) })
        assertTrue(kept.civ.units.getCivUnits().any { FrontResolver.isDivision(it) })
    }

    private val divisionType get() = testGame.ruleset.units[FrontResolver.DIVISION_UNIT_NAME]!!

    @Test
    fun `a division costs one inhabitant whether it is produced or bought`() {
        testGame.gameInfo.gameParameters.frontMode = true
        city.population.setPopulation(4)
        divisionType.construct(city.cityConstructions, null)
        assertEquals(3, city.population.population)
        divisionType.construct(city.cityConstructions, com.unciv.models.stats.Stat.Gold)
        assertEquals(2, city.population.population)
    }

    @Test
    fun `a city with a single inhabitant cannot raise a division`() {
        testGame.gameInfo.gameParameters.frontMode = true
        city.population.setPopulation(1)
        assertTrue(divisionType.getRejectionReasons(civ, city).any { it.type == RejectionReasonType.PopulationRequirement })
        city.population.setPopulation(3)
        assertFalse(divisionType.getRejectionReasons(civ, city).any { it.type == RejectionReasonType.PopulationRequirement })
    }
}