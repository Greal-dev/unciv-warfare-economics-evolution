package com.unciv.logic.civilization.transients

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.StatMap
import com.unciv.models.stats.Stats
import com.unciv.ui.components.extensions.toPercent
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** TW v2: a road on an impassable tile (a mountain pass) costs this many times the normal road upkeep. */
private const val MOUNTAIN_ROAD_UPKEEP_MULTIPLIER = 10f

/** CivInfo class was getting too crowded */
class CivInfoStatsForNextTurn(val civInfo: Civilization) {

    @Transient
    /** Happiness for next turn */
    var happiness = 0

    @Transient
    var statsForNextTurn = Stats()

    @Readonly
    /** Territorial Warfare: unit maintenance = 1 gold × distance to nearest allied city.
     *  Free if the unit is on a road connected to an allied city. */
    private fun getUnitMaintenance(): Int {
        if (civInfo.cities.isEmpty()) return 0

        var totalMaintenance = 0f
        val eraMaintenancePerUnit = civInfo.getEraNumber().coerceAtMost(7) // +1 gold per unit per era (0 in Ancient, capped at 7)

        // Cache connected tiles: all tiles reachable via roads from any city center
        val connectedRoadTiles = HashSet<com.unciv.logic.map.HexCoord>()
        for (city in civInfo.cities) {
            val bfs = com.unciv.logic.map.BFS(city.getCenterTile()) { tile ->
                tile.isCityCenter() || (tile.hasConnection(civInfo) && !tile.roadIsPillaged)
            }
            bfs.stepToEnd()
            for (tile in bfs.getReachedTiles()) {
                connectedRoadTiles.add(tile.position)
            }
        }

        for (unit in civInfo.units.getCivUnits()) {
            // TW v2: workers (BuildImprovements) follow the same era-scaled upkeep as military units,
            // because their cost was divided by 4 — supply now lives in the upkeep, not the build cost.
            val isWorker = unit.hasUnique(UniqueType.BuildImprovements)
            if (!unit.isMilitary() && !isWorker) continue

            // Era-based maintenance: 1 gold per era (0 in Ancient, 1 in Classical, etc.)
            totalMaintenance += eraMaintenancePerUnit.toFloat()

            // Skip units whose tile hasn't been initialized yet (e.g. during addUnit in tests)
            val unitTile = try { unit.currentTile } catch (_: UninitializedPropertyAccessException) { continue }

            // If unit is on a road connected to a city, no distance-based maintenance
            if (connectedRoadTiles.contains(unitTile.position)) continue

            // Otherwise, distance-based maintenance = distance to nearest city
            val nearestCityDist = civInfo.cities.minOf {
                it.getCenterTile().aerialDistanceTo(unitTile)
            }
            totalMaintenance += nearestCityDist.toFloat()
        }

        if (!civInfo.isHuman())
            totalMaintenance *= civInfo.gameInfo.getDifficulty().aiUnitMaintenanceModifier

        return totalMaintenance.toInt()
    }

    @Readonly
    private fun getTransportationUpkeep(): Stats {
        // TW v2: roads cost gold upkeep (RoadStatus.upkeep — 1 for Road, 2 for Railroad) on owned tiles,
        // so civs are pushed to mesh efficiently rather than carpet the map. A road on an impassable tile
        // (a mountain pass) costs 10x as much: keeping an engineered pass open is a heavy, ongoing effort.
        @LocalState var goldCost = 0f
        for (city in civInfo.cities) {
            for (tile in city.getTiles()) {
                if (tile.isCityCenter()) continue
                val road = tile.getUnpillagedRoad()
                if (road == RoadStatus.None) continue
                goldCost += road.upkeep.toFloat() * (if (tile.isImpassible()) MOUNTAIN_ROAD_UPKEEP_MULTIPLIER else 1f)
            }
        }
        if (goldCost == 0f) return Stats()
        // Honour maintenance-reduction uniques (e.g. "-25% maintenance on road & railroads").
        for (unique in civInfo.getMatchingUniques(UniqueType.RoadMaintenance))
            goldCost *= unique.params[0].toPercent()
        return Stats(gold = goldCost)
    }

