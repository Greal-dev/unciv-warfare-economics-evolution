package com.unciv.logic.battle

import com.unciv.logic.map.tile.Tile
import com.unciv.models.Counter
import com.unciv.models.ruleset.GlobalUniques
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toPercent
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Pure
import yairm210.purity.annotations.Readonly
import kotlin.collections.set
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

object BattleDamage {

    @Readonly
    private fun getModifierStringFromUnique(unique: Unique): String {
        val source = when (unique.sourceObjectType) {
            UniqueTarget.Unit -> "Unit ability"
            UniqueTarget.Nation -> "National ability"
            UniqueTarget.Global -> GlobalUniques.getUniqueSourceDescription(unique)
            else -> "[${unique.sourceObjectName}] ([${unique.getSourceNameForUser()}])"
        }.tr()
        if (unique.modifiers.isEmpty()) return source

        val conditionalsText = unique.modifiers.joinToString { it.text.tr() }
        return "$source - $conditionalsText"
    }

    @Readonly
    private fun getGeneralModifiers(combatant: ICombatant, enemy: ICombatant, combatAction: CombatAction, tileToAttackFrom: Tile): Counter<String> {
        val modifiers = Counter<String>()

        val conditionalState = getGameContext(combatAction, combatant, enemy)
        val civInfo = combatant.getCivInfo()

        if (combatant is MapUnitCombatant) {

            val unitUniqueModifiers = getUnitUniqueModifiers(combatant, enemy, conditionalState, tileToAttackFrom)
            modifiers.add(unitUniqueModifiers)

            val civResources = civInfo.getCivResourcesByName()
            for (resource in combatant.unit.getResourceRequirementsPerTurn().keys)
                if (civResources[resource]!! < 0 && !civInfo.isBarbarian)
                    modifiers["Missing resource"] = BattleConstants.MISSING_RESOURCES_MALUS

            val (greatGeneralName, greatGeneralBonus) = GreatGeneralImplementation.getGreatGeneralBonus(combatant, enemy, combatAction)
            if (greatGeneralBonus != 0)
                modifiers[greatGeneralName] = greatGeneralBonus

            // Territorial Warfare: adjacency bonus (+20% for 2 adjacent allies, +40% for 3+)
            val adjacentFriendlyMilitary = combatant.getTile().neighbors.count { neighbor ->
                neighbor.militaryUnit != null && neighbor.militaryUnit!!.civ == civInfo
            }
            if (adjacentFriendlyMilitary >= 2) {
                modifiers["Adjacent allies"] = if (adjacentFriendlyMilitary >= 3) 40 else 20
            }

            // Territorial Warfare: war experience bonus (+1% per turn of war, max +30%)
            val warXpBonus = civInfo.warExperienceBonus
            if (warXpBonus > 0) modifiers["War experience"] = warXpBonus

            // Territorial Warfare: kill bonus (+5% per kill, decays -1%/turn)
            if (combatant.unit.killBonus > 0f) {
                modifiers["Kill experience"] = combatant.unit.killBonus.toInt()
            }

            // Territorial Warfare: XP-based strength bonus = log10(XP + 10) * 10
            val xp = combatant.unit.promotions.XP
            val xpBonus = (kotlin.math.log10((xp + 10).toDouble()) * 10).toInt()
            if (xpBonus > 0) {
                modifiers["Veterancy"] = xpBonus
            }

            // TW v2 — Total encirclement: when every adjacent LAND tile is owned by a civ
            // currently at war with this unit's civ, strength collapses to 25%. Water tiles never
            // count as encirclement (logistics can still flow by sea, landings stay possible),
            // and even a single friendly/neutral/non-belligerent neighbour breaks the lock.
            if (isFullyEncircled(combatant)) modifiers["Encircled"] = -75

        } else if (combatant is CityCombatant) {
            for (unique in combatant.city.getMatchingUniques(UniqueType.StrengthForCities, conditionalState)) {
                modifiers.add(getModifierStringFromUnique(unique), unique.params[0].toInt())
            }
        }

        if (enemy.getCivInfo().isBarbarian) {
            modifiers["Difficulty"] =
                (civInfo.gameInfo.getDifficulty().barbarianBonus * 100).toInt()
        }

        return modifiers
    }

