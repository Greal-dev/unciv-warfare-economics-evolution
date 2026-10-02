package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.utils.randomWeighted
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

/** TW v2 — A freshly-spawned barbarian unit is only allowed on this tile when:
 *  - the tile is unowned (wild lands, no culture map yet), OR
 *  - the owner is the Barbarian civ itself, OR
 *  - the owned tile has at least 60 % "Barbarians" culture share, OR
 *  - the owner is Russia (Russia trait — barbarian-free territory).
 *
 *  Applied as a post-placement check in [BarbarianManager.trySpontaneousBarbarianSpawn]
 *  and [Encampment.spawnUnit] — if a freshly placed barbarian drifts (via
 *  [TileMap.placeUnitNearTile]'s neighbour search) onto a forbidden tile, it is
 *  immediately destroyed. */
@Readonly
internal fun barbSpawnAllowedOn(tile: Tile): Boolean {
    // TW v2 — Post-absorption pacification: a freshly absorbed city-state's former
    // territory is barbarian-free for a grace period (see TileCultureLogic absorption).
    if (tile.barbarianGraceTurns > 0) return false
    val owner = tile.getOwner() ?: return true
    if (owner.isBarbarian) return true
    if (owner.civName == "Russia") return false  // protected territory
    // TW v2 — Maritime borders: no barbarian ever spawns in a civ's owned territorial
    // waters. Coastal navies of major civs / city-states keep their waters clear.
    if (tile.isWater) return false
    val barbCulture = tile.cultureMap["Barbarians"] ?: 0f
    return barbCulture >= 0.60f
}

class BarbarianManager : IsPartOfGameInfoSerialization {

    val encampments = ArrayList<BarbarianEncampment>()

    /** TW v2 — Russia trait: any tile owned by Russia repels barbarians (no camps, no
     *  spontaneous uprisings). Russia is famously barbarian-free historically. */
    @Readonly
    private fun isRussianProtectedTile(tile: Tile): Boolean {
        return tile.getOwner()?.civName == "Russia"
    }

    @Transient
    lateinit var gameInfo: GameInfo

    @Transient
    lateinit var tileMap: TileMap

    fun clone(): BarbarianManager {
        val toReturn = BarbarianManager()
        toReturn.encampments.addAll(encampments.map { it.clone() })
        return toReturn
    }

    fun setTransients(gameInfo: GameInfo) {
        this.gameInfo = gameInfo
        this.tileMap = gameInfo.tileMap

        // Add any preexisting camps as Encampment objects

        val existingEncampmentLocations = encampments.asSequence().map { it.position }.toHashSet()

        for (tile in tileMap.values) {
            if (tile.isBarbarianEncampment() && !existingEncampmentLocations.contains(tile.position)) {
                encampments.add(BarbarianEncampment(tile.position))
            }
        }

        for (camp in encampments)
            camp.gameInfo = gameInfo
    }

    fun updateEncampments() {
        // Check if camps were destroyed
        for (encampment in encampments.toList()) { // tolist to avoid concurrent modification
            if (!tileMap[encampment.position].isBarbarianEncampment()) {
                encampment.wasDestroyed()
            }
            // Check if the ghosts are ready to depart
            if (encampment.destroyed && encampment.countdown == 0)
                encampments.remove(encampment)
        }

        // Possibly place a new encampment
        placeBarbarianEncampment()

        for (encampment in encampments) encampment.update()

        // TW: Spontaneous barbarian uprising on tiles with high barbarian culture
        trySpontaneousBarbarianSpawn()
    }

