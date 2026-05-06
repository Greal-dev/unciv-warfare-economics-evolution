package com.unciv.logic.diplomacy.coalition

import com.unciv.logic.IsPartOfGameInfoSerialization

/** Persisted record of a formed coalition. Tracks the leader, accepted members
 *  (including the leader), the shared target, and the formation turn. Used at
 *  peace time to decide whether to dissolve the coalition (leader peace) or
 *  simply remove a member who signed a separate peace.
 */
class Coalition() : IsPartOfGameInfoSerialization {
    var leaderCivName: String = ""
    var memberCivNames: HashSet<String> = HashSet()
    var targetCivName: String = ""
    var formedTurn: Int = 0

    constructor(leader: String, members: Set<String>, target: String, turn: Int) : this() {
        this.leaderCivName = leader
        this.memberCivNames = HashSet(members)
        this.targetCivName = target
        this.formedTurn = turn
    }
}
