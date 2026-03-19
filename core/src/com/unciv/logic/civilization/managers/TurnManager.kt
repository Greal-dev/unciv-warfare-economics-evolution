package com.unciv.logic.civilization.managers

import com.unciv.UncivGame
import com.unciv.logic.VictoryData
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.city.managers.CityTurnManager
import com.unciv.logic.civilization.*
import com.unciv.logic.civilization.diplomacy.DiplomacyTurnManager.nextTurn
import com.unciv.logic.map.mapunit.UnitTurnManager
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unique.endTurn
import com.unciv.models.stats.Stats
import com.unciv.ui.components.MayaCalendar
import com.unciv.ui.screens.worldscreen.status.NextTurnProgress
import com.unciv.utils.Log
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class TurnManager(val civInfo: Civilization) {


    fun startTurn(progressBar: NextTurnProgress? = null) {
        if (civInfo.isSpectator()) return

        civInfo.threatManager.clear()
        if (civInfo.isMajorCiv() && civInfo.isAlive()) {
            civInfo.statsHistory.recordRankingStats(civInfo)
        }

        if (civInfo.cities.isNotEmpty() && civInfo.gameInfo.ruleset.technologies.isNotEmpty())
            civInfo.tech.updateResearchProgress()

        civInfo.cache.updateCivResources() // If you offered a trade last turn, this turn it will have been accepted/declined
        for (stockpiledResource in civInfo.getCivResourceSupply().filter { it.resource.isStockpiled })
            civInfo.gainStockpiledResource(stockpiledResource.resource, stockpiledResource.amount)

        civInfo.civConstructions.startTurn()
        civInfo.attacksSinceTurnStart.clear()
        civInfo.unitsLostThisTurn = 0
        civInfo.updateStatsForNextTurn() // for things that change when turn passes e.g. golden age, city state influence

        // Do this after updateStatsForNextTurn but before cities.startTurn
        if (civInfo.playerType == PlayerType.AI && civInfo.gameInfo.ruleset.modOptions.hasUnique(UniqueType.ConvertGoldToScience))
            NextTurnAutomation.automateGoldToSciencePercentage(civInfo)

        // Generate great people at the start of the turn,
        // so they won't be generated out in the open and vulnerable to enemy attacks before you can control them
        if (civInfo.cities.isNotEmpty()) { //if no city available, addGreatPerson will throw exception
            var greatPerson = civInfo.greatPeople.getNewGreatPerson()
            while (greatPerson != null) {
                if (civInfo.gameInfo.ruleset.units.containsKey(greatPerson))
                    civInfo.units.addUnit(greatPerson)
                greatPerson = civInfo.greatPeople.getNewGreatPerson()
            }
            civInfo.religionManager.startTurn()
            if (civInfo.isLongCountActive())
                MayaCalendar.startTurnForMaya(civInfo)
        }

        civInfo.cache.updateViewableTiles() // adds explored tiles so that the units will be able to perform automated actions better
        civInfo.cache.updateCitiesConnectedToCapital()

        // Territorial Warfare: Imperial Stability Index
        if (civInfo.isMajorCiv() && civInfo.cities.isNotEmpty()) {
            val previousISI = civInfo.imperialStability
            civInfo.imperialStability = civInfo.stabilityManager.calculateISI()
            civInfo.stabilityManager.checkRenaissanceTransition(previousISI, civInfo.imperialStability)
            civInfo.stabilityManager.decrementRenaissance()
            civInfo.demographicShockCitiesThisTurn = 0
            civInfo.stabilityManager.checkForDemographicShock()
            civInfo.stabilityManager.checkForRevolt()

            // Notify on tier change
            val previousTier = ImperialStabilityManager.StabilityTier.fromISI(previousISI)
            val newTier = civInfo.stabilityManager.getTier()
            if (newTier != previousTier) {
                val message = when (newTier) {
                    ImperialStabilityManager.StabilityTier.GoldenAge ->
                        "Imperial Golden Age! Our empire is thriving! (ISI: ${civInfo.imperialStability})"
                    ImperialStabilityManager.StabilityTier.Stable ->
                        "Our empire has stabilized. (ISI: ${civInfo.imperialStability})"
                    ImperialStabilityManager.StabilityTier.Tensions ->
                        "Tensions are rising across the empire! (ISI: ${civInfo.imperialStability})"
                    ImperialStabilityManager.StabilityTier.Crisis ->
                        "Imperial crisis! Cities may revolt! (ISI: ${civInfo.imperialStability})"
                    ImperialStabilityManager.StabilityTier.Collapse ->
                        "Empire is collapsing! Sécessions imminent! (ISI: ${civInfo.imperialStability})"
                }
                civInfo.addNotification(message, NotificationCategory.General)
            }
        }

        startTurnFlags()
        updateRevolts()

        for (unique in civInfo.getTriggeredUniques(UniqueType.TriggerUponTurnStart, civInfo.state, ignoreCities = true))
            UniqueTriggerActivation.triggerUnique(unique, civInfo)

        for (city in civInfo.cities) {
            progressBar?.increment()
            CityTurnManager(city).startTurn()  // Most expensive part of startTurn
        }

        for (unit in civInfo.units.getCivUnits()) UnitTurnManager(unit).startTurn()

        if (civInfo.playerType == PlayerType.Human && UncivGame.Current.settings.automatedUnitsMoveOnTurnStart) {
            civInfo.hasMovedAutomatedUnits = true
            for (unit in civInfo.units.getCivUnits())
                unit.doAction()
        } else civInfo.hasMovedAutomatedUnits = false

        for (tradeRequest in civInfo.tradeRequests.toList()) { // remove trade requests where one of the sides can no longer supply
            val offeringCiv = civInfo.gameInfo.getCivilization(tradeRequest.requestingCiv)
            if (offeringCiv.isDefeated() || !TradeEvaluation().isTradeValid(tradeRequest.trade, civInfo, offeringCiv)) {
                civInfo.tradeRequests.remove(tradeRequest)
                // Yes, this is the right direction. I checked.
                offeringCiv.addNotification("Our proposed trade is no longer relevant!", NotificationCategory.Trade, NotificationIcon.Trade)
                // If it's a counteroffer, remove notification
                civInfo.notifications.removeAll { it.text == "[${offeringCiv.civName}] has made a counteroffer to your trade request" }
            }
        }
        
        for (unit in civInfo.units.getCivUnits().filter { it.promotions.canBePromoted() }){
            civInfo.addNotification("[${unit.displayName()}] can be promoted!",
                listOf(MapUnitAction(unit), PromoteUnitAction(unit)),
                NotificationCategory.Units, unit.name)
        }

        updateWinningCiv()
    }


    private fun startTurnFlags() {
        for (flag in civInfo.flagsCountdown.keys.toList()) {
            // In case we remove flags while iterating
            if (!civInfo.flagsCountdown.containsKey(flag)) continue

            if (flag == CivFlags.CityStateGreatPersonGift.name) {
                val cityStateAllies: List<Civilization> =
                        civInfo.getKnownCivs().filter { it.isCityState && it.allyCiv == civInfo }.toList()
                val givingCityState = cityStateAllies.filter { it.cities.isNotEmpty() }.randomOrNull()

                if (cityStateAllies.isNotEmpty()) civInfo.flagsCountdown[flag] = civInfo.flagsCountdown[flag]!! - 1

                if (civInfo.flagsCountdown[flag]!! < min(cityStateAllies.size, 10) && civInfo.cities.isNotEmpty()
                        && givingCityState != null
                ) {
                    givingCityState.cityStateFunctions.giveGreatPersonToPatron(civInfo)
                    civInfo.flagsCountdown[flag] = civInfo.cityStateFunctions.turnsForGreatPersonFromCityState()
                }

                continue
            }

            if (civInfo.flagsCountdown[flag]!! > 0)
                civInfo.flagsCountdown[flag] = civInfo.flagsCountdown[flag]!! - 1

            if (civInfo.flagsCountdown[flag] != 0) continue

            when (flag) {
                CivFlags.RevoltSpawning.name -> doRevoltSpawn()
                CivFlags.TurnsTillCityStateElection.name -> civInfo.cityStateFunctions.holdElections()
            }
        }
        handleDiplomaticVictoryFlags()
    }

    private fun handleDiplomaticVictoryFlags() {
        if (civInfo.flagsCountdown[CivFlags.ShouldResetDiplomaticVotes.name] == 0) {
            civInfo.gameInfo.diplomaticVictoryVotesCast.clear()
            civInfo.removeFlag(CivFlags.ShowDiplomaticVotingResults.name)
            civInfo.removeFlag(CivFlags.ShouldResetDiplomaticVotes.name)
        }

        if (civInfo.flagsCountdown[CivFlags.ShowDiplomaticVotingResults.name] == 0) {
            civInfo.gameInfo.processDiplomaticVictory()
            if (civInfo.gameInfo.civilizations.any { it.victoryManager.hasWon() } ) {
                civInfo.removeFlag(CivFlags.TurnsTillNextDiplomaticVote.name)
            } else {
                civInfo.addFlag(CivFlags.ShouldResetDiplomaticVotes.name, 1)
                civInfo.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, civInfo.getTurnsBetweenDiplomaticVotes())
            }
        }

        if (civInfo.flagsCountdown[CivFlags.TurnsTillNextDiplomaticVote.name] == 0) {
            civInfo.addFlag(CivFlags.ShowDiplomaticVotingResults.name, 1)
        }
    }


    private fun updateRevolts() {
        if (civInfo.gameInfo.civilizations.none { it.isBarbarian }) {
            // Can't spawn revolts without barbarians ¯\_(ツ)_/¯
            return
        }

        if (!civInfo.hasUnique(UniqueType.SpawnRebels)) {
            civInfo.removeFlag(CivFlags.RevoltSpawning.name)
            return
        }

        if (!civInfo.hasFlag(CivFlags.RevoltSpawning.name)) {
            civInfo.addFlag(CivFlags.RevoltSpawning.name, getTurnsBeforeRevolt().coerceAtLeast(1))
            return
        }
    }

    private fun doRevoltSpawn() {
        val barbarians = try {
            // The first test in `updateRevolts` should prevent getting here in a no-barbarians game, but it has been shown to still occur
            civInfo.gameInfo.getBarbarianCivilization()
        } catch (ex: NoSuchElementException) {
            Log.error("Barbarian civilization not found", ex)
            civInfo.removeFlag(CivFlags.RevoltSpawning.name)
            return
        }

        val random = Random.Default
        val rebelCount = 1 + random.nextInt(100 + 20 * (civInfo.cities.size - 1)) / 100
        val spawnCity = civInfo.cities.maxByOrNull { random.nextInt(it.population.population + 10) } ?: return
        val spawnTile = spawnCity.getTiles().maxByOrNull { rateTileForRevoltSpawn(it) } ?: return
        val unitToSpawn = civInfo.gameInfo.ruleset.units.values.asSequence().filter {
            it.uniqueTo == null && it.isMelee() && it.isLandUnit
                    && !it.hasUnique(UniqueType.CannotAttack) && it.isBuildable(civInfo)
        }.maxByOrNull {
            random.nextInt(1000)
        } ?: return

        repeat(rebelCount) {
            civInfo.gameInfo.tileMap.placeUnitNearTile(
                spawnTile.position,
                unitToSpawn,
                barbarians
            )
        }

        // Will be automatically added again as long as unhappiness is still low enough
        civInfo.removeFlag(CivFlags.RevoltSpawning.name)

        civInfo.addNotification("Your citizens are revolting due to very high unhappiness!", spawnTile.position, NotificationCategory.General, unitToSpawn.name, "StatIcons/Malcontent")
    }

    // Higher is better
    @Readonly
    private fun rateTileForRevoltSpawn(tile: Tile): Int {
        if (tile.isWater || tile.militaryUnit != null || tile.civilianUnit != null || tile.isCityCenter() || tile.isImpassible())
            return -1
        var score = 10
        if (tile.improvement == null) {
            score += 4
            if (tile.resource != null) {
                score += 3
            }
        }
        if (tile.getDefensiveBonus() > 0)
            score += 4
        return score
    }
    
    @Readonly
    private fun getTurnsBeforeRevolt() =
        ((civInfo.gameInfo.ruleset.modOptions.constants.baseTurnsUntilRevolt + Random.Default.nextInt(3)) 
            * civInfo.gameInfo.speed.modifier.coerceAtLeast(1f)).toInt()


    fun endTurn(progressBar: NextTurnProgress? = null) {
        // TW: Free vassal if suzerain is defeated
        if (civInfo.isVassal()) {
            val suzerain = civInfo.getSuzerain()
            if (suzerain == null || suzerain.isDefeated()) {
                civInfo.releaseFromVassalage()
                civInfo.addNotification("We are free! Our suzerain has fallen!", NotificationCategory.Diplomacy, NotificationIcon.Diplomacy)
            }
        }

        // TW: AI vassal independence request (20-turn cooldown enforced in canDeclareIndependence)
        if (civInfo.isVassal() && !civInfo.isHuman() && civInfo.canDeclareIndependence()) {
            val suzerain = civInfo.getSuzerain()
            if (suzerain != null) {
                if (suzerain.isHuman()) {
                    // Don't add duplicate popup if one is already pending
                    val alreadyPending = suzerain.popupAlerts.any {
                        it.type == AlertType.VassalIndependenceRequest && it.value == civInfo.civName
                    }
                    if (!alreadyPending)
                        suzerain.popupAlerts.add(PopupAlert(AlertType.VassalIndependenceRequest, civInfo.civName))
                } else {
                    // AI suzerain: refuse if stronger, accept if vassal is strong enough
                    val suzerainMight = suzerain.calculateMilitaryMight()
                    val vassalMight = civInfo.calculateMilitaryMight()
                    if (vassalMight >= suzerainMight * 0.5f) {
                        // Vassal is strong enough — AI accepts peacefully
                        civInfo.releaseFromVassalage()
                        civInfo.addNotification("We have gained our independence from [${suzerain.civName}]!", NotificationCategory.Diplomacy, NotificationIcon.Diplomacy)
                        suzerain.addNotification("[${civInfo.civName}] has gained independence.", NotificationCategory.Diplomacy, NotificationIcon.Diplomacy)
                    } else {
                        // AI refuses — independence war
                        civInfo.declareIndependence()
                    }
                }
            }
        }

        if (UncivGame.Current.settings.citiesAutoBombardAtEndOfTurn)
            NextTurnAutomation.automateCityBombardment(civInfo) // Bombard with all cities that haven't, maybe you missed one

        for (unique in civInfo.getTriggeredUniques(UniqueType.TriggerUponTurnEnd, civInfo.state, ignoreCities = true))
            UniqueTriggerActivation.triggerUnique(unique, civInfo)

        val notificationsLog = civInfo.notificationsLog
        val notificationsThisTurn = Civilization.NotificationsLog(civInfo.gameInfo.turns)
        notificationsThisTurn.notifications.addAll(civInfo.notifications)

        while (notificationsLog.size >= UncivGame.Current.settings.notificationsLogMaxTurns) {
            notificationsLog.removeAt(0)
        }

        if (notificationsThisTurn.notifications.isNotEmpty())
            notificationsLog.add(notificationsThisTurn)

        civInfo.notifications.clear()
        civInfo.notificationCountAtStartTurn = null

        if (civInfo.isDefeated() || civInfo.isSpectator()) return  // yes they do call this, best not update any further stuff
        
        var nextTurnStats =
            if (civInfo.isBarbarian)
                Stats()
            else {
                civInfo.updateStatsForNextTurn()
                civInfo.stats.statsForNextTurn
            }

        civInfo.policies.endTurn(nextTurnStats.culture.toInt())
        civInfo.totalCultureForContests += nextTurnStats.culture.toInt()

        if (civInfo.isCityState) {
            civInfo.questManager.endTurn()

            // Set turns to elections to a random number so not every city-state has the same election date
            // May be called at game start or when migrating a game from an older version
            if (civInfo.gameInfo.isEspionageEnabled() && !civInfo.hasFlag(CivFlags.TurnsTillCityStateElection.name)) {
                civInfo.addFlag(CivFlags.TurnsTillCityStateElection.name, Random.nextInt(civInfo.gameInfo.ruleset.modOptions.constants.cityStateElectionTurns + 1))
            }
        }

        // disband units until there are none left OR the gold values are normal
        if (!civInfo.isBarbarian && civInfo.gold <= -200 && nextTurnStats.gold.toInt() < 0) {
            do {
                val militaryUnits = civInfo.units.getCivUnits().filter { it.isMilitary() }  // New sequence as disband replaces unitList
                val unitToDisband = militaryUnits.minByOrNull { it.baseUnit.cost }
                    // or .firstOrNull()?
                    ?: break
                unitToDisband.disband()
                val unitName = unitToDisband.shortDisplayName()
                civInfo.addNotification("Cannot provide unit upkeep for $unitName - unit has been disbanded!", NotificationCategory.Units, unitName, NotificationIcon.Death)
                // No need to recalculate unit upkeep, disband did that in UnitManager.removeUnit
                nextTurnStats = civInfo.stats.statsForNextTurn
            } while (civInfo.gold <= -200 && nextTurnStats.gold.toInt() < 0)
        }

        // TW: Vassal tribute - transfer 25% of gold/science to suzerain
        // Note: the 25% deduction is already included in nextTurnStats via getStatMapForNextTurn()
        // Here we only transfer the tribute amount to the suzerain
        if (civInfo.isVassal()) {
            val suzerain = civInfo.getSuzerain()
            if (suzerain != null && !suzerain.isDefeated()) {
                // nextTurnStats is post-tribute (raw * 0.75), so tribute = post / 3
                if (nextTurnStats.gold > 0) {
                    val goldTribute = (nextTurnStats.gold / 3f).toInt()
                    if (goldTribute > 0) suzerain.addGold(goldTribute)
                }
                if (nextTurnStats.science > 0) {
                    val scienceTribute = (nextTurnStats.science / 3f).toInt()
                    if (scienceTribute > 0) suzerain.tech.addScience(scienceTribute)
                }
            }
        }

        civInfo.addGold(nextTurnStats.gold.toInt() )

        if (civInfo.cities.isNotEmpty() && civInfo.gameInfo.ruleset.technologies.isNotEmpty())
            civInfo.tech.endTurn(nextTurnStats.science.toInt())

        civInfo.religionManager.endTurn(nextTurnStats.faith.toInt())
        civInfo.totalFaithForContests += nextTurnStats.faith.toInt()

        civInfo.espionageManager.endTurn()

        if (civInfo.isMajorCiv()) // City-states don't get great people!
            civInfo.greatPeople.addGreatPersonPoints()

        // To handle tile's owner issue (#8246), we need to run cities being razed first.
        // a city can be removed while iterating (if it's being razed) so we need to iterate over a copy - sorting does one
        for (city in civInfo.cities.sortedByDescending { it.isBeingRazed }) {
            progressBar?.increment()
            CityTurnManager(city).endTurn()
        }

        civInfo.temporaryUniques.endTurn()

        // Territorial Warfare: tile culture propagation, rebellion, and secession
        if (!civInfo.isBarbarian && civInfo.cities.isNotEmpty()) {
            com.unciv.logic.map.TileCultureLogic.processCivTiles(civInfo)
        }

        // Territorial Warfare: encirclement — conquer cut-off enemy tiles, attrition on isolated units
        if (!civInfo.isBarbarian && civInfo.cities.isNotEmpty()) {
            com.unciv.logic.map.TileCultureLogic.processEncirclement(civInfo)
        }

        // Territorial Warfare: update war experience bonus
        if (civInfo.isAtWar()) {
            civInfo.warExperienceBonus = min(30, civInfo.warExperienceBonus + 1)
        } else {
            civInfo.warExperienceBonus = max(0, civInfo.warExperienceBonus - 1)
        }

        // Territorial Warfare: track turns in industrial era for logistic production growth
        val industrialEra = civInfo.gameInfo.ruleset.eras.values.firstOrNull { it.name == "Industrial era" }
        if (industrialEra != null && civInfo.getEraNumber() >= industrialEra.eraNumber) {
            civInfo.turnsInIndustrialEra++
        }

        // Territorial Warfare: from Renaissance era, auto-exploration
        // - Every turn: 10 coast tiles revealed (maritime exploration)
        // - Every 2 turns: each explored land tile reveals its unexplored land neighbors
        val renaissanceEra = civInfo.gameInfo.ruleset.eras.values.firstOrNull { it.name == "Renaissance era" }
        if (renaissanceEra != null && civInfo.getEraNumber() >= renaissanceEra.eraNumber) {
            expandExploredMapCoast(civInfo, 10)
            if (civInfo.gameInfo.turns % 2 == 0) {
                expandExploredMapLand(civInfo)
            }
        }

        civInfo.goldenAges.endTurn(civInfo.getHappiness())
        civInfo.units.getCivUnits().forEach { UnitTurnManager(it).endTurn() }  // This is the most expensive part of endTurn

        // Territorial Warfare: check for encircled neutral and enemy territory
        com.unciv.logic.map.TerritoryEncirclementCheck.checkEncirclement(civInfo)
        // TW: border harmonization disabled — territory transfers are diplomatic only
        // (via TerritoryExchangeScreen)
        civInfo.diplomacy.values.toList().forEach { it.nextTurn() } // we copy the diplomacy values so if it changes in-loop we won't crash
        civInfo.cache.updateHasActiveEnemyMovementPenalty()

        civInfo.resetMilitaryMightCache()

        updateWinningCiv() // Maybe we did something this turn to win
    }

    /** TW: Maritime exploration — reveal up to [count] unexplored coast tiles
     *  adjacent to already-explored tiles. Simulates Age of Discovery seafaring. */
    private fun expandExploredMapCoast(civInfo: com.unciv.logic.civilization.Civilization, count: Int) {
        val tileMap = civInfo.gameInfo.tileMap
        val candidates = mutableListOf<com.unciv.logic.map.tile.Tile>()
        for (tile in tileMap.values) {
            if (!tile.isExplored(civInfo) && tile.isCoastalTile()) {
                if (tile.neighbors.any { it.isExplored(civInfo) }) {
                    candidates.add(tile)
                }
            }
        }
        if (candidates.isEmpty()) return
        candidates.shuffle()
        val toReveal = candidates.take(count)
        for (tile in toReveal) {
            tile.setExplored(civInfo, true)
        }
        if (toReveal.isNotEmpty()) {
            civInfo.addNotification(
                "Our navigators have charted [${toReveal.size}] coastal tiles!",
                com.unciv.logic.civilization.NotificationCategory.General,
                com.unciv.logic.civilization.NotificationIcon.Science
            )
        }
    }

    /** TW: Land exploration — each explored land tile reveals all its unexplored
     *  land neighbors. Slower than maritime exploration (runs every 2 turns). */
    private fun expandExploredMapLand(civInfo: com.unciv.logic.civilization.Civilization) {
        val tileMap = civInfo.gameInfo.tileMap
        var revealed = 0
        for (tile in tileMap.values) {
            if (!tile.isExplored(civInfo) && tile.isLand) {
                if (tile.neighbors.any { it.isExplored(civInfo) && it.isLand }) {
                    tile.setExplored(civInfo, true)
                    revealed++
                }
            }
        }
        if (revealed > 0) {
            civInfo.addNotification(
                "Our cartographers have mapped [$revealed] new land tiles!",
                com.unciv.logic.civilization.NotificationCategory.General,
                com.unciv.logic.civilization.NotificationIcon.Science
            )
        }
    }

    fun updateWinningCiv() {
        if (civInfo.gameInfo.victoryData != null) return // Game already won

        val victoryType = civInfo.victoryManager.getVictoryTypeAchieved()
        if (victoryType != null) {
            civInfo.gameInfo.victoryData =
                    VictoryData(civInfo, victoryType, civInfo.gameInfo.turns)

            // Notify other human players about this civInfo's victory
            for (otherCiv in civInfo.gameInfo.civilizations) {
                // Skip winner, displaying VictoryScreen is handled separately in WorldScreen.update
                // by checking `viewingCiv.isDefeated() || gameInfo.checkForVictory()`
                if (otherCiv.playerType != PlayerType.Human || otherCiv == civInfo) continue
                otherCiv.popupAlerts.add(PopupAlert(AlertType.GameHasBeenWon, ""))
            }
        }
    }

    fun automateTurn() {
        // Defeated civs do nothing
        if (civInfo.isDefeated())
            return

        // Do stuff
        NextTurnAutomation.automateCivMoves(civInfo)

        // Update barbarian camps
        if (civInfo.isBarbarian && !civInfo.gameInfo.gameParameters.noBarbarians)
            civInfo.gameInfo.barbarians.updateEncampments()
    }

}