    /** TW v2 — Returns true if the unit's combat strength should collapse from encirclement.
     *  Two cases trigger the malus:
     *    1. Every land neighbour is owned by a civ at war with us AND there is no water
     *       neighbour either — pure land encirclement, no way out, full -75% malus.
     *    2. Every land neighbour is enemy-controlled but at least one water neighbour exists.
     *       The sea provides a logistical opening, so the malus only fires when the unit is
     *       crammed in a saturated own-land pocket — pocket BFS over own land, malus only if
     *       (military units / pocket tiles) > 0.5. A lone unit on a 2-tile peninsula sees a
     *       0.5 ratio → no malus. Same unit on a 1-tile islet → ratio 1.0 → malus.
     *  A single land opening (neutral, own, non-belligerent neighbour) or no land neighbour at
     *  all (true island / fully waterbound) always breaks the encirclement: returns false. */
    @Readonly
    private fun isFullyEncircled(combatant: MapUnitCombatant): Boolean {
        // TW v2 — Naval units are never "encircled": the sea is their natural medium, enemy land
        // tiles on the coast don't constrain their movement or logistics.
        if (combatant.unit.baseUnit.isWaterUnit) return false
        val civ = combatant.unit.civ
        val tile = combatant.getTile()
        var hasEnemyLand = false
        var hasNonCountingNeighbor = false  // water OR impassable-without-road: an opening, not a wall
        for (neighbor in tile.neighbors) {
            // TW v2 — impassable terrain (mountains) acts like the sea for encirclement: it doesn't
            // count as a land tile to be held. Exception: if a road has been carved through it
            // (mountain pass), it behaves like normal land — units and ownership flow across it.
            val isUncountedTile = neighbor.isWater
                || (neighbor.isImpassible()
                    && neighbor.getUnpillagedRoad() == com.unciv.logic.map.tile.RoadStatus.None)
            if (isUncountedTile) { hasNonCountingNeighbor = true; continue }
            val owner = neighbor.getOwner()
            if (owner == null || owner == civ || !civ.isAtWarWith(owner)) {
                return false  // any land opening breaks the lock
            }
            hasEnemyLand = true
        }
        if (!hasEnemyLand) return false  // no land neighbour at all (waterbound / sealed by mountains) — no encirclement
        // Pure land encirclement (no sea / mountain opening) → full malus.
        if (!hasNonCountingNeighbor) return true
        // Sea/mountain opening present → gate on own-land pocket saturation.
        if (tile.getOwner() != civ) {
            // Not on own land; with an opening nearby, treat the lone tile as the pocket (saturation 1.0).
            return true
        }
        return isLandPocketSaturated(tile, civ)
    }

    /** BFS over own land starting from [start], collecting all reachable own-land tiles
     *  (without crossing water, impassable terrain, or foreign tiles). Returns true if more than
     *  half of the pocket's tiles are occupied by friendly military units. */
    @Readonly
    private fun isLandPocketSaturated(start: Tile, civ: com.unciv.logic.civilization.Civilization): Boolean {
        @LocalState val pocket = HashSet<Tile>()
        @LocalState val frontier = ArrayDeque<Tile>()
        pocket.add(start); frontier.add(start)
        while (frontier.isNotEmpty()) {
            val cur = frontier.removeFirst()
            for (n in cur.neighbors) {
                if (n in pocket) continue
                if (!n.isLand) continue
                // TW v2 — impassable terrain (mountain) is part of the pocket only if it has a road
                // (a pass): units can transit it, so it counts as navigable own land for saturation.
                if (n.isImpassible() && n.getUnpillagedRoad() == com.unciv.logic.map.tile.RoadStatus.None) continue
                if (n.getOwner() != civ) continue
                pocket.add(n); frontier.add(n)
            }
        }
        if (pocket.isEmpty()) return false
        val militaryCount = pocket.count { it.militaryUnit?.civ == civ }
        return militaryCount.toFloat() / pocket.size.toFloat() > 0.5f
    }

