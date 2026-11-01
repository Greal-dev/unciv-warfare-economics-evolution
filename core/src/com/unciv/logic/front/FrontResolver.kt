package com.unciv.logic.front

import com.unciv.logic.GameInfo
import com.unciv.logic.battle.CombatCostCalculator
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.TileCultureLogic
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unit.BaseUnit
import yairm210.purity.annotations.Readonly
import kotlin.math.roundToInt

/**
 * Front mode: resolves, once per round, the pressure that divisions exert on enemy territory.
 *
 * A round ends when the last civilization has played its turn, so every order is already given and the
 * resolution is simultaneous. See docs/superpowers/specs/2026-10-02-mode-front-tranche-verticale-design.md.
 *
 * Iteration order is always derived from civilization order, unit order and tile coordinates, never from
 * hash order, so that two runs with the same seed give the same result.
 */
object FrontResolver {
    /** Name of the only unit type that fights in front mode. */
    const val DIVISION_UNIT_NAME = "Division"

    @Readonly fun isDivision(unit: MapUnit) = DivisionKind.fromUnitName(unit.baseUnit.name) != null

    @Readonly fun isDivisionName(unitName: String) = DivisionKind.fromUnitName(unitName) != null

    @Readonly fun kindOf(unit: MapUnit): DivisionKind = DivisionKind.fromUnitName(unit.baseUnit.name) ?: DivisionKind.Infantry

    /** Citizens of [city] who are neither specialists nor occupied by a division. */
    @Readonly fun workersAvailable(city: City): Int =
        city.population.population - city.population.getNumberOfSpecialists() - divisionsBoundTo(city)

    /** Divisions raised by [city], each one a citizen who no longer works. */
    @Readonly fun divisionsBoundTo(city: City): Int {
        if (!city.civ.gameInfo.gameParameters.frontMode) return 0
        return city.civ.units.getCivUnits().count { isDivision(it) && it.frontHomeCityId == city.id }
    }

    fun stanceOf(unit: MapUnit) = FrontStance.fromName(unit.frontStance)

    /** Technologies that unlock a military unit, per ruleset: the military technologies of a civilization. */
    private val militaryTechsByRuleset = java.util.WeakHashMap<Ruleset, Set<String>>()

    private fun militaryTechs(ruleset: Ruleset): Set<String> = militaryTechsByRuleset.getOrPut(ruleset) {
        ruleset.units.values.filter { it.isMilitary }.flatMap { it.requiredTechs().toList() }.toSet()
    }

    /** Military level of a civilization, from the military technologies it has researched. */
    fun militaryLevel(civ: Civilization): Float {
        val techs = militaryTechs(civ.gameInfo.ruleset)
        return FrontMath.militaryLevel(techs.count { civ.tech.isResearched(it) }, techs.size)
    }

    /** A share of a tile's force provided by one division. */
    private class Contribution(val unit: MapUnit, val force: Float)

    /** Land units other than divisions are no longer produced in front mode: they leave the production queues of the cities. */
    private fun purgeRetiredUnits(gameInfo: GameInfo) {
        for (civ in gameInfo.civilizations)
            for (city in civ.cities) {
                val constructions = city.cityConstructions
                for (index in constructions.constructionQueue.indices.reversed()) {
                    val construction = constructions.getConstruction(constructions.constructionQueue[index])
                    if (construction is BaseUnit && construction.isMilitary && construction.isLandUnit && !isDivisionName(construction.name))
                        constructions.removeFromQueue(index, true)
                }
            }
    }

