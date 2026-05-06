package com.unciv.logic.diplomacy.coalition

/** Compact payload encoded into [com.unciv.logic.civilization.PopupAlert.value]
 *  so a coalition invitation can survive serialization and be addressed to the
 *  correct invitee when the popup is rendered.
 *
 *  Format: `<proposalId>|<inviteeCivName>`
 */
object CoalitionPayload {
    fun encode(proposalId: String, inviteeName: String): String =
        "$proposalId|$inviteeName"

    data class Decoded(val proposalId: String, val inviteeName: String)

    fun decode(payload: String): Decoded? {
        val parts = payload.split("|")
        if (parts.size < 2) return null
        return Decoded(parts[0], parts[1])
    }
}
