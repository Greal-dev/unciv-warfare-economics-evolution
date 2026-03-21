package com.unciv.logic.city

import com.unciv.logic.map.tile.RoadStatus
import com.unciv.models.Counter
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.unique.LocalUniqueCache
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.models.stats.StatMap
import com.unciv.models.stats.Stats
import com.unciv.ui.components.extensions.toPercent
import com.unciv.logic.civilization.managers.ImperialStabilityManager
import com.unciv.utils.DebugUtils
import yairm210.purity.annotations.InternalState
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Pure
import yairm210.purity.annotations.Readonly
import kotlin.math.min

@InternalState
class StatTreeNode {
    val children = LinkedHashMap<String, StatTreeNode>()
    private var innerStats: Stats? = null

    fun setInnerStat(stat: Stat, value: Float) {
        if (innerStats == null) innerStats = Stats()
        innerStats!![stat] = value
    }

    private fun addInnerStats(stats: Stats) {
        if (innerStats == null) innerStats = stats.clone() // Copy the stats instead of referencing them
        else innerStats!!.add(stats) // What happens if we add 2 stats to the same leaf?
    }

    fun addStats(newStats: Stats?, vararg hierarchyList: String) {
        if (newStats == null) return
        if (newStats.isEmpty()) return
        if (hierarchyList.isEmpty()) {
            addInnerStats(newStats)
            return
        }
        val childName = hierarchyList.first()
        if (!children.containsKey(childName))
            children[childName] = StatTreeNode()
        children[childName]!!.addStats(newStats, *hierarchyList.drop(1).toTypedArray())
    }

    fun add(otherTree: StatTreeNode) {
        if (otherTree.innerStats != null) addInnerStats(otherTree.innerStats!!)
        for ((key, value) in otherTree.children) {
            if (!children.containsKey(key)) children[key] = value
            else children[key]!!.add(value)
        }
    }

    fun clone() : StatTreeNode {
        val new = StatTreeNode()
        new.innerStats = this.innerStats?.clone()
        new.children.putAll(this.children.mapValues { it.value.clone() })
        return new
    }

    val totalStats: Stats
        get() {
            val toReturn = Stats()
            if (innerStats != null) toReturn.add(innerStats!!)
            for (child in children.values) toReturn.add(child.totalStats)
            return toReturn
        }
}

/** Holds and calculates [Stats] for a city.
 *
 * No field needs to be saved, all are calculated on the fly,
 * so its field in [City] is @Transient and no such annotation is needed here.
 */
class CityStats(val city: City) {
    //region Fields, Transient

    var baseStatTree = StatTreeNode()

    var statPercentBonusTree = StatTreeNode()

    // Computed from baseStatList and statPercentBonusList - this is so the players can see a breakdown
    var finalStatList = LinkedHashMap<String, Stats>()

    var happinessList = LinkedHashMap<String, Float>()

    var statsFromTiles = Stats()

    /** TW: tile shields accumulated as % production bonus (1 shield = +1%) */
    var tileProductionBonus = 0f

    var currentCityStats: Stats = Stats()  // This is so we won't have to calculate this multiple times - takes a lot of time, especially on phones

    //endregion
    //region Pure Functions

    @Readonly
    private fun getStatsFromTradeRoute(): Stats {
        val stats = Stats()
        val capitalForTradeRoutePurposes = city.civ.getCapital()!!
        if (city != capitalForTradeRoutePurposes && city.isConnectedToCapital()) {
            stats.gold = (capitalForTradeRoutePurposes.population.population * 0.15f + city.population.population * 1.1f - 1) * 3f // Territorial Warfare: trade route bonus ×3
            for (unique in city.getMatchingUniques(UniqueType.StatsFromTradeRoute))
                stats.add(unique.stats)
            val percentageStats = Stats()
            for (unique in city.getMatchingUniques(UniqueType.StatPercentFromTradeRoutes))
                percentageStats[Stat.valueOf(unique.params[1])] += unique.params[0].toFloat()
            for ((stat) in stats) {
                stats[stat] *= percentageStats[stat].toPercent()
            }
        }
        return stats
    }

    @Readonly
    private fun getStatsFromProduction(production: Float): Stats? {
        if (city.cityConstructions.currentConstructionName() in Stat.statsWithCivWideField.map { it.name }) {
            val stats = Stats()
            val stat = Stat.valueOf(city.cityConstructions.currentConstructionName())
            stats[stat] = production * getStatConversionRate(stat)
            return stats
        }
        return null
    }

    @Readonly
    fun getStatConversionRate(stat: Stat): Float {
        var conversionRate = 1 / 4f
        val conversionUnique = city.civ.getMatchingUniques(UniqueType.ProductionToCivWideStatConversionBonus).firstOrNull { it.params[0] == stat.name }
        if (conversionUnique != null) {
            conversionRate *= conversionUnique.params[1].toPercent()
        }
        return conversionRate
    }

