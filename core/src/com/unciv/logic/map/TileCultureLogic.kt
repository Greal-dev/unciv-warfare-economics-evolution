package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import yairm210.purity.annotations.Readonly

/**
 * Territorial Warfare: tile-level culture system.
 *
 * Each tile has a [Tile.cultureMap] tracking cultural composition (civName -> 0.0–1.0).
 * Unowned tiles start at 100% "Barbarians".
 *
 * Culture sources:
 * - Base owner growth: +1%/turn
 * - Adjacent city center (any civ): +3%/turn for that civ
 * - Military garrison (any civ): +10%/turn for that civ
 * - Tile diffusion: each neighbor pushes its composition proportionally (1%/neighbor/turn)
 *
 * All civs compete for cultural influence simultaneously.
 */
object TileCultureLogic {

    private const val GRACE_TURNS = 10
    // TW v2 — When a city-state is culturally absorbed, its whole former territory is
    // pacified: no barbarian may spawn there for this many turns.
    private const val ABSORPTION_BARBARIAN_GRACE_TURNS = 40
    // TW v2 — Lowered from 0.70 / 0.30 after dry-run showed zero cultural revolts in 200 turns.
    // Tighter thresholds force more frequent dynamic shifts and let the new garrison/identity
    // rules actually fire instead of staying dormant.
    private const val REBELLION_THRESHOLD = 0.60f   // rebellion if foreign culture > 60%
    private const val REBELLION_OWNER_MIN = 0.40f   // rebellion if owner < 40% AND barbarians+foreign > 50%
    private const val BARBARIAN_DECAY_OWNER = 0.40f // rebellion if owner < 40% AND barbarians > 25% → tile becomes neutral
    private const val BARBARIAN_DECAY_THRESHOLD = 0.25f
    private const val CULTURAL_CONQUEST_THRESHOLD = 0.70f // neutral tile claimed at 70%+
    private const val FULL_PRODUCTIVITY_THRESHOLD = 0.80f // 100% yield at 80%+ owner culture
    private const val SECESSION_TURNS = 3           // turns without garrison before secession
    private const val GARRISON_ATTRITION = 15       // HP lost per turn on rebelling tile

    // Culture influence rates per turn (absolute points added before normalization)
    private const val BASE_GROWTH = 0.005f          // owner's natural assimilation
    private const val CITY_INFLUENCE_ADJ = 0.04f    // from adjacent city center (distance 1) — ×2
    private const val CITY_INFLUENCE_NEAR = 0.02f   // from city center at distance 2 — ×2
    private const val CITY_INFLUENCE_FAR3 = 0.01f   // distance 3 — always active (radius ≥3 from start)
    private const val CITY_INFLUENCE_FAR4 = 0.005f  // distance 4 — Renaissance+ projecting civ
    private const val CITY_INFLUENCE_FAR5 = 0.003f  // distance 5 — Atomic+ projecting civ
    private const val BARBARIAN_NO_CITY_4 = 0.01f   // barbarian growth if no city within (4 + radiusBonus) tiles
    private const val BARBARIAN_NO_CITY_5 = 0.02f   // barbarian growth if no city within (5 + radiusBonus) tiles
    private const val BARBARIAN_NO_CITY_6 = 0.03f   // barbarian growth if no city within (6 + radiusBonus)+ tiles
    private const val BARBARIAN_DESERT = 0.01f      // extra barbarian pressure on desert
    private const val BARBARIAN_JUNGLE = 0.01f      // extra barbarian pressure on jungle
    private const val RIVER_DIFFUSION_FACTOR = 0.5f // cultural diffusion crossing a river is halved (unless bridged by road)
    private const val BORDER_DIFFUSION_FACTOR = 0.5f // TW v2 — cultural diffusion across a political
                                                     // border (between two different civ owners) is
                                                     // halved. Models language/customs/administration
                                                     // friction at the frontier. Stacks with river.
    private const val GARRISON_PACIFICATION = 0.02f // garrison converts 2% of foreign culture per turn
                                                    // (slow occupation: historically conversion takes
                                                    // generations, not a decade)
    private const val CAPITAL_PROJECTION_RATE = 0.02f // national culture drip from capital onto each
                                                       // land-connected non-capital city's centre.
                                                       // Replaces the old "connected city projects 100%
                                                       // national" rule with a slow homogenisation.
    private const val WONDER_FOUNDER_GRACE_TURNS = 10 // a wonder keeps projecting the city's founding
                                                       // civ's culture for this many turns after a
                                                       // change of ownership.
    private const val DIFFUSION_RATE = 0.02f        // per neighbor, proportional to neighbor's composition
    private const val DIFFUSION_ABSORPTION_FLOOR = 0.3f // tile-to-tile exchange still trickles into
                                                        // wilderness, bypassing the city-projection
                                                        // mesh requirement (just a population whisper).
    private const val ROAD_CITY_BONUS = 0.01f       // per nearby city on a tile with road
    private const val WONDER_AURA_RATE = 0.005f     // per wonder in city, per turn (capped)
    private const val WONDER_AURA_RADIUS = 4        // tiles around city projecting wonder aura
    private const val WONDER_AURA_CAP_PER_CITY = 3  // max wonders contributing per city
    private const val UNIT_NEIGHBOR_AURA = 0.01f    // a military unit projects 1%/turn on adjacent tiles

    // TW: Russia trait — wider cultural reach and stronger projection per distance band
    private const val RUSSIA_CIV = "Russia"
    private const val RUSSIA_RADIUS_BONUS = 1        // Russian cities reach +1 tile further than peers (per era band)
    private const val RUSSIA_DISTANCE_BONUS = 0.01f  // Russian cities project an extra +1%/turn for their civ at every reached distance

    // Passive spread to unowned neighbors
    private const val PASSIVE_SPREAD = 0.02f
    private const val CAPITAL_ADJ_BONUS = 0.05f       // original capital bonus on adjacent tiles

    // Global civilizational crises — each crisis has its own year and duration
    private val CRISES = arrayOf(
        Pair(-1200, 5),   // 1200 BC — Bronze Age collapse, 5 turns (halved from 10)
        Pair(450, 20),    // 450 AD — Fall of Rome, 20 turns
        Pair(2030, 20)    // 2030 AD — Late modern collapse, 20 turns
    )
    private const val CRISIS_BARBARIAN_ALL = 0.05f    // +5% barbarian pressure on ALL tiles during crisis
    private const val CRISIS_BARBARIAN_BORDER = 0.05f // +5% barbarian pressure on border tiles during crisis
    private const val CRISIS_CONQUERED_BARBARIAN = 0.02f  // +2% barbarian per conquered city nearby during crisis
    private const val CRISIS_CONQUERED_RADIUS = 3         // radius around conquered cities for localized pressure
    private const val CRISIS_CITY_CAPITAL_PRESSURE = 0.10f // founding civ pressure on conquered original capitals
    private const val CRISIS_CITY_OTHER_PRESSURE = 0.05f   // founding civ pressure on other conquered cities

    // City culture dynamics
    private const val CITY_SECESSION_TURNS = 2        // city centers rebel faster than regular tiles
    private const val CITY_OWNER_PROJECTION = 0.25f   // 25% of city influence = owner civ
    private const val CITY_COMPOSITION_PROJECTION = 0.75f // 75% = city center's own cultural composition
    private const val CITY_FOUNDING_OWNER_SHARE = 0.90f   // newly founded city = 90% founder

    // Colonial era: REMOVED — no longer suppresses rebellions/secessions


    /** TW v2 — Cultural radius grows with era, mirroring (and extending) the
     *  era-progressive city work range. Tight diffusion early forces dense
     *  early-game settlement; broad late-game projection rewards consolidation.
     *
     *   Ancient..Classical (0-1)      → 2 tiles
     *   Medieval..Renaissance (2-3)   → 3 tiles
     *   Industrial..Modern (4-5)      → 4 tiles
     *   Atomic..Information (6-8)     → 5 tiles
     *
     *  Russia gets RUSSIA_RADIUS_BONUS (+1) on top of the base, peaking at 6 tiles. */
    @Readonly
    fun getCityCultureRadius(eraNumber: Int): Int = when {
        eraNumber >= 6 -> 5
        eraNumber >= 4 -> 4
        eraNumber >= 2 -> 3
        else -> 2
    }

    /** TW: Effective culture-projection radius for a given city. Russian cities reach
     *  one tile further than peers at the same era. */
    @Readonly
    fun getCityCultureRadius(city: com.unciv.logic.city.City): Int {
        val base = getCityCultureRadius(city.civ.getEraNumber())
        return if (city.civ.civName == RUSSIA_CIV) base + RUSSIA_RADIUS_BONUS else base
    }

    /** Bonus added to barbarian-pressure distance thresholds based on the most
     *  advanced era in play (or the tile owner's era if any). */
    @Readonly
    private fun getBarbarianRadiusBonus(tile: Tile, closestCityCivName: String? = null): Int {
        val ownerCiv = tile.getOwner()
        val era = ownerCiv?.getEraNumber() ?: run {
            var maxEra = 1
            for (civ in tile.tileMap.gameInfo.civilizations) {
                if (!civ.isAlive() || civ.isBarbarian) continue
                val e = civ.getEraNumber()
                if (e > maxEra) maxEra = e
            }
            maxEra
        }
        // TW: Russia pushes its barbarian-pressure threshold one tile further than peers,
        // either through tile ownership or through being the closest city to an unowned tile.
        val russiaBonus = if (ownerCiv?.civName == RUSSIA_CIV
            || closestCityCivName == RUSSIA_CIV) RUSSIA_RADIUS_BONUS else 0
        return getCityCultureRadius(era) - 3 + russiaBonus
    }

    /** Adjacent military units project [UNIT_NEIGHBOR_AURA] per turn for their civ on
     *  this tile. Stacks across multiple neighboring units (e.g. an army on the border
     *  projects strongly into the adjacent enemy hinterland). Barbarian units project
     *  for "Barbarians".
     *  TW v2 — Units transiting foreign territory under an Open Borders treaty exert
     *  NO cultural influence: peaceful passage is not occupation. The same suppression
     *  also applies to the garrison pacification on the tile the unit stands on. */
    private fun addUnitNeighborAura(tile: Tile, influences: HashMap<String, Float>) {
        for (neighbor in tile.neighbors) {
            val unit = neighbor.militaryUnit ?: continue
            if (isTransitingUnderOpenBorders(unit, neighbor)) continue
            val civName = if (unit.civ.isBarbarian) "Barbarians" else unit.civ.civName
            addInfluence(influences, civName, UNIT_NEIGHBOR_AURA)
        }
    }

    /** TW v2 — A military unit sitting on foreign-owned territory under an Open Borders
     *  treaty (or a city-state's allied-territory equivalent) is in peaceful transit and
     *  must NOT project culture: the suzerain has consented to passage, not assimilation.
     *  Returns true only when the unit's civ is genuinely a foreign guest on this tile. */
    @Readonly
    private fun isTransitingUnderOpenBorders(
        unit: com.unciv.logic.map.mapunit.MapUnit,
        tile: Tile
    ): Boolean {
        if (unit.civ.isBarbarian) return false
        val tileOwner = tile.getOwner() ?: return false
        if (tileOwner == unit.civ) return false
        val diplo = tileOwner.getDiplomacyManager(unit.civ) ?: return false
        return diplo.hasOpenBorders
    }

    /** Wonder soft-power aura: each city with built wonders projects extra culture for
     *  its civ on tiles within [WONDER_AURA_RADIUS] of the city center. Each wonder adds
     *  [WONDER_AURA_RATE] up to [WONDER_AURA_CAP_PER_CITY].
     *  TW v2 — also blocked by a Mountain hex or by 2+ consecutive Ocean hexes on the straight line. */
    private fun addWonderAura(tile: Tile, influences: HashMap<String, Float>) {
        val seenCities = HashSet<com.unciv.logic.city.City>()
        for (otherTile in tile.getTilesInDistance(WONDER_AURA_RADIUS)) {
            if (!otherTile.isCityCenter()) continue
            val city = otherTile.getCity() ?: continue
            if (!seenCities.add(city)) continue
            val wonderCount = city.cityConstructions.getBuiltBuildings()
                .count { it.isAnyWonder() }
                .coerceAtMost(WONDER_AURA_CAP_PER_CITY)
            if (wonderCount <= 0) continue
            if (!hasUnblockedPath(otherTile, tile)) continue  // wonders behind mountains / across seas don't radiate
            addInfluence(influences, getWonderProjectingCivName(city), WONDER_AURA_RATE * wonderCount)
        }
    }

