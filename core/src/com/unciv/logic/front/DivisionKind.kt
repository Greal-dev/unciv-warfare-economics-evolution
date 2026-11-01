package com.unciv.logic.front

import yairm210.purity.annotations.Readonly

/**
 * The three kinds of division. Cavalry and armoured divisions strike hard in the aggressive postures and
 * fall back well, but they cannot hold a line.
 * @param unitName name of the unit in the ruleset
 * @param buildTurns turns a city needs to raise one, whatever its production
 */
enum class DivisionKind(val unitName: String, val buildTurns: Int) {
    Infantry("Division", 2),
    Cavalry("Cavalry Division", 3),
    Armored("Armored Division", 4);

    /** Whether the kind is mobile: it presses and withdraws well but defends poorly on the spot. */
    val isMobile get() = this != Infantry

    /** Multiplier on the pressure a division exerts in a posture. */
    @Readonly fun offenseFactor(stance: FrontStance): Float = when (this) {
        Infantry -> 1f
        Cavalry -> when (stance) { FrontStance.Aggressive -> 1.6f; FrontStance.Moderate -> 1.15f; else -> 1f }
        Armored -> when (stance) { FrontStance.Aggressive -> 2.2f; FrontStance.Moderate -> 1.25f; else -> 1f }
    }

    /** Multiplier on the resistance a division opposes in a posture. */
    @Readonly fun defenseFactor(stance: FrontStance): Float = when (this) {
        Infantry -> 1f
        Cavalry -> when (stance) { FrontStance.Defensive -> 0.7f; FrontStance.Withdrawal -> 1.5f; else -> 1f }
        Armored -> when (stance) { FrontStance.Defensive -> 0.6f; FrontStance.Withdrawal -> 1.6f; else -> 1f }
    }

    companion object {
        @Readonly fun fromUnitName(name: String): DivisionKind? = entries.firstOrNull { it.unitName == name }
    }
}
