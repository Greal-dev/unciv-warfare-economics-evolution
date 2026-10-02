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

    fun isDivision(unit: MapUnit) = unit.baseUnit.name == DIVISION_UNIT_NAME

    fun stanceOf(unit: MapUnit) = FrontStance.fromName(unit.frontStance)

    /** A share of a tile's force provided by one division. */
    private class Contribution(val unit: MapUnit, val force: Float)

    fun resolveRound(gameInfo: GameInfo) {
        val divisions = gameInfo.civilizations.flatMap { civ ->
            if (civ.isDefeated()) emptyList() else civ.units.getCivUnits().filter { isDivision(it) }.toList()
        }
        if (divisions.isEmpty()) {
            clearIdleProgress(gameInfo, emptySet())
            return
        }

        val pressures = collectPressures(divisions)
        val losses = LinkedHashMap<MapUnit, Float>()
        val inContact = LinkedHashSet<MapUnit>()
        val pressedTiles = LinkedHashSet<Tile>()

        for ((tile, byAttacker) in pressures) {
            val owner = tile.getOwner() ?: continue
            val (defenders, resistance) = resistanceOf(tile, owner, divisions)

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
            if (bestAttacker != null) advance(tile, bestAttacker, bestRatio)
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
        return origin.getTilesInDistance(FrontMath.ZONE_RADIUS)
            .filter { isContactTile(it, civ) }
            .sortedWith(compareBy<Tile>({ it.aerialDistanceTo(origin) }, { it.position.x }, { it.position.y }))
            .take(width)
            .toList()
    }

    /** A tile is in contact for [civ] when an enemy owns it and it touches the territory or a unit of [civ]. */
    fun isContactTile(tile: Tile, civ: Civilization): Boolean {
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
            val total = FrontMath.pressure(division.health, civ.getEraNumber(), stance, supply)
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
                Contribution(it, FrontMath.resistance(it.health, owner.getEraNumber(), stanceOf(it), it.frontEntrenchment))
            }
        val sympathy = FrontMath.culturalSympathy(TileCultureLogic.getFriendlyShare(tile, owner))
        val resistance = FrontMath.tileResistance(
            defenders.sumOf { it.force.toDouble() }.toFloat(), defenders.isNotEmpty(), tile.getDefensiveBonus(), sympathy
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
            // A city centre never changes hands directly: the siege rule takes the city once its surroundings fall.
            // A tile that would not touch the winner's territory waits instead of creating an enclave.
            val reachable = !tile.isCityCenter() && previousOwner != null &&
                tile.neighbors.any { it.getOwner() == attacker }
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

    private fun nearestCity(civ: Civilization, tile: Tile): City? =
        civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }

    // endregion
    // region losses and upkeep of divisions

    private fun accumulateLosses(
        losses: MutableMap<MapUnit, Float>, inContact: MutableSet<MapUnit>,
        attackers: List<Contribution>, pressure: Float,
        defenders: List<Contribution>, resistance: Float
    ) {
        val dealtByDefenders = weightedDealt(defenders)
        val dealtByAttackers = weightedDealt(attackers)
        for (attacker in attackers) {
            inContact += attacker.unit
            val loss = FrontMath.loss(resistance, pressure, attacker.force / pressure,
                stanceOf(attacker.unit).taken, dealtByDefenders)
            losses[attacker.unit] = (losses[attacker.unit] ?: 0f) + loss
        }
        val defenderForce = defenders.sumOf { it.force.toDouble() }.toFloat()
        for (defender in defenders) {
            inContact += defender.unit
            val loss = FrontMath.loss(pressure, resistance, defender.force / defenderForce,
                stanceOf(defender.unit).taken, dealtByAttackers)
            losses[defender.unit] = (losses[defender.unit] ?: 0f) + loss
        }
    }

    /** Average [FrontStance.dealt] of a side, weighted by force; 1 when nobody is there. */
    private fun weightedDealt(side: List<Contribution>): Float {
        val total = side.sumOf { it.force.toDouble() }.toFloat()
        if (total <= 0f) return 1f
        return side.sumOf { (stanceOf(it.unit).dealt * it.force).toDouble() }.toFloat() / total
    }

    private fun applyLosses(losses: Map<MapUnit, Float>) {
        for ((unit, loss) in losses) unit.health = (unit.health - loss.roundToInt()).coerceAtLeast(0)
    }

    private fun updateDivisions(divisions: List<MapUnit>, inContact: Set<MapUnit>) {
        for (division in divisions) {
            if (division.health <= FrontMath.DISSOLVE_HEALTH) {
                division.civ.addNotification("Our [${division.name}] has broken up!",
                    division.currentTile.position, NotificationCategory.War, NotificationIcon.Death)
                division.destroy()
                continue
            }
            val stance = stanceOf(division)
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