    @Readonly
    private fun getGameContext(
        combatAction: CombatAction,
        combatant: ICombatant,
        enemy: ICombatant,
    ): GameContext {
        val attackedTile =
            if (combatAction == CombatAction.Attack) enemy.getTile()
            else combatant.getTile()

        val conditionalState = GameContext(
            combatant.getCivInfo(),
            city = (combatant as? CityCombatant)?.city,
            ourCombatant = combatant,
            theirCombatant = enemy,
            attackedTile = attackedTile,
            combatAction = combatAction
        )
        return conditionalState
    }

    @Readonly
    private fun getUnitUniqueModifiers(combatant: MapUnitCombatant, enemy: ICombatant, conditionalState: GameContext,
                                       tileToAttackFrom: Tile): Counter<String> {
        val civInfo = combatant.getCivInfo()
        val modifiers = Counter<String>()

        for (unique in combatant.getMatchingUniques(UniqueType.Strength, conditionalState, true)) {
            modifiers.add(getModifierStringFromUnique(unique), unique.params[0].toInt())
        }

        // e.g., Mehal Sefari https://civilization.fandom.com/wiki/Mehal_Sefari_(Civ5)
        for (unique in combatant.getMatchingUniques(
            UniqueType.StrengthNearCapital, conditionalState, true
        )) {
            if (civInfo.cities.isEmpty() || civInfo.getCapital() == null) break
            val distance =
                combatant.getTile().aerialDistanceTo(civInfo.getCapital()!!.getCenterTile())
            // https://steamcommunity.com/sharedfiles/filedetails/?id=326411722#464287
            val effect = unique.params[0].toInt() - 3 * distance
            if (effect > 0)
                modifiers.add(getModifierStringFromUnique(unique), effect)
        }

        //https://www.carlsguides.com/strategy/civilization5/war/combatbonuses.php
        var adjacentUnits = combatant.getTile().neighbors.flatMap { it.getUnits() }
        if (enemy.getTile() !in combatant.getTile().neighbors && tileToAttackFrom in combatant.getTile().neighbors
            && enemy is MapUnitCombatant
        )
            adjacentUnits += sequenceOf(enemy.unit)

        // e.g., Maori Warrior - https://civilization.fandom.com/wiki/Maori_Warrior_(Civ5)
        val strengthMalus = adjacentUnits.filter { it.civ.isAtWarWith(combatant.getCivInfo()) }
            .flatMap { it.getMatchingUniques(UniqueType.StrengthForAdjacentEnemies) }
            .filter { combatant.matchesFilter(it.params[1]) && combatant.getTile().matchesFilter(it.params[2]) }
            .maxByOrNull { it.params[0] }
        if (strengthMalus != null) {
            modifiers.add("Adjacent enemy units", strengthMalus.params[0].toInt())
        }
        return modifiers
    }

    @Readonly
    fun getAttackModifiers(
        attacker: ICombatant,
        defender: ICombatant, tileToAttackFrom: Tile
    ): Counter<String> {
        @LocalState val modifiers = getGeneralModifiers(attacker, defender, CombatAction.Attack, tileToAttackFrom)

        if (attacker is MapUnitCombatant) {

            val terrainAttackModifiers = getTerrainAttackModifiers(attacker, defender, tileToAttackFrom)
            modifiers.add(terrainAttackModifiers)

            // Air unit attacking with Air Sweep
            if (attacker.unit.isPreparingAirSweep())
                modifiers.add(getAirSweepAttackModifiers(attacker))

            if (attacker.isMelee()) {
                val numberOfOtherAttackersSurroundingDefender = defender.getTile().neighbors.count {
                    it.militaryUnit != null && it.militaryUnit != attacker.unit
                            && it.militaryUnit!!.civ == attacker.getCivInfo()
                            && MapUnitCombatant(it.militaryUnit!!).isMelee()
                }
                if (numberOfOtherAttackersSurroundingDefender > 0) {
                    var flankingBonus = BattleConstants.BASE_FLANKING_BONUS

                    // e.g., Discipline policy - https://civilization.fandom.com/wiki/Discipline_(Civ5)
                    for (unique in attacker.unit.getMatchingUniques(UniqueType.FlankAttackBonus, checkCivInfoUniques = true,
                            gameContext = getGameContext(CombatAction.Attack, attacker, defender)))
                        flankingBonus *= unique.params[0].toPercent()
                    modifiers["Flanking"] =
                        (flankingBonus * numberOfOtherAttackersSurroundingDefender).toInt()
                }
            }

            // TW: cultural sympathy on the target tile shifts attacker strength.
            //   ≥ 70% attacker share → +30% attack ; ≤ 20% → -30%.
            // TW v2 — Naval units never engage the local populace culturally: skip both bonus and malus.
            if (!attacker.unit.baseUnit.isWaterUnit) {
                val targetTile = defender.getTile()
                val attackerShare = com.unciv.logic.map.TileCultureLogic.getFriendlyShare(targetTile, attacker.getCivInfo())
                if (attackerShare >= 0.70f) modifiers["Cultural sympathy"] = 30
                else if (attackerShare <= 0.20f) modifiers["Hostile populace"] = -30
            }
        }

        return modifiers
    }

