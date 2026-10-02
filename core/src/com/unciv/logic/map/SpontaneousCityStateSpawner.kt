package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.tile.Tile
import yairm210.purity.annotations.Readonly

/**
 * TW v2 — Spontaneous city-state spawning.
 *
 * Empires no longer expand by sending settlers across the map: every civilisation gets
 * exactly the single initial Settler that founds its capital. From there, all other
 * unfounded territory is colonised organically by **city-states** that emerge naturally
 * every few turns, settling roughly one city per ~6 hexes until the world is full.
 *
 * Major civilisations then expand only by:
 *   1. military conquest;
 *   2. cultural absorption of nearby city-states (see [TileCultureLogic.processCityStateAbsorption]).
 *
 * Spawn cadence: one city-state attempt every [SPAWN_PERIOD] turns. The spawner stops
 * once no eligible site or no available CS nation remains.
 */
object SpontaneousCityStateSpawner {

    private const val SPAWN_PERIOD = 5
    // TW v2 — Cities sit exactly 3 hexes apart: tight grid, no dead-zones, no overlap of working radii.
    private const val MIN_DISTANCE_FROM_CITY = 3
    private const val MAX_DISTANCE_FROM_CITY = 3
    private const val MIN_LAND_NEIGHBOURS = 4      // viable footing — avoid singleton islets

    /** Called once per round from [com.unciv.logic.civilization.managers.TurnManager.startTurn]
     *  on the barbarian civ (the first to process each round). Cheap when not on a spawn tick. */
    fun maybeSpawn(gameInfo: GameInfo) {
        if (gameInfo.turns <= 0) return
        if (gameInfo.turns % SPAWN_PERIOD != 0) return

        val site = findSpawnSite(gameInfo) ?: return
        val nation = SyntheticCityStateNations.pickOrSynthesizeCityStateNation(gameInfo) ?: return

        // Found the new CS
        val cs = Civilization(nation.name)
        cs.playerType = PlayerType.AI
        cs.gameInfo = gameInfo
        gameInfo.civilizations.add(cs)
        cs.setNationTransient()
        cs.setTransients()
        cs.cityStateFunctions.initCityState(
            gameInfo.ruleset,
            gameInfo.gameParameters.startingEra,
            emptySequence(),
            com.unciv.models.ruleset.unique.GameContext(gameInfo = gameInfo).stateBasedRandom("SpontaneousCityStateSpawner.initCityState")
        )

        // Plant the capital using the engine's city-founding machinery
        cs.addCity(site.position)

        // TW v2 — Give the newborn CS a starting army and a worker so it can
        // immediately defend itself and develop its tiles.
        grantStartingUnits(cs, site)

        // Make sure every alive major civ knows the newcomer (otherwise the CS would
        // float in diplomatic limbo and the absorption rules below would never engage).
        for (other in gameInfo.civilizations.filter {
            it.isAlive() && it != cs && !it.isBarbarian
        }) {
            if (!cs.knows(other))
                cs.diplomacyFunctions.makeCivilizationsMeet(other)
        }

        // Notify every major civ that's already aware of the region
        for (other in gameInfo.civilizations.filter {
            it.isAlive() && !it.isBarbarian && it.isMajorCiv()
        }) {
            other.addNotification(
                "A new city-state has emerged: [${cs.civName}]",
                site.position,
                NotificationCategory.General,
                NotificationIcon.Culture,
                cs.civName
            )
        }
    }