    /** TW v2 — A wonder stays culturally attached to its city's founding civ for
     *  [WONDER_FOUNDER_GRACE_TURNS] after a change of ownership: Notre-Dame doesn't become English
     *  the morning the English take Paris — it takes a few decades for the local cultural relevance
     *  to shift. Once the grace window expires, the wonder projects the current owner's culture. */
    @Readonly
    private fun getWonderProjectingCivName(city: com.unciv.logic.city.City): String {
        val founder = city.foundingCivObject
        if (founder != null && founder != city.civ) {
            val turnsSinceAcquisition = city.civ.gameInfo.turns - city.turnAcquired
            if (turnsSinceAcquisition < WONDER_FOUNDER_GRACE_TURNS) return founder.civName
        }
        return city.civ.civName
    }

    /** Add city-center cultural influence at distances 3 to 5 (long-range projection).
     *  Each contributing city projects only if its civ's era allows that distance.
     *  Distance 1 and 2 are handled separately upstream (they keep mountain blocking).
     *  TW v2 — also blocked by a Mountain hex or by 2+ consecutive Ocean hexes on the straight line. */
    private fun addLongRangeCityInfluences(tile: Tile, influences: HashMap<String, Float>): Boolean {
        var found = false
        // Loop up to 6 to allow Russia (radius +1) to reach distance 6 in late eras.
        for (distance in 3..6) {
            val rate = when (distance) {
                3 -> CITY_INFLUENCE_FAR3
                4 -> CITY_INFLUENCE_FAR4
                else -> CITY_INFLUENCE_FAR5  // distances 5 and 6 share the same baseline rate
            }
            for (otherTile in tile.getTilesAtDistance(distance)) {
                if (!otherTile.isCityCenter()) continue
                val otherCity = otherTile.getCity() ?: continue
                if (distance > getCityCultureRadius(otherCity)) continue
                if (!hasUnblockedPath(otherTile, tile)) continue  // mountains / wide seas block projection
                addCityInfluence(influences, otherTile, rate)
                // TW: Russia projects an extra +1%/turn for its civ at every reached distance.
                if (otherCity.civ.civName == RUSSIA_CIV) {
                    addInfluence(influences, RUSSIA_CIV, RUSSIA_DISTANCE_BONUS)
                }
                found = true
            }
        }
        return found
    }

    /** Ocean and ice tiles have no population — excluded from culture system. */
    @Readonly
    private fun isExcludedFromCulture(tile: Tile): Boolean =
        tile.isOcean || tile.baseTerrain == Constants.ice || tile.terrainFeatures.contains(Constants.ice)

    /** Check if a global civilizational crisis is active.
     *  A crisis is active if the crisis year was crossed within the last N turns (per-crisis duration). */
    @Readonly
    fun isGlobalCrisisActive(gameInfo: com.unciv.logic.GameInfo): Boolean {
        val currentYear = gameInfo.getYear()
        return CRISES.any { (crisisYear, duration) ->
            val yearAtDurationAgo = gameInfo.getYear(-duration)
            currentYear >= crisisYear && yearAtDurationAgo < crisisYear
        }
    }

    /** Mountains are absolute cultural barriers (no projection at all).
     *  Coasts are barriers toward land only — they project to other water tiles. */
    @Readonly
    private fun isCulturalBarrier(source: Tile, target: Tile): Boolean {
        if (source.baseTerrain == Constants.mountain) return true
        if (source.baseTerrain == Constants.coast && target.isLand) return true  // coast → land blocked
        return false
    }

    /** TW: Cultural diffusion is halved when crossing a river, unless a road bridges both sides. */
    @Readonly
    private fun riverDiffusionFactor(source: Tile, target: Tile): Float {
        if (!source.isConnectedByRiver(target)) return 1f
        val sourceRoad = source.getUnpillagedRoad() != com.unciv.logic.map.tile.RoadStatus.None
        val targetRoad = target.getUnpillagedRoad() != com.unciv.logic.map.tile.RoadStatus.None
        if (sourceRoad && targetRoad) return 1f
        return RIVER_DIFFUSION_FACTOR
    }

    /** TW v2 — Cultural diffusion is halved when crossing a political border (the source and
     *  target tiles belong to different civilisations). Unowned tiles don't count as a border:
     *  spreading into / out of wilderness is unimpeded. */
    @Readonly
    private fun borderDiffusionFactor(source: Tile, target: Tile): Float {
        val sourceOwner = source.getOwner() ?: return 1f
        val targetOwner = target.getOwner() ?: return 1f
        return if (sourceOwner != targetOwner) BORDER_DIFFUSION_FACTOR else 1f
    }

    /** TW: Check if a city is a conquered enemy capital (behaves culturally like a puppet). */
    @Readonly
    private fun isConqueredCapital(city: com.unciv.logic.city.City): Boolean =
        city.isOriginalCapital && city.foundingCivObject != null && city.foundingCivObject != city.civ

    /** TW v2: Is this city connected to the capital by a pure land route (Road or Railroad,
     *  no maritime hop) ? Maritime-only connections do not count as land-connected. */
    @Readonly
    private fun isLandConnectedToCapital(city: com.unciv.logic.city.City): Boolean {
        return city.isConnectedToCapital { mediums ->
            mediums.contains(com.unciv.logic.civilization.transients.CapitalConnectionsFinder.CapitalConnectionMedium.Road)
                || mediums.contains(com.unciv.logic.civilization.transients.CapitalConnectionsFinder.CapitalConnectionMedium.Railroad)
        }
    }

    /** TW v2: Is this city connected to the capital, but only through a maritime hop
     *  (Harbor / HarborFromRoad / HarborFromRailroad) — i.e. there is no continuous land path ? */
    @Readonly
    private fun isOnlySeaConnectedToCapital(city: com.unciv.logic.city.City): Boolean {
        if (isLandConnectedToCapital(city)) return false
        return city.isConnectedToCapital()  // any remaining connection is via Harbor*
    }

    /** TW: Get the culture name a city projects.
     *  Own capital → civ culture (the capital is the civ's cultural fountainhead).
     *  Conquered enemy capital → founding civ's culture (still projects the original culture).
     *  All other cities → local culture (city name). National culture only reaches non-capital
     *  cities slowly, via the [CAPITAL_PROJECTION_RATE] drip flowing along roads. */
    @Readonly
    fun getCultureName(city: com.unciv.logic.city.City): String {
        if (city.isCapital() && !isConqueredCapital(city)) return city.civ.civName
        if (isConqueredCapital(city)) return city.foundingCivObject!!.civName
        return city.name
    }

    /** TW: Get the culture split for a city — how much goes to local vs national in its projection.
     *  Own capital: 0% local / 100% national — the capital IS the national culture.
     *  Conquered enemy capital: 70% founding civ, 30% current owner.
     *  Puppet cities: 70% local, 30% national.
     *  Sea-only connected: 80% local, 20% national (island identity).
     *  Everyone else (land-connected, disconnected): 100% local, 0% national. Capitals push national
     *  culture into land-connected cities slowly via [CAPITAL_PROJECTION_RATE] — homogenisation is
     *  no longer instantaneous on annexation. */
    @Readonly
    fun getCultureSplit(city: com.unciv.logic.city.City): Pair<Float, Float> {
        if (city.isCapital() && !isConqueredCapital(city)) return Pair(0f, 1f)
        if (isConqueredCapital(city)) return Pair(0.70f, 0.30f)
        if (city.isPuppet) return Pair(0.70f, 0.30f)
        if (isOnlySeaConnectedToCapital(city)) return Pair(0.80f, 0.20f)
        return Pair(1f, 0f)
    }

    /** TW v2 — Logarithmic boost of a city's cultural projection based on its culture/turn output.
     *  k = 0.4 (moderate, recalibrated after first dry-run revealed Carthage snowballing to 2.5×
     *  the average territory). A typical mid-game city (~20 culture/turn) gets ~1.5x; an ambitious
     *  capital (~100 culture/turn) gets ~1.85x; a wonder-stuffed late-game megacity (~300/turn)
     *  gets ~2.3x. Diminishing returns by construction. Falls back to 1f for cities still in
     *  transient init. Multiplies all per-turn rates emitted via [addCityInfluence] and the
     *  garrison pacification. */
    @Readonly
    fun getCityCultureBoost(city: com.unciv.logic.city.City): Float {
        val culturePerTurn = try {
            city.cityStats.currentCityStats.culture
        } catch (_: Throwable) {
            return 1f  // city stats not yet computed (e.g. just-founded city this turn)
        }
        if (culturePerTurn <= 0f) return 1f
        return 1f + 0.4f * kotlin.math.ln(1f + culturePerTurn)
    }

    /** TW v2 — Tile-level cultural inflow multiplier.
     *  Beyond a city's immediate ring (distance > 1), a tile only absorbs cultural influence in
     *  proportion to its civilising infrastructure.
     *    - City center, or adjacent to a city center  → multiplier 1.0 (city's intrinsic aura)
     *    - Road tile, or adjacent to a roaded tile    → multiplier 1.0 (road conduit + 1-ring aura)
     *    - Improvement only                           → multiplier 0.5 (partial absorption)
     *    - Wilderness                                 → multiplier 0.0 (no incoming culture)
     *  Roads carry culture along their length (road → road) and project a 1-tile cultural aura
     *  the same way cities do — this is the structural reason civilisations are pushed to mesh
     *  their empire with a dense road network to project culture inland and seal their territory. */
    @Readonly
    fun getCulturalInflowMultiplier(tile: Tile): Float {
        if (tile.isCityCenter()) return 1f
        if (tile.neighbors.any { it.isCityCenter() }) return 1f
        val hasRoad = tile.getUnpillagedRoad() != com.unciv.logic.map.tile.RoadStatus.None
        if (hasRoad) return 1f
        if (tile.neighbors.any { it.getUnpillagedRoad() != com.unciv.logic.map.tile.RoadStatus.None }) return 1f
        // Outside road auras, an improvement still provides partial cultural absorption.
        if (tile.improvement != null && !tile.improvementIsPillaged) return 0.5f
        return 0f
    }

    /** TW v2 — Check whether a hex line between [source] and [target] is unobstructed by a
     *  Mountain hex or by two consecutive Ocean hexes. Used to gate long-range cultural
     *  projection (cities behind a mountain range or across an ocean cannot directly
     *  imprint culture on far tiles). Cheap line walk; intermediate hexes only. */
    @Readonly
    private fun hasUnblockedPath(source: Tile, target: Tile): Boolean {
        val dist = source.aerialDistanceTo(target)
        if (dist <= 1) return true
        val tileMap = source.tileMap
        var consecutiveOcean = 0
        // Walk a straight line, sampling intermediate hexes (exclude both endpoints).
        for (step in 1 until dist) {
            val t = step.toFloat() / dist.toFloat()
            val ix = source.position.x + (target.position.x - source.position.x) * t
            val iy = source.position.y + (target.position.y - source.position.y) * t
            val mid = tileMap.getIfTileExistsOrNull(
                kotlin.math.round(ix).toInt(),
                kotlin.math.round(iy).toInt()
            ) ?: continue
            if (mid.baseTerrain == Constants.mountain) return false
            if (mid.isOcean) {
                consecutiveOcean++
                if (consecutiveOcean >= 2) return false
            } else {
                consecutiveOcean = 0
            }
        }
        return true
    }

    /** TW: Get the total "friendly" culture share on a tile for a civilization.
     *  Sums the civ's national culture + all local city cultures belonging to that civ.
     *  This is used for yield calculation, science penalty, and rebellion checks. */
    @Readonly
    fun getFriendlyShare(tile: Tile, civ: Civilization): Float {
        if (tile.cultureMap.isEmpty()) return 0f
        var total = tile.cultureMap[civ.civName] ?: 0f
        // Add local city cultures belonging to this civ
        for (city in civ.cities) {
            val cityShare = tile.cultureMap[city.name] ?: 0f
            if (cityShare > 0f) total += cityShare
        }
        return total.coerceAtMost(1f)
    }

