package com.unciv.logic.front

import com.unciv.logic.GameInfo
import com.unciv.logic.battle.CombatCostCalculator
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.TileCultureLogic
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unit.BaseUnit
import yairm210.purity.annotations.Readonly
import kotlin.math.roundToInt

/**
 * Front mode: the attacks of divisions and the upkeep of the front.
 *
 * An attack is launched during the turn of its owner, on one target tile, and is resolved on the spot.
 * The defenders react with the posture they hold (hold the line, strategic withdrawal), which therefore
 * takes effect during the turn and the action of the adversary. Once per round, [endOfRound] pays the
 * upkeep: retired units, lost cities, entrenchment, reinforcements.
 * See docs/superpowers/specs/2026-10-02-mode-front-tranche-verticale-design.md.
 *
 * Iteration order is always derived from civilization order, unit order and tile coordinates, never from
 * hash order, so that two runs with the same seed give the same result.
 */
object FrontResolver {
    /** Name of the only unit type that fights in front mode. */
    const val DIVISION_UNIT_NAME = "Division"

    @Readonly fun isDivision(unit: MapUnit) = DivisionKind.fromUnitName(unit.baseUnit.name) != null

    @Readonly fun isDivisionName(unitName: String) = DivisionKind.fromUnitName(unitName) != null

    @Readonly fun kindOf(unit: MapUnit): DivisionKind = DivisionKind.fromUnitName(unit.baseUnit.name) ?: DivisionKind.Infantry

    /** Citizens of [city] who are neither specialists nor occupied by a division. */
    @Readonly fun workersAvailable(city: City): Int =
        city.population.population - city.population.getNumberOfSpecialists() - divisionsBoundTo(city)

    /** Divisions raised by [city], each one a citizen who no longer works. */
    @Readonly fun divisionsBoundTo(city: City): Int {
        if (!city.civ.gameInfo.gameParameters.frontMode) return 0
        return city.civ.units.getCivUnits().count { isDivision(it) && it.frontHomeCityId == city.id }
    }

    fun stanceOf(unit: MapUnit) = FrontStance.fromName(unit.frontStance)

    /** Posture a division opposes to an attack: an old or missing attack intensity counts as holding the line. */
    fun reactionOf(unit: MapUnit): FrontStance = stanceOf(unit).takeIf { !it.presses } ?: FrontStance.Defensive

    /** Technologies that unlock a military unit, per ruleset: the military technologies of a civilization. */
    private val militaryTechsByRuleset = java.util.WeakHashMap<Ruleset, Set<String>>()

    private fun militaryTechs(ruleset: Ruleset): Set<String> = militaryTechsByRuleset.getOrPut(ruleset) {
        ruleset.units.values.filter { it.isMilitary }.flatMap { it.requiredTechs().toList() }.toSet()
    }

    /** Military level of a civilization, from the military technologies it has researched. */
    fun militaryLevel(civ: Civilization): Float {
        val techs = militaryTechs(civ.gameInfo.ruleset)
        return FrontMath.militaryLevel(techs.count { civ.tech.isResearched(it) }, techs.size)
    }

    /** Land units other than divisions are no longer produced in front mode: they leave the production queues of the cities. */
    private fun purgeRetiredUnits(gameInfo: GameInfo) {
        for (civ in gameInfo.civilizations)
            for (city in civ.cities) {
                val constructions = city.cityConstructions
                for (index in constructions.constructionQueue.indices.reversed()) {
                    val construction = constructions.getConstruction(constructions.constructionQueue[index])
                    if (construction is BaseUnit && construction.isMilitary && construction.isLandUnit && !isDivisionName(construction.name))
                        constructions.removeFromQueue(index, true)
                }
            }
    }

