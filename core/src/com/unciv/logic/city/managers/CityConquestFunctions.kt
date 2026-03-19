package com.unciv.logic.city.managers

import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.battle.Battle
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFlags
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.logic.map.mapunit.UnitPromotions
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.extensions.toPercent
import com.unciv.utils.withItem
import com.unciv.utils.withoutItem
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Helper class for containing 200 lines of "how to move cities between civs" */
class CityConquestFunctions(val city: City) {
    private val tileBasedRandom = Random(city.getCenterTile().position.hashCode())

    @Readonly
    private fun getGoldForCapturingCity(conqueringCiv: Civilization): Int {
        val baseGold = 20 + 10 * city.population.population + tileBasedRandom.nextInt(40)
        val turnModifier = max(0, min(50, city.civ.gameInfo.turns - city.turnAcquired)) / 50f
        
        var cityModifier = 1f
        for (unique in city.getMatchingUniques(UniqueType.GoldFromCapturingCity, city.state)) {
            cityModifier *= unique.params[0].toPercent()
        }

        var conqueringCivModifier = 1f
        for (unique in conqueringCiv.getMatchingUniques(UniqueType.GoldFromEncampmentsAndCities, conqueringCiv.state)) {
            conqueringCivModifier *= unique.params[0].toPercent()
        }

        val goldPlundered = baseGold * turnModifier * cityModifier * conqueringCivModifier
        return goldPlundered.toInt()
    }

    private fun destroyBuildingsOnCapture() {
        // Possibly remove other buildings
        for (building in city.cityConstructions.getBuiltBuildings()) {
            when {
                building.hasUnique(UniqueType.NotDestroyedWhenCityCaptured) || building.isWonder -> continue
                building.hasUnique(UniqueType.IndicatesCapital, city.state) -> continue // Palace needs to stay a just a bit longer so moveToCiv isn't confused
                building.hasUnique(UniqueType.DestroyedWhenCityCaptured) ->
                    city.cityConstructions.removeBuilding(building)
                // Regular buildings have a 34% chance of removal
                tileBasedRandom.nextInt(100) < 34 -> city.cityConstructions.removeBuilding(building)
            }
        }
    }

    private fun removeAutoPromotion() {
        city.unitShouldUseSavedPromotion = HashMap<String, Boolean>()
        city.unitToPromotions = HashMap<String, UnitPromotions>()
    }

    private fun removeBuildingsOnMoveToCiv() {
        // Remove all buildings provided for free to this city
        // At this point, the city has *not* yet moved to the new civ
        for (building in city.civ.civConstructions.getFreeBuildingNames(city)) {
            city.cityConstructions.removeBuilding(building)
        }
        city.cityConstructions.freeBuildingsProvidedFromThisCity.clear()

        for (building in city.cityConstructions.getBuiltBuildings()) {
            // Remove national wonders
            if (building.isNationalWonder && !building.hasUnique(UniqueType.NotDestroyedWhenCityCaptured))
                city.cityConstructions.removeBuilding(building)

            // Check if we exceed MaxNumberBuildable for any buildings
            for (unique in building.getMatchingUniques(UniqueType.MaxNumberBuildable)) {
                if (city.civ.cities
                        .count {
                            it.cityConstructions.containsBuildingOrEquivalent(building.name)
                                || it.cityConstructions.isBeingConstructedOrEnqueued(building.name)
                        } >= unique.params[0].toInt()
                ) {
                    // For now, just destroy in new city. Even if constructing in own cities
                    city.cityConstructions.removeBuilding(building)
                }
            }
        }
    }

