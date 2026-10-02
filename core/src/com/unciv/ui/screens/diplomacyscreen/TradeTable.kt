package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeOffersList
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.CivView
import com.unciv.view.ForeignCivView

class TradeTable(
    private val civ: CivView,
    private val otherCivilization: ForeignCivView,
    diplomacyScreen: DiplomacyScreen
): Table(BaseScreen.skin) {
    internal val tradeView = civ.getTradeView(otherCivilization)
    internal val offerColumnsTable = OfferColumnsTable(tradeView, diplomacyScreen, civ, otherCivilization) { onChange() }
    // This is so that after a trade has been traded, we can switch out the offersToDisplay to start anew - this is the easiest way
    private val offerColumnsTableWrapper = Table()

    val offerTradeText = "{Offer trade}\n({They'll decide on their turn})"
    private val offerButton = offerTradeText.toTextButton()

    // TW v2 — auto-suggest the optimal gold (lump + per-turn) terms based on AI acceptance gate.
    // If the current non-monetary offer would be accepted, fill the maximum gold we can extract
    // from them. If it would be refused, fill the minimum we'd need to pay for them to accept.
    private val optimalTermsButton = "Optimal gold terms".toTextButton()

    private fun isTradeOffered() = tradeView.hasPendingOfferFromUs()


    private fun retractOffer() {
        tradeView.tryRetractOffer()
        offerButton.setText(offerTradeText.tr())
    }

    init {
        offerColumnsTableWrapper.add(offerColumnsTable)
        add(offerColumnsTableWrapper).row()

        val lowerTable = Table().apply { defaults().pad(10f) }

        if (tradeView.tryLoadOurPendingOffer())
            offerColumnsTable.update()

        if (tradeView.hasPendingOfferFromUs()) offerButton.setText("Retract offer".tr())
        else offerButton.apply { isEnabled = false }.setText(offerTradeText.tr())

        offerButton.onClick {
            if (tradeView.hasPendingOfferFromUs()) {
                retractOffer()
                return@onClick
            }
            // If there is a research agreement trade, make sure both civilizations should be able to pay for it.
            // If not lets add an extra gold offer to satisfy this.
            // There must be enough gold to add to the offer to satisfy this, otherwise the research agreement button would be disabled
            if (tradeView.ourStagedOffers().any { it.name == Constants.researchAgreement}) {
                val researchCost = civ.getResearchAgreementCost(otherCivilization)
                val currentPlayerOfferedGold = tradeView.ourStagedOffers().firstOrNull { it.type == TradeOfferType.Gold }?.amount ?: 0
                val otherCivOfferedGold = tradeView.theirStagedOffers().firstOrNull { it.type == TradeOfferType.Gold }?.amount ?: 0
                val newCurrentPlayerGold = civ.gold + otherCivOfferedGold - researchCost
                val newOtherCivGold = otherCivilization.gold + currentPlayerOfferedGold - researchCost
                // Check if we require more gold from them
                if (newCurrentPlayerGold < 0) {
                    offerColumnsTable.addOffer( tradeView.theirAvailableOffers().first { it.type == TradeOfferType.Gold }
                            .copy(amount = -newCurrentPlayerGold), tradeView.theirStagedOffers(), tradeView.ourStagedOffers())
                }
                // Check if they require more gold from us
                if (newOtherCivGold < 0) {
                    offerColumnsTable.addOffer( tradeView.ourAvailableOffers().first { it.type == TradeOfferType.Gold }
                            .copy(amount = -newOtherCivGold), tradeView.ourStagedOffers(), tradeView.theirStagedOffers())
                }
            }

            tradeView.tryProposeStagedTrade()
            offerButton.setText("Retract offer".tr())
        }

        lowerTable.add(offerButton)

        optimalTermsButton.onClick { suggestOptimalGoldTerms() }
        lowerTable.add(optimalTermsButton)

        lowerTable.pack()
        lowerTable.y = 10f
        add(lowerTable)
        pack()
    }

    /** TW v2 — Auto-fill the lump-sum + per-turn gold on the trade so the AI is exactly at the
     *  edge of acceptance:
     *   - if the current non-monetary terms would already be accepted, demand the maximum gold
     *     the AI can still afford to give while accepting (added to their offers);
     *   - if the current terms would be refused, fill the minimum gold we'd need to pay for them
     *     to accept (added to our offers).
     *  Lump fills first (capped by the payer's treasury), the rest spills into per-turn gold
     *  (capped by the payer's income). */
    private fun suggestOptimalGoldTerms() {
        val trade = tradeView.tradeLogic.currentTrade

        // Probe: strip any existing gold/GPT on both sides so the margin reflects ONLY the
        // non-monetary value of the deal.
        val probe = trade.clone()
        probe.ourOffers.removeAll { it.type == TradeOfferType.Gold || it.type == TradeOfferType.Gold_Per_Turn }
        probe.theirOffers.removeAll { it.type == TradeOfferType.Gold || it.type == TradeOfferType.Gold_Per_Turn }

        // From otherCiv's perspective: reverse the probe (otherCiv is the evaluator).
        val margin = TradeEvaluation().getTradeAcceptability(
            probe.reverse(), otherCivilization.getCiv(), civ.getCiv(), includeDiplomaticGifts = true
        )

        // Clear existing monetary offers on the live trade before refilling.
        trade.ourOffers.removeAll { it.type == TradeOfferType.Gold || it.type == TradeOfferType.Gold_Per_Turn }
        trade.theirOffers.removeAll { it.type == TradeOfferType.Gold || it.type == TradeOfferType.Gold_Per_Turn }

        when {
            margin > 0 -> {
                // They'd accept the deal — we can extract up to `margin` more gold from them.
                fillOptimalGold(
                    trade.theirOffers,
                    tradeView.tradeLogic.theirAvailableOffers,
                    target = margin,
                    lumpCap = otherCivilization.gold.coerceAtLeast(0),
                    gptCap = otherCivilization.getGoldPerTurn().coerceAtLeast(0)
                )
            }
            margin < 0 -> {
                // They'd refuse — we need to pay at least `-margin` for them to accept.
                fillOptimalGold(
                    trade.ourOffers,
                    tradeView.tradeLogic.ourAvailableOffers,
                    target = -margin,
                    lumpCap = civ.gold.coerceAtLeast(0),
                    gptCap = civ.getGoldPerTurn().coerceAtLeast(0)
                )
            }
            // margin == 0 → white peace exactly at the boundary; nothing to add.
        }

        offerColumnsTable.update()
        retractOffer()  // any previously-sent offer is now stale
        offerButton.isEnabled = !(trade.theirOffers.size == 0 && trade.ourOffers.size == 0)
    }

    /** Greedy split: fill lump-sum gold first (up to the payer's treasury), spill the
     *  remainder into per-turn gold (up to the payer's net income). Both fields land in
     *  [targetList]; templates are pulled from [availableOffers]. */
    private fun fillOptimalGold(
        targetList: TradeOffersList,
        availableOffers: TradeOffersList,
        target: Int,
        lumpCap: Int,
        gptCap: Int
    ) {
        if (target <= 0) return
        val goldTemplate = availableOffers.firstOrNull { it.type == TradeOfferType.Gold }
        val gptTemplate = availableOffers.firstOrNull { it.type == TradeOfferType.Gold_Per_Turn }

        val lump = minOf(target, lumpCap)
        if (lump > 0 && goldTemplate != null) {
            targetList.add(goldTemplate.copy(amount = lump))
        }

        val remaining = target - lump
        if (remaining > 0 && gptTemplate != null && gptCap > 0) {
            // Per-turn gold is valued at `amount * duration * 4/5` by the AI (see
            // TradeEvaluation.evaluateBuyCost). Convert remaining target-gold back into per-turn.
            val gptValuePerTurn = (gptTemplate.duration * 4 / 5).coerceAtLeast(1)
            val gpt = minOf(remaining / gptValuePerTurn, gptCap)
            if (gpt > 0) {
                targetList.add(gptTemplate.copy(amount = gpt))
            }
        }
    }

    private fun onChange() {
        offerColumnsTable.update()
        retractOffer()
        offerButton.isEnabled = !(tradeView.theirStagedOffers().size == 0 && tradeView.ourStagedOffers().size == 0)
    }

    fun enableOfferButton(isEnabled: Boolean) {
        offerButton.isEnabled = isEnabled
    }
}
