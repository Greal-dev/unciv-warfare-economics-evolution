package com.unciv.logic.front

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/**
 * Front mode: AI for divisions. During its turn a division attacks the best tile in reach when the attack
 * is sure to succeed at an acceptable cost, otherwise it holds, rests, relieves a weakened comrade or walks
 * toward the closest enemy territory. The posture it keeps for the adversary's turn follows the balance of forces.
 */
object FrontAutomation {
    /** Radius around the token in which enemy divisions count toward the estimated balance. */
    private const val SCOUT_RADIUS = 3

    /** Health below which a division in contact is relieved: it falls back to be reinforced. */
    const val RELIEVE_BELOW = 45
    /** Health a resting division regains before it returns to the front, so it does not oscillate. */
    const val REST_UNTIL = 80
    /** Health under which a division no longer attacks. */
    const val FIT_FOR_OFFENSIVE = 60
    /** Health points an attacking division must keep above the dissolution threshold, whatever the attack costs. */
    private const val SAFETY_MARGIN = 10
    /** Radius in which a fresh division looks for a weakened comrade to relieve. */
    private const val RELIEF_RADIUS = 6

    /**
     * Posture kept for the adversary's turn, from the ratio of own force to the force expected in front,
     * and the health of the division. Mobile divisions cannot hold a line: when outmatched in contact they fall back.
     */
    fun chooseReaction(ratio: Float, health: Int, inContact: Boolean, kind: DivisionKind = DivisionKind.Infantry): FrontStance = when {
        inContact && health < RELIEVE_BELOW -> FrontStance.Withdrawal
        kind.isMobile && inContact && ratio < 1.0f -> FrontStance.Withdrawal
        else -> FrontStance.Defensive
    }

    /**
     * The attack a division should launch, if any: the first target in order of priority for which some intensity
     * is sure to succeed (the cheaper intensity first) without leaving the division too weak.
     */
    fun bestAttack(division: MapUnit): FrontResolver.AttackPreview? {
        if (!FrontResolver.canAttack(division) || division.health < FIT_FOR_OFFENSIVE) return null
        for (tile in FrontResolver.attackTargets(division))
            for (intensity in listOf(FrontStance.Moderate, FrontStance.Aggressive)) {
                val preview = FrontResolver.previewAttack(division, tile, intensity) ?: continue
                if (preview.succeeds && division.health - preview.attackerLoss > FrontMath.DISSOLVE_HEALTH + SAFETY_MARGIN)
                    return preview
            }
        return null
    }

    fun automate(division: MapUnit) {
        val civ = division.civ
        if (!civ.isAtWar()) {
            division.frontStance = FrontStance.Defensive.name
            return
        }
        val level = FrontResolver.militaryLevel(civ)
        val neighbourhood = division.currentTile.getTilesInDistance(SCOUT_RADIUS).mapNotNull { it.militaryUnit }
            .filter { FrontResolver.isDivision(it) }.toList()
        // Own force counts the friendly divisions of the neighbourhood: they cover the same front
        val own = neighbourhood.filter { it.civ == civ }
            .sumOf { (FrontMath.baseStrength(level) * it.health / 100f).toDouble() }.toFloat()
        val enemies = neighbourhood.filter { civ.isAtWarWith(it.civ) }
        val expected = if (enemies.isEmpty()) FrontMath.BASE_RESISTANCE
            // An enemy division that holds its line resists with its posture and its entrenchment
            else enemies.sumOf {
                FrontMath.resistance(it.health, FrontResolver.militaryLevel(it.civ), FrontResolver.reactionOf(it), it.frontEntrenchment).toDouble()
            }.toFloat()

        val contact = FrontResolver.isInContact(division)
        division.frontStance = chooseReaction(own / expected, division.health, contact, FrontResolver.kindOf(division)).name

        val attack = bestAttack(division)
        when {
            attack != null -> FrontResolver.attack(division, attack.tile, attack.intensity)
            contact && division.health < RELIEVE_BELOW -> retreat(division)
            division.health < FIT_FOR_OFFENSIVE -> Unit // rest and be reinforced
            !contact -> advanceTowardEnemy(division)
            division.health >= REST_UNTIL -> relieveWeakComrade(division)
        }
    }

    /** A weak division in contact walks back toward its closest city, to be reinforced there. */
    private fun retreat(division: MapUnit) {
        val here = division.currentTile
        val city = division.civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(here) } ?: return
        moveToward(division, city.getCenterTile())
    }

    /** A fresh division holding the line walks toward the closest weakened comrade in contact, to take over its front. */
    private fun relieveWeakComrade(division: MapUnit) {
        val civ = division.civ
        val here = division.currentTile
        val weak = here.getTilesInDistance(RELIEF_RADIUS).mapNotNull { it.militaryUnit }
            .filter { it != division && it.civ == civ && FrontResolver.isDivision(it) && it.health < REST_UNTIL && FrontResolver.isInContact(it) }
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
