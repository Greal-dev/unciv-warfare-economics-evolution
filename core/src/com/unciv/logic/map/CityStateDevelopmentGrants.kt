package com.unciv.logic.map

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType


/**
 * TW v2 — Free development credits for spontaneous city-states.
 *
 * City-states have no workers. Instead they receive two periodic free grants:
 *   - Every 5 turns:  one free **repair** on the worst pillaged tile they own.
 *   - Every 20 turns: one free **improvement** placed on the best unimproved tile they own.
 *
 * Great person improvements (Academy, Landmark, etc.) are explicitly excluded —
 * those remain reserved for great person units only.
 *
 * Both actions bypass the gold cost completely (no gold is deducted).
 * Called from [com.unciv.logic.civilization.managers.TurnManager] at end-of-turn for CS civs.
 */
object CityStateDevelopmentGrants {

    private const val REPAIR_PERIOD = 5
    private const val BUILD_PERIOD = 20

    fun applyGrants(cs: Civilization) {
        if (cs.cities.isEmpty()) return
        val turn = cs.gameInfo.turns
        if (turn % REPAIR_PERIOD == 0) freeRepair(cs)
        if (turn % BUILD_PERIOD == 0)  freeBuild(cs)
    }

    // -------------------------------------------------------------------------
    // Repair
    // -------------------------------------------------------------------------

    private fun freeRepair(cs: Civilization) {
        val tile = cs.cities
            .flatMap { it.getTiles() }
            .filter { it.isPillaged() && it.position !in cs.pendingPurchaseTiles }
            .maxByOrNull { repairPriority(it) }
            ?: return
        tile.setRepaired()
    }

    private fun repairPriority(tile: Tile): Int {
        var score = 0
        if (tile.improvementIsPillaged) score += 10
        if (tile.roadIsPillaged)        score += 5
        if (tile.tileResource != null)  score += 8
        return score
    }

    // -------------------------------------------------------------------------
    // Free improvement build
    // -------------------------------------------------------------------------

    private fun freeBuild(cs: Civilization) {
        val ruleset = cs.gameInfo.ruleset
        data class Candidate(val tile: Tile, val improvement: String, val score: Float)
        val candidates = mutableListOf<Candidate>()

        for (city in cs.cities) {
            for (tile in city.getTiles()) {
                if (tile.isCityCenter()) continue
                // Only act on tiles with no current (non-pillaged) improvement
                if (tile.improvement != null && !tile.improvementIsPillaged) continue
                if (tile.position in cs.pendingPurchaseTiles) continue
                val ctx = GameContext(civInfo = cs, tile = tile)

                for (improvement in ruleset.tileImprovements.values) {
                    if (improvement.isGreatImprovement()) continue  // reserved for great people only
                    if (improvement.hasUnique(UniqueType.Unbuildable, ctx)) continue
                    val tech = improvement.techRequired
                    if (tech != null && !cs.tech.isResearched(tech)) continue
                    if (!tile.improvementFunctions.canBuildImprovement(improvement, ctx)) continue
                    val score = scoreImprovement(tile, improvement, cs)
                    if (score > 0f) candidates.add(Candidate(tile, improvement.name, score))
                }
            }
        }

        val best = candidates.maxByOrNull { it.score } ?: return
        best.tile.setImprovement(best.improvement, cs, null)
    }

    private fun scoreImprovement(tile: Tile, improvement: TileImprovement, cs: Civilization): Float {
        val ruleset = cs.gameInfo.ruleset
        var score = 0f
        val resource = tile.tileResource
        if (resource != null && cs.canSeeResource(resource) && resource.isImprovedBy(improvement.name))
            score += 20f
        if (improvement.name == "Farm") score += 10f
        score += (improvement.food + improvement.production + improvement.gold +
                  improvement.science + improvement.culture + improvement.faith) * 2f
        // Clearance (Remove Forest / Jungle / Marsh): score equal to a Farm if a Farm
        // would become buildable on this tile once the feature is gone.
        if (improvement.name.startsWith("Remove ")) {
            val featureName = improvement.name.removePrefix("Remove ")
            val farmImprovement = ruleset.tileImprovements["Farm"]
            val farmBuildableAfterClearing = farmImprovement != null
                && tile.terrainFeatures.contains(featureName)
                && farmImprovement.terrainsCanBeBuiltOn.contains(tile.baseTerrain)
                && (farmImprovement.techRequired == null
                    || cs.tech.isResearched(farmImprovement.techRequired!!))
            if (farmBuildableAfterClearing)
                score += 10f  // same bonus as Farm — clearing IS the first step toward a Farm
            else
                score -= 2f   // irreversible but no farm benefit → mild penalty
        }
        return score
    }
}
