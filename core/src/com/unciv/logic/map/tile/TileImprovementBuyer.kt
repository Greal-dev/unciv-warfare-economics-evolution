package com.unciv.logic.map.tile

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import yairm210.purity.annotations.Readonly

/**
 * TW v2 — Public Works system: Workers are obsolete. The civilization buys tile
 * improvements directly from its treasury. Cost scales with era, distance from
 * the nearest city, and whether the target tile is outside controlled territory.
 *
 * Pricing rules:
 *   - Base by era (Ancient → Information): 150, 200, 250, 300, 350, 400, 450, 500 gold (+50 / era)
 *   - Road: flat 100 gold (era-independent — road tech still gates access)
 *   - Railroad: flat 200 gold
 *   - Great Improvements (Academy/Manufactory/Customs House/Holy Site/Landmark/Citadel): era base × 10
 *   - Distance from nearest own city center : adjacent (dist 1) = ×0.9 ; dist 2 = ×1.0 ;
 *     +0.1× per additional tile (dist 3 → 1.1, dist 4 → 1.2, ...)
 *   - Outside territory (only Road / Railroad / Fort): +0.5 additive on the multiplier.
 *   - Repair (pillaged improvement / road) : half the new-build price.
 *
 * 1-turn build delay: payment is taken immediately, the improvement appears at the
 * start of the buyer's next turn (processed via [Civilization.pendingPurchaseTiles]).
 */
object TileImprovementBuyer {

    private val ERA_BASE_COST = listOf(150, 200, 250, 300, 350, 400, 450, 500)
    private const val ROAD_FLAT_COST = 100
    private const val RAILROAD_FLAT_COST = 200
    /** Great Improvements (Academy, Manufactory, Customs House, Holy Site, Landmark, Citadel)
     *  cost 10× the era base — they're permanent civilization-defining upgrades. */
    private const val GREAT_IMPROVEMENT_MULTIPLIER = 10
    private const val REPAIR_RATIO = 0.5f

    /** Improvements that can be bought on a tile not owned by the buyer (with +50% markup). */
    private val OUTSIDE_TERRITORY_ALLOWED = setOf("Road", "Railroad", "Fort")

    @Readonly
    fun isOutsideTerritoryAllowed(improvementName: String) =
        improvementName in OUTSIDE_TERRITORY_ALLOWED

    /** Returns the gold price, or `null` if the improvement cannot be purchased on this tile
     *  (terrain incompatible, outside territory but not allowed, etc.). */
    @Readonly
    fun computePrice(tile: Tile, improvement: TileImprovement, civInfo: Civilization): Int? {
        val nearestCity = civInfo.cities.minByOrNull {
            it.getCenterTile().aerialDistanceTo(tile)
        } ?: return null

        val owner = tile.getOwner()
        val outOfTerritory = owner != civInfo
        if (outOfTerritory && !isOutsideTerritoryAllowed(improvement.name)) return null

        val era = civInfo.getEraNumber().coerceIn(0, ERA_BASE_COST.size - 1)
        val eraBase = ERA_BASE_COST[era]
        val improvementBase = when {
            improvement.name == "Road" -> ROAD_FLAT_COST
            improvement.name == "Railroad" -> RAILROAD_FLAT_COST
            improvement.hasUnique(com.unciv.models.ruleset.unique.UniqueType.GreatImprovement) ->
                eraBase * GREAT_IMPROVEMENT_MULTIPLIER
            else -> eraBase
        }

        val distance = nearestCity.getCenterTile().aerialDistanceTo(tile)
        // adjacent (dist 1) = 0.9 ; dist 2 = 1.0 ; +0.1 per additional tile
        val distanceMultiplier = (0.8f + 0.1f * distance).coerceAtLeast(0.5f)
        val outOfTerritoryAddon = if (outOfTerritory) 0.5f else 0.0f
        val finalMultiplier = distanceMultiplier + outOfTerritoryAddon
        return (improvementBase * finalMultiplier).toInt().coerceAtLeast(1)
    }