    @Readonly
    private fun getTerrainAttackModifiers(attacker: MapUnitCombatant, defender: ICombatant, tileToAttackFrom: Tile): Counter<String> {
        val modifiers = Counter<String>()
        // TW v2 — Amphibious landings are punished only when no friendly foothold exists near the
        // target tile. Once the attacking civ holds land within 3 hexes (= an established
        // bridgehead, a captured city, or even a previous landing that survived), reinforcements
        // coming off transports only suffer half the malus (-25 instead of -50). Without a
        // foothold the original -50 still applies — the first wave still pays the full price.
        val landingMalus: (defenderTile: Tile) -> Int = { tile ->
            val hasFoothold = tile.getTilesInDistance(3).any {
                it.isLand && it.getOwner() == attacker.unit.civ
            }
            if (hasFoothold) BattleConstants.LANDING_MALUS / 2 else BattleConstants.LANDING_MALUS
        }

        if (attacker.unit.isEmbarked() && defender.getTile().isLand
            && !attacker.unit.hasUnique(UniqueType.AttackAcrossCoast)
        )
            modifiers["Landing"] = landingMalus(defender.getTile())

        // Land Melee Unit attacking to Water
        if (attacker.unit.type.isLandUnit() && !attacker.getTile().isWater && attacker.isMelee() && defender.getTile().isWater
            && !attacker.unit.hasUnique(UniqueType.AttackAcrossCoast)
        )
            modifiers["Boarding"] = BattleConstants.BOARDING_MALUS

        // Melee Unit on water attacking to Land (not City) unit
        if (!attacker.unit.type.isAirUnit() && attacker.isMelee() && attacker.getTile().isWater && !defender.getTile().isWater
            && !attacker.unit.hasUnique(UniqueType.AttackAcrossCoast) && !defender.isCity()
        )
            modifiers["Landing"] = landingMalus(defender.getTile())

        if (isMeleeAttackingAcrossRiverWithNoBridge(attacker, tileToAttackFrom, defender))
            modifiers["Across river"] = BattleConstants.ATTACKING_ACROSS_RIVER_MALUS
        return modifiers
    }

    @Readonly
    private fun isMeleeAttackingAcrossRiverWithNoBridge(attacker: MapUnitCombatant, tileToAttackFrom: Tile, defender: ICombatant) = (
        attacker.isMelee()
            &&
            (tileToAttackFrom.aerialDistanceTo(defender.getTile()) == 1
                && tileToAttackFrom.isConnectedByRiver(defender.getTile())
                && !attacker.unit.hasUnique(UniqueType.AttackAcrossRiver))
            &&
            (!tileToAttackFrom.hasConnection(attacker.getCivInfo()) // meaning, the tiles are not road-connected for this civ
                || !defender.getTile().hasConnection(attacker.getCivInfo())
                || !attacker.getCivInfo().tech.roadsConnectAcrossRivers)
        )

    @Readonly
    fun getAirSweepAttackModifiers(
        attacker: ICombatant
    ): Counter<String> {
        val modifiers = Counter<String>()

        if (attacker is MapUnitCombatant) {
            for (unique in attacker.unit.getMatchingUniques(UniqueType.StrengthWhenAirsweep)) {
                modifiers.add(getModifierStringFromUnique(unique), unique.params[0].toInt())
            }
        }

        return modifiers
    }

