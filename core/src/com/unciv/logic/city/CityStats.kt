package com.unciv.logic.city

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.models.Counter
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
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
            stats.gold = (capitalForTradeRoutePurposes.population.population * 0.15f + city.population.population * 1.1f - 1) * 2f // TW v2: trade route bonus ×2 (was ×3, reduced to curb runaway gold)
            city.forEachMatchingUnique(UniqueType.StatsFromTradeRoute) { unique ->
                stats.add(unique.stats)
            }
            val percentageStats = Stats()
            city.forEachMatchingUnique(UniqueType.StatPercentFromTradeRoutes) { unique ->
                percentageStats[Stat.valueOf(unique.params[1])] += unique.params[0].toFloat()
            }
            for ((stat) in stats) {
                stats[stat] *= percentageStats[stat].toPercent()
            }
        }
        return stats
    }

    @Readonly
    private fun getStatsFromProduction(production: Float): Stats? {
        if (Stat.isStat(city.cityConstructions.currentConstructionName())) {
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
        val conversionUnique = city.civ.getMatchingUniques(UniqueType.ProductionToStatConversionBonus).firstOrNull { it.params[0] == stat.name }
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
    fun getGrowthBonus(totalFood: Float): StatMap {
        val growthSources = StatMap()
        // "[amount]% growth [cityFilter]"
        city.forEachMatchingUnique(UniqueType.GrowthPercentBonus, city.state) { unique: Unique ->
            if (!city.matchesFilter(unique.params[1])) return@forEachMatchingUnique

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
    fun getStatsOfSpecialist(specialistName: String): Stats {
        val specialist = city.getRuleset().specialists[specialistName]
            ?: return Stats()
        @LocalState val stats = specialist.cloneStats()
        city.forEachMatchingUnique(UniqueType.StatsFromSpecialist, city.state) { unique: Unique ->
            if (city.matchesFilter(unique.params[1]))
                stats.add(unique.stats)
        }
        city.forEachMatchingUnique(UniqueType.StatsFromObject, city.state) { unique: Unique ->
            if (unique.params[1] == specialistName)
                stats.add(unique.stats)
        }
        return stats
    }

    @Readonly
    private fun getStatsFromSpecialists(specialists: Counter<String>): Stats {
        val stats = Stats()
        for ((key, value) in specialists.filter { it.value > 0 }.toList()) // avoid concurrent modification when calculating construction costs
            stats.add(getStatsOfSpecialist(key) * value)
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

        city.forEachMatchingUnique(UniqueType.StatsPerCity) { unique ->
            if (city.matchesFilter(unique.params[1]))
                addUniqueStats(unique)
        }

        // "[stats] per [amount] population [cityFilter]"
        city.forEachMatchingUnique(UniqueType.StatsPerPopulation) { unique ->
            if (city.matchesFilter(unique.params[2])) {
                val amountOfEffects = (city.population.population / unique.params[1].toInt()).toFloat()
                sourceToStats.addStats(unique.stats.times(amountOfEffects), unique.getSourceNameForUser(), unique.sourceObjectName ?: "")
            }
        }

        city.forEachMatchingUnique(UniqueType.StatsFromCitiesOnSpecificTiles) { unique ->
            if (city.getCenterTile().matchesTerrainFilter(unique.params[1], city.civ))
                addUniqueStats(unique)
        }



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

    /** Territorial Warfare: -30% production per conquered city (recovers 0.5%/turn) + -1% global per city owned */
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

        // Expansion malus: -1% per city beyond the first (light penalty, culture already penalizes)
        if (civ.cities.size > 1)
            productionMalus -= 1f * (civ.cities.size - 1)

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

    /** TW v2 — "Small-civ cluster" science bonus.
     *  When your civ has 3 cities or fewer and at least one OTHER small civ (also ≤3 cities,
     *  founding-civ-controlled capital) sits within range, you receive a strong science bonus.
     *  Models a renaissance-of-small-states dynamic (e.g., Italian city republics) — small
     *  neighbors stimulate each other intellectually, but a large empire next door does not.
     *
     *  Formula per qualifying neighbor: `Bonus = 150 - distance × 10` (min 0, cumulative, capped at 400). */
    @Readonly
    private fun getStatPercentBonusesFromSmallCivCluster(): Stats? {
        val civ = city.civ
        if (!civ.isMajorCiv()) return null
        if (civ.cities.size > 3) return null

        val myCapital = civ.getCapital() ?: return null

        var totalBonus = 0f
        for (otherCiv in civ.gameInfo.civilizations) {
            if (otherCiv == civ) continue
            if (!otherCiv.isMajorCiv()) continue
            if (otherCiv.isDefeated()) continue
            if (otherCiv.cities.size > 3) continue  // big empires don't qualify

            val otherCapital = otherCiv.getCapital() ?: continue
            if (otherCapital.foundingCivObject != null && otherCapital.foundingCivObject != otherCiv) continue

            val distance = myCapital.getCenterTile().aerialDistanceTo(otherCapital.getCenterTile())
            val bonus = (150f - distance * 10f).coerceAtLeast(0f)
            totalBonus += bonus
        }

        totalBonus = totalBonus.coerceAtMost(400f)
        return if (totalBonus == 0f) null else Stats(science = totalBonus)
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

        city.forEachMatchingUnique(UniqueType.StatPercentBonus) { unique -> 
            addUniqueStats(unique, Stat.valueOf(unique.params[1]), unique.params[0].toFloat())
        }


        city.forEachMatchingUnique(UniqueType.StatPercentBonusCities) { unique ->
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

        city.forEachMatchingUnique(UniqueType.StatPercentFromReligionFollowers) { unique ->
            addUniqueStats(unique, Stat.valueOf(unique.params[1]),
                min(
                    unique.params[0].toFloat() * city.religion.getFollowersOfMajorityReligion(),
                    unique.params[2].toFloat()
                ))
        }

        if (currentConstruction is Building
            && city.civ.getCapital()?.cityConstructions?.isBuilt(currentConstruction.name) == true
        ) {
            city.forEachMatchingUnique(UniqueType.PercentProductionBuildingsInCapital) { unique ->
                addUniqueStats(unique, Stat.Production, unique.params[0].toFloat())
            }
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

    /**
     * Territorial Warfare v2: reward controlling diverse resource portfolios instead of
     * penalising raw city count. Each unique resource type owned (capped) gives flat
     * production from strategics and flat gold from luxuries to every city.
     */
    @Readonly
    private fun getStatsFromResourceDiversity(): Stats {
        if (!city.civ.isMajorCiv()) return Stats()
        val diversity = city.civ.getResourceDiversity()
        // TW v2: strategic diversity moved to a % production bonus (see [getStatPercentBonusesFromResourceDiversity])
        // so newly founded pop-1 cities don't inherit large flat shield boosts. Luxury → flat gold still.
        return Stats(gold = 2f * diversity.luxury)
    }

    /** Territorial Warfare v2:
     *  - Food % bonus per unique BONUS resource owned (5%/each)
     *  - Production % bonus per unique STRATEGIC resource owned (5%/each)
     *  Percentage rather than flat keeps the benefit proportional to the city's actual base. */
    @Readonly
    private fun getStatPercentBonusesFromResourceDiversity(): Stats? {
        if (!city.civ.isMajorCiv()) return null
        val diversity = city.civ.getResourceDiversity()
        if (diversity.bonus == 0 && diversity.strategic == 0) return null
        return Stats(
            food = 5f * diversity.bonus,
            production = 5f * diversity.strategic
        )
    }

    /**
     * TW v2 — Strategic surplus bonus: every 2 unused strategic resources (iron, coal,
     * oil, aluminum, uranium, horses) add +1 gold to every city in the empire.
     * Rewards stockpiling spares that could otherwise be sold for gpt to other civs.
     */
    @Readonly
    private fun getStatsFromStrategicSurplus(): Stats {
        if (!city.civ.isMajorCiv()) return Stats()
        val surplus = city.civ.getStrategicSurplus()
        if (surplus < 2) return Stats()
        return Stats(gold = (surplus / 2).toFloat())
    }

    /** TW v2 — Federal Bonus removed: was over-rewarding pop-1 newly founded cities by
     *  granting full empire-wide infrastructure value from turn one. */
    @Readonly
    private fun getStatsFromFederalBonus(): Stats {
        return Stats()
    }

    /**
     * TW v2 — Diminishing returns on the "1 pop = 1 sci/prod" rule for mega-cities.
     * Pop 1-25  : 1.0× yield per pop (full)
     * Pop 26-35 : 0.5× yield per pop (capped contribution after 25)
     * Pop 36+   : 0.25× yield per pop
     * Tames the population snowball in concentrated empires (Byzantium-style)
     * without stopping growth or hurting normal-sized cities.
     */
    @Readonly
    private fun effectivePopForBaseYield(): Float {
        val pop = city.population.population
        return when {
            pop <= 25 -> pop.toFloat()
            pop <= 35 -> 25f + (pop - 25) * 0.5f
            else -> 30f + (pop - 35) * 0.25f
        }
    }

    /**
     * TW v2 — Multiplier soft-cap to prevent explosive stat stacking on a single city.
     * Once total % bonuses on a stat exceed 250%, additional bonuses count for half.
     * Caps the wonder-and-NC mega-cities without hurting normally-developed cities.
     */
    @Readonly
    private fun softCapMultiplierPercent(percent: Float, cap: Float = 250f): Float {
        return if (percent <= cap) percent else cap + (percent - cap) * 0.5f
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

    fun updateTileStats():Unit = timeThis("updateTileStats") {
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
                city.stopWorkingTile(tile)
                city.shouldReassignPopulation = true
                continue
            }
            val tileStats = tile.stats.getTileStats(city, city.civ)
            // TW: Tile production stays as base stat (not converted to % bonus)
            // This ensures the production base is high enough for the -60% penalty cap to work
            // Territorial Warfare: for worked tiles, only add non-food stats
            val nonFoodStats = tileStats.clone().apply { food = 0f }
            stats.add(nonFoodStats)
        }

        // Territorial Warfare: food + tile tax in single pass over all territory tiles
        var totalTerritoryFood = 0f
        var totalTileTax = 0f
        for (tile in city.getTiles()) {
            totalTerritoryFood += tile.stats.getTileStats(city, city.civ).food
            totalTileTax += com.unciv.logic.map.TileCultureLogic.getTerritorialGoldBase(tile) *
                com.unciv.logic.map.TileCultureLogic.getYieldMultiplier(tile)
        }
        val foodEfficiency = when {
            city.cityConstructions.containsBuildingOrEquivalent("Medical Center") -> 1.0f
            city.cityConstructions.containsBuildingOrEquivalent("Hospital") -> 0.75f
            city.cityConstructions.containsBuildingOrEquivalent("Aqueduct") -> 0.50f
            else -> 0.25f
        }
        stats.food += totalTerritoryFood * foodEfficiency
        stats.gold += totalTileTax

        tileProductionBonus = 0f  // TW: tile production is now base stat, not % bonus
        statsFromTiles = stats
    }


    // needs to be a separate function because we need to know the global happiness state
    // in order to determine how much food is produced in a city!
    fun updateCityHappiness(statsFromBuildings: StatTreeNode,
                            statsFromSpecialists: Stats = getStatsFromSpecialists(city.population.getNewSpecialists()),
                            statsFromUniquesBySource: StatTreeNode = getStatsFromUniquesBySource()) {
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

        city.forEachMatchingUnique(UniqueType.UnhappinessFromPopulationTypePercentageChange) { unique ->
            if (city.matchesFilter(unique.params[2]))
                unhappinessFromCitizens += (unique.params[0].toFloat() / 100f) * city.population.getPopulationFilterAmount(unique.params[1])
        }

        if (hasExtraAnnexUnhappiness())
            unhappinessFromCitizens *= 2f

        if (unhappinessFromCitizens < 0) unhappinessFromCitizens = 0f

        newHappinessList["Population"] = -unhappinessFromCitizens * unhappinessModifier

        if (hasExtraAnnexUnhappiness()) newHappinessList["Occupied City"] = -2f //annexed city

        val happinessFromSpecialists = statsFromSpecialists.happiness.toInt().toFloat()
        if (happinessFromSpecialists > 0) newHappinessList["Specialists"] = happinessFromSpecialists

        newHappinessList["Buildings"] = statsFromBuildings.totalStats.happiness.toInt().toFloat()

        newHappinessList["Tile yields"] = statsFromTiles.happiness

        val happinessBySource = statsFromUniquesBySource
        for ((source, stats) in happinessBySource.children)
            if (stats.totalStats.happiness != 0f) {
                if (!newHappinessList.containsKey(source)) newHappinessList[source] = 0f
                newHappinessList[source] = newHappinessList[source]!! + stats.totalStats.happiness
            }

        // we don't want to modify the existing happiness list because that leads
        // to concurrency problems if we iterate on it while changing
        happinessList = newHappinessList
    }

    private fun updateBaseStatList(statsFromBuildings: StatTreeNode, statsFromSpecialists: Stats,
                                   statsFromUniquesBySource: StatTreeNode) {
        val newBaseStatTree = StatTreeNode()

        // We don't edit the existing baseStatList directly, in order to avoid concurrency exceptions
        val newBaseStatList = StatMap()

        // TW v2: each pop contributes 1 sci + 1 prod, with diminishing returns past 25
        // (pop 26-35: 0.5×, pop 36+: 0.25×) to tame mega-city snowballs.
        val effectivePop = effectivePopForBaseYield()
        newBaseStatTree.addStats(Stats(
            science = effectivePop,
            production = effectivePop
        ), "Population")
        newBaseStatList["Tile yields"] = statsFromTiles
        newBaseStatList["Specialists"] = statsFromSpecialists
        newBaseStatList["Trade routes"] = getStatsFromTradeRoute()
        newBaseStatList["Resource diversity"] = getStatsFromResourceDiversity()
        newBaseStatList["Strategic surplus"] = getStatsFromStrategicSurplus()
        newBaseStatList["Federal bonus"] = getStatsFromFederalBonus()
        newBaseStatTree.children["Buildings"] = statsFromBuildings

        for ((source, stats) in newBaseStatList)
            newBaseStatTree.addStats(stats, source)

        newBaseStatTree.add(statsFromUniquesBySource)
        baseStatTree = newBaseStatTree
    }
    
    @Readonly
    private fun getStatPercentBonusList(currentConstruction: IConstruction): StatTreeNode = timeThis("CityStats.getStatPercentBonusList") {
        val newStatsBonusTree = StatTreeNode()

        newStatsBonusTree.addStats(getStatPercentBonusesFromGoldenAge(city.civ.goldenAges.isGoldenAge()),"Golden Age")
        newStatsBonusTree.addStats(getStatPercentBonusesFromRailroad(), "Railroad")
        newStatsBonusTree.addStats(getStatPercentBonusesFromPuppetCity(), "Puppet City")
        if (city.isColony) {
            // TW v2: colonies funnel surplus into the metropole — extra gold output.
            newStatsBonusTree.addStats(Stats(gold = 50f), "Colony")
        }
        newStatsBonusTree.addStats(getStatPercentBonusesFromUnitSupply(), "Unit Supply")
        newStatsBonusTree.addStats(getStatPercentBonusesFromConquestAndExpansion(), "Conquest & Expansion")
        newStatsBonusTree.addStats(getStatPercentBonusesFromCapitalProximity(), "Capital Proximity")
        newStatsBonusTree.addStats(getStatPercentBonusesFromSmallCivCluster(), "Small Civ Cluster")
        newStatsBonusTree.addStats(getStatPercentBonusesFromImperialStability(), "Imperial Stability")
        newStatsBonusTree.addStats(getStatPercentBonusesFromResourceDiversity(), "Resource Diversity")
        newStatsBonusTree.addStats(getStatPercentBonusesFromHappiness(), "Happiness")
        newStatsBonusTree.add(getStatsPercentBonusesFromUniquesBySource(currentConstruction))
        
        for (building in city.cityConstructions.getBuiltBuildings())
            newStatsBonusTree.addStats(building.getStatPercentageBonuses(city),
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
               calculateGrowthModifiers:Boolean = true): Unit = timeThis<Unit>("CityStats.update") {

        if (updateTileStats) updateTileStats()

        // We need to compute Tile yields before happiness

        val statsFromBuildings = city.cityConstructions.getStats() // this is performance heavy, so calculate once
        // Also performance-heavy, and previously computed twice with identical inputs (once inside
        // updateBaseStatList, once inside updateCityHappiness) - only baseStatTree is assigned between
        // the two, and neither reads it. Compute once and pass down.
        val statsFromSpecialists = getStatsFromSpecialists(city.population.getNewSpecialists())
        val statsFromUniquesBySource = getStatsFromUniquesBySource()
        updateBaseStatList(statsFromBuildings, statsFromSpecialists, statsFromUniquesBySource)
        updateCityHappiness(statsFromBuildings, statsFromSpecialists, statsFromUniquesBySource)
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

        // TW: Cap production penalties at -60% (floor) AND apply v2 soft-cap on the upside (+250% then half)
        val cappedProductionPercent = softCapMultiplierPercent(
            statPercentBonusesSum.production.coerceAtLeast(-60f)
        )
        for (entry in newFinalStatList.values)
            entry.production *= cappedProductionPercent.toPercent()

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
        // TW: Production smoothing — converges toward computed potential at 5%/turn
        // Floor: smoothedProduction never below 40% of computed (matches the -60% penalty cap)
        if (computedProduction > 0f) {
            val smoothingFloor = computedProduction * 0.40f
            if (city.smoothedProduction < 0f || city.smoothedProduction < smoothingFloor) {
                city.smoothedProduction = smoothingFloor.coerceAtLeast(computedProduction * 0.40f)
            }
            val gap = computedProduction - city.smoothedProduction
            city.smoothedProduction += 0.05f * gap
            // Apply the smoothed production: scale all production entries proportionally
            if (computedProduction > 0.1f) {
                val ratio = city.smoothedProduction / computedProduction
                for (entry in newFinalStatList.values)
                    entry.production *= ratio
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

        // TW v2: soft-cap each multiplier @ +250% (anti-mega-city stacking)
        val cappedGoldPercent = softCapMultiplierPercent(statPercentBonusesSum.gold)
        val cappedCulturePercent = softCapMultiplierPercent(statPercentBonusesSum.culture)
        val cappedFoodPercent = softCapMultiplierPercent(statPercentBonusesSum.food)
        val cappedFaithPercent = softCapMultiplierPercent(statPercentBonusesSum.faith)
        for (entry in newFinalStatList.values) {
            entry.gold *= cappedGoldPercent.toPercent()
            entry.culture *= cappedCulturePercent.toPercent()
            entry.food *= cappedFoodPercent.toPercent()
            entry.faith *= cappedFaithPercent.toPercent()
        }

        // TW: Gold-to-Science: slider % gives science bonus AND costs gold proportionally.
        // 100% slider → +300% science (×4) AND -100% gold.
        // 50% slider  → +150% science (×2.5) AND -50% gold.
        // 25% slider  → +75% science (×1.75) AND -25% gold.
        val goldToSciencePercent = if (city.getRuleset().modOptions.hasUnique(UniqueType.ConvertGoldToScience))
            city.civ.tech.goldPercentConvertedToScience else 0f

        // TW v2: soft-cap science multiplier @ +250% (gold-to-science slider added before cap)
        val cappedSciencePercent = softCapMultiplierPercent(
            statPercentBonusesSum.science + goldToSciencePercent * 300f
        )
        for (entry in newFinalStatList.values) {
            entry.science *= cappedSciencePercent.toPercent()
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

        // TW v2 — Soft hyperbolic-tangent dampening on raw city production only.
        // y = K · tanh(x / K), tangent to y = x at the origin (no effect on small cities)
        // and asymptotically approaching K. With K = 300:
        //   x=50 → 49.5 (−1%), x=100 → 96.4 (−4%), x=200 → 174.7 (−13%),
        //   x=300 → 228.5 (−24%), x=500 → 279.4 (−44%), x=1000 → 299 (−70%).
        // Late-game mega-cities (raw 500–1000 with all production buildings + wonders)
        // are noticeably capped but still keep a clear lead over standard cities (~100/turn).
        // Science is dampened at the EMPIRE level instead — see CivInfoStatsForNextTurn.
        val tanhK = 300.0
        val rawProd = newFinalStatList.values.sumOf { it.production.toDouble() }
        if (rawProd > 0.5) {
            val damped = tanhK * kotlin.math.tanh(rawProd / tanhK)
            val delta = (damped - rawProd).toFloat()
            if (delta < -0.01f) {
                newFinalStatList.add("Tanh dampening", Stats(production = delta))
            }
        }

        if (newFinalStatList.values.map { it.production }.sum() < 1)  // Minimum production for things to progress
            newFinalStatList["Production"] = Stats(production = 1f)

        // TW: Production growth cap REMOVED — smoothing (5% convergence) already handles this
        // The old cap trapped low-production cities at their previous value forever

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
        
        city.forEachMatchingUnique(UniqueType.FoodConsumptionBySpecialists) { unique ->
            if (city.matchesFilter(unique.params[1]))
                foodEatenBySpecialists *= unique.params[0].toPercent()
        }

        foodEaten += foodEatenBySpecialists

        city.forEachMatchingUnique(UniqueType.FoodConsumptionByPopulation) { unique ->
            if (city.matchesFilter(unique.params[2])) {
                val foodEatenByPopulationFilter = 2f * city.population.getPopulationFilterAmount(unique.params[1])
                foodEaten -= foodEatenByPopulationFilter * (1f - unique.params[0].toPercent())
            }
        }
        
        return foodEaten
    }

    //endregion
}