    @Readonly
    private fun getStatPercentBonusesFromRailroad(): Stats? {
        val railroadImprovement = city.getRuleset().railroadImprovement
            ?: return null // for mods
        val techEnablingRailroad = railroadImprovement.techRequired
        // If we conquered enemy cities connected by railroad, but we don't yet have that tech,
        // we shouldn't get bonuses, it's as if the tracks are laid out but we can't operate them.
        if ( (techEnablingRailroad == null || city.civ.tech.isResearched(techEnablingRailroad))
                && (city.isCapital() || isConnectedToCapital(RoadStatus.Railroad)))
            return Stats(production = 25f)
        return null
    }

    @Readonly
    private fun getStatPercentBonusesFromPuppetCity(): Stats? {
        if (!city.isPuppet) return null
        return Stats(science = -25f, culture = -25f)
    }

    @Readonly
    fun getGrowthBonus(totalFood: Float, localUniqueCache: LocalUniqueCache = LocalUniqueCache(false)): StatMap {
        val growthSources = StatMap()
        // "[amount]% growth [cityFilter]"
        for (unique in localUniqueCache.forCityGetMatchingUniques(city, UniqueType.GrowthPercentBonus)) {
            if (!city.matchesFilter(unique.params[1])) continue

            growthSources.add(
                unique.getSourceNameForUser(),
                Stats(food = unique.params[0].toFloat() / 100f * totalFood)
            )
        }
        return growthSources
    }

    @Readonly
    fun hasExtraAnnexUnhappiness(): Boolean {
        if (city.civ == city.foundingCivObject || city.isPuppet) return false
        return !city.containsBuildingUnique(UniqueType.RemovesAnnexUnhappiness)
    }

    @Readonly
    fun getStatsOfSpecialist(specialistName: String, localUniqueCache: LocalUniqueCache = LocalUniqueCache(false)): Stats {
        val specialist = city.getRuleset().specialists[specialistName]
            ?: return Stats()
        @LocalState val stats = specialist.cloneStats()
        for (unique in localUniqueCache.forCityGetMatchingUniques(city, UniqueType.StatsFromSpecialist))
            if (city.matchesFilter(unique.params[1]))
                stats.add(unique.stats)
        for (unique in localUniqueCache.forCityGetMatchingUniques(city, UniqueType.StatsFromObject))
            if (unique.params[1] == specialistName)
                stats.add(unique.stats)
        return stats
    }

    @Readonly
    private fun getStatsFromSpecialists(specialists: Counter<String>): Stats {
        val stats = Stats()
        val localUniqueCache = LocalUniqueCache()
        for ((key, value) in specialists.filter { it.value > 0 }.toList()) // avoid concurrent modification when calculating construction costs
            stats.add(getStatsOfSpecialist(key, localUniqueCache) * value)
        return stats
    }


    @Readonly
    private fun getStatsFromUniquesBySource(): StatTreeNode {
        val sourceToStats = StatTreeNode()

        val cityStateStatsMultipliers = city.civ.getMatchingUniques(UniqueType.BonusStatsFromCityStates).toList()

        fun addUniqueStats(unique: Unique) {
            @LocalState val stats = unique.stats.clone()
            if (unique.sourceObjectType==UniqueTarget.CityState)
                for (multiplierUnique in cityStateStatsMultipliers)
                    stats[Stat.valueOf(multiplierUnique.params[1])] *= multiplierUnique.params[0].toPercent()
            sourceToStats.addStats(stats, unique.getSourceNameForUser(), unique.sourceObjectName ?: "")
        }

        for (unique in city.getMatchingUniques(UniqueType.StatsPerCity))
            if (city.matchesFilter(unique.params[1]))
                addUniqueStats(unique)

        // "[stats] per [amount] population [cityFilter]"
        for (unique in city.getMatchingUniques(UniqueType.StatsPerPopulation))
            if (city.matchesFilter(unique.params[2])) {
                val amountOfEffects = (city.population.population / unique.params[1].toInt()).toFloat()
                sourceToStats.addStats(unique.stats.times(amountOfEffects), unique.getSourceNameForUser(), unique.sourceObjectName ?: "")
            }

        for (unique in city.getMatchingUniques(UniqueType.StatsFromCitiesOnSpecificTiles))
            if (city.getCenterTile().matchesTerrainFilter(unique.params[1], city.civ))
                addUniqueStats(unique)



        return sourceToStats
    }

    /** Territorial Warfare: progressive golden age bonus (ramp 2%/turn over 5 turns, plateau 10%, decay 5 turns) */
    @Readonly
    private fun getStatPercentBonusesFromGoldenAge(isGoldenAge: Boolean): Stats? {
        if (!isGoldenAge) return null
        val bonus = city.civ.goldenAges.getProgressiveBonus()
        if (bonus <= 0f) return null
        return Stats(production = bonus, gold = bonus)
    }