    /** Function for stuff that should happen on any capture, be it puppet, annex or liberate.
     * Stuff that should happen any time a city is moved between civs, so also when trading,
     * should go in `this.moveToCiv()`, which is called by `this.conquerCity()`.
     */
    private fun conquerCity(conqueringCiv: Civilization, conqueredCiv: Civilization, receivingCiv: Civilization) {
        city.espionage.removeAllPresentSpies(SpyFleeReason.CityCaptured)

        // Gain gold for plundering city
        val goldPlundered = getGoldForCapturingCity(conqueringCiv)
        conqueringCiv.addGold(goldPlundered)
        conqueringCiv.addNotification("Received [$goldPlundered] Gold for capturing [${city.name}]",
            city.getCenterTile().position, NotificationCategory.General, NotificationIcon.Gold)

        val reconqueredCityWhileStillInResistance = city.previousOwner == receivingCiv.civID && city.isInResistance()

        destroyBuildingsOnCapture()

        // Territorial Warfare: only transfer the city center tile, not all territory.
        // Redistribute non-center tiles to other cities of the old owner.
        val nonCenterTilePositions = city.tiles.filter { it != city.location }
        val otherOldCivCities = conqueredCiv.cities.filter { it != city }
        for (tilePos in nonCenterTilePositions) {
            val tile = city.civ.gameInfo.tileMap[tilePos]
            city.expansion.relinquishOwnership(tile)
            if (otherOldCivCities.isNotEmpty()) {
                val nearestCity = otherOldCivCities.minByOrNull {
                    it.getCenterTile().aerialDistanceTo(tile)
                }!!
                nearestCity.expansion.takeOwnership(tile)
            }
        }

        city.moveToCiv(receivingCiv)

        // Territorial Warfare: if the conquered civ has no cities left, transfer ALL remaining territory
        if (conqueredCiv.cities.isEmpty()) {
            val gameInfo = city.civ.gameInfo
            for (tile in gameInfo.tileMap.values) {
                if (tile.getOwner() == conqueredCiv) {
                    val nearestConquerorCity = receivingCiv.cities.minByOrNull {
                        it.getCenterTile().aerialDistanceTo(tile)
                    }
                    if (nearestConquerorCity != null) {
                        tile.getCity()?.expansion?.relinquishOwnership(tile)
                        nearestConquerorCity.expansion.takeOwnership(tile)
                    }
                }
            }
        }

        Battle.destroyIfDefeated(conqueredCiv, conqueringCiv, city.location.toHexCoord())

        city.health = city.getMaxHealth() / 2 // I think that cities recover to half health when conquered?
        city.avoidGrowth = false // reset settings
        city.setCityFocus(CityFocus.NoFocus) // reset settings
        if (city.population.population > 1)
            city.population.addPopulation(-1 - city.population.population / 4) // so from 2-4 population, remove 1, from 5-8, remove 2, etc.
        city.reassignAllPopulation()

        if (!reconqueredCityWhileStillInResistance && city.foundingCivObject != receivingCiv) {
            // add resistance
            // I checked, and even if you puppet there's resistance for conquering
            city.setFlag(CityFlags.Resistance, city.population.population)
        } else {
            // reconquering or liberating city in resistance so eliminate it
            city.removeFlag(CityFlags.Resistance)
        }

        for (unique in conqueredCiv.getTriggeredUniques(UniqueType.TriggerUponLosingCity, GameContext(civInfo = conqueredCiv))) {
            UniqueTriggerActivation.triggerUnique(unique, civInfo = conqueredCiv)
        }
    }


    /** This happens when we either puppet OR annex, basically whenever we conquer a city and don't liberate it */
    fun puppetCity(conqueringCiv: Civilization) {
        val oldCiv = city.civ

        // must be before moving the city to the conquering civ,
        // so the repercussions are properly checked
        // Territorial Warfare: barbarians have no diplomacy, skip repercussions
        if (!conqueringCiv.isBarbarian) {
            diplomaticRepercussionsForConqueringCity(oldCiv, conqueringCiv)
        }

        conquerCity(conqueringCiv, oldCiv, conqueringCiv)
        makePuppet()
        city.cityStats.update()
    }
    
    private fun makePuppet(){
        city.isPuppet = true
        // The city could be producing something that puppets shouldn't, like units
        city.cityConstructions.removeAll()
    }