    /** Pure validation: can this civ legally purchase this improvement on this tile right now? */
    @Readonly
    fun canBuy(tile: Tile, improvement: TileImprovement, civInfo: Civilization): Boolean {
        if (civInfo.isDefeated() || civInfo.cities.isEmpty()) return false
        val price = computePrice(tile, improvement, civInfo) ?: return false
        if (civInfo.gold < price) return false
        // Reuse the engine's terrain/tech eligibility check
        val context = GameContext(civInfo = civInfo, tile = tile)
        return tile.improvementFunctions.canBuildImprovement(improvement, context)
    }

    /** Deduct gold, queue the improvement (1-turn delay), and register the tile for
     *  processing at the next turn start. @return true if the purchase went through. */
    fun buy(tile: Tile, improvement: TileImprovement, civInfo: Civilization): Boolean {
        if (!canBuy(tile, improvement, civInfo)) return false
        val price = computePrice(tile, improvement, civInfo)!!
        civInfo.addGold(-price)
        // Replace any existing queue entry on this tile (former Worker leftover, etc.)
        tile.improvementQueue.clear()
        tile.queueImprovement(improvement.name, 1)
        civInfo.pendingPurchaseTiles.add(tile.position)
        civInfo.addNotification(
            "Bought [${improvement.name}] for [${price}] gold",
            tile.position,
            NotificationCategory.Production
        )
        return true
    }

    /** Called at start of each civ turn for every tile in [Civilization.pendingPurchaseTiles].
     *  @return true if the improvement was placed this turn (caller should remove the entry). */
    fun processPendingPurchase(tile: Tile, civInfo: Civilization): Boolean {
        if (tile.improvementQueue.isEmpty()) return true  // nothing to process — drop entry
        if (tile.improvementQueue.first().countDown()) return false  // still counting down
        val entry = tile.improvementQueue.removeAt(0)
        // setImprovement routes "Repair" → setRepaired() automatically.
        tile.setImprovement(entry.improvement, civInfo, null)
        return true
    }

    /** Repair price for a pillaged improvement or pillaged road on this tile.
     *  Returns null if the tile is not pillaged or no priceable component is found.
     *  When BOTH the improvement and the road are pillaged, this returns the SUM of both
     *  repair costs (so the caller knows the full amount needed to bring the tile back).
     *
     *  Great-Person improvements use the regular era base price for repair (no ×10
     *  multiplier): a destroyed Academy is rebuilt for the same cost as a destroyed farm. */
    @Readonly
    fun computeRepairPrice(tile: Tile, civInfo: Civilization): Int? {
        if (!tile.isPillaged()) return null
        var total = 0
        if (tile.improvementIsPillaged && tile.improvement != null) {
            total += computeRepairPriceForName(tile, civInfo, tile.improvement!!) ?: return null
        }
        if (tile.roadIsPillaged && tile.roadStatus != RoadStatus.None) {
            total += computeRepairPriceForName(tile, civInfo, tile.roadStatus.name) ?: return null
        }
        return if (total > 0) total else null
    }

    /** Repair price for a single named pillaged component (improvement or road) on this tile.
     *  Pulled out of [computeRepairPrice] so callers can price the improvement and the road
     *  independently when both are pillaged on the same tile. */
    @Readonly
    private fun computeRepairPriceForName(tile: Tile, civInfo: Civilization, improvementName: String): Int? {
        val era = civInfo.getEraNumber().coerceIn(0, ERA_BASE_COST.size - 1)
        val improvementBase = when (improvementName) {
            "Road" -> ROAD_FLAT_COST
            "Railroad" -> RAILROAD_FLAT_COST
            else -> ERA_BASE_COST[era]   // ignore GreatImprovement ×10 for repairs
        }

        val nearestCity = civInfo.cities.minByOrNull {
            it.getCenterTile().aerialDistanceTo(tile)
        } ?: return null
        val distance = nearestCity.getCenterTile().aerialDistanceTo(tile)
        val distanceMultiplier = (0.8f + 0.1f * distance).coerceAtLeast(0.5f)
        val outOfTerritoryAddon = if (tile.getOwner() != civInfo) 0.5f else 0.0f
        val finalMultiplier = distanceMultiplier + outOfTerritoryAddon

        val basePrice = (improvementBase * finalMultiplier).toInt().coerceAtLeast(1)
        return (basePrice * REPAIR_RATIO).toInt().coerceAtLeast(1)
    }

