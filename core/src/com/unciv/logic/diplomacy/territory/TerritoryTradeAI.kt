package com.unciv.logic.diplomacy.territory

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.tile.Tile

/**
 * Detects mutually beneficial territory trades, composes packages, and decides
 * whether to accept incoming offers. Skips human players in V1 (no UI yet).
 *
 * Cadence: called from NextTurnAutomation. Internal turn-stride throttling
 * prevents per-turn churn — each civ pair is re-evaluated every 20 turns
 * unless a triggering event resets the timer.
 */
object TerritoryTradeAI {

    private const val MAX_TILES_PER_OFFER = 8
    private const val MAX_ACCEPTED_TRADES_PER_TURN = 3
    private const val MIN_VALUE_DIFFERENTIAL = 2.0f
    private const val EVAL_STRIDE_TURNS = 20

    fun proposeTradesIfDue(civ: Civilization) {
        if (civ.isBarbarian || civ.isSpectator()) return
        if (civ.cities.isEmpty()) return
        if (!isDueForEvaluation(civ)) return

        var acceptedCount = 0
        for (other in civ.gameInfo.civilizations) {
            if (acceptedCount >= MAX_ACCEPTED_TRADES_PER_TURN) break
            if (other == civ || !other.isAlive() || other.isBarbarian || other.isSpectator()) continue
            if (other.isCityState) continue
            if (civ.isAtWarWith(other)) continue
            val diplo = civ.getDiplomacyManager(other) ?: continue
            if (diplo.relationshipIgnoreAfraid() == RelationshipLevel.Enemy ||
                diplo.relationshipIgnoreAfraid() == RelationshipLevel.Unforgivable) continue

            val offer = composeOffer(civ, other) ?: continue
            if (other.isHuman()) {
                // Queue an alert for the human; they'll see it at their next turn start.
                queueOfferForHuman(offer)
                acceptedCount++ // count it to throttle so we don't spam the player
                continue
            }
            if (decideAccept(other, offer)) {
                TerritoryTradeManager.apply(offer)
                acceptedCount++
            } else {
                TerritoryTradeManager.recordRefusal(offer)
            }
        }
        markEvaluated(civ)
    }

    /** Receives a human-initiated offer, returns the AI's verdict synchronously. */
    fun respondToHumanOffer(offer: TerritoryTradeOffer): HumanOfferVerdict {
        val ai = offer.toCiv
        if (offer.isUltimatum()) {
            handleUltimatum(ai, offer)
            // handleUltimatum may have declared war; reflect that in the verdict
            return if (ai.isAtWarWith(offer.fromCiv)) HumanOfferVerdict.UltimatumWar
                   else HumanOfferVerdict.UltimatumRefused
        }
        return if (decideAccept(ai, offer)) HumanOfferVerdict.Accepted
               else HumanOfferVerdict.Refused
    }

    enum class HumanOfferVerdict { Accepted, Refused, UltimatumWar, UltimatumRefused }

    private fun queueOfferForHuman(offer: TerritoryTradeOffer) {
        val payload = TerritoryTradePayload.encode(offer)
        offer.toCiv.popupAlerts.add(
            com.unciv.logic.civilization.PopupAlert(
                com.unciv.logic.civilization.AlertType.TerritoryTradeOffer,
                payload
            )
        )
    }

    private fun isDueForEvaluation(civ: Civilization): Boolean {
        val lastTurn = civ.lastTerritoryTradeEvalTurn
        return civ.gameInfo.turns - lastTurn >= EVAL_STRIDE_TURNS
    }

    private fun markEvaluated(civ: Civilization) {
        civ.lastTerritoryTradeEvalTurn = civ.gameInfo.turns
    }

    /**
     * Compose a mutually beneficial package: pick our tiles that are highly
     * valued by the other and weakly valued by us; ask in exchange for their
     * tiles that we value highly and they value weakly. Greedy by value gap.
     */
    private fun composeOffer(civ: Civilization, other: Civilization): TerritoryTradeOffer? {
        val ourCedeCandidates = findCedeCandidates(civ, other)
        val theirCedeCandidates = findCedeCandidates(other, civ)
        if (ourCedeCandidates.isEmpty() && theirCedeCandidates.isEmpty()) return null

        val tilesToOffer = ourCedeCandidates.take(MAX_TILES_PER_OFFER)
        val tilesToRequest = theirCedeCandidates.take(MAX_TILES_PER_OFFER)

        val offered = TradePackage(tiles = tilesToOffer)
        val requested = TradePackage(tiles = tilesToRequest)

        val valueWeGive = TerritoryTradeOffer.valueOfPackage(offered, civ)
        val valueWeGet = TerritoryTradeOffer.valueOfPackage(requested, civ)

        // Balance with gold so the deal is acceptable to the other side AND profitable for us.
        var goldOfferedByUs = 0
        var goldRequestedFromThem = 0
        val theirSideGiven = TerritoryTradeOffer.valueOfPackage(requested, other)
        val theirSideReceived = TerritoryTradeOffer.valueOfPackage(offered, other)
        val theirNet = theirSideReceived - theirSideGiven
        if (theirNet < 0f) {
            // We owe them gold to make the deal balance for them.
            goldOfferedByUs = (-theirNet / TerritoryTradeOffer.GOLD_VALUE_PER_UNIT).toInt()
                .coerceAtMost(civ.gold / 2)
        } else if (theirNet > 0f && valueWeGet - valueWeGive < theirNet) {
            // They net positive but we still net negative — ask gold to balance us.
            val deficit = theirNet
            goldRequestedFromThem = (deficit / TerritoryTradeOffer.GOLD_VALUE_PER_UNIT).toInt()
                .coerceAtMost(other.gold / 2)
        }

        val finalOffered = offered.copy(gold = goldOfferedByUs)
        val finalRequested = requested.copy(gold = goldRequestedFromThem)

        // Final sanity: must be a meaningful win for us.
        val finalValueGain = TerritoryTradeOffer.valueOfPackage(finalRequested, civ) -
                             TerritoryTradeOffer.valueOfPackage(finalOffered, civ)
        if (finalValueGain < MIN_VALUE_DIFFERENTIAL) return null

        if (TerritoryTradeManager.isOnCooldown(civ, other, finalOffered.tiles + finalRequested.tiles, emptyList()))
            return null

        return TerritoryTradeOffer(civ, other, finalOffered, finalRequested)
    }