    @Readonly
    fun getUnitSupply(): Int {
        /* TotalSupply = BaseSupply + NumCities*modifier + Population*modifier
        * In civ5, it seems population modifier is always 0.5, so i hardcoded it down below */
        var supply = getBaseUnitSupply() + getUnitSupplyFromCities() + getUnitSupplyFromPop()

        if (civInfo.isMajorCiv() && civInfo.playerType == PlayerType.AI)
            supply = (supply*(1f + civInfo.getDifficulty().aiUnitSupplyModifier)).toInt()
        return supply
    }

    @Readonly
    fun getBaseUnitSupply(): Int {
        return civInfo.getDifficulty().unitSupplyBase +
            civInfo.getMatchingUniques(UniqueType.BaseUnitSupply).sumOf { it.params[0].toInt() }
    }
    @Readonly
    fun getUnitSupplyFromCities(): Int {
        return civInfo.cities.size *
            (civInfo.getDifficulty().unitSupplyPerCity
                    + civInfo.getMatchingUniques(UniqueType.UnitSupplyPerCity).sumOf { it.params[0].toInt() })
    }
    @Readonly
    fun getUnitSupplyFromPop(): Int {
        var totalSupply = civInfo.cities.sumOf { it.population.population } * civInfo.gameInfo.ruleset.modOptions.constants.unitSupplyPerPopulation

        for (unique in civInfo.getMatchingUniques(UniqueType.UnitSupplyPerPop)) {
            val applicablePopulation = civInfo.cities
                .filter { it.matchesFilter(unique.params[2]) }
                .sumOf { it.population.population / unique.params[1].toInt() }
            totalSupply += unique.params[0].toDouble() * applicablePopulation
        }
        return totalSupply.toInt()
    }
    @Readonly fun getUnitSupplyDeficit(): Int = max(0,civInfo.units.getCivUnitsSize() - getUnitSupply())

    /** Per each supply missing, a player gets -10% production. Capped at -70%. */
    @Readonly fun getUnitSupplyProductionPenalty(): Float = -min(getUnitSupplyDeficit() * 10f, 70f)

    /**
     * TW v2 — flat science upkeep: each researched technology costs 1% of its science cost
     * per turn, in science. Models the cumulative complexity of maintaining a science empire
     * and creates a natural ceiling on tech speed regardless of multiplier stacking.
     */
    @Readonly
    fun getResearchedTechUpkeepCost(): Float {
        if (civInfo.cities.isEmpty()) return 0f
        var total = 0f
        for (techName in civInfo.tech.techsResearched) {
            total += civInfo.tech.costOfTech(techName).toFloat()
        }
        return total * 0.002f  // TW v2: 0.2% upkeep — calibrated so most civs still progress (cf. balance analysis)
    }

    /** Territorial Warfare: tech maintenance — superlinear penalty for being ahead of average.
     *  cost = (excessRatio²) × totalTechs × civScale
     *  Small leads are nearly free, large leads are very expensive.
     *  Adapts to map size via number of alive major civs. */
    @Readonly
    private fun getTechMaintenanceCost(): Float {
        val aliveMajorCivs = civInfo.gameInfo.civilizations.filter { it.isMajorCiv() && !it.isDefeated() }
        if (aliveMajorCivs.size <= 1) return 0f

        val totalTechs = civInfo.gameInfo.ruleset.technologies.size
        if (totalTechs == 0) return 0f

        val myTechs = civInfo.tech.techsResearched.size.toFloat()
        val avgTechs = aliveMajorCivs.sumOf { it.tech.techsResearched.size }.toFloat() / aliveMajorCivs.size
        val excess = myTechs - avgTechs
        if (excess <= 0f) return 0f

        val excessRatio = excess / totalTechs
        // Quadratic: small leads nearly free, large leads very expensive
        // civScale adapts to map size (more civs = harder to stay ahead)
        val civScale = kotlin.math.sqrt(aliveMajorCivs.size.toFloat() / 6f)
        return excessRatio * excessRatio * totalTechs * civScale
    }

