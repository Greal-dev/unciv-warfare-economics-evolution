package com.unciv.logic.front

import kotlin.math.min
import yairm210.purity.annotations.Readonly

/**
 * Front mode: the formulas, kept free of any game state so they can be tested and calibrated on their own.
 * See docs/superpowers/specs/2026-10-02-mode-front-tranche-verticale-design.md, section 4.
 */
object FrontMath {
    /** Hex distance around the token that its zone covers */
    const val ZONE_RADIUS = 2
    /** Resistance of an uncovered tile, the militia of a territory without divisions */
    const val BASE_RESISTANCE = 6f
    /** Ratio of attack force to resistance from which an attack succeeds (the tile is taken or the defenders give way): the attacker needs a clear edge */
    const val VICTORY_RATIO = 1.2f
    /** Share of its pressure that a division in the zone of the target adds to an attack it does not lead */
    const val SUPPORT_SHARE = 0.5f
    /** Health points a defender loses when it has to give way and has nowhere to fall back */
    const val CORNERED_LOSS = 15
    /** Defence bonus of a city centre for the divisions that hold it, as decided for the fork: +200%. */
    const val CITY_DEFENSE_BONUS = 2f
    /** Citizens a city keeps at work: divisions are raised from the rest of its population. */
    const val MIN_CITIZENS_AT_WORK = 1
    /** Health points lost by each side in one attack between equal forces, with moderate coefficients */
    const val BASE_LOSS = 12f
    /** Entrenchment gained per round spent holding still */
    const val ENTRENCHMENT_PER_ROUND = 0.05f
    /** Health at or below which a division breaks up */
    const val DISSOLVE_HEALTH = 10
    const val REINFORCE_IN_SUPPLY = 10
    const val REINFORCE_IN_CONTACT = 3
    /** Largest effective supply distance at which a division is reinforced */
    const val SUPPLY_DISTANCE_FOR_REINFORCEMENT = 4f

    /** Military level reached when a civilization owns every military technology of its ruleset. */
    const val MAX_MILITARY_LEVEL = 7f

    /** Military level from the share of military technologies researched: a gradual climb from 0 to [MAX_MILITARY_LEVEL]. */
    @Readonly fun militaryLevel(researched: Int, total: Int): Float =
        if (total <= 0) 0f else MAX_MILITARY_LEVEL * researched.coerceAtMost(total) / total

    /** Strength of a full-health division, growing with the military technologies its civilization owns. */
    @Readonly fun baseStrength(militaryLevel: Float): Float = 20f * (1f + 0.25f * militaryLevel)

    /** Largest army the AI keeps: two divisions per city and one to start with. */
    @Readonly fun maxDivisions(cityCount: Int): Int = 1 + 2 * cityCount

    /** Supply factor from the effective distance to the nearest own city (halved on roads, see CombatCostCalculator). */
    fun supplyFactor(effectiveDistance: Float): Float = when {
        effectiveDistance <= 4f -> 1.0f
        effectiveDistance <= 7f -> 0.9f
        effectiveDistance <= 11f -> 0.75f
        else -> 0.6f
    }

    /** Pressure a division exerts, before it is divided between the tiles it presses. */
    fun pressure(health: Int, militaryLevel: Float, stance: FrontStance, supply: Float, kind: DivisionKind = DivisionKind.Infantry): Float =
        health / 100f * baseStrength(militaryLevel) * stance.offense * kind.offenseFactor(stance) * supply

    /** Resistance a division opposes, before the modifiers of the tile. */
    fun resistance(
        health: Int, militaryLevel: Float, stance: FrontStance, entrenchment: Float, kind: DivisionKind = DivisionKind.Infantry
    ): Float = health / 100f * baseStrength(militaryLevel) * stance.defense * kind.defenseFactor(stance) * (1f + entrenchment)

    /** Resistance of a tile with the modifiers that belong to the tile and to its population. */
    fun tileResistance(
        divisionsResistance: Float, hasDefenders: Boolean, tileDefenseBonus: Float, culturalSympathy: Float, cityBonus: Float = 0f
    ): Float {
        val raw = if (hasDefenders) divisionsResistance else BASE_RESISTANCE
        // The walls of a city strengthen the divisions that hold it, not the militia of an empty one
        val city = if (hasDefenders) 1f + cityBonus else 1f
        return raw * (1f + tileDefenseBonus) * (1f + culturalSympathy) * city
    }

    /** Cultural effect on the defender, same bands as the combat sympathy of the fork: +30% from 70%, -30% up to 20%. */
    fun culturalSympathy(friendlyShare: Float): Float = when {
        friendlyShare >= 0.7f -> 0.3f
        friendlyShare <= 0.2f -> -0.3f
        else -> 0f
    }

    /**
     * Health points a division loses on one tile.
     * @param opposingForce force of the other side on that tile
     * @param ownForce force of this side on that tile
     * @param share part of this side's force that the division provides, from 0 to 1
     * @param taken the [FrontStance.taken] coefficient of the division
     * @param dealtByOpponent average [FrontStance.dealt] coefficient of the opponents
     */
    fun loss(opposingForce: Float, ownForce: Float, share: Float, taken: Float, dealtByOpponent: Float): Float {
        val total = ownForce + opposingForce
        if (total <= 0f) return 0f
        return 2f * BASE_LOSS * (opposingForce / total) * share * taken * dealtByOpponent
    }

    /** Entrenchment after one round: grows when holding still in a posture that allows it, otherwise resets. */
    fun nextEntrenchment(current: Float, stance: FrontStance, moved: Boolean): Float =
        if (moved || stance.entrenchmentCap <= 0f) 0f
        else min(stance.entrenchmentCap, current + ENTRENCHMENT_PER_ROUND)
}
