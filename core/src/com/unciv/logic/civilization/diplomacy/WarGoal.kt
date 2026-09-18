package com.unciv.logic.civilization.diplomacy

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.screens.victoryscreen.RankingType
import yairm210.purity.annotations.Readonly

/**
 * Territorial Warfare v2 — what a civilization is actually trying to obtain by going to war.
 *
 * Before this, an AI declared war on a scalar motivation and later sued for peace on the raw
 * force ratio of the moment, so it never knew what it wanted and wars of attrition dragged on
 * with no resolution. A war goal is set once, at the declaration, and answers two questions
 * every turn: has this been settled, and is it still worth fighting for.
 *
 * V1 ships the Conquest goal only. Tribute, territorial cession (which would plug into the
 * existing territory exchange screen) and alliance-breaking are deliberately left for later.
 */
enum class WarGoalType {
    /** Take one named city from the target. */
    Conquest
}

enum class WarGoalStatus {
    /** Still worth fighting for. */
    InProgress,

    /** We got what we came for. */
    Achieved,

    /** It can no longer be had: the city is gone, someone else took it, or we are being crushed. */
    Unreachable
}

/**
 * Held by the attacker's [DiplomacyManager] towards the civ it declared war on.
 * Null on the defender's side and on every pre-existing save, which is why the field is
 * nullable: no save migration is needed.
 */
class WarGoal() : IsPartOfGameInfoSerialization {

    var type: WarGoalType = WarGoalType.Conquest

    /** Stable [com.unciv.logic.city.City.id], so the goal survives a rename or a change of hands. */
    var targetCityId: String = ""

    /** Kept only so the goal can be shown and logged after the city is razed. */
    var targetCityName: String = ""

    var declaredOnTurn: Int = 0

    constructor(type: WarGoalType, targetCityId: String, targetCityName: String, declaredOnTurn: Int) : this() {
        this.type = type
        this.targetCityId = targetCityId
        this.targetCityName = targetCityName
        this.declaredOnTurn = declaredOnTurn
    }

    fun clone(): WarGoal = WarGoal(type, targetCityId, targetCityName, declaredOnTurn)

    /**
     * Where this war stands for [civInfo], which declared it on [targetCiv].
     *
     * A goal turns [WarGoalStatus.Unreachable] rather than dragging on when the prize is gone
     * (razed, or taken by a third party) or when the balance has tipped far enough that
     * continuing is ruinous. That second test is what actually ends attrition wars: an
     * attacker who has lost the initiative now has a reason to come to the table.
     */
    @Readonly
    fun evaluate(civInfo: Civilization, targetCiv: Civilization): WarGoalStatus {
        // The prize itself
        val targetCity = civInfo.gameInfo.getCities().firstOrNull { it.id == targetCityId }
            ?: return WarGoalStatus.Unreachable          // razed in the meantime
        val owner = targetCity.civ
        if (owner == civInfo) return WarGoalStatus.Achieved
        if (owner != targetCiv) return WarGoalStatus.Unreachable  // a third party got there first

        // A war we are clearly losing is not worth prolonging for a city we will never reach.
        val ourForce = civInfo.getStatForRanking(RankingType.Force).toFloat()
        val theirForce = targetCiv.getStatForRanking(RankingType.Force).toFloat()
        if (theirForce > 0f && ourForce < theirForce * LOSING_FORCE_RATIO)
            return WarGoalStatus.Unreachable

        return WarGoalStatus.InProgress
    }

    /** One line for the diplomacy screen, so the player can read what the war is about. */
    @Readonly
    fun describe(): String = when (type) {
        WarGoalType.Conquest -> "Take [$targetCityName]"
    }

    companion object {
        /** Below this share of the enemy's military force, the attacker gives up on its goal. */
        private const val LOSING_FORCE_RATIO = 0.5f

        /**
         * Picks what [civInfo] is after when it declares war on [targetCiv]: the enemy city
         * closest to one of our own, which is both the likeliest to fall and the one our units
         * can actually reach. Returns null when the target holds no city we could name.
         */
        fun forDeclarationOfWar(civInfo: Civilization, targetCiv: Civilization): WarGoal? {
            if (civInfo.cities.isEmpty() || targetCiv.cities.isEmpty()) return null

            val closest = targetCiv.cities.minByOrNull { theirCity ->
                civInfo.cities.minOf { ourCity ->
                    ourCity.getCenterTile().aerialDistanceTo(theirCity.getCenterTile())
                }
            } ?: return null

            return WarGoal(
                WarGoalType.Conquest,
                closest.id,
                closest.name,
                civInfo.gameInfo.turns
            )
        }
    }
}
