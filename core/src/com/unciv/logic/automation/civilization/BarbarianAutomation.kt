package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.automation.unit.BattleHelper
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.battle.BattleUnitCapture
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType

class BarbarianAutomation(val civInfo: Civilization) {

    fun automate() {
        // ranged go first, after melee and then everyone else
        civInfo.units.getCivUnits().filter { it.baseUnit.isRanged() }.forEach { automateUnit(it) }
        civInfo.units.getCivUnits().filter { it.baseUnit.isMelee() }.forEach { automateUnit(it) }
        civInfo.units.getCivUnits().filter { !it.baseUnit.isRanged() && !it.baseUnit.isMelee() }.forEach { automateUnit(it) }
        // fix buildup of alerts - to shrink saves and ease debugging
        civInfo.popupAlerts.clear()
    }

    private fun automateUnit(unit: MapUnit) {
        if (unit.isCivilian()) automateCapturedCivilian(unit)
        else if (unit.currentTile.improvement == Constants.barbarianEncampment) automateUnitOnEncampment(unit)
        else automateCombatUnit(unit)
    }

    private fun automateCapturedCivilian(unit: MapUnit) {
        // TW: Barbarian settlers seek a good location and found a city-state
        // Optimization: disable settler AI from Modern era onwards (era 5+) — too expensive
        if (unit.hasUnique(UniqueType.FoundCity, GameContext.IgnoreConditionals)) {
            val maxEra = civInfo.gameInfo.civilizations
                .filter { it.isMajorCiv() && it.isAlive() }
                .maxOfOrNull { it.getEraNumber() } ?: 0
            if (maxEra < 5) {
                automateBarbarianSettler(unit)
                return
            } else {
                // Modern+: destroy the settler, no more barbarian city-state founding
                unit.destroy()
                return
            }
        }

        // 1 - Stay on current encampment
        if (unit.currentTile.improvement == Constants.barbarianEncampment) return

        val campTiles = unit.civ.gameInfo.barbarians.encampments.map { unit.civ.gameInfo.tileMap[it.position] }
            .sortedBy { unit.currentTile.aerialDistanceTo(it) }
        val bestCamp = campTiles.firstOrNull { it.civilianUnit == null && unit.movement.canReach(it)}
        if (bestCamp != null)
            unit.movement.headTowards(bestCamp) // 2 - Head towards an encampment
        else
            UnitAutomation.wander(unit) // 3 - Can't find a reachable encampment, wander aimlessly
    }

    /** TW: Barbarian settler AI — searches for a good city location far from existing cities,
     *  can cross oceans. When a suitable tile is found, founds a city-state. */
    private fun automateBarbarianSettler(unit: MapUnit) {
        val currentTile = unit.currentTile

        // Check if current tile is suitable for founding
        if (currentTile.isLand && !currentTile.isImpassible() && isSuitableForCityState(currentTile)) {
            // Found a city-state here
            BattleUnitCapture.barbarianFoundCityState(currentTile, civInfo)
            unit.destroy()
            return
        }

        // Search for a good location within movement range
        val searchRadius = 15
        val bestTile = currentTile.getTilesInDistance(searchRadius)
            .filter { tile ->
                tile.isLand
                && !tile.isImpassible()
                && tile.getOwner() == null
                && tile.militaryUnit == null
                && tile.civilianUnit == null
                && !tile.isCityCenter()
                && isSuitableForCityState(tile)
            }
            .maxByOrNull { scoreCityLocation(it) }

        if (bestTile != null && unit.movement.canReach(bestTile)) {
            unit.movement.headTowards(bestTile)
        } else {
            // Wander towards unexplored territory / far from cities
            val wanderTarget = currentTile.getTilesInDistance(6)
                .filter { it.isLand && !it.isImpassible() && it.getOwner() == null
                    && unit.movement.canReach(it) }
                .maxByOrNull { tile ->
                    val distToNearestCity = civInfo.gameInfo.civilizations
                        .filter { it.isAlive() && !it.isBarbarian }
                        .flatMap { it.cities }
                        .minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: 99
                    distToNearestCity
                }
            if (wanderTarget != null) unit.movement.headTowards(wanderTarget)
            else UnitAutomation.wander(unit)
        }
    }

    /** Check if a tile is suitable for founding a city-state:
     *  at least 4 tiles from any existing city */
    private fun isSuitableForCityState(tile: Tile): Boolean {
        if (!tile.isLand || tile.isImpassible()) return false
        if (tile.getOwner() != null) return false
        val minDistFromCity = 4
        for (civ in civInfo.gameInfo.civilizations) {
            if (!civ.isAlive()) continue
            for (city in civ.cities) {
                if (city.getCenterTile().aerialDistanceTo(tile) < minDistFromCity) return false
            }
        }
        return true
    }

    /** Score a tile for city-state founding. Higher = better location. */
    private fun scoreCityLocation(tile: Tile): Int {
        var score = 0
        // Distance from any civ's city: prefer remote locations
        val minDist = civInfo.gameInfo.civilizations
            .filter { it.isAlive() && !it.isBarbarian }
            .flatMap { it.cities }
            .minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: 20
        score += minDist * 5

        // Prefer tiles with good yields (food + production from terrain)
        for (neighbor in tile.neighbors) {
            if (neighbor.isLand && !neighbor.isImpassible()) score += 2
            if (neighbor.isAdjacentToRiver()) score += 1
        }
        if (tile.isAdjacentToRiver()) score += 5
        if (tile.isCoastalTile()) score += 3

        // Prefer tiles with high barbarian culture (easier to hold)
        val barbCulture = tile.cultureMap["Barbarians"] ?: 0f
        score += (barbCulture * 10).toInt()

        return score
    }