    /** TW: Check if a culture name belongs to a civilization (national or local city). */
    @Readonly
    private fun isFriendlyCulture(cultureName: String, civ: Civilization): Boolean {
        if (cultureName == civ.civName) return true
        return civ.cities.any { it.name == cultureName }
    }

    /** TW v2 — Border-creep guard for cultural flips.
     *  A tile may only switch to civilisation [civ] when it touches a NON-MARITIME tile already
     *  owned by [civ]. Cultural conquest, secession, and unowned-tile claims propagate by
     *  contiguous land creep — never by appearing as enclaves in the middle of recent conquests. */
    @Readonly
    private fun hasLandBorderAdjacency(tile: Tile, civ: Civilization): Boolean {
        return tile.neighbors.any { it.getOwner() == civ && !it.isWater }
    }

    /** Check if a tile is on a border between two different civilizations. */
    @Readonly
    private fun isBorderTile(tile: Tile): Boolean {
        val owner = tile.getOwner() ?: return false
        return tile.neighbors.any { neighbor ->
            val neighborOwner = neighbor.getOwner()
            neighborOwner != null && neighborOwner != owner && !neighborOwner.isBarbarian
        }
    }

    /** TW v2 — Crisis-mode border check: a tile counts as "border" if it touches
     *  unowned land OR is adjacent to a foreign civ. Used to gate where barbarians
     *  may APPEAR during a global crisis (deep-empire spawns are forbidden). */
    @Readonly
    fun isFrontierTile(tile: Tile): Boolean {
        val owner = tile.getOwner() ?: return true  // unowned = always frontier
        return tile.neighbors.any { neighbor ->
            val neighborOwner = neighbor.getOwner()
            neighborOwner == null || (neighborOwner != owner && !neighborOwner.isBarbarian)
        }
    }

    /**
     * Called once per civ at end of turn, after city endTurns but before unit endTurns.
     * Processes all tiles owned by [civ] + passive spread to unowned neighbors.
     */
    fun processCivTiles(civ: Civilization) {
        val civName = civ.civName

        // Notify crisis start (only on the first turn of a crisis)
        val gameInfo = civ.gameInfo
        val currentYear = gameInfo.getYear()

        // TW v2 — Cultural transfers slow as game years progress (modern era ≈ 20% of antique speed).
        currentInertia = getCulturalInertia(currentYear)

        // TW v2 — City-state cultural absorption check (runs BEFORE the rest of the turn so
        // an absorbed CS doesn't get double-processed as both a CS and the absorbing civ).
        if (civ.isCityState) {
            processCityStateAbsorption(civ)
            if (civ.cities.isEmpty()) return  // CS absorbed away — nothing left to process
        }
        val lastTurnYear = gameInfo.getYear(-1)
        for ((crisisYear, _) in CRISES) {
            if (currentYear >= crisisYear && lastTurnYear < crisisYear) {
                civ.addNotification(
                    "A global crisis has begun! Barbarian hordes threaten all civilizations!",
                    NotificationCategory.War,
                    NotificationIcon.War
                )
            }
        }

        val crisisActive = isGlobalCrisisActive(gameInfo)

        // TW v2 — Phase 2: during a global crisis, cities cut off from the capital come
        // under heavy separatist pressure. Each turn adds local-culture share on the
        // city center, naturally pushing them toward the existing secession rules.
        //  - Base pressure: +3%/turn on disconnected cities of any size
        //  - Big empire surcharge: +5%/turn extra if the civ has more than 5 cities
        //    (over-extended empires collapse hardest, mimicking Rome 450/Bronze 1200)
        //  - Distance multiplier: cities far from the capital crumble fastest
        if (crisisActive) {
            val isOverExtended = civ.cities.size > 5
            val capitalTile = civ.getCapital()?.getCenterTile()
            for (city in civ.cities.toList()) {
                if (city.isCapital() || city.isPuppet) continue
                if (city.isConnectedToCapital() && !isOverExtended) continue
                val cityTile = city.getCenterTile()
                ensureCultureMap(cityTile, civName)
                val localName = city.name
                var pressure = 0.03f
                if (isOverExtended) pressure += 0.05f
                if (capitalTile != null) {
                    val distance = capitalTile.aerialDistanceTo(cityTile)
                    if (distance > 6) pressure += 0.02f * ((distance - 6).coerceAtMost(8))
                }
                val current = cityTile.cultureMap[localName] ?: 0f
                cityTile.cultureMap[localName] = current + pressure
                normalizeAll(cityTile.cultureMap)
            }
        }

        // TW v2 — Phase 4: Atomic-era decolonization. Colonies where local culture
        // has decisively overtaken national culture declare independence (city-states).
        // The former metropole takes a hard one-shot gold hit per loss.
        if (civ.getEraNumber() >= 6) {
            for (city in civ.cities.toList()) {
                if (!city.isColony) continue
                val cityTile = city.getCenterTile()
                val localShare = cityTile.cultureMap[city.name] ?: 0f
                val nationalShare = cityTile.cultureMap[civName] ?: 0f
                if (localShare > nationalShare && localShare > 0.40f) {
                    val penalty = 200 + 50 * civ.cities.size
                    civ.addGold(-penalty)
                    civ.addNotification(
                        "Decolonization crisis! [${city.name}] has declared independence — we lose [$penalty] gold.",
                        cityTile.position, NotificationCategory.General, NotificationIcon.Death
                    )
                    convertCityToCityState(city, cityTile, civ)
                }
            }
        }

// TW: For connected non-puppet cities, gradually convert local culture → national culture
        // 5% of local culture converts to national each turn
        // Puppet cities do NOT convert — they retain their local identity
        for (city in civ.cities.toList()) {
            if (city.isPuppet) {
                // Puppet independence check (Modern era+, era 5+)
                if (civ.getEraNumber() >= 5) {
                    val cityTile = city.getCenterTile()
                    val localShare = cityTile.cultureMap[city.name] ?: 0f
                    val nationalShare = cityTile.cultureMap[civName] ?: 0f
                    if (localShare >= nationalShare * 2f && localShare > 0.1f) {
                        city.puppetIndependenceTurns++
                        if (city.puppetIndependenceTurns >= 10) {
                            // Declare independence!
                            declarePuppetIndependence(city, civ)
                        }
                    } else {
                        city.puppetIndependenceTurns = 0
                    }
                }
                continue  // skip local→national conversion for puppets
            }
            // TW v2: Colonies retain their local identity — no local→national conversion.
            //   Otherwise the metropole would absorb overseas possessions in a few decades,
            //   defeating the colonial/decolonisation arc entirely.
            if (city.isColony) continue
            if (city.isCapital() || city.isConnectedToCapital()) {
                val cityName = city.name
                for (pos in city.tiles) {
                    val tile = gameInfo.tileMap[pos]
                    val localShare = tile.cultureMap[cityName] ?: 0f
                    if (localShare > 0.01f) {
                        val transfer = localShare * 0.05f
                        tile.cultureMap[cityName] = localShare - transfer
                        tile.cultureMap[civName] = (tile.cultureMap[civName] ?: 0f) + transfer
                    }
                }
            }
        }

        val ownedTiles = civ.cities.flatMap { city ->
            city.tiles.map { pos -> city.civ.gameInfo.tileMap[pos] }
        }

        // Optimization: alternate even/odd tiles each turn, double the rates
        val turnParity = gameInfo.turns % 2
        for (tile in ownedTiles) {
            if (tile.getOwner() != civ) continue  // tile was transferred mid-processing (city secession)
            if (isExcludedFromCulture(tile)) { tile.cultureMap.clear(); continue }

            // Process rebellion every turn (cheap), but culture only on alternating tiles
            val tileHash = (tile.position.x.toInt() + tile.position.y.toInt()) and 1
            if (tileHash == turnParity) {
                ensureCultureMap(tile, civName)
                propagateCulture(tile, civName, 2f) // doubled rates since processed every 2 turns
            }
            updateGraceAndRebellion(tile, civ)

            // TW v2 — Count down the post-absorption barbarian-free grace.
            if (tile.barbarianGraceTurns > 0) tile.barbarianGraceTurns--

            // TW: Decrement combat damage stacks on improvements
            if (tile.combatDamageStacks.isNotEmpty()) {
                tile.combatDamageStacks = ArrayList(tile.combatDamageStacks.map { it - 1 }.filter { it > 0 })
            }
        }

        // Passive cultural spread to adjacent unowned tiles
        val unownedNeighbors = mutableSetOf<Tile>()
        for (tile in ownedTiles) {
            for (neighbor in tile.neighbors) {
                if (neighbor.getOwner() == null && !isExcludedFromCulture(neighbor)) {
                    unownedNeighbors.add(neighbor)
                }
            }
        }
        for (tile in unownedNeighbors) {
            ensureCultureMap(tile, null)
            spreadToUnowned(tile)
            tryCulturalConquest(tile, civ.gameInfo)
        }
    }

    /** If a non-barbarian civ reaches 70%+ culture on an unowned tile, claim it. */
    private fun tryCulturalConquest(tile: Tile, gameInfo: com.unciv.logic.GameInfo) {
        if (tile.getOwner() != null) return
        // TW v2 — Pas d'unité sur la case (la culture ne déloge pas une présence militaire).
        if (tile.militaryUnit != null || tile.civilianUnit != null) return
        val dominant = tile.cultureMap.entries
            .filter { it.key != "Barbarians" }
            .maxByOrNull { it.value } ?: return
        if (dominant.value < CULTURAL_CONQUEST_THRESHOLD) return

        val targetCiv = gameInfo.civilizations
            .firstOrNull { it.civName == dominant.key && it.isAlive() && !it.isBarbarian }
            ?: return
        // TW v2 — Border-creep: la case ne peut basculer que si elle touche une case
        // non maritime du futur propriétaire.
        if (!hasLandBorderAdjacency(tile, targetCiv)) return
        val targetCity = targetCiv.cities
            .filter { city ->
                tile.neighbors.any { it.getOwner() == targetCiv } ||
                    tile.aerialDistanceTo(city.getCenterTile()) <= 5
            }
            .minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
            ?: return

        targetCity.expansion.takeOwnership(tile)
        tile.conquestGraceTurns = 0
        tile.rebellionTurns = 0
        targetCiv.addNotification(
            "A tile has been culturally absorbed into [${targetCity.name}]!",
            tile.position,
            NotificationCategory.General,
            NotificationIcon.Culture
        )
    }

    /**
     * Make sure the cultureMap is initialized.
     * - Unowned tiles default to 100% Barbarians.
     * - Owned tiles from old saves (no cultureMap yet) get initialized based on
     *   distance to city center.
     * - City centers: 90% owner + 10% barbarians (city centers participate in culture system).
     */
    private fun ensureCultureMap(tile: Tile, ownerName: String?) {
        if (tile.cultureMap.isEmpty()) {
            if (ownerName == null) {
                tile.cultureMap["Barbarians"] = 1.0f
            } else {
                val city = tile.getCity()
                // TW: Use city's culture name (local or national) for initialization
                val cultureName = if (city != null) getCultureName(city) else ownerName
                if (city != null && tile.isCityCenter()) {
                    tile.cultureMap[cultureName] = CITY_FOUNDING_OWNER_SHARE
                    tile.cultureMap["Barbarians"] = 1.0f - CITY_FOUNDING_OWNER_SHARE
                } else if (city != null) {
                    val dist = city.getCenterTile().aerialDistanceTo(tile)
                    val ownerShare = (0.9f - dist * 0.1f).coerceIn(0.3f, 0.9f)
                    tile.cultureMap[cultureName] = ownerShare
                    tile.cultureMap["Barbarians"] = 1.0f - ownerShare
                } else {
                    tile.cultureMap["Barbarians"] = 1.0f
                }
            }
        }
    }