    @Readonly
    fun getStatMapForNextTurn(): StatMap {
        val statMap = StatMap()
        for (city in civInfo.cities) {
            for (entry in city.cityStats.finalStatList)
                statMap.add(entry.key, entry.value)
        }

        //City-States bonuses
        for (otherCiv in civInfo.getKnownCivs()) {
            if (!otherCiv.isCityState) continue
            if (otherCiv.getDiplomacyManager(civInfo)!!.relationshipIgnoreAfraid() != RelationshipLevel.Ally)
                continue
            for (unique in civInfo.getMatchingUniques(UniqueType.CityStateStatPercent)) {
                val stats = Stats()
                stats.add(
                    Stat.valueOf(unique.params[0]),
                    otherCiv.stats.statsForNextTurn[Stat.valueOf(unique.params[0])] * unique.params[1].toFloat() / 100f
                )
                statMap.add(
                    Constants.cityStates,
                    stats
                )
            }
        }

        statMap["Transportation upkeep"] = getTransportationUpkeep() * -1
        statMap["Unit upkeep"] = Stats(gold = -getUnitMaintenance().toFloat())

        // TW: Tech maintenance — leaders pay superlinear cost for being ahead
        val techMaintenance = getTechMaintenanceCost()
        if (techMaintenance > 0f)
            statMap["Tech maintenance"] = Stats(science = -techMaintenance)

        // TW v2: flat 1% science upkeep per researched tech of its science cost
        val techUpkeep = getResearchedTechUpkeepCost()
        if (techUpkeep > 0f)
            statMap["Researched tech upkeep"] = Stats(science = -techUpkeep)

        // TW: Happiness bonus is now applied per-city as % bonus in CityStats
        // Old excessHappinessConversion removed

        // negative gold hurts science
        // if we have - or 0, then the techs will never be complete and the tech button
        // will show a negative number of turns and int.max, respectively
        if (statMap.values.map { it.gold }.sum() < 0 && civInfo.gold < 0) {
            val scienceDeficit = max(statMap.values.map { it.gold }.sum(),
                1 - statMap.values.map { it.science }.sum()
            )// Leave at least 1
            statMap["Treasury deficit"] = Stats(science = scienceDeficit)
        }

        val goldDifferenceFromTrade = civInfo.diplomacy.values.sumOf { it.goldPerTurn() }
        if (goldDifferenceFromTrade != 0)
            statMap["Trade"] = Stats(gold = goldDifferenceFromTrade.toFloat())

        for ((key, value) in getGlobalStatsFromUniques())
            statMap.add(key,value)

        // TW: Vassal tribute display (25% of positive gold/science goes to suzerain)
        if (civInfo.isVassal()) {
            val totalGold = statMap.values.sumOf { it.gold.toDouble() }.toFloat()
            val totalScience = statMap.values.sumOf { it.science.toDouble() }.toFloat()
            val tributeGold = if (totalGold > 0) -(totalGold * 0.25f) else 0f
            val tributeScience = if (totalScience > 0) -(totalScience * 0.25f) else 0f
            if (tributeGold != 0f || tributeScience != 0f) {
                statMap["Vassal tribute"] = Stats(gold = tributeGold, science = tributeScience)
            }
        }
        // TW: Income from vassals — calculate directly from vassal city stats to avoid stale cache
        val vassalIncome = Stats()
        for (vassal in civInfo.getVassals()) {
            // Sum raw gold/science from vassal's cities directly
            var vassalGold = 0f
            var vassalScience = 0f
            for (city in vassal.cities) {
                val cityStats = city.cityStats.currentCityStats
                vassalGold += cityStats.gold
                vassalScience += cityStats.science
            }
            // 25% tribute of positive income
            if (vassalGold > 0) vassalIncome.gold += vassalGold * 0.25f
            if (vassalScience > 0) vassalIncome.science += vassalScience * 0.25f
        }
        if (vassalIncome.gold > 0 || vassalIncome.science > 0) {
            statMap["Vassal income"] = vassalIncome
        }

        // TW: Science porosity — shared border tiles generate science proportional to tech gap
        val borderScience = getBorderSciencePorosity()
        if (borderScience > 0f) {
            statMap["Border science exchange"] = Stats(science = borderScience)
        }

        // TW v2 — Empire science tanh dampening REMOVED.
        // It compressed every high producer toward the same ~300 ceiling, so all
        // ratios in the calendar-paced science system collapsed to ~99%. Raw science
        // now flows through unchanged — the calendar pacing already prevents runaway,
        // and the ratio dynamic gives a real disadvantage to laggards.

        // TW v2 — Same hyperbolic-tangent dampening on empire-wide gold income.
        // y = K · tanh(x / K) with K = 3000. Small/mid empires barely touched; runaway
        // gold (>3000/turn) plateaus aggressively. Calibrated for the post-Banking era
        // where Markets+Banks+Stock Exchanges + trade routes + tile tax otherwise stack
        // into 5000+/turn. Negative gold (deficit) passes through tanh symmetrically — a
        // small reduction of the bleed for very-bankrupt civs.
        val empireGoldK = 3000.0
        val rawGold = statMap.values.sumOf { it.gold.toDouble() }
        if (kotlin.math.abs(rawGold) > 0.5) {
            val damped = empireGoldK * kotlin.math.tanh(rawGold / empireGoldK)
            val delta = (damped - rawGold).toFloat()
            if (kotlin.math.abs(delta) > 0.5f) {
                statMap["Empire gold dampening"] = Stats(gold = delta)
            }
        }

        return statMap
    }

