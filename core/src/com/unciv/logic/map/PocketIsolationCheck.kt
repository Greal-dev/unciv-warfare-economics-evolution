package com.unciv.logic.map

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/**
 * TW v2 — Land-pocket isolation check (Dunkirk-style logistics).
 *
 * Mechanics:
 *  - "Connectivity" is over LAND tiles owned by the civ. Water tiles never bridge a pocket.
 *  - The "main bloc" is the connected land owned by the civ containing the capital.
 *  - Every other connected land pocket is "isolated" — only reachable by sea.
 *  - A unit in an isolated pocket takes attrition damage proportional to how choked the pocket
 *    is. Below 50% military occupancy, no damage at all (the units have room to manoeuvre).
 *    From 50% upward, damage scales linearly to 100% — a fully packed pocket takes full damage,
 *    a moderately occupied one only a fraction.
 */
object PocketIsolationCheck {

    private const val CHOKE_RATIO_FLOOR = 0.5f  // below this, no attrition at all

    /** Compute, per own military unit caught in an isolated pocket, the choke ratio in [0, 1].
     *  Returned map only contains units whose pocket exceeds the [CHOKE_RATIO_FLOOR]; below
     *  that threshold the unit isn't choked at all. Callers scale damage by the returned ratio. */
    fun findChokedPocketUnits(civ: Civilization): Map<MapUnit, Float> {
        val capital = civ.getCapital() ?: return emptyMap()
        val ownLandTiles = HashSet<Tile>()
        for (city in civ.cities)
            for (tile in city.getTiles())
                if (tile.isLand && !tile.isImpassible()) ownLandTiles.add(tile)
        if (ownLandTiles.isEmpty()) return emptyMap()

        val capitalTile = capital.getCenterTile()
        if (capitalTile !in ownLandTiles) return emptyMap()  // floating capital? bail

        val mainBloc = bfsThrough(capitalTile, ownLandTiles)
        if (mainBloc.size == ownLandTiles.size) return emptyMap()  // nothing isolated

        val isolatedTiles = ownLandTiles - mainBloc
        val result = HashMap<MapUnit, Float>()
        val visited = HashSet<Tile>()
        for (start in isolatedTiles) {
            if (start in visited) continue
            val pocket = bfsThrough(start, isolatedTiles)
            visited.addAll(pocket)

            if (pocket.isEmpty()) continue
            val militaryTilesInPocket = pocket.count { it.militaryUnit?.civ == civ }
            val ratio = militaryTilesInPocket.toFloat() / pocket.size.toFloat()
            if (ratio <= CHOKE_RATIO_FLOOR) continue  // not choked enough — no attrition
            for (tile in pocket) {
                val unit = tile.militaryUnit ?: continue
                if (unit.civ == civ) result[unit] = ratio
            }
        }
        return result
    }

    private fun bfsThrough(start: Tile, allowed: Set<Tile>): Set<Tile> {
        val visited = HashSet<Tile>()
        val frontier = ArrayDeque<Tile>()
        frontier.add(start)
        visited.add(start)
        while (frontier.isNotEmpty()) {
            val cur = frontier.removeFirst()
            for (n in cur.neighbors) {
                if (n in visited || n !in allowed) continue
                visited.add(n)
                frontier.add(n)
            }
        }
        return visited
    }
}