    /** TW: Happiness = direct % bonus on science, production and gold.
     *  1 happiness = +1%. Malus capped at -15%. No cap on bonus. */
    @Readonly
    private fun getStatPercentBonusesFromHappiness(): Stats? {
        val happiness = city.civ.getHappiness()
        val bonus = happiness.toFloat().coerceAtLeast(-15f) // malus capped at -15%
        if (bonus == 0f) return null
        return Stats(science = bonus, production = bonus, gold = bonus)
    }

    /** Territorial Warfare: -30% production per conquered city (recovers 0.5%/turn) + -5% global per city owned */
    @Readonly
    private fun getStatPercentBonusesFromConquestAndExpansion(): Stats? {
        val civ = city.civ
        if (!civ.isMajorCiv()) return null

        var productionMalus = 0f

        // Conquest malus: -30% decaying at 0.5%/turn (60 turns to recover)
        val isConqueredCity = city.foundingCivObject != null && city.foundingCivObject != civ
        if (isConqueredCity) {
            val turnsSinceAcquired = civ.gameInfo.turns - city.turnAcquired
            val conquestPenalty = (30f - 0.5f * turnsSinceAcquired).coerceAtLeast(0f)
            productionMalus -= conquestPenalty
        }

        // Expansion malus: -5% per city beyond the first
        if (civ.cities.size > 1)
            productionMalus -= 5f * (civ.cities.size - 1)

        return if (productionMalus == 0f) null else Stats(production = productionMalus)
    }

    /** Territorial Warfare: production bonus based on distance between capitals.
     *  Bonus per other major civ capital = 100 - distance*10 (min 0, cumulative).
     *  Only active from Medieval era onwards. Disappears if the other capital is captured or converted. */
    @Readonly
    private fun getStatPercentBonusesFromCapitalProximity(): Stats? {
        val civ = city.civ
        if (!civ.isMajorCiv()) return null

        val myCapital = civ.getCapital() ?: return null

        var totalBonus = 0f
        for (otherCiv in civ.gameInfo.civilizations) {
            if (otherCiv == civ) continue
            if (!otherCiv.isMajorCiv()) continue
            if (otherCiv.isDefeated()) continue

            val otherCapital = otherCiv.getCapital() ?: continue
            // Skip if the capital was captured (founder != current owner)
            if (otherCapital.foundingCivObject != null && otherCapital.foundingCivObject != otherCiv) continue

            val distance = myCapital.getCenterTile().aerialDistanceTo(otherCapital.getCenterTile())
            val bonus = (100f - distance * 10f).coerceAtLeast(0f)
            totalBonus += bonus
        }

        return if (totalBonus == 0f) null else Stats(production = totalBonus, science = totalBonus)
    }

    /** Territorial Warfare: production/culture modifiers based on Imperial Stability Index */
    @Readonly
    private fun getStatPercentBonusesFromImperialStability(): Stats? {
        val civ = city.civ
        if (!civ.isMajorCiv()) return null

        val tier = civ.stabilityManager.getTier()
        val isConqueredCity = city.foundingCivObject != null && city.foundingCivObject != civ

        val stats = Stats()
        when (tier) {
            ImperialStabilityManager.StabilityTier.GoldenAge -> {
                stats.production = 10f
                stats.culture = 10f
            }
            ImperialStabilityManager.StabilityTier.Stable -> return null
            ImperialStabilityManager.StabilityTier.Tensions -> {
                if (isConqueredCity) stats.production = -25f
                else return null
            }
            ImperialStabilityManager.StabilityTier.Crisis -> {
                if (isConqueredCity) stats.production = -50f
                else return null
            }
            ImperialStabilityManager.StabilityTier.Collapse -> {
                stats.production = -50f
            }
        }

        // Renaissance bonus (additive)
        val renaissanceBonus = civ.stabilityManager.getRenaissanceBonusPercent()
        if (renaissanceBonus > 0f) {
            stats.production += renaissanceBonus
            stats.culture += renaissanceBonus
        }

        return if (stats.production == 0f && stats.culture == 0f) null else stats
    }