    /**
     * When the city that raised a division is lost, the citizen it occupied moves to another city of the same
     * civilization, the one with the most citizens to spare. A division that no city can support disbands.
     */
    private fun rehomeOrphans(gameInfo: GameInfo) {
        for (civ in gameInfo.civilizations) {
            if (civ.cities.isEmpty()) continue
            val orphans = civ.units.getCivUnits().filter { unit ->
                isDivision(unit) && unit.frontHomeCityId != null && civ.cities.none { it.id == unit.frontHomeCityId }
            }.toList()
            for (division in orphans) {
                val host = civ.cities.filter { workersAvailable(it) >= FrontMath.MIN_CITIZENS_AT_WORK + 1 }
                    .sortedWith(compareBy<City>({ -workersAvailable(it) }, { it.name }))
                    .firstOrNull()
                if (host == null) {
                    civ.addNotification("Our [${division.name}] has disbanded: no city can spare a citizen for it.",
                        division.currentTile.position, NotificationCategory.War, NotificationIcon.Death)
                    division.destroy()
                    continue
                }
                division.frontHomeCityId = host.id
                host.population.unassignExtraPopulation()
                host.cityStats.update()
                civ.addNotification("Our [${division.name}] is now supported by [${host.name}].",
                    division.currentTile.position, NotificationCategory.War, NotificationIcon.War)
            }
        }
    }

    /** Upkeep of the front, once per round, after every civilization has played. Attacks are not resolved here. */
    fun endOfRound(gameInfo: GameInfo) {
        purgeRetiredUnits(gameInfo)
        rehomeOrphans(gameInfo)
        // Without a city there is nothing left to pay, supply or recruit them: the divisions of such a civilization disband
        for (civ in gameInfo.civilizations)
            if (civ.cities.isEmpty() && !civ.isBarbarian)
                civ.units.getCivUnits().filter { isDivision(it) }.toList().forEach { it.destroy() }
        // Citizens freed by lost divisions go back to work
        for (civ in gameInfo.civilizations)
            for (city in civ.cities)
                if (city.population.getFreePopulation() > 0) city.shouldReassignPopulation = true

        val divisions = gameInfo.civilizations.flatMap { civ ->
            if (civ.isDefeated()) emptyList() else civ.units.getCivUnits().filter { isDivision(it) }.toList()
        }
        for (division in divisions) upkeep(division)
    }

    // region attack

    /** Tiles a division can attack: those of its zone that are in contact, enemy divisions first, then cities, then the rest. */
    fun attackTargets(division: MapUnit): List<Tile> {
        val civ = division.civ
        val origin = division.currentTile
        return origin.getTilesInDistance(FrontMath.ZONE_RADIUS)
            .filter { isContactTile(it, civ) }
            .sortedWith(compareBy<Tile>({ targetPriority(it, civ) }, { it.aerialDistanceTo(origin) }, { it.position.x }, { it.position.y }))
            .toList()
    }

    /** Whether the division can launch an attack now: front mode, at war, and still holding movement for this turn. */
    fun canAttack(division: MapUnit): Boolean =
        division.civ.gameInfo.gameParameters.frontMode && isDivision(division) && division.currentMovement > 0f &&
            division.health > FrontMath.DISSOLVE_HEALTH && division.civ.isAtWar()

    /** What an attack would amount to, before it is launched. All values are those the resolution will use. */
    class AttackPreview(
        val tile: Tile, val intensity: FrontStance,
        val pressure: Float, val resistance: Float,
        val attackerLoss: Int, val defenderLoss: Int,
        val defenders: Int, val supporters: Int
    ) {
        val ratio: Float get() = pressure / resistance
        val succeeds: Boolean get() = ratio >= FrontMath.VICTORY_RATIO
    }

    /** Outcome of a launched attack. [message] tells the player what happened. */
    class AttackResult(val preview: AttackPreview, val tileTaken: Boolean, val message: String)

    /** A share of the force of the attackers or of the defenders of a tile, provided by one division. */
    private class Contribution(val unit: MapUnit, val force: Float, val stance: FrontStance)

    private class Engagement(
        val tile: Tile, val defenderCiv: Civilization,
        val attackers: List<Contribution>, val defenders: List<Contribution>,
        val pressure: Float, val resistance: Float
    )