    fun resolveRound(gameInfo: GameInfo) {
        purgeRetiredUnits(gameInfo)
        // Without a city there is nothing left to pay, supply or recruit them: the divisions of such a civilization disband
        for (civ in gameInfo.civilizations)
            if (civ.cities.isEmpty() && !civ.isBarbarian)
                civ.units.getCivUnits().filter { isDivision(it) }.toList().forEach { it.destroy() }
        // Citizens freed by lost divisions go back to work
        for (civ in gameInfo.civilizations)
            for (city in civ.cities)
                if (city.population.getFreePopulation() > 0) city.shouldReassignPopulation = true

        val divisions = gameInfo.civilizations.flatMap { civ ->
            if (civ.isDefeated()) emptyList() else civ.units.getCivUnits().filter { isDivision(it) }.toList()
        }
        if (divisions.isEmpty()) {
            clearIdleProgress(gameInfo, emptySet())
            return
        }

        val pressures = collectPressures(divisions)
        val losses = LinkedHashMap<MapUnit, LossTally>()
        val inContact = LinkedHashSet<MapUnit>()
        val pressedTiles = LinkedHashSet<Tile>()

        for ((tile, byAttacker) in pressures) {
            // An enemy division standing on the tile defends it wherever the tile belongs, otherwise its owner does
            val occupant = tile.militaryUnit?.takeIf { isDivision(it) && it.civ !in byAttacker.keys }?.civ
            val defenderCiv = occupant ?: tile.getOwner() ?: continue
            val (defenders, resistance) = resistanceOf(tile, defenderCiv, divisions)

            // Several civilizations may press the same tile: only the strongest ratio makes it progress,
            // but all of them take and inflict losses.
            var bestAttacker: Civilization? = null
            var bestRatio = 0f
            for ((attacker, contributions) in byAttacker) {
                val pressure = contributions.sumOf { it.force.toDouble() }.toFloat()
                if (pressure <= 0f) continue
                val ratio = pressure / resistance
                if (bestAttacker == null || ratio > bestRatio) { bestAttacker = attacker; bestRatio = ratio }
                accumulateLosses(losses, inContact, contributions, pressure, defenders, resistance)
            }
            pressedTiles += tile
            // Only a tile that belongs to someone else can change hands: a fight against an intruder just costs troops
            val tileOwner = tile.getOwner()
            if (bestAttacker != null && tileOwner != null && tileOwner != bestAttacker) advance(tile, bestAttacker, bestRatio)
        }

        clearIdleProgress(gameInfo, pressedTiles)
        flipTiles(gameInfo)
        applyLosses(losses)
        updateDivisions(divisions, inContact)
    }

    // region pressure

    /** Contact tiles that a division presses, closest to its token first, limited by its front width. */
    fun pressedTilesOf(division: MapUnit): List<Tile> {
        val stance = stanceOf(division)
        if (!stance.presses) return emptyList()
        val civ = division.civ
        val origin = division.currentTile
        val width = FrontMath.frontWidth(civ.getEraNumber())
        // Tiles chosen by the player come first: the whole force goes to them, concentrated on one or spread over several
        val chosen = validTargets(division)
        if (chosen.isNotEmpty()) return chosen.take(width)
        return origin.getTilesInDistance(FrontMath.ZONE_RADIUS)
            .filter { isContactTile(it, civ) }
            .sortedWith(compareBy<Tile>({ targetPriority(it, civ) }, { it.aerialDistanceTo(origin) }, { it.position.x }, { it.position.y }))
            .take(width)
            .toList()
    }

    /** Adds or removes [tile] from the tiles the player wants the division to press. A tile out of reach or out of contact is refused. */
    fun toggleTarget(division: MapUnit, tile: Tile) {
        if (division.frontTargets.remove(tile.position)) return
        if (tile.aerialDistanceTo(division.currentTile) <= FrontMath.ZONE_RADIUS && isContactTile(tile, division.civ))
            division.frontTargets.add(tile.position)
    }

    /** Cities that can take the citizen a division occupies, the current one included. */
    fun burdenCandidates(division: MapUnit): List<City> = division.civ.cities.filter {
        it.id == division.frontHomeCityId || workersAvailable(it) >= FrontMath.MIN_CITIZENS_AT_WORK + 1
    }

    /** Moves the citizen a division occupies to another city of its civilization. */
    fun reassignBurden(division: MapUnit, city: City) {
        if (division.frontHomeCityId == city.id) return
        val previous = division.civ.cities.firstOrNull { it.id == division.frontHomeCityId }
        division.frontHomeCityId = city.id
        city.population.unassignExtraPopulation()
        city.cityStats.update()
        previous?.shouldReassignPopulation = true
    }