    /**
     * Territorial Warfare: barbarians can spawn spontaneously on tiles where
     * barbarian culture is dominant, even without a camp.
     * Simulates local populations rising up against a foreign occupier.
     */
    private fun trySpontaneousBarbarianSpawn() {
        if (gameInfo.turns < 15) return // too early

        val barbarianCiv = gameInfo.getBarbarianCivilization()

        // TW v2: during a global crisis, barbarian APPEARANCES are restricted to
        // frontier tiles only. They still raid deep inland — they just spawn at borders.
        val crisisActive = com.unciv.logic.map.TileCultureLogic.isGlobalCrisisActive(gameInfo)

        // TW v2 — Tightened thresholds.
        //  - Tile must have ≥60% barbarian culture (was 50%)
        //  - Spawn chance: 60%→0%, 100%→4%/turn (was 50%→0%, 100%→20%)
        //  - Empire-wide cap: at most 3 spontaneous spawns per turn world-wide
        //  - At least 4 tiles between concurrent barbarians (was 3)
        val candidates = mutableListOf<Tile>()
        for (civ in gameInfo.civilizations) {
            if (civ.isBarbarian || civ.isSpectator() || civ.isDefeated()) continue
            for (city in civ.cities) {
                for (pos in city.tiles) {
                    val tile = tileMap[pos]
                    if (tile.isCityCenter()) continue
                    if (tile.militaryUnit != null) continue
                    if (tile.isWater) continue
                    if (tile.barbarianGraceTurns > 0) continue  // TW v2 post-absorption pacification
                    if (isRussianProtectedTile(tile)) continue  // TW v2 Russia trait
                    if (crisisActive && !com.unciv.logic.map.TileCultureLogic.isFrontierTile(tile)) continue
                    val barbCulture = tile.cultureMap["Barbarians"] ?: 0f
                    if (barbCulture > 0.60f) candidates.add(tile)
                }
            }
        }

        if (candidates.isEmpty()) return

        candidates.shuffle()  // avoid bias toward early-iterated civs
        var spawnedThisTurn = 0
        val globalCap = 3

        for (tile in candidates) {
            if (spawnedThisTurn >= globalCap) break
            val barbCulture = tile.cultureMap["Barbarians"] ?: 0f
            val spawnChance = (barbCulture - 0.60f) * 0.10f  // 60%→0%, 80%→2%, 100%→4%
            if (Random.Default.nextFloat() >= spawnChance) continue

            if (tile.getTilesInDistance(4).count { it.militaryUnit?.civ?.isBarbarian == true } > 0) continue

            // Spawn a barbarian unit — use shared tech update
            updateBarbarianTech()

            val unitList = gameInfo.ruleset.units.values.filter {
                it.isMilitary
                    && !it.hasUnique(UniqueType.CannotAttack)
                    && !it.hasUnique(UniqueType.CannotBeBarbarian)
                    && it.isLandUnit
                    && it.isBuildable(barbarianCiv)
            }
            if (unitList.isEmpty()) continue

            val weightings = unitList.map { it.getForceEvaluation().toFloat() }
            val chosenUnit = unitList.randomWeighted(weightings,
                GameContext(gameInfo = gameInfo).stateBasedRandom("BarbarianManager.spontaneousUprising"))

            val spawned = tileMap.placeUnitNearTile(tile.position, chosenUnit, barbarianCiv)
            if (spawned != null) {
                if (!barbSpawnAllowedOn(spawned.currentTile)) {
                    spawned.destroy()
                    continue
                }
                spawnedThisTurn++
                tile.getOwner()?.addNotification(
                    "Barbarian uprising! Rebels have appeared near [${tile.getCity()?.name ?: "unknown"}]!",
                    tile.position,
                    NotificationCategory.War,
                    NotificationIcon.War
                )
            }
        }
    }

    /** Called when an encampment was attacked, will speed up time to next spawn */
    fun campAttacked(position: HexCoord) {
        encampments.firstOrNull { it.position == position }?.wasAttacked()
    }

