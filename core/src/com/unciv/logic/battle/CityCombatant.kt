package com.unciv.logic.battle

import com.unciv.Constants
import com.unciv.logic.MultiFilter
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.UnitType
import com.unciv.ui.components.extensions.toPercent
import yairm210.purity.annotations.Readonly
import kotlin.math.roundToInt

/**
 * TW v2 — Cities are no longer combat entities with their own HP and "magic city arrow"
 * ranged attack. The garrison unit IS the city's defender:
 *   - HP, max HP, isDefeated() all delegate to the garrison
 *   - Empty city = defeated, any melee unit captures it next attack
 *   - canAttack() = false (cities never bombard)
 *   - Strength = garrison strength + terrain + walls (no base, no tech, no pop)
 *
 * Consequence: weakly garrisoned cities (city-states, backward civs) are easy to take;
 * properly defended cities (strong garrison + walls + fort terrain) hold for many turns.
 */
class CityCombatant(val city: City) : ICombatant {
    private val garrison get() = city.getCenterTile().militaryUnit

    override fun getMaxHealth(): Int = 100  // all garrison units have HP 100 max
    override fun getHealth(): Int = garrison?.health ?: 0
    @Readonly override fun getCivInfo(): Civilization = city.civ
    override fun getTile(): Tile = city.getCenterTile()
    override fun getName(): String = city.name
    @Readonly override fun isDefeated(): Boolean = garrison == null || garrison!!.health <= 0
    override fun isVisibleTo(to: Civilization): Boolean = true
    override fun canAttack(): Boolean = false   // TW v2: cities never bombard, the garrison fights
    override fun matchesFilter(filter: String, multiFilter: Boolean) =
        if (multiFilter) MultiFilter.multiFilter(filter, { it == "City" || it in Constants.all || city.matchesFilter(it, multiFilter = false) })
        else filter == "City" || filter in Constants.all || city.matchesFilter(filter, multiFilter = false)
    override fun getAttackSound() = UncivSound.Bombard

    override fun takeDamage(damage: Int) {
        val g = garrison ?: return  // no garrison — nothing to damage; isDefeated handles capture
        g.health -= damage
        if (g.health <= 0) g.destroy()
    }

    override fun getUnitType(): UnitType = UnitType.City
    override fun getAttackingStrength(defender: ICombatant?): Int =
        (getCityStrength(defender, CombatAction.Attack) * 0.75).roundToInt()
    @Readonly override fun getDefendingStrength(attacker: ICombatant?): Int {
        // TW v2 — Empty cities still mount a token defense (15 base strength) for a slight
        // challenge. They remain `isDefeated()` so capture still triggers, but the attacker
        // takes some counter-damage walking in.
        if (isDefeated()) return 15
        return getCityStrength(attacker)
    }

    @Readonly
    fun getCityStrength(theirCombatant: ICombatant? = null, combatAction: CombatAction = CombatAction.Defend): Int {
        val cityTile = city.getCenterTile()
        val g = cityTile.militaryUnit
        var strength = if (g != null) {
            // Garrison's full combat strength (scaled by health) carries the defense.
            // TW v2 — Cities provide an inherent +200% to the garrison strength
            // (urban terrain advantage), independent of walls / fortification / terrain.
            // Storming a defended city head-on is meant to be a last resort: the siege
            // mechanic (full encirclement, surrender after 3 turns) is the normal way in.
            g.baseUnit.strength.toFloat() * (g.health / 100f) * 3f
        } else 1f  // undefended city — barely standing

        // Terrain still matters (hill, fort, etc.)
        for (terrain in cityTile.allTerrains)
            terrain.forEachMatchingUnique(UniqueType.GrantsCityStrength, GameContext.EmptyState) { unique ->
                strength += unique.params[0].toInt()
            }

        // Walls / defensive buildings still grant their bonus
        var buildingsStrength = city.getStrength()
        val gameContext = GameContext(getCivInfo(), city, ourCombatant = this, theirCombatant = theirCombatant, combatAction = combatAction)
        getCivInfo().forEachMatchingUnique(UniqueType.BetterDefensiveBuildings, gameContext) { unique ->
            buildingsStrength *= unique.params[0].toPercent()
        }
        strength += buildingsStrength

        var extraStrength = 0
        city.forEachMatchingUnique(UniqueType.StrengthAmount, gameContext) { extraStrength += it.params[0].toInt() }
        strength += extraStrength

        return strength.roundToInt().coerceAtLeast(1)
    }

    @Readonly
    override fun getTriggeredUniques(
        trigger: UniqueType,
        gameContext: GameContext,
        triggerFilter: (Unique) -> Boolean
    ): Sequence<Unique> {
        return city.getTriggeredUniques(trigger, gameContext, triggerFilter)
    }

    override fun toString() = city.name // for debug
}