    private fun engagement(division: MapUnit, tile: Tile, intensity: FrontStance): Engagement? {
        val civ = division.civ
        if (!intensity.presses) return null
        if (tile.aerialDistanceTo(division.currentTile) > FrontMath.ZONE_RADIUS || !isContactTile(tile, civ)) return null
        // An enemy division standing on the tile defends it wherever the tile belongs, otherwise its owner does
        val occupantCiv = if (holdsEnemyDivision(tile, civ)) tile.militaryUnit?.civ else null
        val defenderCiv = occupantCiv ?: tile.getOwner() ?: return null

        fun pressureOf(unit: MapUnit, stance: FrontStance) = FrontMath.pressure(
            unit.health, militaryLevel(civ), stance,
            FrontMath.supplyFactor(CombatCostCalculator.computeDistanceFactor(civ, unit.currentTile)), kindOf(unit))

        val attackers = ArrayList<Contribution>()
        attackers += Contribution(division, pressureOf(division, intensity), intensity)
        // The divisions of the attacker in the zone of the target support the attack with part of their force
        for (unit in civ.units.getCivUnits().filter { isDivision(it) && it != division && it.health > FrontMath.DISSOLVE_HEALTH }.toList())
            if (unit.currentTile.aerialDistanceTo(tile) <= FrontMath.ZONE_RADIUS)
                attackers += Contribution(unit, pressureOf(unit, FrontStance.Moderate) * FrontMath.SUPPORT_SHARE, FrontStance.Moderate)

        val defenders = defenderCiv.units.getCivUnits().filter { isDivision(it) }.toList()
            .filter { it.currentTile.aerialDistanceTo(tile) <= FrontMath.ZONE_RADIUS }
            .map {
                val reaction = reactionOf(it)
                Contribution(it, FrontMath.resistance(it.health, militaryLevel(defenderCiv), reaction, it.frontEntrenchment, kindOf(it)), reaction)
            }
        // Local sympathy only protects a tile that really belongs to the defender
        val sympathy = if (tile.getOwner() == defenderCiv) FrontMath.culturalSympathy(TileCultureLogic.getFriendlyShare(tile, defenderCiv)) else 0f
        val resistance = FrontMath.tileResistance(
            defenders.sumOf { it.force.toDouble() }.toFloat(), defenders.isNotEmpty(), tile.getDefensiveBonus(), sympathy,
            if (tile.isCityCenter() && tile.getOwner() == defenderCiv) FrontMath.CITY_DEFENSE_BONUS else 0f
        )
        return Engagement(tile, defenderCiv, attackers, defenders, attackers.sumOf { it.force.toDouble() }.toFloat(), resistance)
    }

    /** Health points each division of an engagement loses, attackers and defenders alike. */
    private fun lossesOf(e: Engagement): Map<MapUnit, Float> {
        val dealtByDefenders = weightedDealt(e.defenders)
        val dealtByAttackers = weightedDealt(e.attackers)
        val losses = LinkedHashMap<MapUnit, Float>()
        for (attacker in e.attackers)
            losses[attacker.unit] = FrontMath.loss(e.resistance, e.pressure, attacker.force / e.pressure, attacker.stance.taken, dealtByDefenders)
        val defenderForce = e.defenders.sumOf { it.force.toDouble() }.toFloat()
        for (defender in e.defenders)
            losses[defender.unit] = FrontMath.loss(e.pressure, e.resistance, defender.force / defenderForce, defender.stance.taken, dealtByAttackers)
        return losses
    }

    /** Average [FrontStance.dealt] of a side, weighted by force; 1 when nobody is there. */
    private fun weightedDealt(side: List<Contribution>): Float {
        val total = side.sumOf { it.force.toDouble() }.toFloat()
        if (total <= 0f) return 1f
        return side.sumOf { (it.stance.dealt * it.force).toDouble() }.toFloat() / total
    }

    private fun previewOf(e: Engagement, division: MapUnit, intensity: FrontStance): AttackPreview {
        val losses = lossesOf(e)
        val defenderLoss = e.defenders.maxOfOrNull { losses[it.unit] ?: 0f } ?: 0f
        return AttackPreview(e.tile, intensity, e.pressure, e.resistance,
            (losses[division] ?: 0f).roundToInt(), defenderLoss.roundToInt(), e.defenders.size, e.attackers.size - 1)
    }

    /** Forecast of an attack of [intensity] by [division] on [tile], or null when the tile cannot be attacked. */
    fun previewAttack(division: MapUnit, tile: Tile, intensity: FrontStance): AttackPreview? {
        if (!canAttack(division)) return null
        val e = engagement(division, tile, intensity) ?: return null
        return previewOf(e, division, intensity)
    }