    fun placeBarbarianEncampment(forTesting: Boolean = false) {
        val rng = gameInfo.getBarbarianCivilization().state.stateBasedRandom("BarbarianManager.placeBarbarianEncampent", encampments.hashCode())
        // Before we do the expensive stuff, do a roll to see if we will place a camp at all
        if (!forTesting && gameInfo.turns > 1 && rng.nextBoolean())
            return

        // Barbarians will only spawn in places that no one can see
        val allViewableTiles = gameInfo.civilizations.asSequence().filterNot { it.isBarbarian || it.isSpectator() }
            .flatMap { it.viewableTiles }.toHashSet()
        val fogTiles = tileMap.values.filter { it.isLand && it !in allViewableTiles }

        val fogTilesPerCamp = (tileMap.values.size.toFloat().pow(0.4f)).toInt() // Approximately

        // Check if we have more room
        var campsToAdd = (fogTiles.size / fogTilesPerCamp) - encampments.count { !it.destroyed }

        // First turn of the game add 1/3 of all possible camps
        if (gameInfo.turns == 1) {
            campsToAdd /= 3
            campsToAdd = max(campsToAdd, 1) // At least 1 on first turn
        } else if (campsToAdd > 0)
            campsToAdd = 1

        if (campsToAdd <= 0) return

        // Camps can't spawn within 7 tiles of each other or within 4 tiles of major civ capitals
        val tooCloseToCapitals = gameInfo.civilizations.filterNot { it.isBarbarian || it.isSpectator() || it.cities.isEmpty() || it.isCityState || it.getCapital() == null }
            .flatMap { it.getCapital()!!.getCenterTile().getTilesInDistance(4) }.toSet()
        val tooCloseToCamps = encampments
            .flatMap { tileMap[it.position].getTilesInDistance(
                    if (it.destroyed) 4 else 7
            ) }.toSet()

        val viableTiles = fogTiles.filter {
            !it.isImpassible()
                    && it.resource == null
                    && it.terrainFeatureObjects.none { feature -> feature.hasUnique(UniqueType.RestrictedBuildableImprovements) }
                    && it.neighbors.any { neighbor -> neighbor.isLand }
                    && it !in tooCloseToCapitals
                    && it !in tooCloseToCamps
                    && it.barbarianGraceTurns == 0  // TW v2 post-absorption pacification
                    && !isRussianProtectedTile(it)
        }.toMutableList()

        var tile: Tile?
        var addedCamps = 0
        var biasCoast = rng.nextInt(6) == 0

        // Add the camps
        while (addedCamps < campsToAdd) {
            if (viableTiles.isEmpty())
                break

            // If we're biasing for coast, get a coast tile if possible
            if (biasCoast) {
                tile = viableTiles.filter { it.isAdjacentToCoast() }.randomOrNull(rng)
                if (tile == null)
                    tile = viableTiles.random(rng)
            } else
                tile = viableTiles.random(rng)

            createNewCamp(tile, rng)
            notifyCivsOfBarbarianEncampment(tile)
            addedCamps++

            // Still more camps to add?
            if (addedCamps < campsToAdd) {
                // Remove some newly non-viable tiles
                viableTiles.removeAll(tile.getTilesInDistance(7).toSet())
                // Reroll bias
                biasCoast = rng.nextInt(6) == 0
            }
        }
    }

    /**
     * [CivilizationInfo.addNotification][Add a notification] to every civilization that have
     * adopted Honor policy and have explored the [tile] where the Barbarian Encampment has spawned.
     */
    private fun notifyCivsOfBarbarianEncampment(tile: Tile) {
        for (civ in gameInfo.civilizations) {
            if (!civ.hasExplored(tile)) continue
            if (!civ.hasUnique(UniqueType.NotifiedOfBarbarianEncampments)) continue
            civ.addNotification("A new barbarian encampment has spawned!", tile.position, NotificationCategory.War, NotificationIcon.War)
            civ.setLastSeenImprovement(tile.position, tile.improvement)
        }
    }
    /**
     * TW: Update barbarian tech to match majority of civs.
     * Shared between camp spawns and spontaneous uprisings.
     */
    fun updateBarbarianTech() {
        val barbarianCiv = gameInfo.getBarbarianCivilization()
        val aliveMajorCivs = gameInfo.civilizations.filter {
            !it.isBarbarian && !it.isDefeated() && !it.isSpectator() && it.isMajorCiv()
        }
        if (aliveMajorCivs.isEmpty()) return
        val threshold = aliveMajorCivs.size / 2
        val techsToGive = gameInfo.ruleset.technologies.keys.filter { tech ->
            aliveMajorCivs.count { it.tech.techsResearched.contains(tech) } > threshold
        }
        barbarianCiv.tech.techsResearched = techsToGive.toHashSet()
    }

    /** Creates a new camp without any checks - Does not affect notifications */
    fun createNewCamp(tile: Tile, rng: Random = tile.stateThisTile.stateBasedRandom("createNewCamp")) {
        val candidates = this.gameInfo.ruleset.tileImprovements.values
            .filter { it.isBarbarianCampEquivalent(tile.stateThisTile) }
        val improvement = candidates.randomOrNull(rng)?.name ?: Constants.barbarianEncampment
        tile.setImprovement(improvement)
        val newCamp = BarbarianEncampment(tile.position)
        newCamp.gameInfo = gameInfo
        encampments.add(newCamp)
    }

