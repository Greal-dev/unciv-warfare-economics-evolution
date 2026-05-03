package com.unciv.logic.diplomacy.territory

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon

/**
 * Applies accepted territory trades and runs ultimatum-rejection logic.
 *
 * Per-pair cooldown of 20 turns is stored in DiplomacyManager.territoryTradeCooldown
 * (turn at which proposer can propose to this target again). Cooldown is bypassed if
 * the proposed package replaces ≥ 50% of its tiles compared to the previous attempt.
 */
object TerritoryTradeManager {

    const val COOLDOWN_TURNS = 20
    private const val MIN_TURN_INTERVAL_FOR_BYPASS_OVERLAP = 0.5f

    /** Apply an accepted offer: transfer tiles + cities, settle gold, register side agreements. */
    fun apply(offer: TerritoryTradeOffer) {
        transferPackage(offer.offered, offer.fromCiv, offer.toCiv)
        transferPackage(offer.requested, offer.toCiv, offer.fromCiv)
        applySideAgreements(offer.fromCiv, offer.toCiv, offer.offered.sideAgreements)
        applySideAgreements(offer.toCiv, offer.fromCiv, offer.requested.sideAgreements)
        notifyParties(offer)
    }

    /** Record a refusal: arm the cooldown so the proposer can't repeat the same package. */
    fun recordRefusal(offer: TerritoryTradeOffer) {
        val diplo = offer.fromCiv.getDiplomacyManager(offer.toCiv) ?: return
        val currentTurn = offer.fromCiv.gameInfo.turns
        diplo.territoryTradeCooldown[offer.toCiv.civName] = currentTurn + COOLDOWN_TURNS
    }

    /** True if proposer is still on cooldown for this target AND the package overlap is too high. */
    fun isOnCooldown(
        fromCiv: Civilization,
        toCiv: Civilization,
        proposedTiles: Collection<com.unciv.logic.map.tile.Tile>,
        previouslyRefusedTiles: Collection<com.unciv.logic.map.tile.Tile>
    ): Boolean {
        val diplo = fromCiv.getDiplomacyManager(toCiv) ?: return false
        val cooldownEndsTurn = diplo.territoryTradeCooldown[toCiv.civName] ?: return false
        if (fromCiv.gameInfo.turns >= cooldownEndsTurn) return false
        if (proposedTiles.isEmpty() || previouslyRefusedTiles.isEmpty()) return true
        val overlap = proposedTiles.count { it in previouslyRefusedTiles }.toFloat() /
                      proposedTiles.size.toFloat()
        return overlap >= MIN_TURN_INTERVAL_FOR_BYPASS_OVERLAP
    }

    private fun transferPackage(
        pkg: TradePackage,
        fromCiv: Civilization,
        toCiv: Civilization
    ) {
        // Transfer cities first — moving a city moves all its tiles too.
        for (city in pkg.cities) {
            if (city.civ != fromCiv) continue
            city.moveToCiv(toCiv)
        }
        // Then individual tiles. Skip rebelling tiles defensively.
        for (tile in pkg.tiles) {
            if (tile.rebellionTurns > 0) continue
            val owningCity = tile.getCity() ?: continue
            if (owningCity.civ != fromCiv) continue
            // Find the closest city of the receiver to attach the tile to.
            val receivingCity = toCiv.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
                ?: continue
            owningCity.expansion.relinquishOwnership(tile)
            receivingCity.expansion.takeOwnership(tile)
        }
        // Finally gold.
        if (pkg.gold > 0) {
            fromCiv.addGold(-pkg.gold)
            toCiv.addGold(pkg.gold)
        }
    }

    private fun applySideAgreements(
        fromCiv: Civilization,
        toCiv: Civilization,
        agreements: Set<SideAgreement>
    ) {
        if (agreements.isEmpty()) return
        val diplo = fromCiv.getDiplomacyManager(toCiv) ?: return
        for (agreement in agreements) {
            when (agreement) {
                SideAgreement.OpenBorders -> diplo.setOpenBorders(true)
                SideAgreement.PeaceTreaty -> if (fromCiv.isAtWarWith(toCiv)) diplo.makePeace()
                SideAgreement.DeclarationOfFriendship ->
                    if (!diplo.hasFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclarationOfFriendship))
                        diplo.signDeclarationOfFriendship()
                SideAgreement.DefensivePact -> {
                    // No first-class defensive pact API in this codebase — skip silently.
                }
            }
        }
    }

    private fun notifyParties(offer: TerritoryTradeOffer) {
        val tilesCount = offer.offered.tiles.size + offer.requested.tiles.size
        val citiesCount = offer.offered.cities.size + offer.requested.cities.size
        val summary = "Territory trade with [${offer.toCiv.civName}]: " +
                      "$tilesCount tiles, $citiesCount cities, " +
                      "${offer.offered.gold - offer.requested.gold} net gold"
        offer.fromCiv.addNotification(summary, NotificationCategory.Diplomacy, NotificationIcon.Diplomacy)
        val mirrorSummary = "Territory trade with [${offer.fromCiv.civName}]: " +
                            "$tilesCount tiles, $citiesCount cities, " +
                            "${offer.requested.gold - offer.offered.gold} net gold"
        offer.toCiv.addNotification(mirrorSummary, NotificationCategory.Diplomacy, NotificationIcon.Diplomacy)
    }
}

private fun com.unciv.logic.civilization.diplomacy.DiplomacyManager.setOpenBorders(value: Boolean) {
    if (this.hasOpenBorders == value) return
    this.hasOpenBorders = value
}