    @Readonly
    fun getDefenceModifiers(attacker: ICombatant, defender: ICombatant, tileToAttackFrom: Tile): Counter<String> {
        @LocalState val modifiers = getGeneralModifiers(defender, attacker, CombatAction.Defend, tileToAttackFrom)
        val tile = defender.getTile()

        if (defender is MapUnitCombatant && !defender.unit.isEmbarked()) { // Embarked units get no terrain defensive bonuses

            val tileDefenceBonus = tile.getDefensiveBonus(unit = defender.unit)
            if (!defender.unit.hasUnique(UniqueType.NoDefensiveTerrainBonus, checkCivInfoUniques = true) && tileDefenceBonus > 0
                || !defender.unit.hasUnique(UniqueType.NoDefensiveTerrainPenalty, checkCivInfoUniques = true) && tileDefenceBonus < 0
            )
                modifiers["Tile"] = (tileDefenceBonus * 100).toInt()


            if (defender.unit.isFortified() || defender.unit.isGuarding())
                modifiers["Fortification"] = BattleConstants.FORTIFICATION_BONUS * defender.unit.getFortificationTurns()

            // TW: cultural sympathy on the defender's own tile shifts defense.
            //   ≥ 70% defender share → +30% defense ; ≤ 20% → -30%.
            // TW v2 — Naval units never engage the local populace culturally: skip both bonus and malus.
            if (!defender.unit.baseUnit.isWaterUnit) {
                val defenderShare = com.unciv.logic.map.TileCultureLogic.getFriendlyShare(tile, defender.getCivInfo())
                if (defenderShare >= 0.70f) modifiers["Cultural sympathy"] = 30
                else if (defenderShare <= 0.20f) modifiers["Hostile populace"] = -30
            }
        }

        // TW v2 — A garrisoned city inherits the garrison's natural defensive bonuses
        // (terrain, fortification, cultural sympathy). Without this the city version of the
        // same unit was strictly weaker than the unit on an open tile of the same terrain.
        if (defender is CityCombatant) {
            val garrison = tile.militaryUnit
            if (garrison != null && !garrison.isEmbarked()) {
                val tileDefenceBonus = tile.getDefensiveBonus(unit = garrison)
                if (!garrison.hasUnique(UniqueType.NoDefensiveTerrainBonus, checkCivInfoUniques = true) && tileDefenceBonus > 0
                    || !garrison.hasUnique(UniqueType.NoDefensiveTerrainPenalty, checkCivInfoUniques = true) && tileDefenceBonus < 0
                )
                    modifiers["Tile"] = (tileDefenceBonus * 100).toInt()

                if (garrison.isFortified() || garrison.isGuarding())
                    modifiers["Fortification"] = BattleConstants.FORTIFICATION_BONUS * garrison.getFortificationTurns()

                // TW v2 — Naval garrison (rare but possible) is excluded from cultural populace effects.
                if (!garrison.baseUnit.isWaterUnit) {
                    val defenderShare = com.unciv.logic.map.TileCultureLogic.getFriendlyShare(tile, defender.getCivInfo())
                    if (defenderShare >= 0.70f) modifiers["Cultural sympathy"] = 30
                    else if (defenderShare <= 0.20f) modifiers["Hostile populace"] = -30
                }
            }
        }

        // TW v2 — Empire-scale defensive modifier (major civs only).
        //   1 city  → +200%   (city-state level resilience)
        //   2       → +100%
        //   3       →  +50%
        //   4       →    0%
        //   5+      → −5% per city above 4, floored at −50% (5 → −5%, 14 → −50%, 20 → −50%)
        // Models the "hard core, soft periphery" of overstretched empires.
        val defenderCiv = defender.getCivInfo()
        if (defenderCiv.isMajorCiv()) {
            val cityCount = defenderCiv.cities.size
            val empireSizeModifier = when (cityCount) {
                1 -> 200
                2 -> 100
                3 -> 50
                4 -> 0
                else -> (-((cityCount - 4) * 5)).coerceAtLeast(-50)
            }
            if (empireSizeModifier != 0)
                modifiers["Empire size"] = empireSizeModifier
        }

        return modifiers
    }

