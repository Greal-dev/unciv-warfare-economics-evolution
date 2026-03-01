package com.unciv.logic.civilization.managers

import com.unciv.logic.city.managers.CityConquestFunctions
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Notification.NotificationCategory
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
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

        // Cultural identity: scaled penalty based on how recently conquered each city is
        val culturalPenalty = civInfo.cities.sumOf { it.culturalIdentity.toDouble() } * -0.03
        if (culturalPenalty < 0) breakdown["Cultural identity"] = culturalPenalty.toFloat()

        // Demographic shock: -5 per city affected this turn
        if (civInfo.demographicShockCitiesThisTurn > 0)
            breakdown["Demographic shock"] = civInfo.demographicShockCitiesThisTurn * -5f

        return breakdown
    }

    /**
     * Civil War: when in Collapse with 5+ cities and 3+ eligible revolt candidates,
     * the empire splits into loyalists and rebels (a new major civ).
     * One-time event per civilization.
     */
    fun checkForCivilWar(): Boolean {
        val tier = getTier()
        if (tier != StabilityTier.Collapse) return false
        if (civInfo.cities.size < 5) return false
        if (civInfo.hasSufferedCivilWar) return false

        val capital = civInfo.getCapital() ?: return false
        val capitalTile = capital.getCenterTile()
        val currentTurn = civInfo.gameInfo.turns

        // Count eligible revolt cities (cultural identity > 30, in resistance, distant, or recent conquest)
        val eligibleCities = civInfo.cities.filter { city ->
            city != capital && (
                city.culturalIdentity > 30
                || city.isInResistance()
                || city.getCenterTile().aerialDistanceTo(capitalTile) > 15
                || (city.foundingCivObject != null && city.foundingCivObject != civInfo
                    && currentTurn - city.turnAcquired < 10)
            )
        }
        if (eligibleCities.size < 3) return false

        // Find an unused major nation in the ruleset
        val gameInfo = civInfo.gameInfo
        val usedNations = gameInfo.civilizations.map { it.civName }.toSet()
        val availableNation = gameInfo.ruleset.nations.values.firstOrNull {
            it.isMajorCiv && it.name !in usedNations
        } ?: return false

        // Score all non-capital cities: higher = more likely to become rebel
        val scoredCities = civInfo.cities
            .filter { it != capital }
            .map { city ->
                var score = 0.0
                score += city.getCenterTile().aerialDistanceTo(capitalTile).toDouble()
                score += city.culturalIdentity.toDouble()
                if (!city.isConnectedToCapital()) score += 50
                if (city.isInResistance()) score += 30
                Pair(city, score)
            }
            .sortedByDescending { it.second }

        // Split: top half becomes rebels
        val rebelCityCount = scoredCities.size / 2
        if (rebelCityCount < 2) return false
        val rebelCities = scoredCities.take(rebelCityCount).map { it.first }

        // Create rebel civilization
        val rebelCiv = Civilization(availableNation.name)
        rebelCiv.playerType = PlayerType.AI
        rebelCiv.gameInfo = gameInfo
        gameInfo.civilizations.add(rebelCiv)
        rebelCiv.setNationTransient()

        // Copy tech and policies
        rebelCiv.tech = civInfo.tech.clone()
        rebelCiv.policies = civInfo.policies.clone()

        // Proportional gold split
        val totalCities = civInfo.cities.size
        val rebelGoldShare = civInfo.gold * rebelCityCount / totalCities
        rebelCiv.addGold(rebelGoldShare)
        civInfo.addGold(-rebelGoldShare)

        // Transfer cities to rebel civ
        for (city in rebelCities.toList()) {
            CityConquestFunctions(city).moveToCiv(rebelCiv)
            city.culturalIdentity = 0  // Reset: this is now "their" city
        }

        // Transfer units on rebel tiles
        val rebelTiles = rebelCiv.cities.flatMap { it.getTiles().toList() }.toSet()
        for (unit in civInfo.units.getCivUnits().toList()) {
            if (unit.currentTile in rebelTiles) {
                unit.capturedBy(rebelCiv)
            }
        }

        // Set up diplomacy: rebels at war with loyalists
        rebelCiv.diplomacyFunctions.makeCivilizationsMeet(civInfo)
        rebelCiv.getDiplomacyManager(civInfo)!!.diplomaticStatus = DiplomaticStatus.War
        civInfo.getDiplomacyManager(rebelCiv)!!.diplomaticStatus = DiplomaticStatus.War

        // Rebels inherit existing wars and meet all known civs
        for (otherCiv in civInfo.getKnownCivs().toList()) {
            if (otherCiv == rebelCiv) continue
            if (!rebelCiv.knows(otherCiv))
                rebelCiv.diplomacyFunctions.makeCivilizationsMeet(otherCiv)
            // Inherit wars
            if (civInfo.isAtWarWith(otherCiv)) {
                rebelCiv.getDiplomacyManager(otherCiv)!!.diplomaticStatus = DiplomaticStatus.War
                otherCiv.getDiplomacyManager(rebelCiv)!!.diplomaticStatus = DiplomaticStatus.War
            }
        }

        // ISI adjustments
        rebelCiv.imperialStability = 50
        civInfo.imperialStability = (civInfo.imperialStability + 20).coerceAtMost(100)
        civInfo.hasSufferedCivilWar = true

        // Notifications to all civs
        val message = "Civil war! [${availableNation.name}] has broken away from [${civInfo.civName}] with $rebelCityCount cities!"
        for (civ in gameInfo.civilizations) {
            if (civ.isAlive() && (civ.knows(civInfo) || civ == civInfo || civ == rebelCiv))
                civ.addNotification(message, NotificationCategory.Diplomacy)
        }

        return true
    }

    fun checkForRevolt() {
        val tier = getTier()
        if (tier != StabilityTier.Crisis && tier != StabilityTier.Collapse) return

        // Civil war takes priority over individual revolts
        if (tier == StabilityTier.Collapse && checkForCivilWar()) return

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
                score += city.culturalIdentity / 2  // max +50 for identity at 100
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

    /**
     * Demographic shock (plague/famine): when ISI < 30, 2% chance per turn.
     * Ground zero is a random city without Medical Lab. Population loss is -25% to -50%.
     * Spreads to connected cities (30% chance each) without Medical Lab.
     * Buildings mitigate: Medical Lab = immune, Hospital = damage /3, Aqueduct = damage /2.
     */
    fun checkForDemographicShock() {
        if (civInfo.imperialStability >= 30) return
        if (Random.nextFloat() > 0.02f) return
        if (civInfo.cities.isEmpty()) return

        val vulnerableCities = civInfo.cities.filter {
            !it.cityConstructions.containsBuildingOrEquivalent("Medical Lab")
        }
        if (vulnerableCities.isEmpty()) return

        val groundZero = vulnerableCities.random()
        val affectedCities = mutableSetOf(groundZero)

        // Propagation to connected cities
        for (city in civInfo.cities) {
            if (city == groundZero) continue
            if (city.cityConstructions.containsBuildingOrEquivalent("Medical Lab")) continue
            if (!city.isConnectedToCapital()) continue
            if (Random.nextFloat() < 0.30f) {
                affectedCities.add(city)
            }
        }

        for (city in affectedCities) {
            val baseLossPercent = 25 + Random.nextInt(26) // 25-50%
            var effectiveLoss = baseLossPercent

            // Building mitigation
            if (city.cityConstructions.containsBuildingOrEquivalent("Hospital"))
                effectiveLoss /= 3
            else if (city.cityConstructions.containsBuildingOrEquivalent("Aqueduct"))
                effectiveLoss /= 2

            val popLoss = (city.population.population * effectiveLoss / 100).coerceAtLeast(0)
            val newPop = (city.population.population - popLoss).coerceAtLeast(1)
            if (newPop < city.population.population) {
                city.population.setPopulation(newPop)
            }
        }

        civInfo.demographicShockCitiesThisTurn = affectedCities.size

        val shockType = if (Random.nextBoolean()) "plague" else "famine"
        civInfo.addNotification(
            "A $shockType has struck [${groundZero.name}] and spread to ${affectedCities.size} cities! (ISI penalty: -${affectedCities.size * 5})",
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