    /** The tiles the player designated that are still in reach and in contact. */
    fun validTargets(division: MapUnit): List<Tile> {
        if (division.frontTargets.isEmpty()) return emptyList()
        val civ = division.civ
        val origin = division.currentTile
        return division.frontTargets.mapNotNull { civ.gameInfo.tileMap.getOrNull(it.x, it.y) }
            .filter { it.aerialDistanceTo(origin) <= FrontMath.ZONE_RADIUS && isContactTile(it, civ) }
            .sortedWith(compareBy<Tile>({ it.aerialDistanceTo(origin) }, { it.position.x }, { it.position.y }))
    }

    /** Order in which a division presses its contact tiles: enemy divisions, then enemy cities, then the rest. */
    private fun targetPriority(tile: Tile, civ: Civilization) = when {
        holdsEnemyDivision(tile, civ) -> 0
        tile.isCityCenter() -> 1
        else -> 2
    }

    private fun holdsEnemyDivision(tile: Tile, civ: Civilization): Boolean {
        val occupant = tile.militaryUnit ?: return false
        return isDivision(occupant) && occupant.civ != civ && civ.isAtWarWith(occupant.civ)
    }

    /**
     * A tile is in contact for [civ] when an enemy division stands on it, wherever it belongs, or when an enemy
     * owns it and it touches the territory or a unit of [civ].
     */
    fun isContactTile(tile: Tile, civ: Civilization): Boolean {
        if (holdsEnemyDivision(tile, civ)) return true
        val owner = tile.getOwner() ?: return false
        if (owner == civ || !civ.isAtWarWith(owner)) return false
        return tile.neighbors.any { it.getOwner() == civ || it.militaryUnit?.civ == civ }
    }

    private fun collectPressures(divisions: List<MapUnit>): LinkedHashMap<Tile, LinkedHashMap<Civilization, MutableList<Contribution>>> {
        val pressures = LinkedHashMap<Tile, LinkedHashMap<Civilization, MutableList<Contribution>>>()
        for (division in divisions) {
            val tiles = pressedTilesOf(division)
            if (tiles.isEmpty()) continue
            val civ = division.civ
            val stance = stanceOf(division)
            val supply = FrontMath.supplyFactor(CombatCostCalculator.computeDistanceFactor(civ, division.currentTile))
            val total = FrontMath.pressure(division.health, militaryLevel(civ), stance, supply, kindOf(division))
            val perTile = total / tiles.size
            for (tile in tiles)
                pressures.getOrPut(tile) { LinkedHashMap() }
                    .getOrPut(civ) { ArrayList() }
                    .add(Contribution(division, perTile))
        }
        return pressures
    }

    /** Divisions of [owner] covering [tile], and the resistance the tile opposes. */
    private fun resistanceOf(tile: Tile, owner: Civilization, divisions: List<MapUnit>): Pair<List<Contribution>, Float> {
        val defenders = divisions
            .filter { it.civ == owner && it.currentTile.aerialDistanceTo(tile) <= FrontMath.ZONE_RADIUS }
            .map {
                Contribution(it, FrontMath.resistance(it.health, militaryLevel(owner), stanceOf(it), it.frontEntrenchment, kindOf(it)))
            }
        // Local sympathy only protects a tile that really belongs to the defender
        val sympathy = if (tile.getOwner() == owner) FrontMath.culturalSympathy(TileCultureLogic.getFriendlyShare(tile, owner)) else 0f
        val resistance = FrontMath.tileResistance(
            defenders.sumOf { it.force.toDouble() }.toFloat(), defenders.isNotEmpty(), tile.getDefensiveBonus(), sympathy,
            if (tile.isCityCenter() && tile.getOwner() == owner) FrontMath.CITY_DEFENSE_BONUS else 0f
        )
        return Pair(defenders, resistance)
    }

    // endregion
    // region progress and ownership

