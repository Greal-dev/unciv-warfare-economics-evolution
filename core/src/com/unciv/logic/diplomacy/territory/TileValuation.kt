package com.unciv.logic.diplomacy.territory

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.TileCultureLogic
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.ResourceType
import yairm210.purity.annotations.Readonly

/**
 * Asymmetric per-civilization valuation of a tile.
 *
 * value(T, X) = baseValue(T) × proximityFactor(T, X) × cultureFactor(T, X)
 *
 * Proximity favors tiles close to X's nearest city; culture favors tiles whose
 * population is largely composed of X's culture. The same tile therefore has a
 * different value for each civilization, which is the engine of natural border
 * harmonization through voluntary trade.
 */
object TileValuation {

    private const val PROXIMITY_BASE = 2.0f
    private const val PROXIMITY_DECAY = 0.15f
    private const val PROXIMITY_FLOOR = 0.05f
    private const val CULTURE_BASE = 0.3f
    private const val CULTURE_RANGE = 1.7f

    @Readonly
    fun value(tile: Tile, civ: Civilization): Float {
        val base = baseValue(tile)
        val proximity = proximityFactor(tile, civ)
        val culture = cultureFactor(tile, civ)
        return base * proximity * culture
    }

    @Readonly
    fun baseValue(tile: Tile): Float {
        // Use civ-agnostic stats (no city, no observing civ) for a stable baseline.
        val stats = tile.stats.getTileStats(null, null)
        var v = stats.food + stats.production + stats.gold + stats.science + stats.culture

        val resource = tile.tileResource
        if (resource != null) {
            v += when (resource.resourceType) {
                ResourceType.Luxury -> 50f
                ResourceType.Strategic -> 30f
                ResourceType.Bonus -> 10f
            }
        }

        if (tile.isNaturalWonder()) v *= 3f
        if (tile.isAdjacentToRiver()) v += 5f
        if (tile.isCoastalTile()) v += 5f

        return v.coerceAtLeast(1f)
    }

    @Readonly
    fun proximityFactor(tile: Tile, civ: Civilization): Float {
        if (civ.cities.isEmpty()) return PROXIMITY_FLOOR
        var minDist = Int.MAX_VALUE
        for (city in civ.cities) {
            val d = city.getCenterTile().aerialDistanceTo(tile)
            if (d < minDist) minDist = d
        }
        if (minDist == Int.MAX_VALUE) return PROXIMITY_FLOOR
        val raw = PROXIMITY_BASE - PROXIMITY_DECAY * minDist
        return raw.coerceIn(PROXIMITY_FLOOR, PROXIMITY_BASE)
    }

    @Readonly
    fun cultureFactor(tile: Tile, civ: Civilization): Float {
        val share = TileCultureLogic.getFriendlyShare(tile, civ)
        return CULTURE_BASE + CULTURE_RANGE * share
    }
}