    /**
     * Launches an attack of [division] on [tile] and resolves it at once: losses on both sides, then, if the
     * attack succeeds, the defenders give way, the tile changes hands and the division advances onto it.
     * Returns null when the attack cannot be launched.
     */
    fun attack(division: MapUnit, tile: Tile, intensity: FrontStance): AttackResult? {
        if (!canAttack(division)) return null
        val e = engagement(division, tile, intensity) ?: return null
        val preview = previewOf(e, division, intensity)
        val civ = division.civ

        for ((unit, loss) in lossesOf(e)) unit.health = (unit.health - loss.roundToInt()).coerceAtLeast(0)
        division.currentMovement = 0f
        division.frontEntrenchment = 0f

        val spent = HashSet<MapUnit>()
        fun breakUpIfSpent(unit: MapUnit) {
            if (unit in spent || unit.health > FrontMath.DISSOLVE_HEALTH) return
            spent += unit
            unit.civ.addNotification("Our [${unit.name}] has broken up!",
                unit.currentTile.position, NotificationCategory.War, NotificationIcon.Death)
            freeCitizen(unit)
            unit.destroy()
        }
        e.attackers.forEach { breakUpIfSpent(it.unit) }
        e.defenders.forEach { breakUpIfSpent(it.unit) }

        // Defenders in strategic withdrawal fall back, and on a success the others standing on the tile give way too
        for (defender in e.defenders) {
            val unit = defender.unit
            if (unit in spent) continue
            val givesWay = defender.stance == FrontStance.Withdrawal || (preview.succeeds && unit.currentTile == tile)
            if (givesWay && !stepBack(unit)) {
                unit.health = (unit.health - FrontMath.CORNERED_LOSS).coerceAtLeast(0)
                breakUpIfSpent(unit)
            }
        }

        var tileTaken = false
        var cityTaken = false
        if (preview.succeeds && division !in spent) {
            val previousOwner = tile.getOwner()
            if (previousOwner != null && previousOwner != civ) {
                tileTaken = true
                val city = if (tile.isCityCenter()) tile.getCity() else null
                if (city != null) {
                    cityTaken = true
                    previousOwner.addNotification("After an attack of [${civ.civName}], [${city.name}] has fallen!",
                        tile.position, NotificationCategory.War, NotificationIcon.Death)
                    city.puppetCity(civ)
                } else {
                    nearestCity(civ, tile)?.expansion?.takeOwnership(tile)
                    previousOwner.addNotification("An attack of [${civ.civName}] took a tile from us!",
                        tile.position, NotificationCategory.War, NotificationIcon.War)
                }
            }
            advanceOnto(division, tile)
        }
        if (!preview.succeeds)
            e.defenderCiv.addNotification("We repelled an attack of [${civ.civName}].",
                tile.position, NotificationCategory.War, NotificationIcon.War)

        val outcome = when {
            cityTaken -> "Victory: the city is taken."
            tileTaken -> "Victory: the tile is taken."
            preview.succeeds -> "Victory: the defenders give way."
            else -> "Repelled: the attack failed."
        }
        val defenderPart = if (preview.defenders > 0) "the defenders lose up to ${preview.defenderLoss}" else "no enemy division there to lose troops"
        val message = "Attack on (${tile.position.x}, ${tile.position.y}), force ${preview.pressure.roundToInt()} " +
            "against ${preview.resistance.roundToInt()}. $outcome Our division loses ${preview.attackerLoss} health points, $defenderPart."
        return AttackResult(preview, tileTaken, message)
    }

    private fun advanceOnto(division: MapUnit, tile: Tile) {
        if (division.currentTile.aerialDistanceTo(tile) != 1 || tile.militaryUnit != null || !division.movement.canMoveTo(tile)) return
        division.removeFromTile()
        division.putInTile(tile)
        division.frontAnchor = tile.position
    }

    // endregion
    // region contact and burden

    /** Cities that can take the citizen a division occupies, the current one included. */
    fun burdenCandidates(division: MapUnit): List<City> = division.civ.cities.filter {
        it.id == division.frontHomeCityId || workersAvailable(it) >= FrontMath.MIN_CITIZENS_AT_WORK + 1
    }

    /** Moves the citizen a division occupies to another city of its civilization. */
    fun reassignBurden(division: MapUnit, city: City) {
        if (division.frontHomeCityId == city.id) return
        val previous = division.civ.cities.firstOrNull { it.id == division.frontHomeCityId }
        division.frontHomeCityId = city.id
        city.population.unassignExtraPopulation()
        city.cityStats.update()
        previous?.shouldReassignPopulation = true
    }