    /** Territorial Warfare: border science porosity scaled by tech gap with each neighbor.
     *  More unknown techs from a neighbor = more science gained per shared border tile.
     *  0.5 science/tile × (techsTheyHaveThatWeDont / totalTechs) */
    @Readonly
    private fun getBorderSciencePorosity(): Float {
        val totalTechs = civInfo.gameInfo.ruleset.technologies.size
        if (totalTechs == 0) return 0f

        // Count border tiles per neighbor civ
        val borderTilesPerNeighbor = HashMap<Civilization, Int>()
        val counted = HashSet<com.unciv.logic.map.HexCoord>()
        for (city in civInfo.cities) {
            for (tile in city.getTiles()) {
                if (tile.position in counted) continue
                for (neighbor in tile.neighbors) {
                    val neighborOwner = neighbor.getOwner()
                    if (neighborOwner != null && neighborOwner != civInfo
                        && !neighborOwner.isBarbarian && neighborOwner.isMajorCiv()) {
                        counted.add(tile.position)
                        borderTilesPerNeighbor[neighborOwner] =
                            (borderTilesPerNeighbor[neighborOwner] ?: 0) + 1
                        break
                    }
                }
            }
        }

        var totalScience = 0f
        for ((neighbor, borderTiles) in borderTilesPerNeighbor) {
            // How many techs does the neighbor know that we don't?
            val techsTheyHave = neighbor.tech.techsResearched.count { !civInfo.tech.isResearched(it) }
            val gapFactor = techsTheyHave.toFloat() / totalTechs
            // 0 if we know everything they know, up to ~0.5/tile if huge gap
            totalScience += borderTiles * 0.5f * gapFactor
        }
        return totalScience
    }