    private fun advance(tile: Tile, attacker: Civilization, ratio: Float) {
        if (tile.frontAttacker != attacker.civName) {
            tile.frontAttacker = attacker.civName
            tile.frontProgress = 0f
        }
        tile.frontProgress += FrontMath.progressGain(ratio)
    }

    private fun clearIdleProgress(gameInfo: GameInfo, pressed: Set<Tile>) {
        for (tile in gameInfo.tileMap.values) {
            if (tile.frontProgress <= 0f || tile in pressed) continue
            tile.frontProgress = FrontMath.idleProgress(tile.frontProgress)
            if (tile.frontProgress == 0f) tile.frontAttacker = null
        }
    }

    private fun flipTiles(gameInfo: GameInfo) {
        // tileMap.values has a stable order; snapshot because flipping changes ownership as we go
        val ready = gameInfo.tileMap.values.filter { it.frontProgress >= FrontMath.FLIP_PROGRESS }.toList()
        for (tile in ready) {
            val attackerName = tile.frontAttacker ?: continue
            val attacker = gameInfo.civilizations.firstOrNull { it.civName == attackerName } ?: continue
            val previousOwner = tile.getOwner()
            // A city is taken by the pressure on its centre, once the attacker is in contact with it.
            if (tile.isCityCenter() && previousOwner != null) {
                if (!isContactTile(tile, attacker)) { tile.frontProgress = FrontMath.FLIP_PROGRESS - 1f; continue }
                val city = tile.getCity() ?: continue
                tile.frontProgress = 0f
                tile.frontAttacker = null
                previousOwner.addNotification("After the pressure of the enemy front, [${city.name}] has fallen to [${attacker.civName}]!",
                    tile.position, NotificationCategory.War, NotificationIcon.Death)
                attacker.addNotification("Our front has taken [${city.name}] from [${previousOwner.civName}]!",
                    tile.position, NotificationCategory.War, NotificationIcon.War)
                city.puppetCity(attacker)
                continue
            }
            // A tile that would not touch the winner's territory waits instead of creating an enclave.
            val reachable = previousOwner != null && tile.neighbors.any { it.getOwner() == attacker }
            if (!reachable) { tile.frontProgress = FrontMath.FLIP_PROGRESS - 1f; continue }

            val city = nearestCity(attacker, tile) ?: continue
            city.expansion.takeOwnership(tile)
            tile.frontProgress = 0f
            tile.frontAttacker = null
            attacker.addNotification("Our front took a tile from [${previousOwner!!.civName}]!",
                tile.position, NotificationCategory.War, NotificationIcon.War)
            previousOwner.addNotification("Enemy pressure took a tile from us!",
                tile.position, NotificationCategory.War, NotificationIcon.War)
        }
    }

    /** The citizen occupied by a division starts working again at the next reassignment. */
    private fun freeCitizen(division: MapUnit) {
        division.civ.cities.firstOrNull { it.id == division.frontHomeCityId }?.shouldReassignPopulation = true
    }