    /** Order in which a division looks at its contact tiles: enemy divisions, then enemy cities, then the rest. */
    private fun targetPriority(tile: Tile, civ: Civilization) = when {
        holdsEnemyDivision(tile, civ) -> 0
        tile.isCityCenter() -> 1
        else -> 2
    }

    private fun holdsEnemyDivision(tile: Tile, civ: Civilization): Boolean {
        val occupant = tile.militaryUnit ?: return false
        return isDivision(occupant) && occupant.civ != civ && civ.isAtWarWith(occupant.civ)
    }

    /**
     * A tile is in contact for [civ] when an enemy division stands on it, wherever it belongs, or when an enemy
     * owns it and it touches the territory or a unit of [civ].
     */
    fun isContactTile(tile: Tile, civ: Civilization): Boolean {
        if (holdsEnemyDivision(tile, civ)) return true
        val owner = tile.getOwner() ?: return false
        if (owner == civ || !civ.isAtWarWith(owner)) return false
        return tile.neighbors.any { it.getOwner() == civ || it.militaryUnit?.civ == civ }
    }

    /** Whether any tile of the zone of the division is in contact with the enemy. */
    fun isInContact(division: MapUnit): Boolean =
        division.currentTile.getTilesInDistance(FrontMath.ZONE_RADIUS).any { isContactTile(it, division.civ) }

    // endregion
    // region upkeep

    /** The citizen occupied by a division starts working again at the next reassignment. */
    private fun freeCitizen(division: MapUnit) {
        division.civ.cities.firstOrNull { it.id == division.frontHomeCityId }?.shouldReassignPopulation = true
    }

    private fun nearestCity(civ: Civilization, tile: Tile): City? =
        civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }

    private fun upkeep(division: MapUnit) {
        if (division.health <= FrontMath.DISSOLVE_HEALTH) {
            division.civ.addNotification("Our [${division.name}] has broken up!",
                division.currentTile.position, NotificationCategory.War, NotificationIcon.Death)
            freeCitizen(division)
            division.destroy()
            return
        }
        val posture = reactionOf(division)
        val moved = division.frontAnchor != division.currentTile.position
        division.frontEntrenchment = FrontMath.nextEntrenchment(division.frontEntrenchment, posture, moved)
        division.frontAnchor = division.currentTile.position
        reinforce(division, posture, isInContact(division))
    }

    /** The token steps toward the nearest own city, trading space for health. False when it has nowhere to go. */
    fun stepBack(division: MapUnit): Boolean {
        val civ = division.civ
        val here = division.currentTile
        val distance = { tile: Tile -> civ.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: Int.MAX_VALUE }
        val current = distance(here)
        val target = here.neighbors
            .filter { distance(it) < current && division.movement.canMoveTo(it) }
            .minWithOrNull(compareBy<Tile>({ distance(it) }, { it.position.x }, { it.position.y })) ?: return false
        division.removeFromTile()
        division.putInTile(target)
        division.frontAnchor = target.position
        division.frontEntrenchment = 0f
        return true
    }

    /** Health points a division regains, paid for in gold, only inside supplied own territory. */
    private fun reinforce(division: MapUnit, posture: FrontStance, inContact: Boolean) {
        val civ = division.civ
        val tile = division.currentTile
        if (tile.getOwner() != civ) return
        if (CombatCostCalculator.computeDistanceFactor(civ, tile) > FrontMath.SUPPLY_DISTANCE_FOR_REINFORCEMENT) return
        val wanted = when {
            !inContact -> FrontMath.REINFORCE_IN_SUPPLY
            posture == FrontStance.Defensive -> FrontMath.REINFORCE_IN_CONTACT
            else -> 0
        }
        val missing = 100 - division.health
        val points = minOf(wanted, missing)
        if (points <= 0) return
        val costPerPoint = 0.5f * (1 + civ.getEraNumber()) * posture.reinforcementCost
        val affordable = if (costPerPoint <= 0f) points else minOf(points, (civ.gold / costPerPoint).toInt())
        if (affordable <= 0) return
        civ.addGold(-(affordable * costPerPoint).roundToInt())
        division.health += affordable
    }

    // endregion
}