    fun getHappinessBreakdown(): HashMap<String, Float> {
        val statMap = HashMap<String, Float>()

        fun HashMap<String, Float>.add(key:String, value: Float) {
            if (!containsKey(key)) put(key, value)
            else put(key, value+get(key)!!)
        }

        statMap["Base happiness"] = civInfo.getDifficulty().baseHappiness.toFloat()

        var happinessPerUniqueLuxury = 4f + civInfo.getDifficulty().extraHappinessPerLuxury
        for (unique in civInfo.getMatchingUniques(UniqueType.BonusHappinessFromLuxury))
            happinessPerUniqueLuxury += unique.params[0].toInt()

        val ownedLuxuries = civInfo.getCivResourceSupply().map { it.resource }
            .filter { it.resourceType == ResourceType.Luxury }

        val relevantLuxuries = civInfo.getCivResourceSupply().asSequence()
            .map { it.resource }
            .count { it.resourceType == ResourceType.Luxury
                    && it.getMatchingUniques(UniqueType.ObsoleteWith)
                .none { unique -> civInfo.tech.isResearched(unique.params[0]) } }
        statMap["Luxury resources"] = relevantLuxuries * happinessPerUniqueLuxury

        val happinessBonusForCityStateProvidedLuxuries =
            civInfo.getMatchingUniques(UniqueType.CityStateLuxuryHappiness).sumOf { it.params[0].toInt() } / 100f

        val luxuriesProvidedByCityStates = civInfo.getKnownCivs().asSequence()
            .filter { it.isCityState && it.allyCiv == civInfo }
            .flatMap { it.getCivResourceSupply().map { res -> res.resource } }
            .distinct()
            .count { it.resourceType === ResourceType.Luxury && ownedLuxuries.contains(it) }

        statMap["City-State Luxuries"] =
            happinessPerUniqueLuxury * luxuriesProvidedByCityStates * happinessBonusForCityStateProvidedLuxuries

        val luxuriesAllOfWhichAreTradedAway = civInfo.detailedCivResources
            .filter {
                it.amount < 0 && it.resource.resourceType == ResourceType.Luxury
                        && (it.origin == "Trade" || it.origin == "Trade request")
            }
            .map { it.resource }
            .filter { !ownedLuxuries.contains(it) }

        statMap["Traded Luxuries"] =
            luxuriesAllOfWhichAreTradedAway.size * happinessPerUniqueLuxury *
                    civInfo.getMatchingUniques(UniqueType.RetainHappinessFromLuxury)
                        .sumOf { it.params[0].toInt() } / 100f

        for (city in civInfo.cities) {
            // There appears to be a concurrency problem? In concurrent thread in ConstructionsTable.getConstructionButtonDTOs
            // Literally no idea how, since happinessList is ONLY replaced, NEVER altered.
            // Oh well, toList() should solve the problem, wherever it may come from.
            for ((key, value) in city.cityStats.happinessList.toList())
                statMap.add(key, value)
        }

        // TW v2 — Empire-level population unhappiness scaling.
        //  1. Tanh soft-cap: y = K · tanh(x/K) with K=100. Each citizen still adds to
        //     the raw total but the empire-wide pressure plateaus near 100.
        //  2. Cultural homogeneity multiplier: capital's own-culture share scales the
        //     capped value. 100% homogeneous → 0.5×, 50% → 1.0×, 0% → 1.5×.
        // The two adjustments together replace the raw "Population" line via a balancing
        // "Imperial cohesion" entry (positive) or "Cultural fracture" entry (negative).
        val rawPopUnhappiness = -(statMap["Population"] ?: 0f)
        if (rawPopUnhappiness > 0.5f) {
            val K = 100.0
            val tanhCapped = K * kotlin.math.tanh(rawPopUnhappiness.toDouble() / K)
            val capital = civInfo.getCapital()
            val capitalCulture = if (capital != null)
                com.unciv.logic.map.TileCultureLogic.getFriendlyShare(capital.getCenterTile(), civInfo)
            else 0.5f
            val cultMultiplier = (1.5f - capitalCulture).coerceIn(0.5f, 1.5f)
            val finalUnhappiness = (tanhCapped * cultMultiplier).toFloat()
            val savings = rawPopUnhappiness - finalUnhappiness   // positive = happiness restored
            if (savings > 0.5f) statMap["Imperial cohesion"] = savings
            else if (savings < -0.5f) statMap["Cultural fracture"] = savings
        }

        // TW v2 — Soft cap on empire-wide POSITIVE happiness surplus. y = K · tanh(x/K)
        // with K = 100, applied only when the net is > 0. Negative happiness (revolt
        // territory) passes through raw so that struggling civs feel the pain. Tame the
        // runaway "luxury × buildings × cohesion" stacks producing absurd +150 surpluses.
        val happyK = 100.0
        val netHappy = statMap.values.sum()
        if (netHappy > 0.5f) {
            val damped = happyK * kotlin.math.tanh(netHappy.toDouble() / happyK)
            val delta = (damped - netHappy).toFloat()
            if (delta < -0.5f) statMap["Happiness saturation"] = delta
        }

        val transportUpkeep = getTransportationUpkeep()
        if (transportUpkeep.happiness != 0f)
            statMap["Transportation Upkeep"] = -transportUpkeep.happiness

        for ((key, value) in getGlobalStatsFromUniques())
            statMap.add(key,value.happiness)

        return statMap
    }