    /**
     * Multi-source culture propagation. ALL civs compete for influence simultaneously:
     * 1. Owner gets base natural growth (+1%/turn)
     * 2. Adjacent city centers push their civ's culture (+3%/turn each)
     * 3. Military garrison pushes its civ's culture (+5%/turn)
     * 4. Each neighbor tile diffuses its composition proportionally (+1%/neighbor/turn)
     */
    private fun propagateCulture(tile: Tile, ownerName: String, rateMultiplier: Float = 1f) {
        // Collect all cultural influences as deltas
        val influences = HashMap<String, Float>()

        // 1. Base natural growth — split local/national for puppet cities
        val owningCity = tile.getCity()
        if (owningCity != null) {
            val (localRatio, nationalRatio) = getCultureSplit(owningCity)
            val localCultureName = getCultureName(owningCity)
            if (localRatio > 0f) addInfluence(influences, localCultureName, BASE_GROWTH * localRatio)
            if (nationalRatio > 0f) addInfluence(influences, ownerName, BASE_GROWTH * nationalRatio)

            // TW v2 — Capital drip: on the centre of a non-capital city land-connected by road to
            // the capital, the national culture seeps in at [CAPITAL_PROJECTION_RATE]/turn. Replaces
            // the old immediate-100%-national rule with a multi-decade homogenisation curve.
            if (tile.isCityCenter()
                && !owningCity.isCapital()
                && !owningCity.isPuppet
                && isLandConnectedToCapital(owningCity)) {
                addInfluence(influences, ownerName, CAPITAL_PROJECTION_RATE)
            }
        } else {
            addInfluence(influences, ownerName, BASE_GROWTH)
        }

        // 2. City center influence: 25% owner + 75% city center's cultural composition
        var hasNearbyCity = false
        for (neighbor in tile.neighbors) {
            // Mountains block city influence — skip if neighbor is a mountain (no adjacency)
            if (neighbor.baseTerrain == Constants.mountain && !neighbor.isCityCenter()) continue
            if (neighbor.isCityCenter()) {
                addCityInfluence(influences, neighbor, CITY_INFLUENCE_ADJ)
                hasNearbyCity = true
                val neighborCity = neighbor.getCity()
                // Original capital bonus: +5% owner culture on adjacent tiles
                if (neighborCity != null && neighborCity.isOriginalCapital
                    && neighborCity.foundingCivObject == neighborCity.civ) {
                    addInfluence(influences, neighborCity.civ.civName, CAPITAL_ADJ_BONUS)
                }
                // TW: Russia projects +1%/turn extra for its civ at every reached distance.
                if (neighborCity != null && neighborCity.civ.civName == RUSSIA_CIV) {
                    addInfluence(influences, RUSSIA_CIV, RUSSIA_DISTANCE_BONUS)
                }
            }
            // Check distance-2 cities (neighbors of neighbors)
            // Mountains block the path — if the intermediate tile is a mountain, no influence
            for (nn in neighbor.neighbors) {
                if (nn == tile) continue
                if (nn.isCityCenter()) {
                    addCityInfluence(influences, nn, CITY_INFLUENCE_NEAR)
                    hasNearbyCity = true
                    val nnCity = nn.getCity()
                    if (nnCity != null && nnCity.civ.civName == RUSSIA_CIV) {
                        addInfluence(influences, RUSSIA_CIV, RUSSIA_DISTANCE_BONUS)
                    }
                }
            }
        }
        // Long-range city influence (distance 3–5, era-gated)
        if (addLongRangeCityInfluences(tile, influences)) hasNearbyCity = true
        // Wonder soft-power aura (distance 4)
        addWonderAura(tile, influences)
        // Adjacent military units project +1%/turn each
        addUnitNeighborAura(tile, influences)
        // Barbarian pressure if no city nearby (distance-based, shifted outward as civs mature).
        // A non-pillaged improvement on the tile NEUTRALIZES this distance-based pressure
        // (an exploited tile is integrated into the economic/cultural fabric, not a no-man's-land).
        // Terrain-based pressure (desert, tundra, jungle) below is NOT affected.
        // Optimized: manual min instead of flatMap+filter+minOfOrNull
        val hasImprovementProtection = tile.improvement != null && !tile.improvementIsPillaged
        if (!hasNearbyCity && !hasImprovementProtection) {
            var closestCityDist = Int.MAX_VALUE
            var closestCityCivName: String? = null
            for (civ in tile.tileMap.gameInfo.civilizations) {
                if (!civ.isAlive() || civ.isBarbarian) continue
                for (city in civ.cities) {
                    val d = city.getCenterTile().aerialDistanceTo(tile)
                    if (d < closestCityDist) {
                        closestCityDist = d
                        closestCityCivName = civ.civName
                    }
                    if (d <= 2) break // can't be closer than nearby city check already found
                }
                if (closestCityDist <= 2) break
            }
            val radiusBonus = getBarbarianRadiusBonus(tile, closestCityCivName)
            if (closestCityDist >= 6 + radiusBonus) {
                addInfluence(influences, "Barbarians", BARBARIAN_NO_CITY_6)
            } else if (closestCityDist >= 5 + radiusBonus) {
                addInfluence(influences, "Barbarians", BARBARIAN_NO_CITY_5)
            } else if (closestCityDist >= 4 + radiusBonus) {
                addInfluence(influences, "Barbarians", BARBARIAN_NO_CITY_4)
            }
        }

        // Terrain-based barbarian pressure (harsh terrain is harder to assimilate)
        if (tile.baseTerrain == "Desert" || tile.baseTerrain == "Flood plains") {
            addInfluence(influences, "Barbarians", BARBARIAN_DESERT)
        }
        if (tile.terrainFeatures.contains("Jungle")) {
            addInfluence(influences, "Barbarians", BARBARIAN_JUNGLE)
        }

        // Global civilizational crisis: extra barbarian pressure
        if (isGlobalCrisisActive(tile.tileMap.gameInfo)) {
            addInfluence(influences, "Barbarians", CRISIS_BARBARIAN_ALL)
            if (isBorderTile(tile)) {
                addInfluence(influences, "Barbarians", CRISIS_BARBARIAN_BORDER)
            }

            val tileCiv = tile.getOwner()
            if (tileCiv != null) {
                // Localized barbarian pressure near conquered cities (radius 3)
                val conqueredCitiesNearby = tile.getTilesInDistance(CRISIS_CONQUERED_RADIUS)
                    .filter { it.isCityCenter() }
                    .mapNotNull { it.getCity() }
                    .count { city -> city.civ == tileCiv
                        && city.foundingCivObject != null
                        && city.foundingCivObject != tileCiv }
                if (conqueredCitiesNearby > 0) {
                    addInfluence(influences, "Barbarians",
                        CRISIS_CONQUERED_BARBARIAN * conqueredCitiesNearby)
                }

                // Founding civ cultural pressure on conquered city centers
                if (tile.isCityCenter()) {
                    val city = tile.getCity()
                    if (city != null && city.foundingCivObject != null
                        && city.foundingCivObject != tileCiv) {
                        val foundingCivName = city.foundingCivObject!!.civName
                        val pressure = if (city.isOriginalCapital)
                            CRISIS_CITY_CAPITAL_PRESSURE else CRISIS_CITY_OTHER_PRESSURE
                        addInfluence(influences, foundingCivName, pressure)
                    }
                }
            }
        }

        // 3. Garrison pacification: converts foreign culture into the friendly cultures already
        //    present on the tile, proportional to their existing share. This means a garrison in
        //    a city dominated by its local culture reinforces the LOCAL identity (and only
        //    secondarily the imperial one) — preventing sudden national conversions while still
        //    pushing back against foreign influence. On city centers, the conversion rate scales
        //    with total military units in city territory (DOM-TOM effect).
        //    Barbarian garrisons retain the legacy behavior (push 100% toward "Barbarians").
        //    TW v2 — Units transiting under an Open Borders treaty do not pacify: peaceful
        //    passage is not occupation, and the suzerain has consented to passage only.
        val garrison = tile.militaryUnit
        if (garrison != null && !isTransitingUnderOpenBorders(garrison, tile)) {
            // Friendly share = sum of all cultures belonging to this civ (national + local cities)
            val friendlyShare = if (garrison.civ.isBarbarian) tile.cultureMap["Barbarians"] ?: 0f
                else getFriendlyShare(tile, garrison.civ)
            val foreignShare = 1f - friendlyShare
            if (foreignShare > 0.01f) {
                var pacificationRate = GARRISON_PACIFICATION
                if (tile.isCityCenter()) {
                    val city = tile.getCity()
                    if (city != null) {
                        val militaryUnitsInTerritory = city.getTiles().count { t ->
                            t.militaryUnit != null && t.militaryUnit!!.civ == garrison.civ
                        }
                        pacificationRate = (GARRISON_PACIFICATION + (militaryUnitsInTerritory - 1) * 0.02f)
                            .coerceAtMost(0.20f)
                    }
                }
                // TW v2 — garrison projection scales with the owning city's culture/turn.
                if (owningCity != null && owningCity.civ == garrison.civ) {
                    pacificationRate *= getCityCultureBoost(owningCity)
                }
                val converted = foreignShare * pacificationRate
                // Remove proportionally from all foreign cultures
                for (entry in tile.cultureMap.entries) {
                    val key = entry.key
                    val isFriendly = if (garrison.civ.isBarbarian) key == "Barbarians"
                                     else isFriendlyCulture(key, garrison.civ)
                    if (!isFriendly) {
                        entry.setValue(entry.value - entry.value / (foreignShare + 0.001f) * converted)
                    }
                }
                // Distribute the converted share among friendly cultures at prorata of their
                // current weight. If no friendly culture is present yet (e.g. very early garrison
                // on a foreign-dominated tile), fall back to the garrison's natural culture name.
                if (garrison.civ.isBarbarian) {
                    tile.cultureMap["Barbarians"] = (tile.cultureMap["Barbarians"] ?: 0f) + converted
                } else if (friendlyShare > 0.01f) {
                    for (entry in tile.cultureMap.entries.toList()) {
                        if (isFriendlyCulture(entry.key, garrison.civ)) {
                            val weight = entry.value / friendlyShare
                            tile.cultureMap[entry.key] = entry.value + converted * weight
                        }
                    }
                } else {
                    val fallbackName = if (owningCity != null && owningCity.civ == garrison.civ)
                        getCultureName(owningCity) else garrison.civ.civName
                    tile.cultureMap[fallbackName] = (tile.cultureMap[fallbackName] ?: 0f) + converted
                }
            }
        }

        // 4. Road bonus: 2 closest cities exert extra influence if tile has unpillaged road
        // Optimized: manual 2-min scan instead of sort
        if (tile.getUnpillagedRoad() != RoadStatus.None) {
            var best1: com.unciv.logic.city.City? = null; var dist1 = Int.MAX_VALUE
            var best2: com.unciv.logic.city.City? = null; var dist2 = Int.MAX_VALUE
            for (civ in tile.tileMap.gameInfo.civilizations) {
                if (!civ.isAlive() || civ.isBarbarian) continue
                for (city in civ.cities) {
                    val d = city.getCenterTile().aerialDistanceTo(tile)
                    if (d < dist1) { best2 = best1; dist2 = dist1; best1 = city; dist1 = d }
                    else if (d < dist2) { best2 = city; dist2 = d }
                }
            }
            if (best1 != null) addInfluence(influences, best1.civ.civName, ROAD_CITY_BONUS)
            if (best2 != null) addInfluence(influences, best2.civ.civName, ROAD_CITY_BONUS)
        }

        // TW v2 — Tile-level absorption: distant tiles (beyond a city's immediate ring) must be
        // "civilised" (road and/or improvement) to absorb incoming culture. Wilderness tiles drift
        // back toward whatever local culture was there. Pushes empires to mesh their interior.
        val absorption = getCulturalInflowMultiplier(tile)

        // Apply civilisation projection influences (city/wonder/unit/road/barbarian/etc.) scaled by
        // rateMultiplier and the full absorption gate — these need infrastructure to take root.
        for ((civName, amount) in influences) {
            val current = tile.cultureMap.getOrDefault(civName, 0f)
            tile.cultureMap[civName] = current + amount * rateMultiplier * absorption
        }

        // 6. Tile-to-tile diffusion: only from OWNED tiles (unowned wilderness doesn't project).
        //    Mountains block all projection. Coasts block projection to land but not to other water.
        //    Rivers halve diffusion unless a road bridges both sides.
        //    TW v2 — diffusion bypasses the absorption floor: neighbours always exchange a trickle of
        //    culture (DIFFUSION_ABSORPTION_FLOOR), even into wilderness. Roaded/civilised tiles still
        //    absorb more (up to the full multiplier), but a population whisper never goes to zero.
        val diffusionAbsorption = maxOf(absorption, DIFFUSION_ABSORPTION_FLOOR)
        for (neighbor in tile.neighbors) {
            if (neighbor.cultureMap.isEmpty()) continue
            if (neighbor.getOwner() == null) continue  // unowned tiles don't diffuse
            if (isCulturalBarrier(neighbor, tile)) continue  // check source→target barrier
            val riverFactor = riverDiffusionFactor(neighbor, tile)
            val borderFactor = borderDiffusionFactor(neighbor, tile)
            for ((civName, share) in neighbor.cultureMap) {
                val delta = share * DIFFUSION_RATE * riverFactor * borderFactor * rateMultiplier * diffusionAbsorption
                tile.cultureMap[civName] = (tile.cultureMap[civName] ?: 0f) + delta
            }
        }

        // Normalize so total = 1.0
        normalizeAll(tile.cultureMap)
    }