    fun annexCity() {
        city.isPuppet = false
        if (!city.isInResistance()) city.shouldReassignPopulation = true
        city.avoidGrowth = false
        city.setCityFocus(CityFocus.NoFocus)
        city.cityStats.update()
        GUI.setUpdateWorldOnNextRender()
    }

    private fun diplomaticRepercussionsForConqueringCity(oldCiv: Civilization, conqueringCiv: Civilization) {
        val currentPopulation = city.population.population
        val percentageOfCivPopulationInThatCity = currentPopulation * 100f /
                oldCiv.cities.sumOf { it.population.population }
        val aggroGenerated = 10f + percentageOfCivPopulationInThatCity.roundToInt()

        // How can you conquer a city but not know the civ you conquered it from?!
        // I don't know either, but some of our players have managed this, and crashed their game!
        if (!conqueringCiv.knows(oldCiv))
            conqueringCiv.diplomacyFunctions.makeCivilizationsMeet(oldCiv)

        oldCiv.getDiplomacyManager(conqueringCiv)!!
                .addModifier(DiplomaticModifiers.CapturedOurCities, -aggroGenerated)

        for (thirdPartyCiv in conqueringCiv.getKnownCivs().filter { it.isMajorCiv() }) {
            val aggroGeneratedForOtherCivs = (aggroGenerated / 10).roundToInt().toFloat()
            if (thirdPartyCiv.isAtWarWith(oldCiv)) // Shared Enemies should like us more
                thirdPartyCiv.getDiplomacyManager(conqueringCiv)!!
                        .addModifier(DiplomaticModifiers.SharedEnemy, aggroGeneratedForOtherCivs) // Cool, keep at it! =D
            else thirdPartyCiv.getDiplomacyManager(conqueringCiv)!!
                    .addModifier(DiplomaticModifiers.WarMongerer, -aggroGeneratedForOtherCivs) // Uncool bro.
        }
    }

    fun liberateCity(conqueringCiv: Civilization) {
        if (city.foundingCivObject == null) { // this should never happen but just in case...
            this.puppetCity(conqueringCiv)
            this.annexCity()
            return
        }

        val foundingCiv = city.foundingCivObject!!
        if (foundingCiv.isDefeated()) { // resurrected civ
            for (diploManager in foundingCiv.diplomacy.values) {
                if (diploManager.diplomaticStatus == DiplomaticStatus.War)
                    diploManager.makePeace()

                // Clear all diplomatic flags and modifiers to prevent asymmetry after resurrection
                // The defeated civ's flags were frozen while other civs' flags continued to expire
                // Perhaps some of this needs more dedicated treatment but it's a pretty rare case anyway
                //   so I think starting from scratch is a good way to go 
                diploManager.flagsCountdown.clear()
                diploManager.otherCivDiplomacy().flagsCountdown.clear()
                diploManager.diplomaticModifiers.clear()
                diploManager.otherCivDiplomacy().diplomaticModifiers.clear()
            }
        }

        val oldCiv = city.civ

        diplomaticRepercussionsForLiberatingCity(conqueringCiv, oldCiv)

        conquerCity(conqueringCiv, oldCiv, foundingCiv)

        if (foundingCiv.cities.size == 1) {
            // Resurrection!
            val capitalCityIndicator = conqueringCiv.capitalCityIndicator(city)
            if (capitalCityIndicator != null) city.cityConstructions.addBuilding(capitalCityIndicator)
            for (civ in city.civ.gameInfo.civilizations) {
                if (civ == foundingCiv || civ == conqueringCiv) continue // don't need to notify these civs
                when {
                    civ.knows(conqueringCiv) && civ.knows(foundingCiv) ->
                        civ.addNotification("[$conqueringCiv] has liberated [$foundingCiv]", NotificationCategory.Diplomacy, foundingCiv.civName, NotificationIcon.Diplomacy, conqueringCiv.civName)
                    civ.knows(conqueringCiv) && !civ.knows(foundingCiv) ->
                        civ.addNotification("[$conqueringCiv] has liberated [an unknown civilization]", NotificationCategory.Diplomacy, NotificationIcon.Diplomacy, conqueringCiv.civName)
                    !civ.knows(conqueringCiv) && civ.knows(foundingCiv) ->
                        civ.addNotification("[An unknown civilization] has liberated [$foundingCiv]", NotificationCategory.Diplomacy, NotificationIcon.Diplomacy, foundingCiv.civName)
                    else -> continue
                }
            }
        }
        city.isPuppet = false
        city.cityStats.update()

        // Move units out of the city when liberated
        for (unit in city.getCenterTile().getUnits().toList())
            unit.movement.teleportToClosestMoveableTile()
        for (unit in city.getTiles().flatMap { it.getUnits() }.toList())
            if (!unit.movement.canPassThrough(unit.currentTile))
                unit.movement.teleportToClosestMoveableTile()

    }


