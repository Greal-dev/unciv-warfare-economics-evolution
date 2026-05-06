package com.unciv.logic.diplomacy.coalition

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import java.util.UUID

/** Orchestration of coalition lifecycle. Stateless object; all state lives on
 *  [GameInfo.coalitions], [GameInfo.coalitionProposals] and
 *  [GameInfo.coalitionLeaderCooldowns].
 */
object CoalitionManager {

    /** How many turns invitees have to respond before pending = refused. */
    const val NEGOTIATION_TURNS = 3
    /** How long a leader must wait before re-proposing against the same target after a failed attempt. */
    const val LEADER_COOLDOWN_TURNS = 20
    /** Opinion bonus the target gains toward refusants ("they refused to gang up on us"). */
    const val REFUSAL_OPINION_BONUS = 20f

    /** Player UI entry point — register the proposal and dispatch invitations.
     *  Returns the created proposal, or null if validation fails. */
    fun submitProposal(
        leader: Civilization,
        target: Civilization,
        invitees: List<Civilization>,
        bribesByInvitee: Map<String, Int>
    ): CoalitionProposal? {
        if (leader == target) return null
        if (leader.isAtWarWith(target)) return null
        if (invitees.isEmpty()) return null
        if (isLeaderOnCooldown(leader, target)) return null

        val totalBribe = bribesByInvitee.values.sum()
        if (totalBribe > leader.gold) return null
        if (totalBribe > 0) leader.addGold(-totalBribe)

        val game = leader.gameInfo
        val proposal = CoalitionProposal(
            id = UUID.randomUUID().toString(),
            leader = leader.civName,
            target = target.civName,
            invitees = invitees.map { it.civName },
            bribes = bribesByInvitee,
            currentTurn = game.turns,
            deadlineDelay = NEGOTIATION_TURNS
        )
        game.coalitionProposals.add(proposal)

        for (invitee in invitees) {
            invitee.popupAlerts.add(
                PopupAlert(
                    AlertType.JoinCoalitionRequest,
                    CoalitionPayload.encode(proposal.id, invitee.civName)
                )
            )
        }
        return proposal
    }

    /** AI-side evaluation. Called from the per-civ automation loop; the AI
     *  invitee inspects every pending proposal directed at it and records a
     *  response. Humans skip — they respond via the alert popup. */
    fun evaluateForAi(invitee: Civilization) {
        if (invitee.isHuman()) return
        val game = invitee.gameInfo
        for (proposal in game.coalitionProposals.toList()) {
            val status = proposal.responses[invitee.civName] ?: continue
            if (status != CoalitionResponseStatus.Pending) continue
            val leader = game.getCivilizationOrNull(proposal.leaderCivName) ?: continue
            val target = game.getCivilizationOrNull(proposal.targetCivName) ?: continue
            val bribe = proposal.bribesByInvitee[invitee.civName] ?: 0
            val accepts = CoalitionEvaluator.decideAccept(invitee, leader, target, bribe)
            recordResponse(game, proposal, invitee.civName, accepts)
            // also drain the popup alert (it's targeted at this AI civ)
            invitee.popupAlerts.removeAll { it.type == AlertType.JoinCoalitionRequest &&
                CoalitionPayload.decode(it.value)?.proposalId == proposal.id }
        }
    }

    /** Record an invitee's response. Pays / refunds bribes and applies the
     *  refusal opinion bonus to the target's diplomacy with the refusant. */
    fun recordResponse(game: GameInfo, proposal: CoalitionProposal, inviteeName: String, accepted: Boolean) {
        proposal.responses[inviteeName] =
            if (accepted) CoalitionResponseStatus.Accepted else CoalitionResponseStatus.Refused

        val invitee = game.getCivilizationOrNull(inviteeName)
        val leader = game.getCivilizationOrNull(proposal.leaderCivName)
        val target = game.getCivilizationOrNull(proposal.targetCivName)
        val bribe = proposal.bribesByInvitee[inviteeName] ?: 0

        if (bribe > 0) {
            if (accepted && invitee != null) invitee.addGold(bribe)
            else if (!accepted && leader != null) leader.addGold(bribe)
        }

        if (!accepted && target != null && invitee != null) {
            target.getDiplomacyManager(invitee)?.addModifier(
                DiplomaticModifiers.RefusedToJoinWarAgainstUs,
                REFUSAL_OPINION_BONUS
            )
        }
    }