    /** Spread culture to an unowned neighboring tile via diffusion from all adjacent tiles. */
    private fun spreadToUnowned(tile: Tile) {
        val influences = HashMap<String, Float>()

        // City center influence: 25% owner + 75% city center's cultural composition
        var hasNearbyCity = false
        for (neighbor in tile.neighbors) {
            // Mountains block city influence
            if (neighbor.baseTerrain == Constants.mountain && !neighbor.isCityCenter()) continue
            if (neighbor.isCityCenter()) {
                addCityInfluence(influences, neighbor, CITY_INFLUENCE_ADJ)
                hasNearbyCity = true
                val neighborCity = neighbor.getCity()
                // Original capital bonus: +5% owner culture on adjacent tiles
                if (neighborCity != null && neighborCity.isOriginalCapital
                    && neighborCity.foundingCivObject == neighborCity.civ) {
                    addInfluence(influences, neighborCity.civ.civName, CAPITAL_ADJ_BONUS)
                }
                // TW: Russia projects +1%/turn extra for its civ at every reached distance.
                if (neighborCity != null && neighborCity.civ.civName == RUSSIA_CIV) {
                    addInfluence(influences, RUSSIA_CIV, RUSSIA_DISTANCE_BONUS)
                }
            }
            // Mountains block distance-2 city influence path
            for (nn in neighbor.neighbors) {
                if (nn == tile) continue
                if (nn.isCityCenter()) {
                    addCityInfluence(influences, nn, CITY_INFLUENCE_NEAR)
                    hasNearbyCity = true
                    val nnCity = nn.getCity()
                    if (nnCity != null && nnCity.civ.civName == RUSSIA_CIV) {
                        addInfluence(influences, RUSSIA_CIV, RUSSIA_DISTANCE_BONUS)
                    }
                }
            }
        }
        // Long-range city influence (distance 3–5, era-gated)
        if (addLongRangeCityInfluences(tile, influences)) hasNearbyCity = true
        // Wonder soft-power aura (distance 4)
        addWonderAura(tile, influences)
        // Adjacent military units project +1%/turn each
        addUnitNeighborAura(tile, influences)
        if (!hasNearbyCity) {
            var closestCityDist = Int.MAX_VALUE
            var closestCityCivName: String? = null
            for (civ in tile.tileMap.gameInfo.civilizations) {
                if (!civ.isAlive() || civ.isBarbarian) continue
                for (city in civ.cities) {
                    val d = city.getCenterTile().aerialDistanceTo(tile)
                    if (d < closestCityDist) {
                        closestCityDist = d
                        closestCityCivName = civ.civName
                    }
                }
            }
            val radiusBonus = getBarbarianRadiusBonus(tile, closestCityCivName)
            if (closestCityDist >= 6 + radiusBonus) {
                addInfluence(influences, "Barbarians", BARBARIAN_NO_CITY_6)
            } else if (closestCityDist >= 5 + radiusBonus) {
                addInfluence(influences, "Barbarians", BARBARIAN_NO_CITY_5)
            } else if (closestCityDist >= 4 + radiusBonus) {
                addInfluence(influences, "Barbarians", BARBARIAN_NO_CITY_4)
            }
        }

        // Terrain-based barbarian pressure
        if (tile.baseTerrain == "Desert" || tile.baseTerrain == "Flood plains") {
            addInfluence(influences, "Barbarians", BARBARIAN_DESERT)
        }
        if (tile.terrainFeatures.contains("Jungle")) {
            addInfluence(influences, "Barbarians", BARBARIAN_JUNGLE)
        }

        // Global civilizational crisis: extra barbarian pressure on unowned tiles too
        if (isGlobalCrisisActive(tile.tileMap.gameInfo)) {
            addInfluence(influences, "Barbarians", CRISIS_BARBARIAN_ALL)
        }

        // Tile diffusion: only from OWNED tiles (unowned wilderness doesn't project)
        //    Mountains block all projection. Coasts block projection to land but not to other water.
        //    Rivers halve diffusion unless a road bridges both sides.
        for (neighbor in tile.neighbors) {
            if (neighbor.cultureMap.isEmpty()) continue
            if (neighbor.getOwner() == null) continue
            if (isCulturalBarrier(neighbor, tile)) continue  // check source→target barrier
            val riverFactor = riverDiffusionFactor(neighbor, tile)
            for ((civName, share) in neighbor.cultureMap) {
                addInfluence(influences, civName, share * PASSIVE_SPREAD * riverFactor)
            }
        }

        // Garrison pacification on unowned tile
        val garrison = tile.militaryUnit
        if (garrison != null) {
            val garrisonCivName = if (garrison.civ.isBarbarian) "Barbarians" else garrison.civ.civName
            val garrisonShare = tile.cultureMap[garrisonCivName] ?: 0f
            val foreignShare = 1f - garrisonShare
            if (foreignShare > 0.01f) {
                val converted = foreignShare * GARRISON_PACIFICATION
                for (entry in tile.cultureMap.entries) {
                    if (entry.key != garrisonCivName) {
                        entry.setValue(entry.value - entry.value / (1f - garrisonShare + 0.001f) * converted)
                    }
                }
                tile.cultureMap[garrisonCivName] = garrisonShare + converted
            }
        }

        if (influences.isEmpty() && garrison == null) return

        for ((civName, amount) in influences) {
            val current = tile.cultureMap.getOrDefault(civName, 0f)
            tile.cultureMap[civName] = current + amount
        }

        normalizeAll(tile.cultureMap)
    }

    /** TW v2 — Year-indexed cultural inertia. All per-turn culture-transfer rates are
     *  scaled by this factor.
     *
     *  Pre-modern societies (Antiquity through 19th century) assimilated conquered
     *  populations, switched religions, and absorbed frontiers with relative speed
     *  (Hellenization, Romanization, conversion to world religions, settler-colony
     *  creep, mestizaje). The rupture is the nation-state era: mass schooling,
     *  standardised national media, codified ethnicity and bureaucratised borders
     *  hardened identities. We model this as a sharp half-century transition:
     *
     *    year ≤ 1900   →  1.00 (full pre-modern fluidity)
     *    1900 → 1950   →  linear drop, 1.00 → 0.15
     *    year ≥ 1950   →  0.15 (modern/contemporary floor)
     *
     *  Applied uniformly via [addInfluence] (crises included). Tile-to-tile diffusion
     *  is intentionally NOT scaled by this — basic geographic osmosis between
     *  populations persists even in the standardised-media era. */
    @Readonly
    fun getCulturalInertia(year: Int): Float {
        return when {
            year <= 1900 -> 1.0f
            year >= 1950 -> 0.15f
            else -> 1.0f - 0.85f * (year - 1900) / 50f
        }
    }

    /** Set by [processCivTiles] each invocation so [addInfluence] can scale by it.
     *  Sequential per-civ turn processing keeps this thread-effective. */
    @Volatile private var currentInertia: Float = 1f

    private fun addInfluence(map: HashMap<String, Float>, civName: String, amount: Float) {
        map[civName] = (map[civName] ?: 0f) + amount * currentInertia
    }

    /** Add city influence using 25% owner + 75% city center composition split.
     *  TW v2: the effective rate is scaled by [getCityCultureBoost] (log of city's culture/turn). */
    private fun addCityInfluence(influences: HashMap<String, Float>, cityTile: Tile, rate: Float) {
        val city = cityTile.getCity() ?: return
        val boostedRate = rate * getCityCultureBoost(city)
        val (localRatio, nationalRatio) = getCultureSplit(city)
        val localName = getCultureName(city)  // city name, or founding civ name for conquered capitals
        val nationalName = city.civ.civName
        // 25% goes directly to the city's culture identity (split local/national)
        if (localRatio > 0f) addInfluence(influences, localName, boostedRate * CITY_OWNER_PROJECTION * localRatio)
        if (nationalRatio > 0f) addInfluence(influences, nationalName, boostedRate * CITY_OWNER_PROJECTION * nationalRatio)
        // 75% goes proportional to the city center tile's cultural composition
        if (cityTile.cultureMap.isNotEmpty()) {
            for ((civName, share) in cityTile.cultureMap) {
                addInfluence(influences, civName, boostedRate * CITY_COMPOSITION_PROJECTION * share)
            }
        } else {
            if (localRatio > 0f) addInfluence(influences, localName, boostedRate * CITY_COMPOSITION_PROJECTION * localRatio)
            if (nationalRatio > 0f) addInfluence(influences, nationalName, boostedRate * CITY_COMPOSITION_PROJECTION * nationalRatio)
        }
    }

    /** Normalize all entries so they sum to 1.0. Remove tiny entries. */
    fun normalizeAll(map: HashMap<String, Float>) {
        // Remove tiny values first
        map.entries.removeAll { it.value < 0.001f }
        val total = map.values.sum()
        if (total <= 0f) return
        for (entry in map.entries) {
            entry.setValue(entry.value / total)
        }
    }

    /** Handle grace countdown, rebellion triggers, and secession.
     *  City centers can also rebel (2-turn secession) and transfer to the dominant culture's civ.
     *  Colonial era (Renaissance to Modern): no rebellions or secessions. */
    private fun updateGraceAndRebellion(tile: Tile, civ: Civilization) {
        // TW v2: First 10 turns — short grace period for civs to establish minimal borders.
        if (civ.gameInfo.turns < 10) {
            tile.rebellionTurns = 0
            return
        }

        // TW v2 — A city-state's own city centre NEVER converts through the generic cultural
        // rebellion/secession path. The ONLY route by which a city-state joins a major civ is
        // the dedicated 60%-ABSOLUTE absorption gate in processCityStateAbsorption (which also
        // applies the post-absorption barbarian grace). Without this guard, the secession path
        // transferred CS capitals to a neighbour as soon as foreign culture merely exceeded the
        // native share (~30% foreignness), far below the intended 60% bar — and bypassed the
        // grace entirely. Keep the centre stable so only the gate can convert it.
        if (civ.isCityState && tile.isCityCenter()) {
            tile.rebellionTurns = 0
            return
        }

        val ownerName = civ.civName

        // Grace period countdown
        if (tile.conquestGraceTurns > 0) {
            tile.conquestGraceTurns--
            return // No rebellion during grace
        }

        // Calculate foreignness = 1.0 - owner's friendly culture share (national + local city)
        val ownerShare = getFriendlyShare(tile, civ)
        val foreignness = 1.0f - ownerShare

        val hasGarrison = tile.militaryUnit != null && tile.militaryUnit!!.civ == civ
        val secessionLimit = if (tile.isCityCenter()) CITY_SECESSION_TURNS else SECESSION_TURNS

        if (tile.rebellionTurns > 0) {
            // Already in rebellion
            if (hasGarrison) {
                // Garrison holds the rebellion — doesn't escalate but doesn't end by itself
                // Rebellion ends only when foreignness drops below threshold
                if (foreignness <= REBELLION_THRESHOLD) {
                    tile.rebellionTurns = 0
                    civ.addNotification(
                        if (tile.isCityCenter()) "The city rebellion has been quelled!"
                        else "The rebellion has been quelled!",
                        tile.position,
                        NotificationCategory.War,
                        NotificationIcon.War
                    )
                }
                // Attrition is handled in UnitTurnManager
            } else {
                // No garrison: secession countdown
                tile.rebellionTurns++
                if (tile.rebellionTurns > secessionLimit) {
                    performSecession(tile, civ)
                }
            }
        } else {
            // Check if rebellion should start
            // Condition 1: foreign culture > 70%
            // Condition 2: owner < 30% AND barbarians + any other single culture > 50%
            // Condition 3: owner < 40% AND barbarians > 25% → tile becomes neutral (not city centers)
            val barbarianShare = tile.cultureMap["Barbarians"] ?: 0f
            val shouldRebel = if (foreignness > REBELLION_THRESHOLD) true
                else if (ownerShare < REBELLION_OWNER_MIN) {
                    val topForeign = tile.cultureMap.entries
                        .filter { it.key != ownerName && it.key != "Barbarians" }
                        .maxByOrNull { it.value }?.value ?: 0f
                    (barbarianShare + topForeign) > 0.50f
                }
                else if (!tile.isCityCenter() && ownerShare < BARBARIAN_DECAY_OWNER
                    && barbarianShare > BARBARIAN_DECAY_THRESHOLD) true
                else false

            if (shouldRebel) {
                tile.rebellionTurns = 1
                if (tile.isCityCenter()) {
                    civ.addNotification(
                        "Rebellion! [${tile.getCity()?.name ?: "unknown"}] is in revolt!",
                        tile.position,
                        NotificationCategory.War,
                        NotificationIcon.War
                    )
                } else {
                    civ.addNotification(
                        "Rebellion! A tile near [${tile.getCity()?.name ?: "unknown"}] is in revolt!",
                        tile.position,
                        NotificationCategory.War,
                        NotificationIcon.War
                    )
                }
                // Spawn barbarian unit on rebelling tile if unoccupied
                spawnRebellionBarbarian(tile, civ)
            }
        }
    }