    /**
     * Attempts to spawn a random barbarian [MapUnit] at or near the provided [Tile].
     * @param allegiance Which [Civilization] should the unit belong to? Defaults to the barbarian civilization.
     *                   If non-barbarian, there are fewer restrictions on whether a unit can be spawned or not.
     * @return The newly spawned [MapUnit], or null if unsuccessful.
     */
    fun spawnBarbarian(
        tile: Tile,
        allegiance: Civilization = gameInfo.getBarbarianCivilization()
    ): MapUnit? {
        if (allegiance.isBarbarian) {
            // Empty camp - spawn a defender
            if (tile.militaryUnit == null)
                // Try spawning a unit on this tile, return null if unsuccessful
                return spawnUnit(tile.position, false)

            // Don't spawn wandering barbs too early
            if (gameInfo.turns < 10)
                return null

            var nearbyBarbarians = 0
            tile.forEachTileInDistance(4) { if (it.militaryUnit?.civ?.isBarbarian == true) nearbyBarbarians++ }
            // Too many barbarians around already? TW: raised cap from 2 to 4
            if (nearbyBarbarians > 4)
                return null
        }

        val canSpawnNaval = gameInfo.turns > 30
        val validTiles = tile.neighbors.toList().filterNot {
            it.isImpassible()
                || it.isCityCenter()
                || it.getFirstUnit() != null
                || (it.isWater && !canSpawnNaval)
                || (it.terrainHasUnique(UniqueType.FreshWater) && it.isWater) // No Lakes
        }
        if (validTiles.isEmpty())
            return null

        val rng = gameInfo.getBarbarianCivilization().state.stateBasedRandom("BarbarianManager.spawnBarbarian")
        // Attempt to spawn a barbarian on a valid tile
        return spawnUnit(tile.position, validTiles.random(rng).isWater, allegiance) 
    }

    /** Attempts to spawn a barbarian on [position], returns true if successful and false if unsuccessful. */
    private fun spawnUnit(
        position: HexCoord,
        naval: Boolean,
        allegiance: Civilization = gameInfo.getBarbarianCivilization()
    ): MapUnit? {
        updateBarbarianTech()
        val unitToSpawn = chooseBarbarianUnit(naval)
            ?: return null // return null if we didn't find a unit
        val spawnedUnit = gameInfo.tileMap.placeUnitNearTile(position, unitToSpawn, allegiance)
            ?: return null
        // TW v2: forbid landing on tiles where barbarians have <60% cultural share
        // (Russia tiles, deep-civilian-territory of any major civ, etc.)
        if (!barbSpawnAllowedOn(spawnedUnit.currentTile)) {
            spawnedUnit.destroy()
            return null
        }
        return spawnedUnit
    }

    @Readonly
    private fun chooseBarbarianUnit(naval: Boolean): BaseUnit? {
        // if we don't make this into a separate list then the retain() will happen on the Tech keys,
        // which effectively removes those techs from the game and causes all sorts of problems
        val barbarianCiv = gameInfo.getBarbarianCivilization()
        val unitList = gameInfo.ruleset.units.values
            .filter { it.isMilitary &&
                !(it.hasUnique(UniqueType.CannotAttack) ||
                    it.hasUnique(UniqueType.CannotBeBarbarian)) &&
                (if (naval) it.isWaterUnit else it.isLandUnit) &&
                it.isBuildable(barbarianCiv) }

        if (unitList.isEmpty()) return null // No naval tech yet? Mad modders?

        // Civ V weights its list by FAST_ATTACK or ATTACK_SEA AI types, we'll do it a bit differently
        // getForceEvaluation is already conveniently biased towards fast units and against ranged naval
        val weightings = unitList.map { it.getForceEvaluation().toFloat() }
        val rng = GameContext(gameInfo = gameInfo).stateBasedRandom("BarbarianManager.chooseBarbarianUnit")

        return unitList.randomWeighted(weightings, rng)
    }

    /** Return a _mutable_ List of encampment tiles, allowing in-place sort */
    fun getEncampmentTiles() = encampments.mapTo(mutableListOf()) { gameInfo.tileMap[it.position] }
}
