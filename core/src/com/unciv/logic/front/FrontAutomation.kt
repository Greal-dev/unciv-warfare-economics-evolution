package com.unciv.logic.front

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/**
 * Front mode: AI v0 for divisions. It picks a posture from the estimated balance of forces
 * and walks the token toward the closest enemy city when nothing is in contact.
 */
object FrontAutomation {
    /** Radius around the token in which enemy divisions count toward the estimated balance. */
    private const val SCOUT_RADIUS = 3

    /** Health below which a division in contact is relieved: it falls back to be reinforced. */
    const val RELIEVE_BELOW = 45
    /** Health a resting division regains before it returns to the front, so it does not oscillate. */
    const val REST_UNTIL = 80
    /** Health under which a division no longer starts an aggressive offensive nor marches out of contact. */
    const val FIT_FOR_OFFENSIVE = 60
    /** Radius in which a fresh division looks for a weakened comrade to relieve. */
    private const val RELIEF_RADIUS = 6

    /**
     * Posture for a ratio of own force to the force expected in front, and the health of the division.
     * @param previous posture of the previous round, used to rest until [REST_UNTIL] once out of contact
     */
    fun chooseStance(ratio: Float, health: Int, inContact: Boolean, previous: FrontStance = FrontStance.Defensive): FrontStance = when {
        inContact && health < RELIEVE_BELOW -> FrontStance.Withdrawal
        !inContact && health < FIT_FOR_OFFENSIVE -> FrontStance.Defensive // rest and be reinforced
        !inContact && health < REST_UNTIL && !previous.presses -> FrontStance.Defensive
        ratio > 2f && health >= FIT_FOR_OFFENSIVE -> FrontStance.Aggressive
        ratio > 1.2f -> FrontStance.Moderate
        else -> FrontStance.Defensive
    }

    fun automate(division: MapUnit) {
        val civ = division.civ
        if (!civ.isAtWar()) {
            division.frontStance = FrontStance.Defensive.name
            return
        }
        val era = civ.getEraNumber()
        val neighbourhood = division.currentTile.getTilesInDistance(SCOUT_RADIUS).mapNotNull { it.militaryUnit }
            .filter { FrontResolver.isDivision(it) }.toList()
        // Own force counts the friendly divisions of the neighbourhood: they cover the same front
        val own = neighbourhood.filter { it.civ == civ }
            .sumOf { (FrontMath.baseStrength(era) * it.health / 100f).toDouble() }.toFloat()
        val enemies = neighbourhood.filter { civ.isAtWarWith(it.civ) }
        val expected = if (enemies.isEmpty()) FrontMath.BASE_RESISTANCE
            // An enemy division that holds its line resists with its posture and its entrenchment
            else enemies.sumOf {
                FrontMath.resistance(it.health, it.civ.getEraNumber(), FrontResolver.stanceOf(it), it.frontEntrenchment).toDouble()
            }.toFloat()

        val contact = division.currentTile.getTilesInDistance(FrontMath.ZONE_RADIUS)
            .any { FrontResolver.isContactTile(it, civ) }
        val stance = chooseStance(own / expected, division.health, contact, FrontResolver.stanceOf(division))
        division.frontStance = stance.name

        if (!contact && stance != FrontStance.Defensive) advanceTowardEnemy(division)
        else if (stance == FrontStance.Defensive && division.health >= REST_UNTIL) relieveWeakComrade(division)
    }

    /** A fresh division on hold walks toward the closest weakened comrade in contact, to take over its front. */
    private fun relieveWeakComrade(division: MapUnit) {
        val civ = division.civ
        val here = division.currentTile
        val weak = here.getTilesInDistance(RELIEF_RADIUS).mapNotNull { it.militaryUnit }
            .filter { it != division && it.civ == civ && FrontResolver.isDivision(it) && it.health < REST_UNTIL &&
                it.currentTile.getTilesInDistance(FrontMath.ZONE_RADIUS).any { tile -> FrontResolver.isContactTile(tile, civ) } }
            .minWithOrNull(compareBy({ it.currentTile.aerialDistanceTo(here) }, { it.currentTile.position.x }, { it.currentTile.position.y }))
            ?: return
        if (weak.currentTile.aerialDistanceTo(here) <= 1) return
        moveToward(division, weak.currentTile)
    }

    private fun advanceTowardEnemy(division: MapUnit) {
        val civ = division.civ
        // Head for the enemy territory closest to the token, so a defended city centre is never the destination
        val here = division.currentTile
        val enemies = civ.getKnownCivs().filter { civ.isAtWarWith(it) }.toList()
        val target: Tile = enemies.flatMap { it.cities }.flatMap { it.getTiles() }
            .filter { !it.isCityCenter() && it.militaryUnit == null }
            .minWithOrNull(compareBy<Tile>({ it.aerialDistanceTo(here) }, { it.position.x }, { it.position.y }))
            ?: return
        moveToward(division, target)
    }

    /** An occupied or unreachable destination, such as a defended city centre, is not an error for an AI move. */
    private fun moveToward(division: MapUnit, target: Tile) {
        try {
            division.movement.headTowards(target)
        } catch (_: com.unciv.logic.map.mapunit.movement.UnitMovement.UnreachableDestinationException) {
            // stay where we are, the next round will try again
        }
    }
}