    /**
     * Tiles where the other civ values the tile much more than we do — natural cede candidates.
     * Filter: tile must be ours, not rebelling, not a city center, and friendlyShare(tile, civ) ≤ 0.5.
     */
    private fun findCedeCandidates(civ: Civilization, other: Civilization): List<Tile> {
        val candidates = mutableListOf<Pair<Tile, Float>>()
        for (city in civ.cities) {
            for (tile in city.getTiles()) {
                if (tile.isCityCenter()) continue
                if (tile.rebellionTurns > 0) continue
                val ourValue = TileValuation.value(tile, civ)
                val theirValue = TileValuation.value(tile, other)
                val gap = theirValue - ourValue
                if (gap < MIN_VALUE_DIFFERENTIAL) continue
                candidates.add(tile to gap)
            }
        }
        candidates.sortByDescending { it.second }
        return candidates.map { it.first }
    }

    /** Receiver-side decision: accept iff the deal nets positive value for us at our threshold. */
    private fun decideAccept(receiver: Civilization, offer: TerritoryTradeOffer): Boolean {
        val valueWeGive = TerritoryTradeOffer.valueOfPackage(offer.requested, receiver)
        val valueWeGet = TerritoryTradeOffer.valueOfPackage(offer.offered, receiver)
        if (valueWeGive <= 0f) return valueWeGet > 0f

        if (offer.isUltimatum()) {
            // V1: receiver evaluates whether to declare war based on relative power
            // and culture — but for now we just refuse + propagate the DoW heuristic.
            handleUltimatum(receiver, offer)
            return false
        }

        val diplo = receiver.getDiplomacyManager(offer.fromCiv)
        val threshold = when (diplo?.relationshipIgnoreAfraid()) {
            RelationshipLevel.Ally -> 0.85f
            RelationshipLevel.Friend -> 0.95f
            RelationshipLevel.Favorable -> 1.0f
            RelationshipLevel.Neutral -> 1.05f
            RelationshipLevel.Competitor -> 1.20f
            RelationshipLevel.Enemy, RelationshipLevel.Unforgivable -> return false
            null, RelationshipLevel.Afraid -> 1.0f
        }
        return valueWeGet >= valueWeGive * threshold
    }

    /**
     * Receiver's response to an ultimatum: declare war if it has a power advantage
     * OR if the demanded zone is culturally precious (≥ 50% receiver's culture).
     */
    private fun handleUltimatum(receiver: Civilization, offer: TerritoryTradeOffer) {
        val proposer = offer.fromCiv
        val ourPower = receiver.getStatForRanking(com.unciv.ui.screens.victoryscreen.RankingType.Force)
        val theirPower = proposer.getStatForRanking(com.unciv.ui.screens.victoryscreen.RankingType.Force)
        val powerAdvantage = ourPower >= theirPower * 0.9

        var culturalPreciousness = 0f
        var totalRequestedTiles = 0
        for (tile in offer.requested.tiles) {
            culturalPreciousness += com.unciv.logic.map.TileCultureLogic.getFriendlyShare(tile, receiver)
            totalRequestedTiles++
        }
        val avgCulturalShare = if (totalRequestedTiles > 0) culturalPreciousness / totalRequestedTiles else 0f
        val culturallyPrecious = avgCulturalShare >= 0.5f

        if (powerAdvantage || culturallyPrecious) {
            val diplo = receiver.getDiplomacyManager(proposer) ?: return
            if (diplo.canDeclareWar()) {
                diplo.declareWar()
                receiver.addNotification(
                    "We declared war on [${proposer.civName}] in response to their ultimatum",
                    NotificationCategory.Diplomacy,
                    NotificationIcon.War
                )
            }
        }
    }
}

/** Per-civ throttle for territory trade evaluation. Persisted on the civ. */
internal var Civilization.lastTerritoryTradeEvalTurn: Int
    get() = lastTerritoryTradeEvalTurnStorage[this.civName] ?: -1000
    set(value) { lastTerritoryTradeEvalTurnStorage[this.civName] = value }

private val lastTerritoryTradeEvalTurnStorage = HashMap<String, Int>()