    @Readonly
    private fun modifiersToFinalBonus(modifiers: Counter<String>): Float {
        var finalModifier = 1f
        for (modifierValue in modifiers.values) finalModifier += modifierValue / 100f
        return finalModifier
    }

    @Readonly
    private fun getHealthDependantDamageRatio(combatant: ICombatant): Float {
        return if (combatant !is MapUnitCombatant
            || combatant.unit.hasUnique(UniqueType.NoDamagePenaltyWoundedUnits, checkCivInfoUniques = true)
        ) 1f
        // Each 3 points of health reduces damage dealt by 1%
        else 1 - (100 - combatant.getHealth()) / BattleConstants.DAMAGE_REDUCTION_WOUNDED_UNIT_RATIO_PERCENTAGE
    }


    /**
     * Includes attack modifiers
     */
    @Readonly
    fun getAttackingStrength(
        attacker: ICombatant,
        defender: ICombatant,
        tileToAttackFrom: Tile
    ): Float {
        val attackModifier = modifiersToFinalBonus(getAttackModifiers(attacker, defender, tileToAttackFrom))
        return max(1f, attacker.getAttackingStrength(defender) * attackModifier)
    }


    /**
     * Includes defence modifiers
     */
    @Readonly
    fun getDefendingStrength(attacker: ICombatant, defender: ICombatant, tileToAttackFrom: Tile): Float {
        val defenceModifier = modifiersToFinalBonus(getDefenceModifiers(attacker, defender, tileToAttackFrom))
        return max(1f, defender.getDefendingStrength(attacker) * defenceModifier)
    }
    
    @Readonly
    fun getRandomness(combatant: ICombatant): Float = 
        Random(combatant.getCivInfo().gameInfo.turns
                * combatant.getTile().position.toVector2().hashCode().toLong()).nextFloat()

    @Readonly
    fun calculateDamageToAttacker(
        attacker: ICombatant,
        defender: ICombatant,
        tileToAttackFrom: Tile = defender.getTile(),
        /** Between 0 and 1. */
        randomnessFactor: Float = getRandomness(attacker)
    ): Int {
        if (attacker.isRanged() && !attacker.isAirUnit()) return 0
        if (defender.isCivilian()) return 0
        val ratio = getAttackingStrength(attacker, defender, tileToAttackFrom) / getDefendingStrength(
                attacker, defender, tileToAttackFrom)
        return (damageModifier(ratio, true, randomnessFactor) * getHealthDependantDamageRatio(defender)).roundToInt()
    }

    @Readonly
    fun calculateDamageToDefender(
        attacker: ICombatant,
        defender: ICombatant,
        tileToAttackFrom: Tile = defender.getTile(),
        /** Between 0 and 1.  Defaults to turn and location-based random to avoid save scumming */
        randomnessFactor: Float = getRandomness(defender)
        ,
    ): Int {
        if (defender.isCivilian()) return BattleConstants.DAMAGE_TO_CIVILIAN_UNIT
        val ratio = getAttackingStrength(attacker, defender, tileToAttackFrom) /
                getDefendingStrength(attacker, defender, tileToAttackFrom)
        return (damageModifier(ratio, false, randomnessFactor) * getHealthDependantDamageRatio(attacker)).roundToInt()
    }

    @Pure
    private fun damageModifier(
        attackerToDefenderRatio: Float,
        damageToAttacker: Boolean,
        /** Between 0 and 1. */
        randomnessFactor: Float,
    ): Float {
        // https://forums.civfanatics.com/threads/getting-the-combat-damage-math.646582/#post-15468029
        val strongerToWeakerRatio =
            attackerToDefenderRatio.pow(if (attackerToDefenderRatio < 1) -1 else 1)
        var ratioModifier = (((strongerToWeakerRatio + 3) / 4).pow(4) + 1) / 2
        if (damageToAttacker && attackerToDefenderRatio > 1 || !damageToAttacker && attackerToDefenderRatio < 1) // damage ratio from the weaker party is inverted
            ratioModifier = ratioModifier.pow(-1)
        val randomCenteredAround30 = 24 + 12 * randomnessFactor
        return randomCenteredAround30 * ratioModifier
    }
}
enum class CombatAction {
    Attack,
    Defend,
    Intercept,
}