    /** Tile or city secedes: returns to the dominant foreign culture's civ, or becomes neutral.
     *  City centers transfer the whole city. Dead civs can be resurrected through cultural revolt. */
    private fun performSecession(tile: Tile, currentOwner: Civilization) {
        val ownerName = currentOwner.civName

        // City center secession: transfer the whole city
        if (tile.isCityCenter()) {
            performCitySecession(tile, currentOwner)
            return
        }

        // Find dominant foreign culture — must exceed owner's friendly share to secede
        // TW: Friendly share includes national + local city cultures
        val ownerShare = getFriendlyShare(tile, currentOwner)
        val dominantForeign = tile.cultureMap.entries
            .filter { !isFriendlyCulture(it.key, currentOwner) && it.key != "Barbarians" }
            .maxByOrNull { it.value }

        val city = tile.getCity() ?: return
        val barbarianShare = tile.cultureMap["Barbarians"] ?: 0f

        // Barbarian decay: owner < 40% AND barbarians > 25% → tile becomes neutral
        if (ownerShare < BARBARIAN_DECAY_OWNER && barbarianShare > BARBARIAN_DECAY_THRESHOLD) {
            currentOwner.addNotification(
                "A tile near [${city.name}] has fallen to barbarian decay!",
                tile.position,
                NotificationCategory.War,
                NotificationIcon.Death
            )
            city.expansion.relinquishOwnership(tile)
            tile.rebellionTurns = 0
            return
        }

        // Foreign culture must exceed owner's culture to trigger secession
        if (dominantForeign != null && dominantForeign.value <= ownerShare) {
            // Owner still culturally dominant — tile stays in rebellion but doesn't secede
            return
        }

        // Notify current owner
        currentOwner.addNotification(
            "A tile near [${city.name}] has seceded!",
            tile.position,
            NotificationCategory.War,
            NotificationIcon.Death
        )

        if (dominantForeign != null) {
            // Look for target civ — culture name can be a civ name or a city name
            val targetCiv = currentOwner.gameInfo.civilizations
                .firstOrNull {
                    !it.isBarbarian && (it.civName == dominantForeign.key
                        || it.cities.any { city -> city.name == dominantForeign.key })
                }

            // TW v2 — Border-creep: une case ne bascule chez un voisin que si elle touche
            // une case non maritime de ce voisin. Sinon la case tombe en territoire neutre.
            if (targetCiv != null && targetCiv.isAlive() && hasLandBorderAdjacency(tile, targetCiv)) {
                val targetCity = targetCiv.cities
                    .filter { targetCity ->
                        tile.neighbors.any { it.getOwner() == targetCiv } ||
                            tile.aerialDistanceTo(targetCity.getCenterTile()) <= 4
                    }
                    .minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }

                if (targetCity != null) {
                    targetCity.expansion.takeOwnership(tile)
                    tile.rebellionTurns = 0
                    tile.conquestGraceTurns = 0
                    targetCiv.addNotification(
                        "A tile has joined our territory through cultural secession!",
                        tile.position,
                        NotificationCategory.General,
                        NotificationIcon.Culture
                    )
                    return
                }
            }
        }