    @Readonly
    private fun getStatsPercentBonusesFromUniquesBySource(currentConstruction: IConstruction): StatTreeNode {
        val sourceToStats = StatTreeNode()

        fun addUniqueStats(unique: Unique, stat: Stat, amount: Float) {
            val stats = Stats()
            stats.add(stat, amount)
            sourceToStats.addStats(stats, unique.getSourceNameForUser(), unique.sourceObjectName ?: "")
        }

        for (unique in city.getMatchingUniques(UniqueType.StatPercentBonus)) {
            addUniqueStats(unique, Stat.valueOf(unique.params[1]), unique.params[0].toFloat())
        }


        for (unique in city.getMatchingUniques(UniqueType.StatPercentBonusCities)) {
            if (city.matchesFilter(unique.params[2]))
                addUniqueStats(unique, Stat.valueOf(unique.params[1]), unique.params[0].toFloat())
        }

        val uniquesToCheck =
            when {
                currentConstruction is BaseUnit ->
                    city.getMatchingUniques(UniqueType.PercentProductionUnits)
                currentConstruction is Building && currentConstruction.isAnyWonder() ->
                    city.getMatchingUniques(UniqueType.PercentProductionWonders)
                currentConstruction is Building && !currentConstruction.isAnyWonder() ->
                    city.getMatchingUniques(UniqueType.PercentProductionBuildings)
                else -> emptySequence() // Science/Gold production
            }

        for (unique in uniquesToCheck) {
            if (constructionMatchesFilter(currentConstruction, unique.params[1])
                && city.matchesFilter(unique.params[2])
            )
                addUniqueStats(unique, Stat.Production, unique.params[0].toFloat())
        }

        // TW: Military unit war/peace cost modifier moved to BaseUnitCost.getProductionCost()
        // (÷2 cost in war, ×2 cost in peace — affects unit cost, not city production)
        if (currentConstruction is BaseUnit && currentConstruction.isMilitary) {
            // Territorial Warfare: city-states get ×3 military production
            if (city.civ.isCityState) {
                val csStats = Stats()
                csStats.add(Stat.Production, 200f) // +200% = ×3 total
                sourceToStats.addStats(csStats, "City-State", "Military production bonus")
            }
        }

        for (unique in city.getMatchingUniques(UniqueType.StatPercentFromReligionFollowers))
            addUniqueStats(unique, Stat.valueOf(unique.params[1]),
                min(
                    unique.params[0].toFloat() * city.religion.getFollowersOfMajorityReligion(),
                    unique.params[2].toFloat()
                ))

        if (currentConstruction is Building
            && city.civ.getCapital()?.cityConstructions?.isBuilt(currentConstruction.name) == true
        ) {
            for (unique in city.getMatchingUniques(UniqueType.PercentProductionBuildingsInCapital))
                addUniqueStats(unique, Stat.Production, unique.params[0].toFloat())
        }

        return sourceToStats
    }

    @Readonly
    private fun getStatPercentBonusesFromUnitSupply(): Stats? {
        val supplyDeficit = city.civ.stats.getUnitSupplyDeficit()
        if (supplyDeficit > 0)
            return Stats(production = city.civ.stats.getUnitSupplyProductionPenalty())
        return null
    }

    @Readonly
    private fun constructionMatchesFilter(construction: IConstruction, filter: String): Boolean {
        val state = city.state
        if (construction is Building) return construction.matchesFilter(filter, state)
        if (construction is BaseUnit) return construction.matchesFilter(filter, state)
        return false
    }

    @Readonly
    fun isConnectedToCapital(roadType: RoadStatus): Boolean {
        if (city.civ.cities.size < 2) return false // first city!

        // Railroad, or harbor from railroad
        return if (roadType == RoadStatus.Railroad)
                city.isConnectedToCapital {
                    mediums ->
                    mediums.any { it.roadType == RoadStatus.Railroad }
                }
            else city.isConnectedToCapital()
    }

    @Readonly
    fun getRoadTypeOfConnectionToCapital(): RoadStatus {
        return city.civ.cache.citiesConnectedToCapitalToMediums[city]?.maxOfOrNull { it.roadType }
            ?: RoadStatus.None
    }

    @Readonly
    private fun getBuildingMaintenanceCosts(): Float {
        // Same here - will have a different UI display.
        var buildingsMaintenance = city.cityConstructions.getMaintenanceCosts() // this is AFTER the bonus calculation!
        if (!city.civ.isHuman()) {
            buildingsMaintenance *= city.civ.gameInfo.getDifficulty().aiBuildingMaintenanceModifier
        }

        return buildingsMaintenance
    }

    //endregion
    //region State-Changing Methods

