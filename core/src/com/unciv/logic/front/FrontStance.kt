package com.unciv.logic.front

/**
 * Front mode: the two intensities of an attack and the two postures a division takes when it is attacked.
 *
 * [Moderate] and [Aggressive] are chosen at the moment the player launches an attack, on one target tile.
 * [Defensive] and [Withdrawal] are the posture a division keeps until the adversary attacks it: they decide
 * how it reacts during the adversary's turn.
 *
 * All coefficients are starting points, to be calibrated with short seeded simulations.
 *
 * @param offense multiplier on the pressure the division exerts, 0 means it does not press at all
 * @param defense multiplier on the resistance the division opposes to a pressed tile
 * @param taken multiplier on the losses the division suffers
 * @param dealt multiplier on the losses the division inflicts on the opponent
 * @param entrenchmentCap highest entrenchment bonus (0.35 means +35%) reachable by holding still
 * @param fallsBack whether the token steps back toward its supply every round
 * @param reinforcementCost multiplier on the gold price of reinforcements
 */
enum class FrontStance(
    val offense: Float,
    val defense: Float,
    val taken: Float,
    val dealt: Float,
    val entrenchmentCap: Float,
    val fallsBack: Boolean,
    val reinforcementCost: Float,
    /** Label shown on the action button */
    val label: String
) {
    Moderate(1.0f, 0.8f, 1.0f, 1.0f, 0f, false, 1.0f, "Moderate attack"),
    Aggressive(1.5f, 0.6f, 1.5f, 1.3f, 0f, false, 1.5f, "Aggressive attack"),
    Defensive(0f, 1.4f, 0.8f, 1.0f, 0.35f, false, 1.0f, "Hold the line"),
    Withdrawal(0f, 0.6f, 0.4f, 0.9f, 0f, true, 1.0f, "Strategic withdrawal");

    /** Whether this is an attack intensity, as opposed to a posture taken when attacked */
    val presses: Boolean get() = offense > 0f

    companion object {
        /** The postures a division can hold while waiting to be attacked */
        val postures: List<FrontStance> get() = entries.filter { !it.presses }

        /** Unknown or missing names fall back to the safest posture. */
        fun fromName(name: String?): FrontStance = entries.firstOrNull { it.name == name } ?: Defensive
    }
}
