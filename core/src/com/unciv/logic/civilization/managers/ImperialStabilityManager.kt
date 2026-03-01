package com.unciv.logic.civilization.managers

import com.unciv.logic.city.managers.CityConquestFunctions
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Notification.NotificationCategory
import yairm210.purity.annotations.Readonly
import kotlin.random.Random

/**
 * Territorial Warfare: Imperial Stability Index (ISI) system.
 *
 * Tracks empire stability (0-100) and triggers revolts/sécessions
 * when empires expand beyond their organic capacity.
 */
class ImperialStabilityManager(val civInfo: Civilization) {

    enum class StabilityTier(val minISI: Int) {
        Collapse(0),
        Crisis(20),
        Tensions(40),
        Stable(60),
        GoldenAge(80);

        companion object {
            @Readonly fun fromISI(isi: Int): StabilityTier = when {
                isi >= 80 -> GoldenAge
                isi >= 60 -> Stable
                isi >= 40 -> Tensions
                isi >= 20 -> Crisis
                else -> Collapse
            }
        }
    }

    @Readonly fun getTier(): StabilityTier = StabilityTier.fromISI(civInfo.imperialStability)

    fun calculateISI(): Int {
        val breakdown = getISIBreakdown()
        return breakdown.values.sum().toInt().coerceIn(0, 100)
    }

    fun getISIBreakdown(): HashMap<String, Float> {
        val breakdown = HashMap<String, Float>()
        val currentTurn = civInfo.gameInfo.turns
        val capital = civInfo.getCapital() ?: return breakdown

        // === Positive factors ===

        // Cities owned >20 turns: +1 per city
        val establishedCities = civInfo.cities.count { currentTurn - it.turnAcquired > 20 }
        if (establishedCities > 0) breakdown["Established cities"] = establishedCities.toFloat()

        // Connected to capital: +2 per city
        val connectedCities = civInfo.cities.count { it != capital && it.isConnectedToCapital() }
        if (connectedCities > 0) breakdown["Connected cities"] = connectedCities * 2f

        // Gold: +1 if gold/turn > 0, +2 if treasury > 500
        val goldPerTurn = civInfo.stats.statsForNextTurn.gold
        if (goldPerTurn > 0) breakdown["Positive income"] = 1f
        if (civInfo.gold > 500) breakdown["Gold reserves"] = 2f

        // Peace: +2 if not at war
        if (!civInfo.isAtWar()) breakdown["Peace"] = 2f

        // Culture: +1 if culture/turn > cities × 5
        val culturePerTurn = civInfo.stats.statsForNextTurn.culture
        if (culturePerTurn > civInfo.cities.size * 5) breakdown["Cultural strength"] = 1f

        // Unified religion: +2 if >75% of cities share majority religion
        if (civInfo.cities.size >= 2) {
            val religionCounts = HashMap<String?, Int>()
            for (city in civInfo.cities) {
                val religion = city.religion.getMajorityReligionName()
                if (religion != null) {
                    religionCounts[religion] = (religionCounts[religion] ?: 0) + 1
                }
            }
            val maxReligionCount = religionCounts.values.maxOrNull() ?: 0
            if (maxReligionCount > civInfo.cities.size * 0.75)
                breakdown["Unified religion"] = 2f
        }

        // Base stability: always start from a base of 50
        breakdown["Base stability"] = 50f

        // === Negative factors ===

        // Over-expansion: -1 per city beyond 3 + eraNumber
        val maxComfortableCities = 3 + civInfo.getEraNumber()
        val excessCities = civInfo.cities.size - maxComfortableCities
        if (excessCities > 0) breakdown["Over-expansion"] = -excessCities.toFloat()

        // Recent conquests (<10 turns): -3 per city
        val recentConquests = civInfo.cities.count {
            currentTurn - it.turnAcquired < 10 && it.foundingCivObject != civInfo
        }
        if (recentConquests > 0) breakdown["Recent conquests"] = recentConquests * -3f

        // Cities in resistance: -5 per city
        val resistingCities = civInfo.cities.count { it.isInResistance() }
        if (resistingCities > 0) breakdown["Cities in resistance"] = resistingCities * -5f

        // Distant cities (>15 tiles from capital): -1 per city
        val capitalTile = capital.getCenterTile()
        val distantCities = civInfo.cities.count {
            it != capital && it.getCenterTile().aerialDistanceTo(capitalTile) > 15
        }
        if (distantCities > 0) breakdown["Distant cities"] = -distantCities.toFloat()

        // Gold deficit: -3 if gold/turn < 0
        if (goldPerTurn < 0) breakdown["Gold deficit"] = -3f

        // Multiple wars: -2 per major enemy beyond the first
        val majorEnemies = civInfo.getCivsAtWarWith().count { it.isMajorCiv() }
        if (majorEnemies > 1) breakdown["Multiple wars"] = (majorEnemies - 1) * -2f

        // Military losses: -2 per unit lost this turn
        if (civInfo.unitsLostThisTurn > 0)
            breakdown["Military losses"] = civInfo.unitsLostThisTurn * -2f

        // Foreign cities: -1 per city founded by another civ
        val foreignCities = civInfo.cities.count { it.foundingCivObject != null && it.foundingCivObject != civInfo }
        if (foreignCities > 0) breakdown["Foreign cities"] = -foreignCities.toFloat()

        return breakdown
    }