        // Fallback: tile becomes neutral (no owner)
        city.expansion.relinquishOwnership(tile)
        tile.rebellionTurns = 0
    }

    /** City center secedes: transfer the whole city to the dominant culture's civ.
     *  Can resurrect dead civilizations through cultural revolt.
     *  If barbarian decay (owner < 40% AND barbarians > 25%), city becomes a city-state. */
    private fun performCitySecession(tile: Tile, currentOwner: Civilization) {
        val ownerName = currentOwner.civName
        val city = tile.getCity() ?: return

        // TW: Use friendly share (national + local city cultures)
        val ownerShare = getFriendlyShare(tile, currentOwner)
        val barbarianShare = tile.cultureMap["Barbarians"] ?: 0f

        // Barbarian decay on city: owner < 40% AND barbarians > 25% → city becomes city-state
        if (ownerShare < BARBARIAN_DECAY_OWNER && barbarianShare > BARBARIAN_DECAY_THRESHOLD) {
            convertCityToCityState(city, tile, currentOwner)
            return
        }

        // Find dominant non-barbarian foreign culture on the city center
        val dominantForeign = tile.cultureMap.entries
            .filter { !isFriendlyCulture(it.key, currentOwner) && it.key != "Barbarians" }
            .maxByOrNull { it.value }

        if (dominantForeign == null || dominantForeign.value <= ownerShare) {
            // Foreign culture must EXCEED owner's culture to trigger city secession
            return
        }

        // Find the target civ — culture name can be a civ name or a city name
        val targetCiv = currentOwner.gameInfo.civilizations
            .firstOrNull {
                !it.isBarbarian && (it.civName == dominantForeign.key
                    || it.cities.any { city -> city.name == dominantForeign.key })
            }
            ?: return

        // TW v2 — Border-creep: une ville entière ne bascule chez un autre civ que si ce civ
        // touche déjà la ville par une case non maritime. Sinon, on ne crée pas d'enclave
        // par sécession culturelle — la rébellion continue de couver localement.
        if (!hasLandBorderAdjacency(tile, targetCiv)) return

        val wasDefeated = targetCiv.isDefeated()
        val cityName = city.name

        // Notify current owner
        currentOwner.addNotification(
            "[$cityName] has seceded through cultural revolt!",
            tile.position,
            NotificationCategory.War,
            NotificationIcon.Death
        )

        // Transfer the city
        city.moveToCiv(targetCiv)
        city.isPuppet = false
        tile.rebellionTurns = 0
        tile.conquestGraceTurns = GRACE_TURNS

        // If this resurrected a dead civ, notify everyone
        if (wasDefeated && targetCiv.isAlive()) {
            for (otherCiv in targetCiv.gameInfo.civilizations
                .filter { it.isAlive() && it != targetCiv && !it.isBarbarian }) {
                otherCiv.addNotification(
                    "[${targetCiv.civName}] has been resurrected through cultural revolt in [$cityName]!",
                    tile.position,
                    NotificationCategory.General,
                    NotificationIcon.Culture
                )
            }
        }

        targetCiv.addNotification(
            "[$cityName] has joined us through cultural revolt!",
            tile.position,
            NotificationCategory.General,
            NotificationIcon.Culture
        )
    }

    /**
     * TW v2 — Territorial gold yield (independent of city workers).
     * Each owned tile produces gold every turn regardless of population assignment,
     * before being multiplied by the cultural ownership share ([getYieldMultiplier]).
     * Floor 0.5; Grassland 1.0; +2 for an EXPLOITED luxury/strategic resource
     * (i.e., the unlocking improvement is built and not pillaged).
     */
    @Readonly
    fun getTerritorialGoldBase(tile: Tile): Float {
        var base = if (tile.baseTerrain == Constants.grassland) 1.0f else 0.5f
        val res = tile.tileResource
        if (res != null && res.resourceType != com.unciv.models.ruleset.tile.ResourceType.Bonus) {
            val builtImprovement = tile.getUnpillagedImprovement()
            if (builtImprovement != null && builtImprovement in res.getImprovements()) {
                base += 2f
            }
        }
        return base
    }

    /**
     * TW v2 — Food yield multiplier with a softer curve than [getYieldMultiplier]:
     * floor 0.5 at 0% culture, linearly up to 1.0 at ≥80% owner culture.
     *
     * From Renaissance era onwards (eraNumber ≥ 3) the cultural penalty is removed
     * entirely (multiplier = 1.0): historically the agricultural revolution + early
     * modern crop diffusion ended the famines tied to local ethnic instability.
     *
     * Tiles in active rebellion still produce zero food.
     */
    @Readonly
    fun getFoodMultiplier(tile: Tile): Float {
        if (isExcludedFromCulture(tile)) return 1f
        if (tile.rebellionTurns > 0) return 0f
        val owner = tile.getOwner() ?: return 1f
        if (owner.getEraNumber() >= 3) return 1f
        if (tile.cultureMap.isEmpty()) return 1f
        val friendlyShare = getFriendlyShare(tile, owner)
        val linear = (friendlyShare / FULL_PRODUCTIVITY_THRESHOLD).coerceIn(0f, 1f)
        return 0.5f + 0.5f * linear
    }

    /**
     * Returns the owner's culture share for yield calculation.
     * Tiles in rebellion produce nothing (return 0).
     * 100% productivity at 80%+ owner culture, linear scaling below:
     * efficiency = min(1.0, ownerShare / 0.8)
     */
    @Readonly
    fun getYieldMultiplier(tile: Tile): Float {
        if (isExcludedFromCulture(tile)) return 1f
        if (tile.rebellionTurns > 0) return 0f
        val owner = tile.getOwner() ?: return 1f
        if (tile.cultureMap.isEmpty()) return 1f
        // TW: Friendly share = national culture + all local city cultures of this civ
        val friendlyShare = getFriendlyShare(tile, owner)
        return (friendlyShare / FULL_PRODUCTIVITY_THRESHOLD).coerceIn(0f, 1f)
    }

    /**
     * Called when a tile changes ownership (conquest, trade, colonization).
     * - Capture: preserves existing cultureMap
     * - Colonization of neutral tile: starts at 100% Barbarians
     */
    fun onOwnershipChange(tile: Tile, newOwnerCivName: String) {
        if (isExcludedFromCulture(tile)) return
        if (tile.cultureMap.isEmpty()) {
            tile.cultureMap["Barbarians"] = 1.0f
        }
        tile.conquestGraceTurns = GRACE_TURNS
        tile.rebellionTurns = 0
    }

    /** Check if a military unit on a rebelling tile should take attrition damage. */
    fun shouldTakeRebellionAttrition(tile: Tile): Boolean = tile.rebellionTurns > 0

    /** Attrition damage amount for garrison on rebelling tile. */
    fun getRebellionAttritionDamage(): Int = GARRISON_ATTRITION

    // TW v2 — Garrison cultural stress (separate from rebellion attrition).
    const val GARRISON_STRESS_GRACE_TURNS = 15        // free tours before damage kicks in
    const val GARRISON_MAX_STRESS_DAMAGE = 30         // upper bound HP / turn
    const val GARRISON_STRESS_FRIENDLY_THRESHOLD = 0.70f    // friendly share below this triggers stress
    const val GARRISON_STRESS_DOMINANCE_THRESHOLD = 0.50f   // civShare / friendlyShare below this triggers stress
    const val GARRISON_SUBSIDY_COST_PER_HP = 4              // gold cost per HP suppressed via subsidy

    /** TW v2 — Is the garrison [unit] currently stressed on its tile ?
     *  Stress means: it's a military unit owned by the tile's owner, AND either friendly share
     *  is below 70%, OR the civ's own culture is less than half of the friendly total (the
     *  local culture has overtaken the national identity). Returns null with no stress. */
    @Readonly
    fun getGarrisonStressDamage(tile: Tile, civ: Civilization): Int? {
        if (tile.getOwner() != civ) return null
        val cultureMap = tile.cultureMap
        if (cultureMap.isEmpty()) return null
        val civShare = cultureMap[civ.civName] ?: 0f
        val friendlyShare = getFriendlyShare(tile, civ)
        val dominanceRatio = if (friendlyShare > 0.001f) civShare / friendlyShare else 1f
        val stressed = friendlyShare < GARRISON_STRESS_FRIENDLY_THRESHOLD
                       || dominanceRatio < GARRISON_STRESS_DOMINANCE_THRESHOLD
        if (!stressed) return null
        return (GARRISON_MAX_STRESS_DAMAGE * (1f - civShare)).toInt().coerceIn(1, GARRISON_MAX_STRESS_DAMAGE)
    }

    /** TW v2 — Forced secession triggered by sustained peacetime bankruptcy.
     *  The empire's farthest non-capital city breaks away:
     *    1. If a non-barbarian neighbouring civ holds a culture share ≥ 30% on the city center,
     *       the city joins THAT civ (cultural pull wins).
     *    2. Else, the city becomes an independent city-state (uses convertCityToCityState).
     *    3. If no city-state slot is available, the city is simply released to neutral status
     *       (its tiles relinquish ownership), simulating a collapsed administration.
     *  Returns true if a secession was performed (so the caller can reset the bankruptcy counter). */
    fun performBankruptcySecession(civ: Civilization): Boolean {
        val capital = civ.getCapital() ?: return false
        val capitalTile = capital.getCenterTile()
        val candidates = civ.cities.filter { !it.isCapital() && !it.isInResistance() }
        if (candidates.isEmpty()) return false
        val farthest = candidates.maxByOrNull { it.getCenterTile().aerialDistanceTo(capitalTile) } ?: return false
        val tile = farthest.getCenterTile()
        val cityName = farthest.name

        // Candidate target: the neighbouring civ with the highest culture share on this city
        // center, provided its share is at least 30% (non-trivial cultural presence).
        val externalShares = tile.cultureMap.entries
            .filter { entry ->
                entry.key != civ.civName
                    && entry.key != "Barbarians"
                    && entry.value >= 0.30f
            }
        val targetCiv = externalShares
            .mapNotNull { entry ->
                civ.gameInfo.civilizations.firstOrNull { other ->
                    !other.isBarbarian
                        && other.isAlive()
                        && (other.civName == entry.key || other.cities.any { it.name == entry.key })
                } to entry.value
            }
            .filter { it.first != null && it.first != civ }
            .maxByOrNull { it.second }
            ?.first

        if (targetCiv != null) {
            civ.addNotification(
                "Bankruptcy! [$cityName] declares allegiance to [${targetCiv.civName}] as our treasury collapses.",
                tile.position, NotificationCategory.War, NotificationIcon.Death
            )
            targetCiv.addNotification(
                "[$cityName] has joined our civilization after the collapse of [${civ.civName}]'s treasury!",
                tile.position, NotificationCategory.General, NotificationIcon.Culture
            )
            farthest.moveToCiv(targetCiv)
            farthest.isPuppet = false
            tile.rebellionTurns = 0
            tile.conquestGraceTurns = GRACE_TURNS
            return true
        }

        // No external culture strong enough → city-state path. Reuse convertCityToCityState,
        // which itself falls back to no-op if no city-state nation is available; in that case
        // we release the city's tiles to neutral as a last-resort administrative collapse.
        val gameInfo = civ.gameInfo
        val usedNations = gameInfo.civilizations.map { it.civName }.toSet()
        val canBecomeCityState = gameInfo.ruleset.nations.values.any {
            it.isCityState && it.name !in usedNations
        }
        if (canBecomeCityState) {
            civ.addNotification(
                "Bankruptcy! [$cityName] has declared independence as our finances collapse.",
                tile.position, NotificationCategory.War, NotificationIcon.Death
            )
            convertCityToCityState(farthest, tile, civ)
            return true
        }

        // Last resort: release the city's tiles to neutral. The city itself is destroyed.
        civ.addNotification(
            "Bankruptcy! [$cityName] has been abandoned to the wilderness as our administration crumbles.",
            tile.position, NotificationCategory.War, NotificationIcon.Death
        )
        farthest.destroyCity(overrideSafeties = true)
        return true
    }

    /** TW: Puppet city declares independence after 10 turns of cultural dominance in Modern era+.
     *  If founding civ is alive → return to founding civ. Otherwise → become city-state. */
    private fun declarePuppetIndependence(city: com.unciv.logic.city.City, currentOwner: Civilization) {
        val tile = city.getCenterTile()
        val cityName = city.name
        val foundingCiv = city.foundingCivObject

        // Try to return to founding civ
        if (foundingCiv != null && foundingCiv != currentOwner && foundingCiv.isAlive() && !foundingCiv.isBarbarian) {
            currentOwner.addNotification(
                "[$cityName] has declared independence and returned to [${foundingCiv.civName}]!",
                tile.position,
                NotificationCategory.War,
                NotificationIcon.Death
            )
            foundingCiv.addNotification(
                "[$cityName] has declared independence and rejoined our civilization!",
                tile.position,
                NotificationCategory.General,
                NotificationIcon.Culture
            )
            city.moveToCiv(foundingCiv)
            city.isPuppet = false
            city.puppetIndependenceTurns = 0
            tile.rebellionTurns = 0
            return
        }

        // Founding civ dead or same as owner → become city-state
        currentOwner.addNotification(
            "[$cityName] has declared independence and become a city-state!",
            tile.position,
            NotificationCategory.War,
            NotificationIcon.Death
        )
        convertCityToCityState(city, tile, currentOwner)
    }

    // region city-state cultural absorption

    /** TW v2 — When a major civ has an alliance with a city-state, a continuous road link,
     *  a non-maritime land border with it, AND has accumulated ≥ 60% culture on the
     *  city-state's centre, the CS joins them peacefully as a regular city. This is the
     *  ethno-cultural counterpart to military conquest.
     *
     *  Per-turn we also add a culture boost from each allied / road-connected / land-bordering
     *  major civ on the CS's city centre, so the 60% threshold is REACHABLE (without it the
     *  CS would equilibrate around its own local culture and absorption would never fire). */
    private fun processCityStateAbsorption(csCiv: Civilization) {
        if (!csCiv.isCityState || csCiv.cities.isEmpty()) return
        val csCity = csCiv.cities.firstOrNull { it.isCapital() } ?: csCiv.cities.first()
        val csCenter = csCity.getCenterTile()
        ensureCultureMap(csCenter, csCiv.civName)

        val gameInfo = csCiv.gameInfo
        val allyCiv = csCiv.allyCiv
        val csNativeName = csCiv.civName

        // TW v2 — The CS native identity is re-seeded every turn (base growth + diffusion from its
        // own surrounding tiles), which used to keep it permanently dominant on its own centre:
        // adding culture on top for the neighbour and relying on normalisation was too weak, the
        // native share never fell below 60%. We now ERODE the native identity directly — each
        // qualifying axis TRANSFERS culture from the CS native entry to that major (zero-sum), so
        // the native share monotonically declines. The drain keeps biting late-game by flooring the
        // inertia at 0.5 (otherwise the 0.15 end-game floor would freeze absorption entirely).
        val erosionInertia = currentInertia.coerceAtLeast(0.5f)

        for (majorCiv in gameInfo.civilizations.filter { it.isMajorCiv() && it.isAlive() && it != csCiv }) {
            val isAllied = allyCiv == majorCiv
            val hasLandBorder = hasLandBorderToCiv(csCenter, majorCiv)
            val hasRoadLink = hasRoadConnectionFromTileToCiv(csCenter, majorCiv)

            // Erosion rate on the CS native identity from each axis. Stacks so a committed ally
            // (alliance + land border + road) drains ~6 %/turn of native culture early game and
            // ~3 %/turn late game, comfortably overcoming the native re-seeding.
            //   alliance        → 2.5 %/turn
            //   land border     → 1.5 %/turn
            //   road connexion  → 2.0 %/turn
            // Plain neighbouring civs (no alliance, no border, no road) drain nothing.
            var rate = 0f
            if (isAllied) rate += 0.025f
            if (hasLandBorder) rate += 0.015f
            if (hasRoadLink) rate += 0.020f
            if (rate <= 0f) continue

            val nativeShare = csCenter.cultureMap[csNativeName] ?: 0f
            val transfer = (rate * erosionInertia).coerceAtMost(nativeShare)
            if (transfer <= 0f) continue
            csCenter.cultureMap[csNativeName] = nativeShare - transfer
            csCenter.cultureMap[majorCiv.civName] =
                (csCenter.cultureMap[majorCiv.civName] ?: 0f) + transfer
        }
        normalizeAll(csCenter.cultureMap)

        // Absorption: pick the dominant major civ on the centre and check all four gates.
        val dominantMajor = gameInfo.civilizations
            .filter { it.isMajorCiv() && it.isAlive() && it != csCiv }
            .maxByOrNull { csCenter.cultureMap[it.civName] ?: 0f }
            ?: return
        val share = csCenter.cultureMap[dominantMajor.civName] ?: 0f
        // TW v2 — Absorption requires the major civ to hold a FIXED, ABSOLUTE 60% of the CS
        // centre's culture. This is NOT scaled by the empire's culture output: the earlier
        // relative formula (60 / culture-per-turn) let strong cultural empires absorb at only
        // ~20% share, converting city-states far too early. A flat 60% bar for everyone.
        if (share < 0.60f) return
        if (csCiv.allyCiv != dominantMajor) return
        if (!hasLandBorderToCiv(csCenter, dominantMajor)) return
        if (!hasRoadConnectionFromTileToCiv(csCenter, dominantMajor)) return

        absorbCityStateInto(csCity, csCiv, dominantMajor)
    }

    /** True iff any tile owned by [tile]'s civ touches a non-maritime tile owned by [civ]. */
    @Readonly
    private fun hasLandBorderToCiv(tile: Tile, civ: Civilization): Boolean {
        val owner = tile.getOwner() ?: return false
        for (ownedTile in owner.cities.flatMap { c -> c.tiles.map { c.civ.gameInfo.tileMap[it] } }) {
            if (ownedTile.isWater) continue
            for (neighbour in ownedTile.neighbors) {
                if (neighbour.getOwner() == civ && !neighbour.isWater) return true
            }
        }
        return false
    }

    /** BFS from [from] along the road network — must reach a tile owned by [civ] without
     *  ever stepping off a roaded tile. A pillaged road breaks the chain. */
    private fun hasRoadConnectionFromTileToCiv(from: Tile, civ: Civilization): Boolean {
        if (from.getUnpillagedRoad() == com.unciv.logic.map.tile.RoadStatus.None) return false
        @yairm210.purity.annotations.LocalState val visited = HashSet<Tile>()
        @yairm210.purity.annotations.LocalState val frontier = ArrayDeque<Tile>()
        frontier.add(from); visited.add(from)
        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            if (current.getOwner() == civ) return true
            for (n in current.neighbors) {
                if (n in visited) continue
                if (n.getUnpillagedRoad() == com.unciv.logic.map.tile.RoadStatus.None) continue
                visited.add(n); frontier.add(n)
            }
        }
        return false
    }

    /** Transfer a city-state's city to a major civ as a peaceful cultural absorption.
     *  The CS disappears (no more cities), its territory and identity become the major civ's.
     *  No resistance, no plunder — this is consensual cultural alignment, not conquest. */
    private fun absorbCityStateInto(
        csCity: com.unciv.logic.city.City,
        csCiv: Civilization,
        majorCiv: Civilization
    ) {
        val gameInfo = csCiv.gameInfo
        val csName = csCiv.civName
        val cityName = csCity.name
        val tile = csCity.getCenterTile()

        csCity.moveToCiv(majorCiv)
        csCity.isPuppet = false
        csCity.removeFlag(com.unciv.logic.city.CityFlags.Resistance)
        tile.conquestGraceTurns = GRACE_TURNS
        // TW v2 — Pacify the entire former city-state territory against barbarians for a
        // grace period: the new administration secures the land it just absorbed.
        for (pos in csCity.tiles) {
            gameInfo.tileMap[pos].barbarianGraceTurns = ABSORPTION_BARBARIAN_GRACE_TURNS
        }

        majorCiv.addNotification(
            "[$cityName] has joined our civilization through cultural alignment with [$csName]",
            tile.position,
            NotificationCategory.Diplomacy,
            NotificationIcon.Culture,
            majorCiv.civName
        )
        for (otherCiv in gameInfo.civilizations.filter {
            it.isAlive() && it != majorCiv && it != csCiv && !it.isBarbarian
        }) {
            otherCiv.addNotification(
                "[$csName] has been culturally absorbed by [${majorCiv.civName}]",
                tile.position,
                NotificationCategory.Diplomacy,
                NotificationIcon.Culture,
                majorCiv.civName,
                csName
            )
        }
    }

    // endregion

    /** Barbarian decay on a city: convert it into an independent city-state.
     *  75% of barbarian culture on the city center and tiles within 2 range transfers to the new city-state. */
    private fun convertCityToCityState(city: com.unciv.logic.city.City, tile: Tile, currentOwner: Civilization) {
        val gameInfo = currentOwner.gameInfo
        val ruleset = gameInfo.ruleset
        val oldCityName = city.name

        // Prefer a city-state nation whose name matches the city's own name (Geneva-the-city becomes
        // Geneva-the-city-state). Otherwise fall back to the first available city-state nation.
        val usedNations = gameInfo.civilizations.map { it.civName }.toSet()
        val availableCsNation = ruleset.nations.values.firstOrNull {
            it.isCityState && it.name !in usedNations && it.name.equals(oldCityName, ignoreCase = true)
        } ?: ruleset.nations.values.firstOrNull {
            it.isCityState && it.name !in usedNations
        }

        if (availableCsNation == null) {
            // No available city-state nations — tile just stays in rebellion
            return
        }

        // Create the new city-state civilization
        val newCsCiv = Civilization(availableCsNation.name)
        newCsCiv.playerType = com.unciv.logic.civilization.PlayerType.AI
        newCsCiv.gameInfo = gameInfo

        gameInfo.civilizations.add(newCsCiv)
        newCsCiv.setNationTransient()
        newCsCiv.setTransients()
        newCsCiv.cityStateFunctions.initCityState(ruleset, gameInfo.gameParameters.startingEra, emptySequence())

        // Transfer the city
        city.moveToCiv(newCsCiv)
        city.isPuppet = false
        tile.rebellionTurns = 0
        tile.conquestGraceTurns = GRACE_TURNS

        val csCivName = newCsCiv.civName

        // TW v2 — Rename the city so its cultural identity matches its new nation. A city-state IS
        // its city: it doesn't make sense for "Marseille" to declare independence and become the
        // city-state of "Geneva" with two competing cultural identities on its own territory.
        city.name = csCivName

        // Migrate all references to the old city name across the map's culture maps to the new
        // city-state's civ name. The old name had been projected for many turns and likely permeates
        // the city's interior and adjacent tiles; we collapse both identities into one to keep the
        // population's perceived culture consistent with the new nation.
        if (oldCityName != csCivName) {
            for (mapTile in gameInfo.tileMap.values) {
                val oldShare = mapTile.cultureMap.remove(oldCityName) ?: continue
                mapTile.cultureMap[csCivName] = (mapTile.cultureMap[csCivName] ?: 0f) + oldShare
            }
        }

        // Transfer 75% of barbarian culture to the new city-state on city center + tiles within 2
        // TW v2 — On absorbe également 100% de la part culturelle de l'ancien propriétaire dans
        // un rayon de 2 autour de la ville : la nouvelle cité-État doit avoir une identité
        // culturelle nette dans la ville et ses cases alentours, sans empreinte résiduelle du
        // colonisateur. La diffusion ramènera ensuite progressivement la culture de l'ancien
        // suzerain via les territoires limitrophes restants.
        val formerOwnerName = currentOwner.civName
        for (nearbyTile in tile.getTilesInDistance(2)) {
            val barbShare = nearbyTile.cultureMap["Barbarians"] ?: 0f
            if (barbShare > 0.01f) {
                val transferred = barbShare * 0.75f
                nearbyTile.cultureMap["Barbarians"] = barbShare - transferred
                nearbyTile.cultureMap[csCivName] = (nearbyTile.cultureMap[csCivName] ?: 0f) + transferred
            }
            val formerShare = nearbyTile.cultureMap[formerOwnerName] ?: 0f
            if (formerShare > 0.01f) {
                nearbyTile.cultureMap.remove(formerOwnerName)
                nearbyTile.cultureMap[csCivName] = (nearbyTile.cultureMap[csCivName] ?: 0f) + formerShare
            }
            normalizeAll(nearbyTile.cultureMap)
        }

        // Set up diplomacy with all known civs
        for (otherCiv in gameInfo.civilizations.filter {
            it.isAlive() && it != newCsCiv && !it.isBarbarian
        }) {
            if (!newCsCiv.knows(otherCiv))
                newCsCiv.diplomacyFunctions.makeCivilizationsMeet(otherCiv)
        }

        // Notify
        currentOwner.addNotification(
            "[$oldCityName] has broken away and become the city-state of [${newCsCiv.civName}]!",
            tile.position,
            NotificationCategory.War,
            NotificationIcon.Death
        )
        for (otherCiv in gameInfo.civilizations.filter {
            it.isAlive() && it != currentOwner && it != newCsCiv && !it.isBarbarian
        }) {
            otherCiv.addNotification(
                "[$oldCityName] has broken away from [${currentOwner.civName}] and become the city-state of [${newCsCiv.civName}]!",
                tile.position,
                NotificationCategory.General,
                NotificationIcon.Culture
            )
        }
    }

    /** Spawn a barbarian unit on a tile that just entered rebellion, if unoccupied. */
    private fun spawnRebellionBarbarian(tile: Tile, owner: Civilization) {
        if (tile.militaryUnit != null) return
        // TW v2 — Post-absorption pacification: no rebellion barbarian spawns on a tile still
        // under the barbarian-free grace granted when a city-state was absorbed.
        if (tile.barbarianGraceTurns > 0) return
        val gameInfo = owner.gameInfo
        // TW v2: during a global crisis, barbarian appearances are limited to frontier
        // tiles. Deep-empire rebellions still happen culturally but spawn no unit.
        if (isGlobalCrisisActive(gameInfo) && !isFrontierTile(tile)) return
        val barbCiv = gameInfo.getBarbarianCivilization()
        val unitToSpawn = gameInfo.ruleset.units.values
            .filter { it.isMilitary && !it.isWaterUnit && it.isBuildable(barbCiv) }
            .maxByOrNull { it.strength }
            ?: return
        val spawned = barbCiv.units.placeUnitNearTile(tile.position, unitToSpawn)
        // TW v2 — The unit may have drifted to a neighbour during placement; honour the same
        // spawn rules (Russia, maritime borders, and post-absorption grace) and destroy it if
        // it landed somewhere a barbarian is not allowed to appear.
        if (spawned != null
            && !com.unciv.logic.automation.civilization.barbSpawnAllowedOn(spawned.currentTile)) {
            spawned.destroy()
        }
    }

    private const val ENCIRCLEMENT_ATTRITION = 50  // HP damage per turn for isolated enemy units

    /**
     * Territorial Warfare: Encirclement mechanic.
     * Called once per civ at end of turn.
     *
     * 1. Territory encirclement: enemy tiles cut off from all enemy cities
     *    (via BFS through enemy territory, blocked by [civ]'s military units) are conquered.
     * 2. Unit isolation: enemy military units on cut-off tiles take 50 HP damage/turn.
     */
    fun processEncirclement(civ: Civilization) {
        if (civ.isBarbarian || civ.isSpectator()) return

        for (enemyCiv in civ.gameInfo.civilizations.filter {
            it.isAlive() && !it.isSpectator() && civ.isAtWarWith(it)
        }) {
            if (enemyCiv.cities.isEmpty()) continue

            // Collect all tiles owned by the enemy
            val enemyTileSet = HashSet<Tile>()
            for (city in enemyCiv.cities) {
                for (tile in city.getTiles()) {
                    enemyTileSet.add(tile)
                }
            }

            // BFS from each enemy city center through enemy territory + water
            // Water tiles adjacent to enemy territory allow naval supply lines
            // Blocked by our military units on enemy territory (they cut supply lines)
            val connectedToCity = HashSet<Tile>()
            for (city in enemyCiv.cities) {
                val bfs = BFS(city.getCenterTile()) { tile ->
                    // Can traverse: enemy territory (if not blocked) OR water (naval supply)
                    if (tile in enemyTileSet) {
                        tile.militaryUnit == null || tile.militaryUnit!!.civ != civ
                    } else {
                        tile.isWater // water tiles connect island territories
                    }
                }
                bfs.stepToEnd()
                connectedToCity.addAll(bfs.getReachedTiles())
            }

            // Encircled tiles: enemy tiles not connected to any city
            // Tiles adjacent to water or neutral territory are NOT encircled
            val encircledTiles = enemyTileSet.filter {
                it !in connectedToCity && !it.isCityCenter()
                    && !it.neighbors.any { n -> n.isWater || (n.getOwner() == null && !n.isImpassible()) }
            }

            // Conquer encircled tiles adjacent to our territory
            for (tile in encircledTiles) {
                val targetCity = civ.cities
                    .filter { city ->
                        tile.neighbors.any { it.getOwner() == civ } ||
                            tile.aerialDistanceTo(city.getCenterTile()) <= 3
                    }
                    .minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }

                if (targetCity != null) {
                    targetCity.expansion.takeOwnership(tile)
                    tile.conquestGraceTurns = 0
                    tile.rebellionTurns = 0
                }
            }

            if (encircledTiles.isNotEmpty()) {
                civ.addNotification(
                    "We have encircled and seized [${encircledTiles.size}] enemy tiles!",
                    com.unciv.logic.civilization.NotificationCategory.War,
                    com.unciv.logic.civilization.NotificationIcon.War
                )
                enemyCiv.addNotification(
                    "[${civ.civName}] has encircled and seized [${encircledTiles.size}] of our tiles!",
                    com.unciv.logic.civilization.NotificationCategory.War,
                    com.unciv.logic.civilization.NotificationIcon.War
                )
            }

            // Isolated enemy units: LAND military units on THEIR OWN territory
            // but NOT connected to any of their cities take attrition.
            // Units outside their own territory, naval units, or units with access
            // to water/neutral territory are never "encircled".
            for (unit in enemyCiv.units.getCivUnits().toList()) {
                if (!unit.isMilitary()) continue
                if (unit.baseUnit.isWaterUnit) continue
                val unitTile = unit.currentTile
                if (unitTile !in enemyTileSet) continue  // not on own territory = not encircled
                if (unitTile in connectedToCity) continue  // connected to a city = not isolated
                // Access to water or neutral territory = not encircled (can resupply/retreat)
                if (unitTile.neighbors.any { it.isWater || (it.getOwner() == null && !it.isImpassible()) }) continue

                unit.health -= ENCIRCLEMENT_ATTRITION
                enemyCiv.addNotification(
                    "Our [${unit.baseUnit.name}] is isolated and taking attrition damage!",
                    unitTile.position,
                    com.unciv.logic.civilization.NotificationCategory.War,
                    com.unciv.logic.civilization.NotificationIcon.War
                )
                if (unit.health <= 0) {
                    unit.destroy()
                }
            }
        }
    }
}