    private fun diplomaticRepercussionsForLiberatingCity(conqueringCiv: Civilization, conqueredCiv: Civilization) {
        val foundingCiv = city.foundingCivObject!!
        val percentageOfCivPopulationInThatCity = city.population.population *
                100f / (foundingCiv.cities.sumOf { it.population.population } + city.population.population)
        val respectForLiberatingOurCity = 10f + percentageOfCivPopulationInThatCity.roundToInt()

        if (foundingCiv.isMajorCiv()) {
            // In order to get "plus points" in Diplomacy, you have to establish diplomatic relations if you haven't yet
            foundingCiv.getDiplomacyManagerOrMeet(conqueringCiv)
                    .addModifier(DiplomaticModifiers.CapturedOurCities, respectForLiberatingOurCity)
            val openBordersTrade = TradeLogic(foundingCiv, conqueringCiv)
            openBordersTrade.currentTrade.ourOffers.add(TradeOffer(Constants.openBorders, TradeOfferType.Agreement, speed = conqueringCiv.gameInfo.speed))
            openBordersTrade.acceptTrade(false)
        } else {
            // Territorial Warfare: liberating/returning a city-state gives 500 influence (was 90)
            foundingCiv.getDiplomacyManagerOrMeet(conqueringCiv).setInfluence(500f)
            if (foundingCiv.isAtWarWith(conqueringCiv)) {
                val tradeLogic = TradeLogic(foundingCiv, conqueringCiv)
                tradeLogic.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = conqueringCiv.gameInfo.speed))
                tradeLogic.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = conqueringCiv.gameInfo.speed))
                tradeLogic.acceptTrade(false)
            }
        }

        val otherCivsRespectForLiberating = (respectForLiberatingOurCity / 10).roundToInt().toFloat()
        for (thirdPartyCiv in conqueringCiv.getKnownCivs().filter { it.isMajorCiv() && it != conqueredCiv }) {
            thirdPartyCiv.getDiplomacyManager(conqueringCiv)!!
                    .addModifier(DiplomaticModifiers.LiberatedCity, otherCivsRespectForLiberating) // Cool, keep at at! =D
        }
    }


    fun moveToCiv(newCiv: Civilization) {
        val oldCiv = city.civ

        // Remove/relocate palace for old Civ - need to do this BEFORE we move the cities between
        //  civs so the capitalCityIndicator recognizes the unique buildings of the conquered civ
        if (city.isCapital()) oldCiv.moveCapitalToNextLargest(city)

        oldCiv.cities = oldCiv.cities.withoutItem(city)
        newCiv.cities = newCiv.cities.withItem(city)
        city.civ = newCiv
        city.state = GameContext(city)
        city.hasJustBeenConquered = false
        city.turnAcquired = city.civ.gameInfo.turns
        city.culturalIdentity = if (city.foundingCivObject != null && city.foundingCivObject != newCiv) 100 else 0
        city.previousOwner = oldCiv.civID

        // now that the tiles have changed, we need to reassign population
        for (workedTile in city.workedTiles.filterNot { city.tiles.contains(it) }) {
            city.population.stopWorkingTile(workedTile)
            city.population.autoAssignPopulation()
        }

        // Stop WLTKD if it's still going
        city.resetWLTKD()

        // Remove their free buildings from this city and remove free buildings provided by the city from their cities
        removeBuildingsOnMoveToCiv()

        // Remove auto promotion from city that is being moved
        removeAutoPromotion()

        // catch-all - should ideally not happen as we catch the individual cases with an appropriate notification
        city.espionage.removeAllPresentSpies(SpyFleeReason.Other)


        // Place palace for newCiv if this is the only city they have.
        if (newCiv.cities.size == 1) newCiv.moveCapitalTo(city, null)

        // Add our free buildings to this city and add free buildings provided by the city to other cities
        city.civ.civConstructions.tryAddFreeBuildings()

        city.isBeingRazed = false

        // Transfer unique buildings
        for (building in city.cityConstructions.getBuiltBuildings()) {
            val civEquivalentBuilding = newCiv.getEquivalentBuilding(building)
            if (building != civEquivalentBuilding) {
                city.cityConstructions.removeBuilding(building)
                city.cityConstructions.addBuilding(civEquivalentBuilding)
            }
        }

        if (city.civ.gameInfo.isReligionEnabled()) city.religion.removeUnknownPantheons()

        if (newCiv.hasUnique(UniqueType.MayNotAnnexCities)) makePuppet()

        city.tryUpdateRoadStatus()
        city.cityStats.update()

        // Update proximity rankings
        city.civ.updateProximity(oldCiv, oldCiv.updateProximity(city.civ))

        // Update history
        city.getTiles().forEach { tile ->
            tile.history.recordTakeOwnership(tile)
        }

        newCiv.cache.updateOurTiles()
        oldCiv.cache.updateOurTiles()
    }

    /**
     * TW: Vassalize - return the city to the defeated civ and establish vassalage.
     * The vassal's diplomacy is controlled by the suzerain: follows wars/peace, pays 25% tribute.
     */
    fun vassalizeCity(conqueringCiv: Civilization) {
        val oldCiv = city.civ  // The defeated civ (still owner at popup time)

        // Diplomatic repercussions (moderate, better than pure conquest)
        diplomaticRepercussionsForConqueringCity(oldCiv, conqueringCiv)

        // Conquer + return: city goes back to oldCiv
        conquerCity(conqueringCiv, oldCiv, oldCiv)

        // Ensure the city has a Palace if the old civ had none (resurrection case)
        if (oldCiv.cities.size == 1 || oldCiv.getCapital() == null) {
            val capitalIndicator = conqueringCiv.capitalCityIndicator(city)
            if (capitalIndicator != null) city.cityConstructions.addBuilding(capitalIndicator)
        }

        city.isPuppet = false
        city.cityStats.update()

        // Set up vassalage
        oldCiv.vassalOf = conqueringCiv.civName
        oldCiv.vassalTurnEstablished = conqueringCiv.gameInfo.turns

        // Make peace between vassal and suzerain
        if (oldCiv.isAtWarWith(conqueringCiv)) {
            oldCiv.getDiplomacyManager(conqueringCiv)!!.makePeace()
            // Add peace treaty to prevent immediate re-war
            val tradeLogic = TradeLogic(oldCiv, conqueringCiv)
            tradeLogic.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = conqueringCiv.gameInfo.speed))
            tradeLogic.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = conqueringCiv.gameInfo.speed))
            oldCiv.getDiplomacyManager(conqueringCiv)!!.trades.add(tradeLogic.currentTrade)
            oldCiv.getDiplomacyManager(conqueringCiv)!!.otherCivDiplomacy().trades.add(tradeLogic.currentTrade.reverse())
        }

        // Vassal inherits suzerain's current wars
        for (otherCivDiplo in conqueringCiv.diplomacy.values) {
            if (otherCivDiplo.diplomaticStatus == DiplomaticStatus.War) {
                val enemy = otherCivDiplo.otherCiv
                if (enemy == oldCiv) continue  // Don't declare war on yourself
                if (!oldCiv.isAtWarWith(enemy)) {
                    if (!oldCiv.knows(enemy))
                        oldCiv.diplomacyFunctions.makeCivilizationsMeet(enemy, warOnContact = true)
                    oldCiv.getDiplomacyManager(enemy)?.declareWar(DeclareWarReason(WarType.JoinWar, conqueringCiv))
                }
            }
        }

        // Move conqueror units out
        for (unit in city.getCenterTile().getUnits().toList())
            unit.movement.teleportToClosestMoveableTile()
        for (unit in city.getTiles().flatMap { it.getUnits() }.toList())
            if (!unit.movement.canPassThrough(unit.currentTile))
                unit.movement.teleportToClosestMoveableTile()

        // Notifications
        conqueringCiv.addNotification(
            "[${oldCiv.civName}] is now our vassal!",
            city.getCenterTile().position,
            NotificationCategory.Diplomacy, NotificationIcon.Diplomacy
        )
        oldCiv.addNotification(
            "[${conqueringCiv.civName}] has made us their vassal!",
            city.getCenterTile().position,
            NotificationCategory.Diplomacy, NotificationIcon.Diplomacy
        )
    }

    /**
     * Territorial Warfare: convert a conquered city into an allied city-state.
     * Creates a new city-state civilization, transfers the city to it, and sets 500 influence.
     */
    fun convertToCityState(conqueringCiv: Civilization) {
        val gameInfo = city.civ.gameInfo
        val ruleset = gameInfo.ruleset

        // Find an unused city-state nation
        val usedNations = gameInfo.civilizations.map { it.civName }.toSet()
        val availableCsNation = ruleset.nations.values.firstOrNull {
            it.isCityState && it.name !in usedNations
        }

        if (availableCsNation == null) {
            // No available city-state nations - fall back to puppet
            puppetCity(conqueringCiv)
            return
        }

        val oldCiv = city.civ

        // Diplomatic repercussions happen before city moves
        diplomaticRepercussionsForConqueringCity(oldCiv, conqueringCiv)

        // Create the new city-state civilization
        val newCsCiv = Civilization(availableCsNation.name)
        newCsCiv.playerType = com.unciv.logic.civilization.PlayerType.AI
        newCsCiv.gameInfo = gameInfo

        gameInfo.civilizations.add(newCsCiv)
        newCsCiv.setNationTransient()
        newCsCiv.setTransients()  // Initialize all manager transients (tech, policies, espionage, etc.)
        newCsCiv.cityStateFunctions.initCityState(ruleset, gameInfo.gameParameters.startingEra, emptySequence())

        // Transfer the city via conquerCity
        conquerCity(conqueringCiv, oldCiv, newCsCiv)

        // Set up diplomacy with the conquering civ
        newCsCiv.diplomacyFunctions.makeCivilizationsMeet(conqueringCiv)
        newCsCiv.getDiplomacyManager(conqueringCiv)?.setInfluence(500f)

        // Make peace if at war
        if (newCsCiv.isAtWarWith(conqueringCiv)) {
            val tradeLogic = TradeLogic(newCsCiv, conqueringCiv)
            tradeLogic.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = gameInfo.speed))
            tradeLogic.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = gameInfo.speed))
            tradeLogic.acceptTrade(false)
        }

        // Meet other civs that know the conquering civ
        for (otherCiv in conqueringCiv.getKnownCivs()) {
            if (!newCsCiv.knows(otherCiv))
                newCsCiv.diplomacyFunctions.makeCivilizationsMeet(otherCiv)
        }

        city.isPuppet = false
        city.removeFlag(CityFlags.Resistance)
        city.cityStats.update()

        conqueringCiv.addNotification(
            "[${city.name}] has been converted to an allied city-state",
            city.getCenterTile().position,
            NotificationCategory.Diplomacy,
            NotificationIcon.Diplomacy
        )
    }

}