    @Readonly
    private fun getGlobalStatsFromUniques():StatMap {
        val statMap = StatMap()
        if (civInfo.religionManager.religion != null) {
            for (unique in civInfo.religionManager.religion!!.founderBeliefUniqueMap.getMatchingUniques(
                UniqueType.StatsFromGlobalCitiesFollowingReligion, civInfo.state
            ))
                statMap.add(
                    "Religion",
                    unique.stats * civInfo.religionManager.numberOfCitiesFollowingThisReligion()
                )

            for (unique in civInfo.religionManager.religion!!.founderBeliefUniqueMap.getMatchingUniques(
                UniqueType.StatsFromGlobalFollowers, civInfo.state
            ))
                statMap.add(
                    "Religion",
                    unique.stats * civInfo.religionManager.numberOfFollowersFollowingThisReligion(
                        unique.params[2]
                    ).toFloat() / unique.params[1].toFloat()
                )
        }

        for (unique in civInfo.getMatchingUniques(UniqueType.StatsPerPolicies)) {
            val amount = civInfo.policies.getAdoptedPolicies()
                .count { !Policy.isBranchCompleteByName(it) } / unique.params[1].toInt()
            statMap.add("Policies", unique.stats.times(amount))
        }

        for (unique in civInfo.getMatchingUniques(UniqueType.Stats))
            if (unique.sourceObjectType != UniqueTarget.Building && unique.sourceObjectType != UniqueTarget.Wonder)
                statMap.add(unique.getSourceNameForUser(), unique.stats)

        for (unique in civInfo.getMatchingUniques(UniqueType.StatsPerStat)) {
            val amount = civInfo.getStatReserve(Stat.valueOf(unique.params[2])) / unique.params[1].toInt()
            statMap.add("Stats", unique.stats.times(amount))
        }

        val statsPerNaturalWonder = Stats(happiness = 1f)

        for (unique in civInfo.getMatchingUniques(UniqueType.StatsFromNaturalWonders))
            statsPerNaturalWonder.add(unique.stats)

        statMap.add("Natural Wonders", statsPerNaturalWonder.times(civInfo.naturalWonders.size))

        if (statMap.contains(Constants.cityStates)) {
            for (unique in civInfo.getMatchingUniques(UniqueType.BonusStatsFromCityStates)) {
                val bonusPercent = unique.params[0].toPercent()
                val bonusStat = Stat.valueOf(unique.params[1])
                statMap[Constants.cityStates]!![bonusStat] *= bonusPercent
            }
        }

        return statMap
    }

}