    /** Per-turn tick — called from the AI automation pipeline. Activates
     *  proposals once all responses are in OR the deadline is reached. */
    fun processProposals(game: GameInfo) {
        val toRemove = mutableListOf<CoalitionProposal>()
        for (proposal in game.coalitionProposals) {
            val allResponded = proposal.responses.values.all { it != CoalitionResponseStatus.Pending }
            val expired = game.turns >= proposal.deadlineTurn
            if (!allResponded && !expired) continue

            if (expired) {
                // Pending invitees count as refused; refund their bribes.
                for ((name, status) in proposal.responses.toMap()) {
                    if (status == CoalitionResponseStatus.Pending) {
                        recordResponse(game, proposal, name, accepted = false)
                    }
                }
            }
            activateOrCancel(game, proposal)
            toRemove.add(proposal)
        }
        game.coalitionProposals.removeAll(toRemove)
    }

    private fun activateOrCancel(game: GameInfo, proposal: CoalitionProposal) {
        val leader = game.getCivilizationOrNull(proposal.leaderCivName) ?: return
        val target = game.getCivilizationOrNull(proposal.targetCivName) ?: return
        val acceptedNames = proposal.responses.entries
            .filter { it.value == CoalitionResponseStatus.Accepted }
            .map { it.key }

        if (acceptedNames.isEmpty()) {
            setLeaderCooldown(leader, target, game.turns)
            leader.addNotification(
                "Our coalition proposal against [${target.civName}] failed: nobody accepted.",
                NotificationCategory.Diplomacy, NotificationIcon.Diplomacy
            )
            return
        }

        val coalition = Coalition(
            leader = leader.civName,
            members = (listOf(leader.civName) + acceptedNames).toSet(),
            target = target.civName,
            turn = game.turns
        )
        game.coalitions.add(coalition)

        // Simultaneous war declaration. canDeclareWar may reject if a recent peace truce is active.
        val warriors = listOf(leader) + acceptedNames.mapNotNull { game.getCivilizationOrNull(it) }
        for (civ in warriors) {
            val diplo = civ.getDiplomacyManager(target) ?: continue
            if (diplo.canDeclareWar()) diplo.declareWar()
        }

        val totalCount = warriors.size
        for (civ in (warriors + listOf(target)).distinct()) {
            civ.addNotification(
                "A coalition led by [${leader.civName}] against [${target.civName}] is formed ([$totalCount] civs).",
                NotificationCategory.Diplomacy, NotificationIcon.War
            )
        }
    }

    /** Hook from [com.unciv.logic.civilization.diplomacy.DiplomacyManager.makePeace].
     *  Removes a member that signed a separate peace; if the leader signed,
     *  dissolve the coalition entirely. */
    fun onPeaceMade(civA: Civilization, civB: Civilization) {
        val game = civA.gameInfo
        val toDissolve = mutableListOf<Coalition>()
        for (coalition in game.coalitions) {
            val signer: String
            when {
                coalition.targetCivName == civA.civName && coalition.memberCivNames.contains(civB.civName) ->
                    signer = civB.civName
                coalition.targetCivName == civB.civName && coalition.memberCivNames.contains(civA.civName) ->
                    signer = civA.civName
                else -> continue
            }
            if (signer == coalition.leaderCivName) {
                toDissolve.add(coalition)
                continue
            }
            coalition.memberCivNames.remove(signer)
            if (coalition.memberCivNames.isEmpty()) toDissolve.add(coalition)
        }
        for (c in toDissolve) {
            game.coalitions.remove(c)
            val leaderCiv = game.getCivilizationOrNull(c.leaderCivName)
            leaderCiv?.addNotification(
                "The coalition you led against [${c.targetCivName}] has dissolved.",
                NotificationCategory.Diplomacy, NotificationIcon.Diplomacy
            )
        }
    }

    /** Active coalitions involving this civ, regardless of side (leader, member, or target). */
    fun coalitionsInvolving(civ: Civilization): List<Coalition> {
        return civ.gameInfo.coalitions.filter {
            it.leaderCivName == civ.civName ||
            it.memberCivNames.contains(civ.civName) ||
            it.targetCivName == civ.civName
        }
    }

    fun isLeaderOnCooldown(leader: Civilization, target: Civilization): Boolean {
        val perLeader = leader.gameInfo.coalitionLeaderCooldowns[leader.civName] ?: return false
        val until = perLeader[target.civName] ?: return false
        return leader.gameInfo.turns < until
    }

    private fun setLeaderCooldown(leader: Civilization, target: Civilization, currentTurn: Int) {
        val perLeader = leader.gameInfo.coalitionLeaderCooldowns
            .getOrPut(leader.civName) { HashMap() }
        perLeader[target.civName] = currentTurn + LEADER_COOLDOWN_TURNS
    }
}
