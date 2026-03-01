package com.unciv.logic.civilization.managers

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ImperialStabilityTest {

    private lateinit var civInfo: Civilization
    val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(7)
        civInfo = testGame.addCiv()
    }

    @Test
    fun `new civ should start in stable tier`() {
        assertEquals(70, civInfo.imperialStability)
        assertEquals(ImperialStabilityManager.StabilityTier.Stable,
            civInfo.stabilityManager.getTier())
    }

    @Test
    fun `single city should be stable`() {
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        val isi = civInfo.stabilityManager.calculateISI()
        assertTrue("ISI should be high for a single peaceful city, was $isi", isi >= 50)
    }

    @Test
    fun `many cities should reduce stability via over-expansion`() {
        // Era 0 => max comfortable cities = 3 + 0 = 3
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        testGame.addCity(civInfo, testGame.tileMap[2, 0])
        testGame.addCity(civInfo, testGame.tileMap[-2, 0])
        testGame.addCity(civInfo, testGame.tileMap[0, 2])
        testGame.addCity(civInfo, testGame.tileMap[0, -2])
        testGame.addCity(civInfo, testGame.tileMap[2, 2])

        val breakdown = civInfo.stabilityManager.getISIBreakdown()
        assertTrue("Over-expansion should be negative",
            (breakdown["Over-expansion"] ?: 0f) < 0f)
    }

    @Test
    fun `golden age tier at high stability`() {
        civInfo.imperialStability = 85
        assertEquals(ImperialStabilityManager.StabilityTier.GoldenAge,
            civInfo.stabilityManager.getTier())
    }

    @Test
    fun `crisis tier at low stability`() {
        civInfo.imperialStability = 25
        assertEquals(ImperialStabilityManager.StabilityTier.Crisis,
            civInfo.stabilityManager.getTier())
    }

    @Test
    fun `collapse tier at very low stability`() {
        civInfo.imperialStability = 10
        assertEquals(ImperialStabilityManager.StabilityTier.Collapse,
            civInfo.stabilityManager.getTier())
    }

    @Test
    fun `unit losses reduce stability`() {
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        civInfo.unitsLostThisTurn = 3

        val breakdown = civInfo.stabilityManager.getISIBreakdown()
        assertEquals("Military losses should be -6", -6f, breakdown["Military losses"] ?: 0f, 0.01f)
    }

    @Test
    fun `peace bonus present when not at war`() {
        testGame.addCity(civInfo, testGame.tileMap[0, 0])

        val breakdown = civInfo.stabilityManager.getISIBreakdown()
        assertEquals("Peace bonus should be +2", 2f, breakdown["Peace"] ?: 0f, 0.01f)
    }

    @Test
    fun `renaissance transition activates after recovery from crisis`() {
        civInfo.wasInCrisis = true
        civInfo.stabilityManager.checkRenaissanceTransition(35, 65)

        assertTrue("Renaissance should be active", civInfo.renaissanceTurnsRemaining > 0)
        assertFalse("wasInCrisis should be reset", civInfo.wasInCrisis)
    }

    @Test
    fun `renaissance bonus decreases over time`() {
        civInfo.renaissanceTurnsRemaining = 15
        val initialBonus = civInfo.stabilityManager.getRenaissanceBonusPercent()
        assertEquals(25f, initialBonus, 0.01f)

        civInfo.renaissanceTurnsRemaining = 7
        val laterBonus = civInfo.stabilityManager.getRenaissanceBonusPercent()
        assertTrue("Bonus should decrease over time", laterBonus < initialBonus)
    }

    @Test
    fun `renaissance does not activate without prior crisis`() {
        civInfo.wasInCrisis = false
        civInfo.stabilityManager.checkRenaissanceTransition(55, 65)

        assertEquals("Renaissance should NOT activate", 0, civInfo.renaissanceTurnsRemaining)
    }

    @Test
    fun `ISI is clamped between 0 and 100`() {
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        // Even with extreme conditions, ISI stays in [0, 100]
        val isi = civInfo.stabilityManager.calculateISI()
        assertTrue("ISI should be >= 0", isi >= 0)
        assertTrue("ISI should be <= 100", isi <= 100)
    }

    @Test
    fun `base stability provides foundation`() {
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        val breakdown = civInfo.stabilityManager.getISIBreakdown()
        assertEquals("Base stability should be 50", 50f, breakdown["Base stability"] ?: 0f, 0.01f)
    }
}