    private fun nearestCity(civ: Civilization, tile: Tile): City? =
        civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }

    // endregion
    // region losses and upkeep of divisions

    private fun accumulateLosses(
        losses: MutableMap<MapUnit, LossTally>, inContact: MutableSet<MapUnit>,
        attackers: List<Contribution>, pressure: Float,
        defenders: List<Contribution>, resistance: Float
    ) {
        val dealtByDefenders = weightedDealt(defenders)
        val dealtByAttackers = weightedDealt(attackers)
        for (attacker in attackers) {
            inContact += attacker.unit
            val loss = FrontMath.loss(resistance, pressure, attacker.force / pressure,
                stanceOf(attacker.unit).taken, dealtByDefenders)
            losses.getOrPut(attacker.unit) { LossTally() }.add(loss)
        }
        val defenderForce = defenders.sumOf { it.force.toDouble() }.toFloat()
        for (defender in defenders) {
            inContact += defender.unit
            val loss = FrontMath.loss(pressure, resistance, defender.force / defenderForce,
                stanceOf(defender.unit).taken, dealtByAttackers)
            losses.getOrPut(defender.unit) { LossTally() }.add(loss)
        }
    }

    /** Average [FrontStance.dealt] of a side, weighted by force; 1 when nobody is there. */
    private fun weightedDealt(side: List<Contribution>): Float {
        val total = side.sumOf { it.force.toDouble() }.toFloat()
        if (total <= 0f) return 1f
        return side.sumOf { (stanceOf(it.unit).dealt * it.force).toDouble() }.toFloat() / total
    }

    /**
     * Losses of a division over the tiles it fights on. They are averaged, not summed: a division
     * spread over four tiles is not four times as exposed, its troops are only stretched over them.
     */
    private class LossTally {
        private var sum = 0f
        private var tiles = 0
        fun add(loss: Float) { sum += loss; tiles++ }
        fun average() = if (tiles == 0) 0f else sum / tiles
    }

    private fun applyLosses(losses: Map<MapUnit, LossTally>) {
        for ((unit, tally) in losses) unit.health = (unit.health - tally.average().roundToInt()).coerceAtLeast(0)
    }

    private fun updateDivisions(divisions: List<MapUnit>, inContact: Set<MapUnit>) {
        for (division in divisions) {
            if (division.health <= FrontMath.DISSOLVE_HEALTH) {
                division.civ.addNotification("Our [${division.name}] has broken up!",
                    division.currentTile.position, NotificationCategory.War, NotificationIcon.Death)
                freeCitizen(division)
                division.destroy()
                continue
            }
            division.frontTargets.removeAll { hex ->
                val tile = division.civ.gameInfo.tileMap.getOrNull(hex.x, hex.y)
                tile == null || tile !in validTargets(division)
            }
            val stance = stanceOf(division)
            // Tell a human player why an offensive does nothing
            if (stance.presses && division.civ.isHuman() && pressedTilesOf(division).isEmpty())
                division.civ.addNotification("Our [${division.name}] has no enemy tile or division within reach of its zone.",
                    division.currentTile.position, NotificationCategory.War, NotificationIcon.War)
            val moved = division.frontAnchor != division.currentTile.position
            division.frontEntrenchment = FrontMath.nextEntrenchment(division.frontEntrenchment, stance, moved)
            division.frontAnchor = division.currentTile.position

            if (stance.fallsBack) stepBack(division)
            reinforce(division, stance, division in inContact)
        }
    }

    /** Withdrawal: the token steps toward the nearest own city, trading space for health. */
    private fun stepBack(division: MapUnit) {
        val civ = division.civ
        val here = division.currentTile
        val distance = { tile: Tile -> civ.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: Int.MAX_VALUE }
        val current = distance(here)
        val target = here.neighbors
            .filter { distance(it) < current && division.movement.canMoveTo(it) }
            .minWithOrNull(compareBy<Tile>({ distance(it) }, { it.position.x }, { it.position.y })) ?: return
        division.removeFromTile()
        division.putInTile(target)
        division.frontAnchor = target.position
        division.frontEntrenchment = 0f
    }

    /** Health points a division regains, paid for in gold, only inside supplied own territory. */
    private fun reinforce(division: MapUnit, stance: FrontStance, inContact: Boolean) {
        val civ = division.civ
        val tile = division.currentTile
        if (tile.getOwner() != civ) return
        if (CombatCostCalculator.computeDistanceFactor(civ, tile) > FrontMath.SUPPLY_DISTANCE_FOR_REINFORCEMENT) return
        val wanted = when {
            !inContact -> FrontMath.REINFORCE_IN_SUPPLY
            stance == FrontStance.Defensive -> FrontMath.REINFORCE_IN_CONTACT
            else -> 0
        }
        val missing = 100 - division.health
        val points = minOf(wanted, missing)
        if (points <= 0) return
        val costPerPoint = 0.5f * (1 + civ.getEraNumber()) * stance.reinforcementCost
        val affordable = if (costPerPoint <= 0f) points else minOf(points, (civ.gold / costPerPoint).toInt())
        if (affordable <= 0) return
        civ.addGold(-(affordable * costPerPoint).roundToInt())
        division.health += affordable
    }

    // endregion
}
