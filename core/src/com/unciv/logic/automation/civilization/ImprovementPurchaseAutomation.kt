package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.map.tile.TileImprovementBuyer
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType

/**
 * TW v2 — AI behavior for the gold-based Public Works system.
 * The AI replaces the deleted Worker by spending part of its treasury on
 * pillaged-improvement repairs and new tile improvements each turn.
 *
 * Design principles:
 *  - Balanced: keeps an era-scaled reserve, spends a fraction of free gold per turn.
 *  - Frugal: only buys on tiles with high marginal value (resource tiles, food/prod yield).
 *  - Per-tile single purchase per turn (avoids spamming).
 *  - Repair beats build (cheap and restores known yield).
 */
object ImprovementPurchaseAutomation {

    private val ALLOWED_REMOVE_NAMES = setOf("Remove Forest", "Remove Jungle", "Remove Marsh")

    /** TW v2 — Maximum useful tile-improvement distance from any of the civ's cities. A farm on a
     *  tile no city can ever work yields nothing, so don't waste gold there. Luxury/strategic
     *  resource tiles are exempt: a one-off camp/mine 6 tiles out still produces a copy of the
     *  resource for the civ-wide pool. */
    private const val MAX_USEFUL_TILE_DISTANCE = 4

    /** TW v2 — Returns true when this tile is worth investing infrastructure into. */
    private fun isTileWorthImproving(tile: Tile, civ: Civilization): Boolean {
        val nearestDist = civ.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) }
            ?: return false
        if (nearestDist <= MAX_USEFUL_TILE_DISTANCE) return true
        val resource = tile.tileResource ?: return false
        if (!civ.canSeeResource(resource)) return false
        return resource.resourceType == ResourceType.Luxury
            || resource.resourceType == ResourceType.Strategic
    }

    /** TW v2 — Returns true when this tile has at least one pillaged component worth repairing.
     *  Roads are repaired ANYWHERE in the empire — they link cities and project culture along
     *  their length, regardless of distance to any one city. Non-road improvements still defer
     *  to the standard [isTileWorthImproving] heuristic (within 4 tiles, or on a luxury /
     *  strategic resource). */
    private fun isTileWorthRepairing(tile: Tile, civ: Civilization): Boolean {
        if (tile.roadIsPillaged && tile.roadStatus != com.unciv.logic.map.tile.RoadStatus.None) return true
        if (tile.improvementIsPillaged && tile.improvement != null) {
            return isTileWorthImproving(tile, civ)
        }
        return false
    }

    fun automate(civ: Civilization, reserveOverride: Int? = null) {
        val isHumanAuto = reserveOverride != null
        if (civ.cities.isEmpty()) {
            if (isHumanAuto) println("[TWv2 auto-improve] ${civ.civName}: no cities")
            return
        }

        val era = civ.getEraNumber().coerceAtLeast(0)
        // Minimal flat reserve (AI default), overridable by the human auto-improve mode.
        val reserve = reserveOverride ?: 30
        val available = civ.gold - reserve
        if (available <= 0) {
            if (isHumanAuto) println("[TWv2 auto-improve] ${civ.civName}: gold ${civ.gold} ≤ reserve $reserve, skipping")
            return
        }

        // Per-turn purchase cap scales with empire size. Human auto-mode gets a higher
        // ceiling — the player wants their treasury fluidly converted to infrastructure.
        val maxPurchases = if (isHumanAuto)
            (5 + civ.cities.size).coerceAtMost(40)
        else
            (2 + civ.cities.size / 2).coerceAtMost(10)
        // Spend up to 90% of available gold per turn — leaves a sliver for upgrades.
        val budget = (available * 0.9f).toInt()
        if (budget <= 0) return

        var spent = 0
        var purchases = 0
        val processedTiles = HashSet<com.unciv.logic.map.HexCoord>()

        // 1) Repairs first — half-price, guaranteed value restoration.
        //    TW v2: skip the repair if a hostile (barbarian or war enemy) military unit
        //    is within 2 tiles — the improvement would just get pillaged again.
        //    Roads are repaired anywhere in the empire (cf. isTileWorthRepairing).
        val repairCandidates = mutableListOf<Pair<Tile, Int>>()
        for (city in civ.cities) {
            for (tile in city.getTiles()) {
                if (!tile.isPillaged()) continue
                if (tile.position in civ.pendingPurchaseTiles) continue
                if (hasHostileNearby(tile, civ)) continue
                if (!isTileWorthRepairing(tile, civ)) continue
                val price = TileImprovementBuyer.computeRepairPrice(tile, civ) ?: continue
                repairCandidates.add(tile to price)
            }
        }
        // Cheap repairs first
        repairCandidates.sortBy { it.second }
        for ((tile, price) in repairCandidates) {
            if (purchases >= maxPurchases) break
            if (spent + price > budget) continue
            if (TileImprovementBuyer.repair(tile, civ)) {
                spent += price
                purchases++
                processedTiles.add(tile.position)
            }
        }
        if (purchases >= maxPurchases) return

        // 2) New improvements on un-improved tiles within a city's working radius.
        //    Priority buckets (lower = bought first):
        //      0  matching luxury/strategic resource improvement (incl. upgrading an existing
        //         non-resource improvement when a strategic resource has just been revealed)
        //      0  Farm (where buildable) — food is always priority 1
        //      0  matching bonus resource improvement
        //      0  Remove Forest / Jungle / Marsh — only when farm would be buildable AFTER clearing
        //      1  Trading Post (used where Farm isn't buildable)
        //      2  Remove Forest / Jungle / Marsh — when no farm possible (last resort clearance)
        //      1  anything else
        data class Candidate(
            val tile: Tile,
            val improvement: TileImprovement,
            val price: Int,
            val score: Float,
            val bucket: Int
        )
        val candidates = mutableListOf<Candidate>()

        for (city in civ.cities) {
            for (tile in city.getTiles()) {
                if (tile.isCityCenter()) continue
                if (tile.position in processedTiles) continue
                if (tile.position in civ.pendingPurchaseTiles) continue
                if (!isTileWorthImproving(tile, civ)) continue
                val hasNonPillagedImprovement = tile.improvement != null && !tile.improvementIsPillaged

                // TW v2: allow REPLACING an existing improvement if a strategic/luxury resource
                // has just been revealed on the tile and the current improvement doesn't extract it.
                val resourceHere = tile.tileResource
                val wantsResourceUpgrade = hasNonPillagedImprovement
                    && resourceHere != null
                    && civ.canSeeResource(resourceHere)
                    && resourceHere.resourceType != ResourceType.Bonus
                    && !tile.providesResources(civ)
                    && resourceHere.getImprovements().isNotEmpty()
                if (hasNonPillagedImprovement && !wantsResourceUpgrade) continue

                // Pre-compute whether Farm is buildable on this tile (controls bucket of Trading Post)
                val farmImprovement = tile.ruleset.tileImprovements["Farm"]
                val context = GameContext(civInfo = civ, tile = tile)
                val farmBuildable = farmImprovement != null
                    && !farmImprovement.hasUnique(UniqueType.Unbuildable, context)
                    && (farmImprovement.techRequired == null || civ.tech.isResearched(farmImprovement.techRequired!!))
                    && tile.improvementFunctions.canBuildImprovement(farmImprovement, context)

                // TW v2: would a Farm be buildable AFTER clearing the forest/jungle/marsh feature?
                // Used to redirect investment from Lumber Mills toward forest clearance + farm.
                val hasClearableFeature = tile.terrainFeatures.any {
                    it == "Forest" || it == "Jungle" || it == "Marsh"
                }
                val farmBuildableAfterClearing = !farmBuildable && hasClearableFeature
                    && farmImprovement != null
                    && farmImprovement.terrainsCanBeBuiltOn.contains(tile.baseTerrain)
                    && (farmImprovement.techRequired == null || civ.tech.isResearched(farmImprovement.techRequired!!))

                for (improvement in tile.ruleset.tileImprovements.values) {
                    if (improvement.isGreatImprovement()) continue  // reserved for great people only
                    if (improvement.hasUnique(UniqueType.Unbuildable, context)) continue
                    val techRequired = improvement.techRequired
                    if (techRequired != null && !civ.tech.isResearched(techRequired)) continue
                    if (improvement.name.startsWith("Remove ") &&
                        improvement.name !in ALLOWED_REMOVE_NAMES) continue
                    // TW v2: when we're upgrading an existing improvement for a freshly-revealed
                    // resource, only the matching extractor is a valid candidate.
                    if (wantsResourceUpgrade && !resourceHere!!.isImprovedBy(improvement.name)) continue
                    val price = TileImprovementBuyer.computePrice(tile, improvement, civ) ?: continue
                    if (price > budget - spent) continue
                    if (!tile.improvementFunctions.canBuildImprovement(improvement, context)) continue
                    val score = scoreImprovement(tile, improvement, civ)
                    if (score <= 0f) continue
                    val bucket = priorityBucket(tile, improvement, farmBuildable, farmBuildableAfterClearing)
                    if (bucket < 0) continue  // explicitly skipped (e.g., Trading post on farmable tile)
                    candidates.add(Candidate(tile, improvement, price, score, bucket))
                }
            }
        }

        // Strict bucket priority, then best score/price ratio inside each bucket.
        candidates.sortWith(
            compareBy<Candidate> { it.bucket }
                .thenByDescending { it.score / it.price }
                .thenBy { it.price }
        )
        for (c in candidates) {
            if (purchases >= maxPurchases) break
            if (c.tile.position in processedTiles) continue
            if (spent + c.price > budget) continue
            if (TileImprovementBuyer.buy(c.tile, c.improvement, civ)) {
                spent += c.price
                purchases++
                processedTiles.add(c.tile.position)
            }
        }

        // 3) Road network — TW v2: Workers are gone, so the civ now buys roads with gold to
        //    connect its cities (and tries to extend to known peaceful neighbours for the trade
        //    bonus). Without this pass, the AI's empire stays unconnected forever.
        val roadsSpent = buyRoadNetwork(civ, processedTiles, budget - spent,
            roadBudgetCap = if (isHumanAuto) 10 else 5)
        spent += roadsSpent

        // Temporary diagnostic so we can verify AI improvement-purchase dynamics.
        if (isHumanAuto) {
            val buckets = candidates.groupingBy { it.bucket }.eachCount()
            val improvementsBoughtSummary = candidates
                .filter { it.tile.position in processedTiles }
                .groupingBy { it.improvement.name }.eachCount()
            val line = "[TWv2 auto-improve] T${civ.gameInfo.turns} ${civ.civName}: " +
                "$purchases purchases / max=$maxPurchases, ${spent}g spent / budget=$budget " +
                "(gold ${civ.gold} after, reserve $reserve), candidates=${candidates.size} by bucket=$buckets, " +
                "bought=$improvementsBoughtSummary"
            println(line)
            try {
                java.io.FileWriter("auto_improve_diagnostic.log", true).use { it.append(line).append("\n") }
            } catch (_: Throwable) {}
        } else if (purchases > 0) {
            println("[TWv2 AI improvements] ${civ.civName}: $purchases purchases, ${spent}g spent " +
                "(reserve ${reserve}, available ${available}, budget ${budget}, gold ${civ.gold} after)")
        }
    }

    /** TW v2 — Returns the priority bucket for a candidate improvement.
     *  Lower = higher priority. -1 means "never buy this on this tile". */
    private fun priorityBucket(
        tile: Tile,
        improvement: TileImprovement,
        farmBuildable: Boolean,
        farmBuildableAfterClearing: Boolean
    ): Int {
        val resource = tile.tileResource
        val civSees = resource != null
        // Resource extractor on a revealed resource — always top priority.
        if (resource != null && civSees && resource.isImprovedBy(improvement.name)) {
            when (resource.resourceType) {
                ResourceType.Luxury, ResourceType.Strategic, ResourceType.Bonus -> return 0
            }
        }
        if (improvement.name == "Farm" && farmBuildable) return 0

        // TW v2: on a forest/jungle/marsh tile that would accept a farm once cleared,
        // we want to redirect spending toward clearance — and explicitly REJECT lumber-mill /
        // windmill / camp-on-forest style improvements (unless they extract a resource here,
        // which would have been caught above).
        if (farmBuildableAfterClearing) {
            if (improvement.name in ALLOWED_REMOVE_NAMES) return 0
            // Any other improvement on this tile (Lumber Mill, Windmill, etc.) is rejected
            // so the AI doesn't sink gold into terrain-locked builds when a farm beckons.
            return -1
        }

        // Trading Post — only on tiles where a Farm isn't possible
        if (improvement.name == "Trading post") return if (!farmBuildable) 1 else -1
        // Forest / jungle / marsh clearance — last resort when no farm is reachable
        if (improvement.name in ALLOWED_REMOVE_NAMES) return 2
        return 1
    }

    /** TW v2 — Buy road tiles to (1) connect the civ's own cities and (2) extend to known
     *  peaceful neighbouring civs' nearest cities (trade route bonus). Cap per turn:
     *  [roadBudgetCap] tiles. Returns gold actually spent.
     *
     *  Uses [com.unciv.logic.automation.unit.RoadBetweenCitiesAutomation] for the own-city
     *  plans (already production-tested pathing), then adds a lightweight neighbour-link
     *  layer on top. */
    private fun buyRoadNetwork(
        civ: Civilization,
        processedTiles: HashSet<com.unciv.logic.map.HexCoord>,
        remainingBudget: Int,
        roadBudgetCap: Int
    ): Int {
        if (remainingBudget <= 0 || roadBudgetCap <= 0) return 0
        if (civ.cities.size < 2) return 0  // single-city civ has nothing to connect
        val ruleset = civ.gameInfo.ruleset

        val roadAuto = com.unciv.logic.automation.unit.RoadBetweenCitiesAutomation(
            civ, civ.gameInfo.turns)
        val bestRoadStatus = roadAuto.bestRoadAvailable
        if (bestRoadStatus == com.unciv.logic.map.tile.RoadStatus.None) return 0
        val roadImprovement = bestRoadStatus.improvement(ruleset) ?: return 0

        // Collect candidate tiles: own-city plans + neighbour extensions.
        val ownPlan = roadAuto.planAllRoads()
        // Tile → priority (higher = built sooner). Own plans use their plan priority,
        // neighbour links get a flat lower priority to fill once internal network is done.
        val candidatesByTile = HashMap<Tile, Float>()
        for ((tile, plan) in ownPlan) {
            if (tile.getUnpillagedRoad() >= bestRoadStatus) continue
            if (tile.position in processedTiles) continue
            if (tile.position in civ.pendingPurchaseTiles) continue
            candidatesByTile[tile] = plan.priority
        }

        // Neighbour trade-route extension. For every known major civ at peace, take the
        // closest pair of cities (one ours, one theirs) within 12 tiles and plan a road
        // path between them. Skip if no road tech overlap or if a path can't be found.
        val neighbourTiles = planNeighbourRoadExtensions(civ, bestRoadStatus, candidatesByTile)
        for ((tile, prio) in neighbourTiles) {
            if (tile.position in processedTiles) continue
            if (tile.position in civ.pendingPurchaseTiles) continue
            candidatesByTile.putIfAbsent(tile, prio)
        }
        if (candidatesByTile.isEmpty()) return 0

        // Sort by priority (desc), then by tile distance to the closest connected city
        // (asc — grow the network from existing nodes outward, not random pop-ups).
        val capitalTile = civ.getCapital()?.getCenterTile()
        val sorted = candidatesByTile.entries.sortedWith(
            compareByDescending<Map.Entry<Tile, Float>> { it.value }
                .thenBy { entry ->
                    if (capitalTile == null) 0 else entry.key.aerialDistanceTo(capitalTile)
                }
        )

        var spent = 0
        var bought = 0
        for ((tile, _) in sorted) {
            if (bought >= roadBudgetCap) break
            val price = TileImprovementBuyer.computePrice(tile, roadImprovement, civ) ?: continue
            if (spent + price > remainingBudget) continue
            if (!TileImprovementBuyer.canBuy(tile, roadImprovement, civ)) continue
            if (TileImprovementBuyer.buy(tile, roadImprovement, civ)) {
                spent += price
                bought++
                processedTiles.add(tile.position)
            }
        }
        if (bought > 0) {
            println("[TWv2 AI roads] ${civ.civName}: $bought tiles, ${spent}g")
        }
        return spent
    }

    /** Plan road tiles from each of our border cities to the closest city of a known
     *  peaceful major civ within 12 hexes. Returns tile → priority (lower than internal
     *  road plans so they're filled after the empire is stitched together). */
    private fun planNeighbourRoadExtensions(
        civ: Civilization,
        bestRoadStatus: com.unciv.logic.map.tile.RoadStatus,
        existingPlanTiles: Map<Tile, Float>
    ): Map<Tile, Float> {
        val result = HashMap<Tile, Float>()
        val knownPeers = civ.getKnownCivs().filter { other ->
            other != civ
                && !other.isBarbarian
                && other.isAlive()
                && other.cities.isNotEmpty()
                && !civ.isAtWarWith(other)
        }.toList()
        if (knownPeers.isEmpty()) return result

        for (myCity in civ.cities) {
            val myTile = myCity.getCenterTile()
            // For each known peer, find the closest of their cities within range.
            val targetCity = knownPeers
                .flatMap { it.cities }
                .filter { it.getCenterTile().aerialDistanceTo(myTile) in 1..12 }
                .minByOrNull { it.getCenterTile().aerialDistanceTo(myTile) }
                ?: continue
            val targetTile = targetCity.getCenterTile()
            val path = com.unciv.logic.map.MapPathing.getRoadPath(civ, myTile, targetTile) ?: continue
            // Priority 0.5 — below all own-city plans (which start at priority 2+), so the AI
            // finishes its internal network before splurging on diplomacy.
            for (tile in path) {
                if (tile.getUnpillagedRoad() >= bestRoadStatus) continue
                if (existingPlanTiles.containsKey(tile)) continue
                result.putIfAbsent(tile, 0.5f)
            }
        }
        return result
    }

    /** TW v2 — true if a foreign military unit (civ at war OR a barbarian) sits within
     *  [radius] tiles of [tile]. Used to skip purchases that would just get pillaged again. */
    private fun hasHostileNearby(tile: Tile, civ: Civilization, radius: Int = 2): Boolean {
        for (t in tile.getTilesInDistance(radius)) {
            val unit = t.militaryUnit ?: continue
            val unitCiv = unit.civ
            if (unitCiv == civ) continue
            if (unitCiv.isBarbarian) return true
            val diplo = civ.getDiplomacyManager(unitCiv) ?: continue
            if (diplo.diplomaticStatus == com.unciv.logic.civilization.diplomacy.DiplomaticStatus.War) return true
        }
        return false
    }

    /** TW v2 — City-state freebie: once per scheduled tick, grant a free improvement on the most
     *  meaningful unimproved tile of the city-state's territory. Priority order:
     *    1. A revealed luxury/strategic resource without a working extractor on that tile.
     *    2. A Farm on any flat, unimproved, buildable tile.
     *  Returns true when an improvement is queued (1-turn delay, placed at start of next turn). */
    fun grantFreeImprovement(civInfo: Civilization): Boolean {
        if (civInfo.cities.isEmpty()) return false
        val ruleset = civInfo.gameInfo.ruleset
        val farmImpr = ruleset.tileImprovements["Farm"]
        val farmAvailable = farmImpr != null
            && (farmImpr.techRequired == null || civInfo.tech.isResearched(farmImpr.techRequired!!))

        // Pass 1 — match revealed resources with their extractor (highest infrastructure value).
        for (city in civInfo.cities.sortedByDescending { it.population.population }) {
            for (tile in city.getTiles()) {
                if (tile.isCityCenter()) continue
                if (tile.position in civInfo.pendingPurchaseTiles) continue
                val hasUsableImprovement = tile.improvement != null && !tile.improvementIsPillaged
                if (hasUsableImprovement && tile.providesResources(civInfo)) continue
                val resource = tile.tileResource ?: continue
                if (!civInfo.canSeeResource(resource)) continue
                if (resource.resourceType == ResourceType.Bonus) continue
                val context = GameContext(civInfo = civInfo, tile = tile)
                val extractor = resource.getImprovements().asSequence()
                    .mapNotNull { ruleset.tileImprovements[it] }
                    .firstOrNull { impr ->
                        !impr.isGreatImprovement()
                            && (impr.techRequired == null || civInfo.tech.isResearched(impr.techRequired!!))
                            && tile.improvementFunctions.canBuildImprovement(impr, context)
                    } ?: continue
                return queueFreeImprovement(tile, extractor, civInfo)
            }
        }

        // Pass 2 — drop a Farm on the first unimproved flat tile that accepts one.
        if (farmAvailable) {
            for (city in civInfo.cities.sortedByDescending { it.population.population }) {
                for (tile in city.getTiles()) {
                    if (tile.isCityCenter()) continue
                    if (tile.position in civInfo.pendingPurchaseTiles) continue
                    if (tile.improvement != null && !tile.improvementIsPillaged) continue
                    if (!isTileWorthImproving(tile, civInfo)) continue
                    val context = GameContext(civInfo = civInfo, tile = tile)
                    if (!tile.improvementFunctions.canBuildImprovement(farmImpr!!, context)) continue
                    return queueFreeImprovement(tile, farmImpr, civInfo)
                }
            }
        }
        return false
    }

    /** TW v2 — City-state freebie: once per scheduled tick, repair one pillaged tile for free. */
    fun grantFreeRepair(civInfo: Civilization): Boolean {
        if (civInfo.cities.isEmpty()) return false
        for (city in civInfo.cities) {
            for (tile in city.getTiles()) {
                if (!tile.isPillaged()) continue
                if (tile.position in civInfo.pendingPurchaseTiles) continue
                if (!isTileWorthRepairing(tile, civInfo)) continue
                tile.improvementQueue.clear()
                tile.queueImprovement(Constants.repair, 1)
                civInfo.pendingPurchaseTiles.add(tile.position)
                civInfo.addNotification(
                    "Public works dispatch: a pillaged tile is being repaired (free).",
                    tile.position,
                    NotificationCategory.Production
                )
                return true
            }
        }
        return false
    }

    private fun queueFreeImprovement(tile: Tile, improvement: TileImprovement, civInfo: Civilization): Boolean {
        tile.improvementQueue.clear()
        tile.queueImprovement(improvement.name, 1)
        civInfo.pendingPurchaseTiles.add(tile.position)
        civInfo.addNotification(
            "Public works dispatch: a free [${improvement.name}] is being built.",
            tile.position,
            NotificationCategory.Production
        )
        return true
    }

    /** Heuristic score: weighted yield + resource-match bonus. */
    private fun scoreImprovement(tile: Tile, improvement: TileImprovement, civ: Civilization): Float {
        // TW v2 — Forest / Jungle / Marsh clearance has no direct stats and would otherwise
        // score 0, but it unlocks Farms on the base terrain. Give it a strong positive score
        // so the AI actively converts woodland to farmland whenever it has spare gold.
        if (improvement.name in ALLOWED_REMOVE_NAMES) {
            val farm = tile.ruleset.tileImprovements["Farm"] ?: return 1f
            val techOk = farm.techRequired == null || civ.tech.isResearched(farm.techRequired!!)
            return if (techOk && farm.terrainsCanBeBuiltOn.contains(tile.baseTerrain)) 12f else 1f
        }

        val flat = improvement.cloneStats()
        var score = flat.food * 2f + flat.production * 2f + flat.gold * 1f +
            flat.science * 1.5f + flat.culture * 0.5f + flat.faith * 0.5f + flat.happiness * 0.5f

        // Big bonus for improvements that match the tile's resource.
        val resource = tile.tileResource
        if (resource != null && civ.canSeeResource(resource) && resource.isImprovedBy(improvement.name)) {
            score += when (resource.resourceType) {
                ResourceType.Luxury -> 10f
                ResourceType.Strategic -> 8f
                ResourceType.Bonus -> 4f
            }
        }
        return score
    }
}
