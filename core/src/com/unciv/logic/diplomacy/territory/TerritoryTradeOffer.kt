package com.unciv.logic.diplomacy.territory

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import yairm210.purity.annotations.Readonly

/** What a side puts on the trade table. */
data class TradePackage(
    val tiles: List<Tile> = emptyList(),
    val cities: List<City> = emptyList(),
    val gold: Int = 0,
    val sideAgreements: Set<SideAgreement> = emptySet()
)

enum class SideAgreement {
    OpenBorders,
    PeaceTreaty,
    DefensivePact,
    DeclarationOfFriendship
}

/** A complete trade proposal: who proposes, who receives, what each side offers. */
data class TerritoryTradeOffer(
    val fromCiv: Civilization,
    val toCiv: Civilization,
    val offered: TradePackage,
    val requested: TradePackage
) {
    /** True if the proposer offers nothing meaningful (an ultimatum). */
    @Readonly
    fun isUltimatum(): Boolean {
        val valueOffered = TerritoryTradeOffer.valueOfPackage(offered, toCiv)
        val valueRequested = TerritoryTradeOffer.valueOfPackage(requested, fromCiv)
        if (valueRequested <= 0f) return false
        return valueOffered < ULTIMATUM_THRESHOLD * valueRequested
    }

    companion object {
        const val ULTIMATUM_THRESHOLD = 0.1f
        const val GOLD_VALUE_PER_UNIT = 0.5f
        const val CITY_VALUE_MULTIPLIER = 8f

        @Readonly
        fun valueOfPackage(pkg: TradePackage, evaluator: Civilization): Float {
            var total = 0f
            for (tile in pkg.tiles) total += TileValuation.value(tile, evaluator)
            for (city in pkg.cities) total += valueOfCity(city, evaluator)
            total += pkg.gold * GOLD_VALUE_PER_UNIT
            for (agreement in pkg.sideAgreements) total += valueOfAgreement(agreement, evaluator)
            return total
        }

        @Readonly
        private fun valueOfCity(city: City, evaluator: Civilization): Float {
            var sum = 0f
            for (tile in city.getTiles()) sum += TileValuation.value(tile, evaluator)
            return sum * CITY_VALUE_MULTIPLIER
        }

        @Readonly
        private fun valueOfAgreement(agreement: SideAgreement, evaluator: Civilization): Float =
            when (agreement) {
                SideAgreement.OpenBorders -> 30f
                SideAgreement.PeaceTreaty -> 100f
                SideAgreement.DefensivePact -> 60f
                SideAgreement.DeclarationOfFriendship -> 20f
            }
    }
}