    /**
     * Grant the CS a warrior (era-appropriate) + a worker + starting gold.
     *
     * Military unit: picks the era `startingMilitaryUnit` from the most-advanced
     * era whose eraNumber is ≤ the game's current median major-civ era, so a
     * mid-game spawned CS fields something relevant (not always an Ancient Warrior).
     *
     * Worker: plain "Worker" or, if not in ruleset, any civilian that can build improvements.
     *
     * Gold: 50% more than what the starting era grants majors, to compensate for
     * the CS having no taxation income in its early turns.
     */
    private fun grantStartingUnits(cs: Civilization, site: Tile) {
        val gameInfo = cs.gameInfo
        val ruleset = gameInfo.ruleset

        // --- Era for unit selection: median era of living major civs ---
        val majorEraNumbers = gameInfo.civilizations
            .filter { it.isAlive() && it.isMajorCiv() }
            .map { it.getEraNumber() }
        val medianEraNumber = if (majorEraNumbers.isEmpty()) 0
            else majorEraNumbers.sorted()[majorEraNumbers.size / 2]
        val targetEra = ruleset.eras.values
            .filter { it.eraNumber <= medianEraNumber }
            .maxByOrNull { it.eraNumber }
            ?: ruleset.eras.values.minByOrNull { it.eraNumber }
            ?: return

        // --- Military unit ---
        val militaryUnitName = targetEra.startingMilitaryUnit
        val militaryUnit = if (militaryUnitName in ruleset.units)
            cs.getEquivalentUnit(militaryUnitName)
        else null
        if (militaryUnit != null)
            cs.units.placeUnitNearTile(site.position, militaryUnit)

        // --- Starting gold ---
        val startingEraGold = ruleset.eras[gameInfo.gameParameters.startingEra]?.startingGold ?: 0
        val goldBonus = (startingEraGold * 1.5f * gameInfo.speed.goldCostModifier).toInt()
            .coerceAtLeast(50)   // always at least 50 gold so they can buy something
        cs.addGold(goldBonus)
    }

    /** Pick a viable, well-spaced land tile somewhere in the inhabited belt of the map. */
    @Readonly
    private fun findSpawnSite(gameInfo: GameInfo): Tile? {
        val existingCityCenters = gameInfo.civilizations
            .filter { it.isAlive() && !it.isBarbarian }
            .flatMap { civ -> civ.cities.map { it.getCenterTile() } }
        if (existingCityCenters.isEmpty()) return null

        // Deterministic shuffle via turn number so saves replay identically
        val candidates = mutableListOf<Tile>()
        for (tile in gameInfo.tileMap.values) {
            if (!isViableFounding(tile)) continue
            // Min distance from any existing city
            val nearest = existingCityCenters.minOfOrNull { it.aerialDistanceTo(tile) } ?: continue
            if (nearest < MIN_DISTANCE_FROM_CITY) continue
            if (nearest > MAX_DISTANCE_FROM_CITY) continue
            candidates.add(tile)
        }
        if (candidates.isEmpty()) return null

        // Score: prefer rich terrain (grassland / plains / hill), avoid pure tundra/desert
        return candidates.maxByOrNull { scoreFoundingTile(it) }
    }

    @Readonly
    private fun isViableFounding(tile: Tile): Boolean {
        if (tile.isWater) return false
        if (tile.isImpassible()) return false
        if (tile.getOwner() != null) return false
        if (tile.isCityCenter()) return false
        if (tile.naturalWonder != null) return false
        // Enough surrounding land to support a population
        val landNeighbours = tile.neighbors.count { !it.isWater && !it.isImpassible() }
        if (landNeighbours < MIN_LAND_NEIGHBOURS) return false
        // No barbarian / civilian / military unit on the tile (cleanliness)
        if (tile.militaryUnit != null || tile.civilianUnit != null) return false
        return true
    }

    @Readonly
    private fun scoreFoundingTile(tile: Tile): Float {
        var score = 0f
        when (tile.baseTerrain) {
            Constants.grassland -> score += 5f
            Constants.plains -> score += 4f
            "Hill" -> score += 4f
            Constants.desert -> score -= 2f
            Constants.tundra -> score -= 1f
            Constants.snow -> score -= 4f
        }
        if (tile.terrainFeatures.contains("Forest")) score += 1f
        if (tile.terrainFeatures.contains("Floodplains")) score += 3f
        // Resource on tile is a big plus
        if (tile.tileResource != null) score += 3f
        // Coastal is desirable
        if (tile.neighbors.any { it.isWater }) score += 1f
        return score
    }

}
