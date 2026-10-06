package com.unciv.app.desktop

import com.unciv.UncivGame
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.utils.DebugUtils
import kotlinx.coroutines.runBlocking

/**
 * TW v2 — Headless test runner for AI dynamics.
 * Triggered by `--testai=<N>` on the desktop launcher: starts a new all-AI game
 * (no human player), then auto-simulates N turns. Prints AI improvement-purchase
 * activity (the [TWv2 AI improvements] lines emitted by ImprovementPurchaseAutomation)
 * and a per-civ summary at the end.
 */
object TestAi {

    fun run(maxTurns: Int, runs: Int = 1, speed: String = "Standard", frontMode: Boolean = false) {
        println("[TWv2 test] Bootstrapping $runs run(s) of $maxTurns turns each (speed=$speed)...")

        val game = UncivGame(true)
        UncivGame.Current = game
        UncivGame.Current.settings = com.unciv.models.metadata.GameSettings()
        RulesetCache.loadRulesets(consoleMode = true, noMods = false)

        for (runIndex in 1..runs) {
            println("[TWv2 test] ========== RUN $runIndex / $runs ==========")
            runOne(maxTurns, speed, frontMode)
        }
    }

    private fun runOne(maxTurns: Int, speed: String = "Standard", frontMode: Boolean = false) {
        runBlocking {
            // 10 AI players + 1 Spectator (required: GameInfo.setTransients looks for a Human civ).
            val params = GameParameters().apply {
                this.speed = speed
                players = ArrayList<Player>().apply {
                    add(Player(com.unciv.Constants.spectator, PlayerType.Human, ""))
                    repeat(if (frontMode) 4 else 10) { add(Player(playerType = PlayerType.AI)) }
                }
                numberOfCityStates = if (frontMode) 3 else 6
                minNumberOfCityStates = numberOfCityStates
                maxNumberOfCityStates = numberOfCityStates
                noBarbarians = false
                this.frontMode = frontMode
            }
            val gameSetupInfo = GameSetupInfo(params, com.unciv.logic.map.MapParameters().apply {
                mapSize = if (frontMode) com.unciv.logic.map.MapSize.Small else com.unciv.logic.map.MapSize.Huge
            })
            val newGame = GameStarter.startNewGame(gameSetupInfo)
            newGame.gameParameters.victoryTypes = ArrayList(newGame.ruleset.victories.keys)
            UncivGame.Current.gameInfo = newGame

            println("[TWv2 test] Starting simulation: ${newGame.civilizations.filter { it.isMajorCiv() }.size} major civs, " +
                "${newGame.civilizations.count { it.isCityState }} city-states.")

            // Snapshot every 50 turns to track era/improvement gap evolution.
            val snapshotInterval = if (frontMode) 25 else 50
            var nextSnapshot = snapshotInterval

            try {
                while (newGame.turns < maxTurns) {
                    val targetTurn = minOf(nextSnapshot, maxTurns)
                    DebugUtils.SIMULATE_UNTIL_TURN = targetTurn
                    newGame.simulateMaxTurns = targetTurn
                    newGame.simulateUntilWin = false
                    newGame.nextTurn()

                    val year = newGame.getYear()
                    val yearLabel = if (year < 0) "${-year} BC" else "$year AD"
                    println("[TWv2 test] === Snapshot turn ${newGame.turns} ($yearLabel) ===")
                    for (civ in newGame.civilizations
                        .filter { it.isMajorCiv() && !it.isDefeated() }
                        .sortedByDescending { it.getEraNumber() * 1000 + it.cities.size }) {
                        val cities = civ.cities.size
                        val gold = civ.gold
                        val era = civ.getEraNumber()
                        val improvedTiles = civ.cities.sumOf { city -> city.tiles.count { pos ->
                            val tile = newGame.tileMap[pos]
                            tile.improvement != null && !tile.improvementIsPillaged
                        } }
                        val totalOwnedTiles = civ.cities.sumOf { it.tiles.size }
                        val score = civ.calculateTotalScore().toInt()
                        val divisions = civ.units.getCivUnits().count { com.unciv.logic.front.FrontResolver.isDivision(it) }
                        println("[TWv2 test]   ${civ.civName.padEnd(15)} era=$era cities=$cities gold=$gold improved=$improvedTiles/$totalOwnedTiles score=$score divisions=$divisions tiles=$totalOwnedTiles at war=${civ.isAtWar()}")
                    }

                    nextSnapshot += snapshotInterval
                }
            } catch (e: Throwable) {
                println("[TWv2 test] Simulation interrupted at turn ${newGame.turns}: ${e.message}")
                e.printStackTrace()
            }

            println("[TWv2 test] Simulation done at turn ${newGame.turns}.")
        }
    }
}
