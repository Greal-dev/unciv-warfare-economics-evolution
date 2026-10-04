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

    /** Posture for a ratio of own force to the force expected in front, and the health of the division. */
    fun chooseStance(ratio: Float, health: Int, inContact: Boolean): FrontStance = when {
        !inContact && health < 50 -> FrontStance.Defensive // rest and be reinforced
        ratio > 2f -> FrontStance.Aggressive
        ratio > 1.2f -> FrontStance.Moderate
        ratio >= 0.8f -> FrontStance.Defensive
        health < 40 -> FrontStance.Withdrawal
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
        val stance = chooseStance(own / expected, division.health, contact)
        division.frontStance = stance.name

        if (!contact && stance != FrontStance.Defensive) advanceTowardEnemy(division)
    }

    private fun advanceTowardEnemy(division: MapUnit) {
        val civ = division.civ
        val target: Tile = civ.getKnownCivs().filter { civ.isAtWarWith(it) }
            .flatMap { it.cities }
            .map { it.getCenterTile() }
            .minWithOrNull(compareBy<Tile>({ it.aerialDistanceTo(division.currentTile) }, { it.position.x }, { it.position.y }))
            ?: return
        division.movement.headTowards(target)
    }
}
