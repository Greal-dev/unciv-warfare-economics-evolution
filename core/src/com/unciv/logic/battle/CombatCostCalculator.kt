package com.unciv.logic.battle

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.TileCultureLogic
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import yairm210.purity.annotations.Readonly

/**
 * Territorial Warfare: economic cost of combat damage.
 *
 * Each HP lost in battle costs the unit's owner gold, scaled by:
 *   - Era inflation (linear in (era-1), so Antiquity is free)
 *   - Logistics: distance to closest owned city, halved if the unit's tile has a road
 *   - Cultural homogeneity of the tile (foreign culture costs more)
 *
 * Formula:  cost = hpLost × max(0, era-1) × distFactor × cultureFactor × K
 *           distFactor    = D × (1 - 0.5·R)   where D = aerial distance, R ∈ {0,1}
 *           cultureFactor = 1 + 0.8·(1 - C)   where C = friendly culture share
 *
 * Cost is charged at the moment of damage so the player can evaluate the price
 * of an attack at the same time as the attack itself.
 */
object CombatCostCalculator {

    private const val K = 0.06f
    private const val ROAD_DISCOUNT = 0.5f
    private const val FOREIGN_CULTURE_PENALTY = 0.8f
    /** Attacker pays more (offensive logistics is harder than defensive). */
    const val ATTACKER_MULTIPLIER = 1.5f
    /** Defender pays less (home turf, fortified positions). */
    const val DEFENDER_MULTIPLIER = 0.7f

    @Readonly
    fun calculate(combatant: ICombatant, hpLost: Int, roleMultiplier: Float = 1.0f): Int {
        if (hpLost <= 0) return 0
        if (combatant !is MapUnitCombatant) return 0
        val civ = combatant.getCivInfo()
        if (civ.isBarbarian || civ.isSpectator()) return 0
        val effectiveEra = (civ.getEraNumber() - 1).coerceAtLeast(0)
        if (effectiveEra == 0) return 0

        val tile = combatant.getTile()
        val distFactor = computeDistanceFactor(civ, tile)
        if (distFactor <= 0f) return 0
        val cultureFactor = computeCultureFactor(tile, civ)

        val raw = hpLost.toFloat() * effectiveEra * distFactor * cultureFactor * K * roleMultiplier
        return raw.toInt()
    }

    fun applyCost(combatant: ICombatant, hpLost: Int, roleMultiplier: Float = 1.0f): Int {
        val cost = calculate(combatant, hpLost, roleMultiplier)
        if (cost <= 0) return 0
        val civ = (combatant as MapUnitCombatant).getCivInfo()
        civ.addGold(-cost)
        return cost
    }

    @Readonly
    private fun computeDistanceFactor(civ: Civilization, tile: Tile): Float {
        if (civ.cities.isEmpty()) return 0f
        var minDist = Int.MAX_VALUE
        for (city in civ.cities) {
            val d = city.getCenterTile().aerialDistanceTo(tile)
            if (d < minDist) minDist = d
            if (minDist == 0) break
        }
        if (minDist == 0 || minDist == Int.MAX_VALUE) return 0f
        val hasRoad = tile.roadStatus != RoadStatus.None
        val roadCoef = if (hasRoad) 1f - ROAD_DISCOUNT else 1f
        return minDist * roadCoef
    }

    @Readonly
    private fun computeCultureFactor(tile: Tile, civ: Civilization): Float {
        val friendlyShare = TileCultureLogic.getFriendlyShare(tile, civ)
        return 1f + FOREIGN_CULTURE_PENALTY * (1f - friendlyShare)
    }
}
