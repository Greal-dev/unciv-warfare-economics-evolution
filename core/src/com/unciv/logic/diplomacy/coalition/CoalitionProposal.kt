package com.unciv.logic.diplomacy.coalition

import com.unciv.logic.IsPartOfGameInfoSerialization

enum class CoalitionResponseStatus : IsPartOfGameInfoSerialization {
    Pending, Accepted, Refused
}

/** In-flight coalition proposal awaiting responses. Lives in
 *  [com.unciv.logic.GameInfo.coalitionProposals] until resolved (activation,
 *  cancellation, or expiry). Bribe gold is escrowed in the leader's gold
 *  account at submission time and paid out / refunded as responses arrive.
 */
class CoalitionProposal() : IsPartOfGameInfoSerialization {
    var id: String = ""
    var leaderCivName: String = ""
    var targetCivName: String = ""
    var inviteeCivNames: ArrayList<String> = ArrayList()
    /** Bribe (one-shot gold) offered to each invitee by name. */
    var bribesByInvitee: HashMap<String, Int> = HashMap()
    var responses: HashMap<String, CoalitionResponseStatus> = HashMap()
    var createdTurn: Int = 0
    var deadlineTurn: Int = 0

    constructor(
        id: String,
        leader: String,
        target: String,
        invitees: List<String>,
        bribes: Map<String, Int>,
        currentTurn: Int,
        deadlineDelay: Int
    ) : this() {
        this.id = id
        this.leaderCivName = leader
        this.targetCivName = target
        this.inviteeCivNames = ArrayList(invitees)
        this.bribesByInvitee = HashMap(bribes)
        for (name in invitees) responses[name] = CoalitionResponseStatus.Pending
        this.createdTurn = currentTurn
        this.deadlineTurn = currentTurn + deadlineDelay
    }
}