    fun updateTileStats(localUniqueCache: LocalUniqueCache = LocalUniqueCache()) {
        val stats = Stats()
        var totalTileProduction = 0f  // TW: accumulate tile shields for % bonus
        val workedTiles = city.tilesInRange.asSequence()
            .filter {
                city.location.toHexCoord() == it.position
                        || city.isWorked(it)
                        || it.owningCity == city && (it.getUnpillagedTileImprovement()
                    ?.hasUnique(UniqueType.TileProvidesYieldWithoutPopulation, it.stateThisTile) == true
                        || it.terrainHasUnique(UniqueType.TileProvidesYieldWithoutPopulation, it.stateThisTile))
            }
        for (tile in workedTiles) {
            if (tile.isBlockaded() && city.isWorked(tile)) {
                city.workedTiles.remove(tile.position)
                city.lockedTiles.remove(tile.position)
                city.shouldReassignPopulation = true
                continue
            }
            val tileStats = tile.stats.getTileStats(city, city.civ, localUniqueCache)
            // TW: Tile production becomes % bonus, not base stat
            totalTileProduction += tileStats.production
            tileStats.production = 0f
            // Territorial Warfare: for worked tiles, only add non-food stats
            val nonFoodStats = tileStats.clone().apply { food = 0f }
            stats.add(nonFoodStats)
        }

        // Territorial Warfare: food + tile tax in single pass over all territory tiles
        var totalTerritoryFood = 0f
        var totalTileTax = 0f
        for (tile in city.getTiles()) {
            totalTerritoryFood += tile.stats.getTileStats(city, city.civ, localUniqueCache).food
            totalTileTax += 0.5f * com.unciv.logic.map.TileCultureLogic.getYieldMultiplier(tile)
        }
        val foodEfficiency = when {
            city.cityConstructions.containsBuildingOrEquivalent("Medical Center") -> 1.0f
            city.cityConstructions.containsBuildingOrEquivalent("Hospital") -> 0.75f
            city.cityConstructions.containsBuildingOrEquivalent("Aqueduct") -> 0.50f
            else -> 0.25f
        }
        stats.food += totalTerritoryFood * foodEfficiency
        stats.gold += totalTileTax

        tileProductionBonus = totalTileProduction  // TW: store for % bonus
        statsFromTiles = stats
    }


    // needs to be a separate function because we need to know the global happiness state
    // in order to determine how much food is produced in a city!
    fun updateCityHappiness(statsFromBuildings: StatTreeNode) {
        val civInfo = city.civ
        val newHappinessList = LinkedHashMap<String, Float>()
        // This calculation seems weird to me.
        // Suppose we calculate the modifier for an AI (non-human) player when the game settings has difficulty level 'prince'.
        // We first get the difficulty modifier for this civilization, which results in the 'chieftain' modifier (0.6) being used,
        // as this is a non-human player. Then we multiply that by the ai modifier in general, which is 1.0 for prince.
        // The end result happens to be 0.6, which seems correct. However, if we were playing on chieftain difficulty,
        // we would get back 0.6 twice and the modifier would be 0.36. Thus, in general there seems to be something wrong here
        // I don't know enough about the original whether they do something similar or not and can't be bothered to find where
        // in the source code this calculation takes place, but it would surprise me if they also did this double multiplication thing. ~xlenstra
        var unhappinessModifier = civInfo.getDifficulty().unhappinessModifier
        if (!civInfo.isHuman())
            unhappinessModifier *= civInfo.gameInfo.getDifficulty().aiUnhappinessModifier

        // Territorial Warfare: no unhappiness from number of cities
        // (territory stability is handled by the culture/rebellion system instead)

        var unhappinessFromCitizens = city.population.population.toFloat()

        for (unique in city.getMatchingUniques(UniqueType.UnhappinessFromPopulationTypePercentageChange))
            if (city.matchesFilter(unique.params[2]))
                unhappinessFromCitizens += (unique.params[0].toFloat() / 100f) * city.population.getPopulationFilterAmount(unique.params[1])

        if (hasExtraAnnexUnhappiness())
            unhappinessFromCitizens *= 2f

        if (unhappinessFromCitizens < 0) unhappinessFromCitizens = 0f

        newHappinessList["Population"] = -unhappinessFromCitizens * unhappinessModifier

        if (hasExtraAnnexUnhappiness()) newHappinessList["Occupied City"] = -2f //annexed city

        val happinessFromSpecialists =
            getStatsFromSpecialists(city.population.getNewSpecialists()).happiness.toInt()
                .toFloat()
        if (happinessFromSpecialists > 0) newHappinessList["Specialists"] = happinessFromSpecialists

        newHappinessList["Buildings"] = statsFromBuildings.totalStats.happiness.toInt().toFloat()

        newHappinessList["Tile yields"] = statsFromTiles.happiness

        val happinessBySource = getStatsFromUniquesBySource()
        for ((source, stats) in happinessBySource.children)
            if (stats.totalStats.happiness != 0f) {
                if (!newHappinessList.containsKey(source)) newHappinessList[source] = 0f
                newHappinessList[source] = newHappinessList[source]!! + stats.totalStats.happiness
            }

        // we don't want to modify the existing happiness list because that leads
        // to concurrency problems if we iterate on it while changing
        happinessList = newHappinessList
    }

    private fun updateBaseStatList(statsFromBuildings: StatTreeNode) {
        val newBaseStatTree = StatTreeNode()

        // We don't edit the existing baseStatList directly, in order to avoid concurrency exceptions
        val newBaseStatList = StatMap()

        // TW: All population contributes 1 production each (not just free pop)
        newBaseStatTree.addStats(Stats(
            science = city.population.population.toFloat(),
            production = city.population.population.toFloat()
        ), "Population")
        newBaseStatList["Tile yields"] = statsFromTiles
        newBaseStatList["Specialists"] =
            getStatsFromSpecialists(city.population.getNewSpecialists())
        newBaseStatList["Trade routes"] = getStatsFromTradeRoute()
        newBaseStatTree.children["Buildings"] = statsFromBuildings

        for ((source, stats) in newBaseStatList)
            newBaseStatTree.addStats(stats, source)

        newBaseStatTree.add(getStatsFromUniquesBySource())
        baseStatTree = newBaseStatTree
    }
    