    fun checkForRevolt() {
        val tier = getTier()
        if (tier != StabilityTier.Crisis && tier != StabilityTier.Collapse) return

        val revoltChance = if (tier == StabilityTier.Collapse) 0.15f else 0.05f
        if (Random.nextFloat() > revoltChance) return

        val capital = civInfo.getCapital() ?: return
        val capitalTile = capital.getCenterTile()
        val currentTurn = civInfo.gameInfo.turns

        // Build revolt candidates with priority scores (higher = more likely to revolt)
        val candidates = civInfo.cities
            .filter { it != capital }
            .map { city ->
                var score = 0
                if (city.foundingCivObject != null && city.foundingCivObject != civInfo
                    && currentTurn - city.turnAcquired < 10) score += 40 // Recent conquest
                if (city.isInResistance()) score += 30
                if (city.getCenterTile().aerialDistanceTo(capitalTile) > 15) score += 20
                if (city.foundingCivObject != null && city.foundingCivObject != civInfo) score += 10
                Pair(city, score)
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }

        if (candidates.isEmpty()) return

        val revoltingCity = candidates.first().first
        val cityName = revoltingCity.name

        // Expulse units before conversion
        for (tile in revoltingCity.getTiles()) {
            for (unit in tile.getUnits().filter { it.civ == civInfo }.toList()) {
                unit.movement.teleportToClosestMoveableTile()
            }
        }

        // Convert to city-state
        CityConquestFunctions(revoltingCity).convertToCityState(civInfo)

        // Stability relief
        civInfo.imperialStability = (civInfo.imperialStability + 15).coerceAtMost(100)

        civInfo.addNotification(
            "[$cityName] has revolted due to imperial instability!",
            NotificationCategory.Cities
        )
    }

    fun checkRenaissanceTransition(previousISI: Int, newISI: Int) {
        val wasCrisis = previousISI < 40 || civInfo.wasInCrisis

        if (previousISI < 40) {
            civInfo.wasInCrisis = true
        }

        if (civInfo.wasInCrisis && newISI >= 60) {
            civInfo.renaissanceTurnsRemaining = 15
            civInfo.wasInCrisis = false
            civInfo.addNotification(
                "Imperial Renaissance! Production and culture bonus for 15 turns!",
                NotificationCategory.General
            )
        }
    }

    fun decrementRenaissance() {
        if (civInfo.renaissanceTurnsRemaining > 0) {
            civInfo.renaissanceTurnsRemaining--
        }
    }

    /** Returns the current renaissance bonus percentage (0-25, decreasing) */
    @Readonly fun getRenaissanceBonusPercent(): Float {
        if (civInfo.renaissanceTurnsRemaining <= 0) return 0f
        return 25f * civInfo.renaissanceTurnsRemaining / 15f
    }
}
