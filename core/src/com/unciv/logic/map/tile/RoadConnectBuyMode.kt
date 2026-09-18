package com.unciv.logic.map.tile

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType

/**
 * TW v2 — Multi-tile "Connect Road" buy mode.
 *
 * UX:
 *   1. Player picks Road or Railroad from the [com.unciv.ui.screens.worldscreen.bottombar.TileInfoTable]
 *      → [start] sets the mode active.
 *   2. Every tile clicked while [active] is toggled in/out of [tiles] via [toggleTile].
 *   3. Running [totalPrice] is displayed; Confirm calls [confirm] which loops
 *      [TileImprovementBuyer.buy] for every tile in the plan; Cancel calls [cancel].
 *
 * Lives as a global singleton: the player can only define one path at a time.
 */
object RoadConnectBuyMode {

    var active: Boolean = false
        private set
    var civ: Civilization? = null
        private set
    var improvement: TileImprovement? = null
        private set
    val tiles: MutableList<Tile> = mutableListOf()

    fun start(civ: Civilization, improvement: TileImprovement) {
        this.civ = civ
        this.improvement = improvement
        this.tiles.clear()
        this.active = true
    }

    fun cancel() {
        active = false
        civ = null
        improvement = null
        tiles.clear()
    }

    fun isEligible(tile: Tile): Boolean {
        val civ = this.civ ?: return false
        val improvement = this.improvement ?: return false
        // Already has the same improvement (and not pillaged): skip
        if (tile.getUnpillagedRoad().name == improvement.name) return false
        if (tile.isCityCenter()) return false
        val context = GameContext(civInfo = civ, tile = tile)
        if (improvement.hasUnique(UniqueType.Unbuildable, context)) return false
        if (TileImprovementBuyer.computePrice(tile, improvement, civ) == null) return false
        if (!tile.improvementFunctions.canBuildImprovement(improvement, context)) return false
        return true
    }

    /** Returns true if the tile is now in the plan (was added) or false if it was removed. */
    fun toggleTile(tile: Tile): Boolean {
        if (!active) return false
        if (tiles.contains(tile)) {
            tiles.remove(tile)
            return false
        }
        if (!isEligible(tile)) return false
        tiles.add(tile)
        return true
    }

    fun totalPrice(): Int {
        val civ = this.civ ?: return 0
        val improvement = this.improvement ?: return 0
        return tiles.sumOf { TileImprovementBuyer.computePrice(it, improvement, civ) ?: 0 }
    }

    fun canAfford(): Boolean {
        val civ = this.civ ?: return false
        return civ.gold >= totalPrice()
    }

    /** @return number of tiles successfully purchased. */
    fun confirm(): Int {
        if (!active) return 0
        val civ = this.civ ?: return 0
        val improvement = this.improvement ?: return 0
        val totalCost = totalPrice()
        if (civ.gold < totalCost) {
            civ.addNotification(
                "Not enough gold for road plan ($totalCost needed)",
                NotificationCategory.Production
            )
            return 0
        }
        var bought = 0
        // Deduct once up-front; each buy() also deducts its own price, so refund our pre-deduction
        // by buying each tile individually. We don't pre-deduct — just rely on TileImprovementBuyer.buy().
        for (tile in tiles.toList()) {
            if (TileImprovementBuyer.buy(tile, improvement, civ)) bought++
        }
        cancel()
        return bought
    }
}