    @Readonly
    private fun getStatPercentBonusList(currentConstruction: IConstruction): StatTreeNode {
        val newStatsBonusTree = StatTreeNode()

        newStatsBonusTree.addStats(getStatPercentBonusesFromGoldenAge(city.civ.goldenAges.isGoldenAge()),"Golden Age")
        newStatsBonusTree.addStats(getStatPercentBonusesFromRailroad(), "Railroad")
        newStatsBonusTree.addStats(getStatPercentBonusesFromPuppetCity(), "Puppet City")
        newStatsBonusTree.addStats(getStatPercentBonusesFromUnitSupply(), "Unit Supply")
        newStatsBonusTree.addStats(getStatPercentBonusesFromConquestAndExpansion(), "Conquest & Expansion")
        newStatsBonusTree.addStats(getStatPercentBonusesFromCapitalProximity(), "Capital Proximity")
        newStatsBonusTree.addStats(getStatPercentBonusesFromImperialStability(), "Imperial Stability")
        newStatsBonusTree.addStats(getStatPercentBonusesFromHappiness(), "Happiness")
        newStatsBonusTree.add(getStatsPercentBonusesFromUniquesBySource(currentConstruction))
        
        val localUniqueCache = LocalUniqueCache()
        for (building in city.cityConstructions.getBuiltBuildings())
            newStatsBonusTree.addStats(building.getStatPercentageBonuses(city, localUniqueCache),
                "Buildings", building.name)


        // TW: Tile shields as % production bonus (1 shield = +1%)
        if (tileProductionBonus > 0f) {
            newStatsBonusTree.addStats(Stats(production = tileProductionBonus), "Tile productivity")
        }

        if (DebugUtils.SUPERCHARGED) {
            val stats = Stats()
            for (stat in Stat.entries) stats[stat] = 10000f
            newStatsBonusTree.addStats(stats, "Supercharged")
        }
        return newStatsBonusTree
    }
    
    private fun updateStatPercentBonusList(currentConstruction: IConstruction){
        statPercentBonusTree = getStatPercentBonusList(currentConstruction)
    }

    fun update(currentConstruction: IConstruction = city.cityConstructions.getCurrentConstruction(),
               updateTileStats:Boolean = true,
               updateCivStats:Boolean = true,
               localUniqueCache:LocalUniqueCache = LocalUniqueCache(),
               calculateGrowthModifiers:Boolean = true) {

        if (updateTileStats) updateTileStats(localUniqueCache)

        // We need to compute Tile yields before happiness

        val statsFromBuildings = city.cityConstructions.getStats(localUniqueCache) // this is performance heavy, so calculate once
        updateBaseStatList(statsFromBuildings)
        updateCityHappiness(statsFromBuildings)
        updateStatPercentBonusList(currentConstruction)

        updateFinalStatList(currentConstruction, calculateGrowthModifiers) // again, we don't edit the existing currentCityStats directly, in order to avoid concurrency exceptions

        val newCurrentCityStats = Stats()
        for (stat in finalStatList.values) newCurrentCityStats.add(stat)
        currentCityStats = newCurrentCityStats

        if (updateCivStats) city.civ.updateStatsForNextTurn()
    }

