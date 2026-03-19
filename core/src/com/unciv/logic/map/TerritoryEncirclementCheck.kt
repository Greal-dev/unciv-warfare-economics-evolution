package com.unciv.logic.map

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.tile.Tile

/**
 * Territorial Warfare: checks for encircled neutral and enemy territory pockets each turn.
 * - Neutral tiles completely surrounded by one civ's territory are claimed.
 * - Enemy tiles (at war) that form isolated pockets surrounded by another civ are captured,
 *   except tiles with enemy military units.
 */
object TerritoryEncirclementCheck {

    fun checkEncirclement(civ: Civilization) {
        if (civ.cities.isEmpty()) return
        val tileMap = civ.gameInfo.tileMap

        checkNeutralEncirclement(civ, tileMap)
        checkEnemyEncirclement(civ, tileMap)
    }

    /**
     * Find pockets of neutral (unowned) tiles that are completely surrounded by this civ's territory.
     * A pocket is "encircled" if a BFS from any neutral tile, traversing only neutral tiles,
     * cannot reach a map edge tile (a tile with fewer than 6 neighbors).
     */
    private fun checkNeutralEncirclement(civ: Civilization, tileMap: TileMap) {
        val checked = HashSet<Tile>()

        // Only check neutral tiles adjacent to our territory (optimization)
        val candidateTiles = civ.cities.asSequence()
            .flatMap { it.getTiles() }
            .flatMap { it.neighbors }
            .filter { it.getOwner() == null && it !in checked }
            .distinct()
            .toList()

        for (startTile in candidateTiles) {
            if (startTile in checked) continue

            // BFS through neutral tiles only
            val bfs = BFS(startTile) { it.getOwner() == null }
            bfs.stepToEnd()
            val pocket = bfs.getReachedTiles()
            checked.addAll(pocket)

            // Check if any tile in the pocket is on the map edge
            val reachesEdge = pocket.any { isMapEdgeTile(it) }
            if (reachesEdge) continue

            // Check if the pocket is surrounded ONLY by this civ (not mixed owners)
            val surroundingOwners = pocket.asSequence()
                .flatMap { it.neighbors }
                .filter { it.getOwner() != null }
                .map { it.getOwner()!! }
                .distinct()
                .toList()
            if (surroundingOwners.size != 1 || surroundingOwners[0] != civ) continue

            // Claim all tiles in the pocket
            for (tile in pocket) {
                val nearestCity = civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
                    ?: continue
                nearestCity.expansion.takeOwnership(tile)
            }
        }
    }

    /**
     * Find pockets of enemy territory (at war) that are completely surrounded by this civ's territory.
     * An enemy pocket is "isolated" if a BFS from the enemy tile, traversing only tiles of that same enemy,
     * cannot reach any of that enemy's city centers.
     * Tiles with enemy military units are NOT captured.
     */
    private fun checkEnemyEncirclement(civ: Civilization, tileMap: TileMap) {
        val checked = HashSet<Tile>()

        // Find enemy tiles adjacent to our territory
        val candidateTiles = civ.cities.asSequence()
            .flatMap { it.getTiles() }
            .flatMap { it.neighbors }
            .filter { tile ->
                val owner = tile.getOwner()
                owner != null && civ.isAtWarWith(owner) && tile !in checked
            }
            .distinct()
            .toList()

        for (startTile in candidateTiles) {
            if (startTile in checked) continue
            val enemyCiv = startTile.getOwner() ?: continue

            // BFS through tiles of the same enemy civ
            val bfs = BFS(startTile) { it.getOwner() == enemyCiv }
            bfs.stepToEnd()
            val pocket = bfs.getReachedTiles()
            checked.addAll(pocket)

            // Check if any tile in the pocket is a city center of the enemy
            val connectedToCity = pocket.any { it.isCityCenter() }
            if (connectedToCity) continue

            // Check if the pocket is surrounded only by our civ's territory
            val surroundingOwners = pocket.asSequence()
                .flatMap { it.neighbors }
                .filter { it.getOwner() != null && it.getOwner() != enemyCiv }
                .map { it.getOwner()!! }
                .distinct()
                .toList()
            if (surroundingOwners.size != 1 || surroundingOwners[0] != civ) continue

            // Capture tiles except those with enemy military units
            for (tile in pocket) {
                if (tile.isCityCenter()) continue
                if (tile.militaryUnit != null && tile.militaryUnit!!.civ == enemyCiv) continue

                val nearestCity = civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
                    ?: continue
                nearestCity.expansion.takeOwnership(tile)
            }
        }
    }

    /** A tile is on the map edge if it has fewer than 6 neighbors (hex grid) */
    private fun isMapEdgeTile(tile: Tile): Boolean {
        return tile.neighbors.count() < 6
    }
}
