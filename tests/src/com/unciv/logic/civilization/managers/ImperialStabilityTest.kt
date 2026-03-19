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

    // === R1: Cultural Identity tests ===

    @Test
    fun `cultural identity starts at 0 for new city`() {
        val city = testGame.addCity(civInfo, testGame.tileMap[0, 0])
        assertEquals(0, city.culturalIdentity)
    }

    @Test
    fun `cultural identity creates ISI penalty`() {
        val city = testGame.addCity(civInfo, testGame.tileMap[0, 0])
        city.culturalIdentity = 100  // Just conquered foreign city

        val breakdown = civInfo.stabilityManager.getISIBreakdown()
        val penalty = breakdown["Cultural identity"] ?: 0f
        assertEquals("Cultural identity at 100 should give -3 ISI", -3f, penalty, 0.01f)
    }

    @Test
    fun `cultural identity of 0 creates no penalty`() {
        val city = testGame.addCity(civInfo, testGame.tileMap[0, 0])
        city.culturalIdentity = 0

        val breakdown = civInfo.stabilityManager.getISIBreakdown()
        assertNull("No cultural identity penalty expected", breakdown["Cultural identity"])
    }

    @Test
    fun `cultural identity is cloned correctly`() {
        val city = testGame.addCity(civInfo, testGame.tileMap[0, 0])
        city.culturalIdentity = 75
        val clone = city.clone()
        assertEquals(75, clone.culturalIdentity)
    }

    // === R6: Demographic Shock tests ===

    @Test
    fun `demographic shock does not trigger at high ISI`() {
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        civInfo.imperialStability = 50
        civInfo.demographicShockCitiesThisTurn = 0
        civInfo.stabilityManager.checkForDemographicShock()
        assertEquals("No shock at ISI 50", 0, civInfo.demographicShockCitiesThisTurn)
    }

    @Test
    fun `demographic shock penalty appears in ISI breakdown`() {
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        civInfo.demographicShockCitiesThisTurn = 3

        val breakdown = civInfo.stabilityManager.getISIBreakdown()
        assertEquals("3 cities shocked = -15 ISI", -15f, breakdown["Demographic shock"] ?: 0f, 0.01f)
    }

    // === R2: Civil War tests ===

    @Test
    fun `civil war requires collapse tier`() {
        civInfo.imperialStability = 50  // Tensions, not Collapse
        assertFalse(civInfo.stabilityManager.checkForCivilWar())
    }

    @Test
    fun `civil war requires 5+ cities`() {
        civInfo.imperialStability = 5  // Collapse
        // Only 3 cities
        testGame.addCity(civInfo, testGame.tileMap[0, 0])
        testGame.addCity(civInfo, testGame.tileMap[2, 0])
        testGame.addCity(civInfo, testGame.tileMap[-2, 0])
        assertFalse(civInfo.stabilityManager.checkForCivilWar())
    }

    @Test
    fun `civil war cannot happen twice`() {
        civInfo.hasSufferedCivilWar = true
        civInfo.imperialStability = 5
        assertFalse(civInfo.stabilityManager.checkForCivilWar())
    }

    @Test
    fun `hasSufferedCivilWar is cloned correctly`() {
        civInfo.hasSufferedCivilWar = true
        val clone = civInfo.clone()
        assertTrue(clone.hasSufferedCivilWar)
    }

    @Test
    fun `demographicShockCitiesThisTurn is cloned correctly`() {
        civInfo.demographicShockCitiesThisTurn = 4
        val clone = civInfo.clone()
        assertEquals(4, clone.demographicShockCitiesThisTurn)
    }

    @Test
    fun `TW fields are cloned correctly`() {
        civInfo.warExperienceBonus = 15
        civInfo.turnsInIndustrialEra = 42
        civInfo.imperialStability = 35
        civInfo.renaissanceTurnsRemaining = 10
        civInfo.wasInCrisis = true
        civInfo.unitsLostThisTurn = 3

        val clone = civInfo.clone()
        assertEquals(15, clone.warExperienceBonus)
        assertEquals(42, clone.turnsInIndustrialEra)
        assertEquals(35, clone.imperialStability)
        assertEquals(10, clone.renaissanceTurnsRemaining)
        assertTrue(clone.wasInCrisis)
        assertEquals(3, clone.unitsLostThisTurn)
    }
}