    private fun updateFinalStatList(currentConstruction: IConstruction, calculateGrowthModifiers: Boolean = true) {
        val newFinalStatList = StatMap() // again, we don't edit the existing currentCityStats directly, in order to avoid concurrency exceptions

        for ((key, value) in baseStatTree.children)
            newFinalStatList[key] = value.totalStats.clone()

        val statPercentBonusesSum = statPercentBonusTree.totalStats

        for (entry in newFinalStatList.values)
            entry.production *= statPercentBonusesSum.production.toPercent()

        // Territorial Warfare: exponential-decay production growth in Industrial era
        // f(x) = 1 + 2.886 * (1 - e^(-0.00693x))
        // Growth rate: 2%/turn at start, halving every 100 turns, cap ~×3.89
        val industrialEra = city.civ.gameInfo.ruleset.eras.values.firstOrNull { it.name == "Industrial era" }
        if (industrialEra != null && city.civ.getEraNumber() >= industrialEra.eraNumber && city.civ.turnsInIndustrialEra > 0) {
            val x = city.civ.turnsInIndustrialEra.toDouble()
            val b = kotlin.math.ln(2.0) / 100.0
            val multiplier = 1.0 + (0.02 / b) * (1.0 - kotlin.math.exp(-b * x))
            if (multiplier > 1.0) {
                for (entry in newFinalStatList.values)
                    entry.production *= multiplier.toFloat()
            }
        }

        // TW: Production smoothing — city production converges toward computed potential
        // at 5% of the gap per turn. Prevents instant jumps and makes recovery gradual.
        val computedProduction = newFinalStatList.values.map { it.production }.sum()
        if (computedProduction > 0f) {
            if (city.smoothedProduction < 0f) {
                // First time: initialize to computed production
                city.smoothedProduction = computedProduction
            } else {
                // Converge toward target: 5% of gap per turn
                val gap = computedProduction - city.smoothedProduction
                city.smoothedProduction += 0.05f * gap
                // Apply the smoothed production: scale all production entries proportionally
                if (computedProduction > 0.1f) {
                    val ratio = city.smoothedProduction / computedProduction
                    for (entry in newFinalStatList.values)
                        entry.production *= ratio
                }
            }
        }

        // We only add the 'extra stats from production' AFTER we calculate the production INCLUDING BONUSES
        val statsFromProduction = getStatsFromProduction(newFinalStatList.values.map { it.production }.sum())
        if (statsFromProduction != null && !statsFromProduction.isEmpty()) {
            baseStatTree = StatTreeNode().apply {
                children.putAll(baseStatTree.children)
                addStats(statsFromProduction, "Production")
            } // concurrency-safe addition
            newFinalStatList["Construction"] = statsFromProduction
        }

        for (entry in newFinalStatList.values) {
            entry.gold *= statPercentBonusesSum.gold.toPercent()
            entry.culture *= statPercentBonusesSum.culture.toPercent()
            entry.food *= statPercentBonusesSum.food.toPercent()
            entry.faith *= statPercentBonusesSum.faith.toPercent()
        }

        // TW: Gold-to-Science: slider % gives science bonus AND costs gold proportionally.
        // e.g. 100% slider → +100% science (×2) AND -100% gold. 50% slider → +50% science, -50% gold.
        val goldToSciencePercent = if (city.getRuleset().modOptions.hasUnique(UniqueType.ConvertGoldToScience))
            city.civ.tech.goldPercentConvertedToScience else 0f

        for (entry in newFinalStatList.values) {
            entry.science *= (statPercentBonusesSum.science + goldToSciencePercent * 100f).toPercent()
        }

        if (goldToSciencePercent > 0f) {
            val totalGold = newFinalStatList.values.sumOf { it.gold.toDouble() }.toFloat()
            if (totalGold > 0f) {
                val goldCost = totalGold * goldToSciencePercent
                newFinalStatList["Gold -> Science"] = Stats(gold = -goldCost)
            }
        }

        // TW: Ethnocultural diversity penalty on science.
        // 100% science at 80%+ owner culture. Below 80%: -1% science per 0.8 points of deficit.
        // At 0% owner culture → 0% science.
        val cityCenterTile = city.getCenterTile()
        // TW: Friendly share = national culture + local city cultures of this civ
        val ownerCulture = com.unciv.logic.map.TileCultureLogic.getFriendlyShare(cityCenterTile, city.civ)
        val ownerPercent = ownerCulture * 100f
        if (ownerPercent < 80f) {
            val deficit = 80f - ownerPercent
            val penaltyPercent = (deficit / 0.8f).coerceAtMost(100f)
            val scienceMultiplier = (1f - penaltyPercent / 100f).coerceAtLeast(0f)
            val totalScience = newFinalStatList.values.sumOf { it.science.toDouble() }.toFloat()
            if (totalScience > 0f) {
                val scienceLost = totalScience * (1f - scienceMultiplier)
                newFinalStatList["Cultural diversity"] = Stats(science = -scienceLost)
            }
        }

        for ((unique, statToBeRemoved) in city.getMatchingUniques(UniqueType.NullifiesStat)
            .map { it to Stat.valueOf(it.params[0]) }
            .distinct()
        ) {
            val removedAmount = newFinalStatList.values.sumOf { it[statToBeRemoved].toDouble() }

            newFinalStatList.add(
                unique.getSourceNameForUser(),
                Stats().apply { this[statToBeRemoved] = -removedAmount.toFloat() }
            )
        }

        /* Okay, food calculation is complicated.
        First we see how much food we generate. Then we apply production bonuses to it.
        Up till here, business as usual.
        Then, we deduct food eaten (from the total produced).
        Now we have the excess food, to which "growth" modifiers apply
        Some policies have bonuses for growth only, not general food production. */

        val foodEaten = calcFoodEaten()
        newFinalStatList["Population"]!!.food -= foodEaten

        var totalFood = newFinalStatList.values.map { it.food }.sum()

        // Apply growth modifier only when positive food
        if (totalFood > 0 && calculateGrowthModifiers) {
            // Since growth bonuses are special, (applied afterwards) they will be displayed separately in the user interface as well.
            // All bonuses except We Love The King do apply even when unhappy
            val growthBonuses = getGrowthBonus(totalFood)
            for (growthBonus in growthBonuses) {
                newFinalStatList.add("[${growthBonus.key}] ([Growth])", growthBonus.value)
            }
            if (city.isWeLoveTheKingDayActive() && city.civ.getHappiness() >= 0) {
                // We Love The King Day +25%, only if not unhappy
                val weLoveTheKingFood = Stats(food = totalFood / 4)
                newFinalStatList.add("We Love The King Day", weLoveTheKingFood)
            }
            // recalculate only when all applied - growth bonuses are not multiplicative
            // bonuses can allow a city to grow even with -100% unhappiness penalty, this is intended
            totalFood = newFinalStatList.values.map { it.food }.sum()
        }

        val buildingsMaintenance = getBuildingMaintenanceCosts() // this is AFTER the bonus calculation!
        newFinalStatList["Maintenance"] = Stats(gold = -buildingsMaintenance.toInt().toFloat())

        if (canConvertFoodToProduction(totalFood, currentConstruction)) {
            newFinalStatList["Excess food to production"] =
                Stats(production = getProductionFromExcessiveFood(totalFood), food = -totalFood)
        }

        val growthNullifyingUnique = city.getMatchingUniques(UniqueType.NullifiesGrowth).firstOrNull()
        if (growthNullifyingUnique != null) {
            // Does not nullify negative growth (starvation)
            val currentGrowth = newFinalStatList.values.sumOf { it[Stat.Food].toDouble() }
            if (currentGrowth > 0)
                newFinalStatList.add(
                    growthNullifyingUnique.getSourceNameForUser(),
                    Stats(food = -currentGrowth.toFloat())
                )
        }

        if (city.isInResistance())
            newFinalStatList.clear()  // NOPE

        // Apply custom AI bonus multipliers (adjustable in-game via AI Bonuses popup)
        if (!city.civ.isHuman()) {
            val gameInfo = city.civ.gameInfo
            if (gameInfo.customAiProductionModifier != 1f
                || gameInfo.customAiGrowthModifier != 1f
                || gameInfo.customAiGoldModifier != 1f
                || gameInfo.customAiScienceModifier != 1f) {
                for (entry in newFinalStatList.values) {
                    entry.production *= gameInfo.customAiProductionModifier
                    entry.food *= gameInfo.customAiGrowthModifier
                    entry.gold *= gameInfo.customAiGoldModifier
                    entry.science *= gameInfo.customAiScienceModifier
                }
            }
        }

        if (newFinalStatList.values.map { it.production }.sum() < 1)  // Minimum production for things to progress
            newFinalStatList["Production"] = Stats(production = 1f)

        // TW: Cap production increase at +5%/turn (reductions are immediate)
        val prevProduction = city.previousTurnProduction
        if (prevProduction > 0f) {
            val totalProduction = newFinalStatList.values.map { it.production }.sum()
            val maxAllowed = prevProduction * 1.05f
            if (totalProduction > maxAllowed) {
                val excess = totalProduction - maxAllowed
                newFinalStatList["Production growth cap"] = Stats(production = -excess)
            }
        }

        finalStatList = newFinalStatList
    }