    @Readonly
    fun canRepair(tile: Tile, civInfo: Civilization): Boolean {
        if (civInfo.isDefeated() || civInfo.cities.isEmpty()) return false
        val price = computeRepairPrice(tile, civInfo) ?: return false
        return civInfo.gold >= price
    }

    /** TW v2 — Can this civ remove the improvement on this tile for free?
     *  Only the tile's owner can; roads/railroads and Great-Person improvements are protected. */
    @Readonly
    fun canRemoveImprovement(tile: Tile, civInfo: Civilization): Boolean {
        val name = tile.improvement ?: return false
        if (tile.getOwner() != civInfo) return false
        if (name == "Road" || name == "Railroad") return false
        val improvement = tile.ruleset.tileImprovements[name] ?: return false
        if (improvement.hasUnique(com.unciv.models.ruleset.unique.UniqueType.Irremovable)) return false
        return true
    }

    /** TW v2 — Immediately remove a tile improvement at zero cost.
     *  Lets the player undo mis-placements (e.g., a Farm on a tile where a Strategic
     *  resource later appeared). Returns true on success. */
    fun removeImprovement(tile: Tile, civInfo: Civilization): Boolean {
        if (!canRemoveImprovement(tile, civInfo)) return false
        val previousName = tile.improvement
        tile.improvementFunctions.setImprovement(null, civInfo)
        civInfo.addNotification(
            "Removed improvement [${previousName}]",
            tile.position,
            NotificationCategory.Production
        )
        return true
    }

    /** Pay half-price to repair a pillaged improvement and/or road. 1-turn delay like a new build.
     *
     *  TW v2 — When both the tile improvement AND the road are pillaged on the same tile, we
     *  pay for BOTH up front and queue two repair entries. They process sequentially via
     *  [com.unciv.logic.map.tile.Tile.setRepaired]: improvement first, road second (so the
     *  tile yields production again as soon as possible). This avoids the previous behaviour
     *  where the road was systematically left for a later turn. */
    fun repair(tile: Tile, civInfo: Civilization): Boolean {
        if (civInfo.isDefeated() || civInfo.cities.isEmpty()) return false
        val needsImpr = tile.improvementIsPillaged && tile.improvement != null
        val needsRoad = tile.roadIsPillaged && tile.roadStatus != RoadStatus.None
        if (!needsImpr && !needsRoad) return false

        val imprPrice = if (needsImpr)
            computeRepairPriceForName(tile, civInfo, tile.improvement!!) ?: return false
        else 0
        val roadPrice = if (needsRoad)
            computeRepairPriceForName(tile, civInfo, tile.roadStatus.name) ?: return false
        else 0
        val totalPrice = imprPrice + roadPrice
        if (totalPrice <= 0 || civInfo.gold < totalPrice) return false

        civInfo.addGold(-totalPrice)
        tile.improvementQueue.clear()
        // Queue once per pillaged component. setRepaired naturally repairs the improvement
        // first (the yield-producing one), then the road on the next cycle.
        if (needsImpr) tile.queueImprovement(com.unciv.Constants.repair, 1)
        if (needsRoad) tile.queueImprovement(com.unciv.Constants.repair, 1)
        civInfo.pendingPurchaseTiles.add(tile.position)
        val message = when {
            needsImpr && needsRoad ->
                "Repaired pillaged improvement and road for [${totalPrice}] gold"
            needsImpr -> "Repaired pillaged improvement for [${totalPrice}] gold"
            else -> "Repaired pillaged road for [${totalPrice}] gold"
        }
        civInfo.addNotification(
            message,
            tile.position,
            NotificationCategory.Production
        )
        return true
    }
}
