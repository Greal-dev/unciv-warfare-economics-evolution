package com.unciv.logic.diplomacy.coalition

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.ui.screens.victoryscreen.RankingType

/** Decides whether an AI invitee should accept a coalition request. The decision
 *  is a single scalar score; positive = accept. The score blends:
 *    -opinion(invitee → target)               (the more the invitee dislikes the target, the more they accept)
 *    +0.5 × opinion(invitee → leader)         (trust in the proposer)
 *    +bribe / target.force                    (bribe normalized by the target's military power)
 *    −20 × (4 − era).coerceAtLeast(0)         (early-game civs lack expeditionary capacity)
 *    −20 × ongoing wars                       (overstretched civs decline)
 *    hard refuse if the target is the invitee's vassal or current ally
 */
object CoalitionEvaluator {

    private const val ACCEPT_THRESHOLD = 0f

    fun computeScore(invitee: Civilization, leader: Civilization, target: Civilization, bribeGold: Int): Float {
        if (invitee == target) return -1000f
        if (invitee.isAtWarWith(target)) return -1000f
        if (target.isVassal() && target.vassalOf == invitee.civName) return -1000f
        if (invitee.isVassal() && invitee.vassalOf == target.civName) return -1000f

        val diploTarget = invitee.getDiplomacyManager(target) ?: return -1000f
        val diploLeader = invitee.getDiplomacyManager(leader) ?: return -1000f

        var score = 0f
        score -= diploTarget.opinionOfOtherCiv().toFloat()
        score += 0.5f * diploLeader.opinionOfOtherCiv().toFloat()

        val targetForce = target.getStatForRanking(RankingType.Force).toFloat().coerceAtLeast(1f)
        score += bribeGold / (targetForce / 100f).coerceAtLeast(1f)

        val eraPenalty = ((4 - invitee.getEraNumber()).coerceAtLeast(0)) * 20f
        score -= eraPenalty

        val ongoingWars = invitee.diplomacy.values.count {
            it.diplomaticStatus == DiplomaticStatus.War && !it.otherCiv.isDefeated()
        }
        score -= 20f * ongoingWars

        return score
    }

    fun decideAccept(invitee: Civilization, leader: Civilization, target: Civilization, bribeGold: Int): Boolean {
        return computeScore(invitee, leader, target, bribeGold) > ACCEPT_THRESHOLD
    }
}