    @Readonly
    fun canConvertFoodToProduction(food: Float, currentConstruction: IConstruction): Boolean {
        return (food > 0
            && currentConstruction is INonPerpetualConstruction
            && currentConstruction.hasUnique(UniqueType.ConvertFoodToProductionWhenConstructed))
    }

    /**
     * Calculate the conversion of the excessive food to production when
     * [UniqueType.ConvertFoodToProductionWhenConstructed] is at front of the build queue
     * @param food is amount of excess Food generates this turn
     * See for details: https://civilization.fandom.com/wiki/Settler_(Civ5)
     * @see calcFoodEaten as well for Food consumed this turn
     */
    @Pure
    fun getProductionFromExcessiveFood(food : Float): Float {
        return if (food >= 4.0f ) 2.0f + (food / 4.0f).toInt()
          else if (food >= 2.0f ) 2.0f
          else if (food >= 1.0f ) 1.0f
        else 0.0f
    }

    @Readonly
    private fun calcFoodEaten(): Float {
        var foodEatenBySpecialists = 2f * city.population.getNumberOfSpecialists()
        var foodEaten = city.population.population.toFloat() * 2 - foodEatenBySpecialists
        
        for (unique in city.getMatchingUniques(UniqueType.FoodConsumptionBySpecialists))
            if (city.matchesFilter(unique.params[1]))
                foodEatenBySpecialists *= unique.params[0].toPercent()

        foodEaten += foodEatenBySpecialists
        
        for (unique in city.getMatchingUniques(UniqueType.FoodConsumptionByPopulation)) {
            if (!city.matchesFilter(unique.params[2])) continue
            val foodEatenByPopulationFilter = 2f * city.population.getPopulationFilterAmount(unique.params[1])
            foodEaten -= foodEatenByPopulationFilter * (1f - unique.params[0].toPercent())
        }
        
        return foodEaten
    }

    //endregion
}