    private fun automateUnitOnEncampment(unit: MapUnit) {
        // 1 - trying to upgrade
        if (UnitAutomation.tryUpgradeUnit(unit)) return

        // 2 - trying to attack somebody - but don't leave the encampment
        if (BattleHelper.tryAttackNearbyEnemy(unit, stayOnTile = true)) return

        // 3 - at least fortifying
        unit.fortifyIfCan()
    }

    private fun automateCombatUnit(unit: MapUnit) {
        // 1 - Try pillaging to restore health (barbs don't auto-heal)
        if (unit.health < 50 && UnitAutomation.tryPillageImprovement(unit, true) && !unit.hasMovement()) return

        // 2 - trying to upgrade
        if (UnitAutomation.tryUpgradeUnit(unit)) return

        // 3 - trying to attack enemy
        // if a embarked melee unit can land and attack next turn, do not attack from water.
        if (BattleHelper.tryDisembarkUnitToAttackPosition(unit)) return
        if (!unit.isCivilian() && BattleHelper.tryAttackNearbyEnemy(unit)) return

        // 4 - trying to pillage tile or route
        while (UnitAutomation.tryPillageImprovement(unit)) {
            if (!unit.hasMovement()) return
        }

        // 5 - TW: infiltrate poorly defended enemy territory to foment rebellion
        // Optimization: disable in Atomic era+ (era 6+) — map is fully colonized, expensive search
        val maxEra = civInfo.gameInfo.civilizations
            .filter { it.isMajorCiv() && it.isAlive() }
            .maxOfOrNull { it.getEraNumber() } ?: 0
        if (maxEra < 6 && tryInfiltrateEnemyTerritory(unit)) return

        // 6 - wander
        UnitAutomation.wander(unit)
    }

    /**
     * Territorial Warfare: barbarians actively seek out poorly defended territory
     * far from city centers to occupy and destabilize through cultural pressure.
     *
     * Priority targets:
     * 1. Tiles already in rebellion (stay and hold)
     * 2. Tiles far from city centers with no garrison
     * 3. Tiles with improvements to pillage later
     */
    private fun tryInfiltrateEnemyTerritory(unit: MapUnit): Boolean {
        if (!unit.hasMovement()) return false

        val currentTile = unit.currentTile

        // If already on a poorly defended enemy tile far from cities, stay put to boost barbarian culture
        if (currentTile.getOwner() != null && !currentTile.getOwner()!!.isBarbarian) {
            val owner = currentTile.getOwner()!!
            val distToNearestCity = owner.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(currentTile) } ?: 99
            val nearbyEnemyMilitary = currentTile.neighbors.any {
                it.militaryUnit != null && !it.militaryUnit!!.civ.isBarbarian
            }
            // Stay if we're deep in enemy territory and no threat nearby
            if (distToNearestCity >= 3 && !nearbyEnemyMilitary) {
                unit.fortifyIfCan()
                return true
            }
        }

        // Search for vulnerable enemy tiles to infiltrate
        val targetTile = findInfiltrationTarget(unit) ?: return false

        unit.movement.headTowards(targetTile)
        return true
    }

    /**
     * Find the best tile for a barbarian to infiltrate.
     * Scores tiles based on: distance from enemy cities, absence of military,
     * presence of improvements (pillage targets), and existing barbarian culture.
     */
    private fun findInfiltrationTarget(unit: MapUnit): Tile? {
        val searchRadius = 8
        val currentTile = unit.currentTile

        return currentTile.getTilesInDistance(searchRadius)
            .filter { tile ->
                val owner = tile.getOwner()
                owner != null
                    && !owner.isBarbarian
                    && !tile.isCityCenter()
                    && tile.militaryUnit == null  // no garrison
                    && !tile.isWater
                    && !tile.isImpassible()
                    && unit.movement.canReach(tile)
            }
            .maxByOrNull { tile -> scoreInfiltrationTarget(tile, currentTile) }
    }

    /** Score a tile for infiltration. Higher = more attractive to barbarians. */
    private fun scoreInfiltrationTarget(tile: Tile, unitTile: Tile): Int {
        val owner = tile.getOwner() ?: return 0
        var score = 0

        // Distance from owner's nearest city: further = better target
        val distToCity = owner.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: 0
        score += distToCity * 10

        // Already in rebellion: very attractive (stay and hold)
        if (tile.rebellionTurns > 0) score += 50

        // High barbarian culture already: easier to tip into rebellion
        val barbCulture = tile.cultureMap["Barbarians"] ?: 0f
        score += (barbCulture * 30).toInt()

        // Low owner culture: vulnerable
        val ownerCulture = tile.cultureMap[owner.civName] ?: 1f
        score += ((1f - ownerCulture) * 20).toInt()

        // Has improvement: can be pillaged for healing/denial
        if (tile.improvement != null && !tile.improvementIsPillaged) score += 15

        // No nearby enemy military: safer approach
        val nearbyMilitary = tile.neighbors.count {
            it.militaryUnit != null && !it.militaryUnit!!.civ.isBarbarian
        }
        score -= nearbyMilitary * 20

        // Prefer closer tiles (don't walk forever)
        val distToUnit = unitTile.aerialDistanceTo(tile)
        score -= distToUnit * 2

        return score
    }

}
