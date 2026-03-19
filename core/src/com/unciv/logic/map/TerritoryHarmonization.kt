package com.unciv.logic.map

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.tile.Tile

/**
 * Territorial Warfare: automatic border harmonization each turn.
 * Civs (and city-states) that are not at war exchange border tiles so that each tile
 * gravitates toward the closest city, smoothing out jagged or distant borders.
 *
 * Algorithm per civ pair (A, B) not at war:
 * - Find tiles owned by A that are closer to B's nearest city than to A's nearest city (by >= 2 tiles margin).
 * - Find tiles owned by B that are closer to A's nearest city than to B's nearest city (by >= 2 tiles margin).
 * - Pair them up and swap (limited per turn to avoid massive border shifts).
 * - If one side has more swappable tiles, excess tiles are transferred for free (harmonization, not trade).
 * - Never swap city centers, tiles with military units, or tiles that would break city contiguity.
 */
object TerritoryHarmonization {

    private const val MAX_SWAPS_PER_PAIR_PER_TURN = 3
    private const val MIN_DISTANCE_ADVANTAGE = 2 // tile must be closer to other civ by at least this many tiles

    fun harmonize(civ: Civilization) {
        if (civ.cities.isEmpty()) return
        if (civ.isDefeated()) return

        // Only process each pair once: the civ with the lower civName processes the pair
        val processed = HashSet<String>()
        processed.add(civ.civName)

        for (otherCiv in civ.getKnownCivs()) {
            if (otherCiv.civName in processed) continue
            if (otherCiv.isDefeated()) continue
            if (otherCiv.cities.isEmpty()) continue
            if (civ.isAtWarWith(otherCiv)) continue
            // Barbarians don't participate
            if (otherCiv.isBarbarian) continue

            processed.add(otherCiv.civName)
            harmonizePair(civ, otherCiv)
        }
    }

    private fun harmonizePair(civA: Civilization, civB: Civilization) {
        // Find border tiles: tiles owned by A adjacent to B's territory, and vice versa
        val tilesACloserToB = findMisplacedTiles(civA, civB)
        val tilesBCloserToA = findMisplacedTiles(civB, civA)

        if (tilesACloserToB.isEmpty() && tilesBCloserToA.isEmpty()) return

        // Sort by how "misplaced" they are (biggest distance advantage first)
        val sortedAtoB = tilesACloserToB.sortedByDescending { it.second }
        val sortedBtoA = tilesBCloserToA.sortedByDescending { it.second }

        var swaps = 0

        // Paired swaps first
        val pairedCount = minOf(sortedAtoB.size, sortedBtoA.size, MAX_SWAPS_PER_PAIR_PER_TURN)
        for (i in 0 until pairedCount) {
            val tileFromA = sortedAtoB[i].first
            val tileFromB = sortedBtoA[i].first
            transferTile(tileFromA, civB)
            transferTile(tileFromB, civA)
            swaps++
        }

        // Unmatched excess: transfer remaining misplaced tiles (up to limit)
        if (swaps < MAX_SWAPS_PER_PAIR_PER_TURN) {
            val remainingBudget = MAX_SWAPS_PER_PAIR_PER_TURN - swaps
            if (sortedAtoB.size > pairedCount) {
                for (i in pairedCount until minOf(sortedAtoB.size, pairedCount + remainingBudget)) {
                    transferTile(sortedAtoB[i].first, civB)
                    swaps++
                }
            } else if (sortedBtoA.size > pairedCount) {
                for (i in pairedCount until minOf(sortedBtoA.size, pairedCount + remainingBudget)) {
                    transferTile(sortedBtoA[i].first, civA)
                    swaps++
                }
            }
        }

        if (swaps > 0) {
            civA.addNotification(
                "Border harmonization: exchanged [$swaps] tiles with [${civB.civName}]",
                NotificationCategory.General
            )
            civB.addNotification(
                "Border harmonization: exchanged [$swaps] tiles with [${civA.civName}]",
                NotificationCategory.General
            )
        }
    }

    /**
     * Find tiles owned by [owner] that are closer to [other]'s nearest city
     * than to [owner]'s nearest city, by at least [MIN_DISTANCE_ADVANTAGE] tiles.
     * Returns list of (tile, distanceAdvantage) pairs.
     */
    private fun findMisplacedTiles(owner: Civilization, other: Civilization): List<Pair<Tile, Int>> {
        val result = mutableListOf<Pair<Tile, Int>>()

        // Only check border tiles (tiles adjacent to the other civ's territory) for performance
        val borderTiles = owner.cities.asSequence()
            .flatMap { it.getTiles() }
            .filter { tile ->
                !tile.isCityCenter() &&
                tile.militaryUnit?.civ != owner && // no friendly military unit (avoid disruption)
                tile.neighbors.any { n -> n.getOwner() == other }
            }
            .distinct()
            .toList()

        for (tile in borderTiles) {
            val distToOwnerCity = owner.cities.minOf { it.getCenterTile().aerialDistanceTo(tile) }
            val distToOtherCity = other.cities.minOf { it.getCenterTile().aerialDistanceTo(tile) }

            val advantage = distToOwnerCity - distToOtherCity
            if (advantage >= MIN_DISTANCE_ADVANTAGE) {
                // Verify transferring this tile won't break contiguity for its current city
                val city = tile.getCity() ?: continue
                if (wouldBreakContiguity(tile, city)) continue
                result.add(tile to advantage)
            }
        }

        return result
    }

    private fun transferTile(tile: Tile, newOwner: Civilization) {
        if (tile.isCityCenter()) return
        val nearestCity = newOwner.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
            ?: return
        nearestCity.expansion.takeOwnership(tile)
    }

    /**
     * Check if removing [tile] from [city] would disconnect any of the city's remaining tiles
     * from the city center. Uses BFS from center through city tiles excluding the candidate.
     */
    private fun wouldBreakContiguity(tile: Tile, city: com.unciv.logic.city.City): Boolean {
        val cityTiles = city.getTiles().toSet()
        if (cityTiles.size <= 2) return false // center + this tile only, safe to remove

        val remaining = cityTiles - tile
        val center = city.getCenterTile()

        // BFS from center through remaining tiles
        val visited = HashSet<Tile>()
        val queue = ArrayDeque<Tile>()
        visited.add(center)
        queue.add(center)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (neighbor in current.neighbors) {
                if (neighbor in remaining && neighbor !in visited) {
                    visited.add(neighbor)
                    queue.add(neighbor)
                }
            }
        }

        // If we visited all remaining tiles, contiguity is preserved
        return visited.size < remaining.size
    }
}
