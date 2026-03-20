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
 * - Military garrison (any civ): +5%/turn for that civ
 * - Tile diffusion: each neighbor pushes its composition proportionally (1%/neighbor/turn)
 *
 * All civs compete for cultural influence simultaneously.
 */
object TileCultureLogic {

    private const val GRACE_TURNS = 10
    private const val REBELLION_THRESHOLD = 0.70f   // rebellion if foreign culture > 70%
    private const val REBELLION_OWNER_MIN = 0.30f   // rebellion if owner < 30% AND barbarians+foreign > 50%
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
    private const val BARBARIAN_NO_CITY_3 = 0.01f   // barbarian growth if no city within 3 tiles
    private const val BARBARIAN_NO_CITY_4 = 0.02f   // barbarian growth if no city within 4 tiles
    private const val BARBARIAN_NO_CITY_5 = 0.03f   // barbarian growth if no city within 5+ tiles
    private const val BARBARIAN_DESERT = 0.01f      // extra barbarian pressure on desert
    private const val BARBARIAN_TUNDRA = 0.01f      // extra barbarian pressure on tundra/snow
    private const val BARBARIAN_JUNGLE = 0.005f     // extra barbarian pressure on jungle
    private const val GARRISON_PACIFICATION = 0.05f // garrison converts 5% of foreign culture per turn
    private const val DIFFUSION_RATE = 0.02f        // per neighbor, proportional to neighbor's composition
    private const val IMPROVEMENT_BONUS = 0.005f    // per non-pillaged improvement on tile
    private const val ROAD_CITY_BONUS = 0.01f       // per nearby city on a tile with road

    // Passive spread to unowned neighbors
    private const val PASSIVE_SPREAD = 0.02f
    private const val CAPITAL_ADJ_BONUS = 0.05f       // original capital bonus on adjacent tiles

    // Global civilizational crises — each crisis has its own year and duration
    private val CRISES = arrayOf(
        Pair(-1200, 10),  // 1200 BC — Bronze Age collapse, 10 turns
        Pair(450, 20),    // 450 AD — Fall of Rome, 20 turns
        Pair(2100, 20)    // 2100 AD — Late modern collapse, 20 turns
    )
    private const val CRISIS_BARBARIAN_ALL = 0.05f    // +5% barbarian pressure on ALL tiles during crisis
    private const val CRISIS_BARBARIAN_BORDER = 0.07f // +7% barbarian pressure on border tiles during crisis
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

    // Decolonization: Modern era (5+), overseas cities 15+ tiles from capital
    private const val DECOLONIZATION_ERA = 5         // Modern era
    private const val DECOLONIZATION_DISTANCE = 15   // min aerial distance from capital
    private const val DECOLONIZATION_CITY_PRESSURE = 0.10f  // +10% barbarian on city center
    private const val DECOLONIZATION_TILE_PRESSURE = 0.05f  // +5% barbarian on all tiles


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

    /** Mountains and coasts are cultural barriers: they receive influence but don't project any. */
    @Readonly
    private fun isCulturalBarrier(tile: Tile): Boolean =
        tile.baseTerrain == Constants.mountain || tile.baseTerrain == Constants.coast

    /** TW: Check if a city is a conquered enemy capital (behaves culturally like a puppet). */
    @Readonly
    private fun isConqueredCapital(city: com.unciv.logic.city.City): Boolean =
        city.isOriginalCapital && city.foundingCivObject != null && city.foundingCivObject != city.civ

    /** TW: Get the culture name a city projects.
     *  Own capital → civ culture.
     *  Conquered enemy capital (even annexed) → founding civ's culture (like puppet).
     *  Puppet (even if connected) → local culture (puppet identity persists).
     *  Connected to capital → civ culture.
     *  Not connected → local culture. */
    @Readonly
    fun getCultureName(city: com.unciv.logic.city.City): String {
        if (city.isCapital() && !isConqueredCapital(city)) return city.civ.civName
        if (isConqueredCapital(city)) return city.foundingCivObject!!.civName  // projects original civ culture
        if (city.isPuppet) return city.name  // puppets project local culture
        if (city.isConnectedToCapital()) return city.civ.civName
        return city.name
    }

    /** TW: Get the culture split for a city — how much goes to local vs national.
     *  Conquered enemy capital: 70% founding civ culture, 30% current owner.
     *  Puppet cities (even connected): 70% local, 30% national.
     *  Normal connected cities: 0% local, 100% national.
     *  Unconnected cities: 100% local, 0% national. */
    @Readonly
    fun getCultureSplit(city: com.unciv.logic.city.City): Pair<Float, Float> {
        if (city.isCapital() && !isConqueredCapital(city)) return Pair(0f, 1f)
        if (isConqueredCapital(city)) return Pair(0.70f, 0.30f)  // conquered capital: 70% original civ, 30% owner
        if (city.isPuppet) return Pair(0.70f, 0.30f)
        if (city.isConnectedToCapital()) return Pair(0f, 1f)
        return Pair(1f, 0f)
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

    /** Check if a tile is on a border between two different civilizations. */
    @Readonly
    private fun isBorderTile(tile: Tile): Boolean {
        val owner = tile.getOwner() ?: return false
        return tile.neighbors.any { neighbor ->
            val neighborOwner = neighbor.getOwner()
            neighborOwner != null && neighborOwner != owner && !neighborOwner.isBarbarian
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

        // TW: Decolonization pressure on overseas colonies (Modern+ era)
        // Optimization: run BFS only every 5 turns, multiply pressure by 5
        if (gameInfo.turns % 5 == 0) processDecolonization(civ, 5f)

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
        val dominant = tile.cultureMap.entries
            .filter { it.key != "Barbarians" }
            .maxByOrNull { it.value } ?: return
        if (dominant.value < CULTURAL_CONQUEST_THRESHOLD) return

        val targetCiv = gameInfo.civilizations
            .firstOrNull { it.civName == dominant.key && it.isAlive() && !it.isBarbarian }
            ?: return
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
        } else {
            addInfluence(influences, ownerName, BASE_GROWTH)
        }

        // 2. City center influence: 25% owner + 75% city center's cultural composition
        var hasNearbyCity = false
        for (neighbor in tile.neighbors) {
            if (neighbor.isCityCenter()) {
                addCityInfluence(influences, neighbor, CITY_INFLUENCE_ADJ)
                hasNearbyCity = true
                // Original capital bonus: +5% owner culture on adjacent tiles
                val neighborCity = neighbor.getCity()
                if (neighborCity != null && neighborCity.isOriginalCapital
                    && neighborCity.foundingCivObject == neighborCity.civ) {
                    addInfluence(influences, neighborCity.civ.civName, CAPITAL_ADJ_BONUS)
                }
            }
            // Check distance-2 cities (neighbors of neighbors)
            for (nn in neighbor.neighbors) {
                if (nn == tile) continue
                if (nn.isCityCenter()) {
                    addCityInfluence(influences, nn, CITY_INFLUENCE_NEAR)
                    hasNearbyCity = true
                }
            }
        }
        // Barbarian pressure if no city nearby (distance-based)
        // Optimized: manual min instead of flatMap+filter+minOfOrNull
        if (!hasNearbyCity) {
            var closestCityDist = Int.MAX_VALUE
            for (civ in tile.tileMap.gameInfo.civilizations) {
                if (!civ.isAlive() || civ.isBarbarian) continue
                for (city in civ.cities) {
                    val d = city.getCenterTile().aerialDistanceTo(tile)
                    if (d < closestCityDist) closestCityDist = d
                    if (d <= 2) break // can't be closer than nearby city check already found
                }
                if (closestCityDist <= 2) break
            }
            if (closestCityDist >= 5) {
                addInfluence(influences, "Barbarians", BARBARIAN_NO_CITY_5)
            } else if (closestCityDist >= 4) {
                addInfluence(influences, "Barbarians", BARBARIAN_NO_CITY_4)
            } else if (closestCityDist >= 3) {
                addInfluence(influences, "Barbarians", BARBARIAN_NO_CITY_3)
            }
        }

        // Terrain-based barbarian pressure (harsh terrain is harder to assimilate)
        if (tile.baseTerrain == "Desert" || tile.baseTerrain == "Flood plains") {
            addInfluence(influences, "Barbarians", BARBARIAN_DESERT)
        }
        if (tile.baseTerrain == "Tundra" || tile.baseTerrain == "Snow") {
            addInfluence(influences, "Barbarians", BARBARIAN_TUNDRA)
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

        // 3. Garrison pacification: converts foreign culture into garrison's culture per turn
        //    Uses the culture name of the nearest city (local or national)
        //    On city centers: scales with total military units in city territory (DOM-TOM effect)
        val garrison = tile.militaryUnit
        if (garrison != null) {
            // Garrison projects the culture identity of the nearest friendly city
            val garrisonCultureName = if (garrison.civ.isBarbarian) "Barbarians"
                else if (owningCity != null && owningCity.civ == garrison.civ) getCultureName(owningCity)
                else garrison.civ.civName
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
                val converted = foreignShare * pacificationRate
                // Remove proportionally from all non-friendly cultures
                for (entry in tile.cultureMap.entries) {
                    if (entry.key != garrisonCultureName && !isFriendlyCulture(entry.key, garrison.civ)) {
                        entry.setValue(entry.value - entry.value / (foreignShare + 0.001f) * converted)
                    }
                }
                tile.cultureMap[garrisonCultureName] = (tile.cultureMap[garrisonCultureName] ?: 0f) + converted
            }
        }

        // 4. Non-pillaged improvement boosts owner's culture
        if (tile.improvement != null && !tile.improvementIsPillaged) {
            addInfluence(influences, ownerName, IMPROVEMENT_BONUS)
        }

        // 5. Road bonus: 2 closest cities exert extra influence if tile has unpillaged road
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

        // 6. Tile-to-tile diffusion: only from OWNED tiles (unowned wilderness doesn't project)
        //    Mountains and coasts are cultural barriers — they don't project influence
        for (neighbor in tile.neighbors) {
            if (neighbor.cultureMap.isEmpty()) continue
            if (neighbor.getOwner() == null) continue  // unowned tiles don't diffuse
            if (isCulturalBarrier(neighbor)) continue  // barriers receive but don't project
            for ((civName, share) in neighbor.cultureMap) {
                addInfluence(influences, civName, share * DIFFUSION_RATE)
            }
        }

        // Apply all influences to the tile's cultureMap (scaled by rateMultiplier)
        for ((civName, amount) in influences) {
            val current = tile.cultureMap.getOrDefault(civName, 0f)
            tile.cultureMap[civName] = current + amount * rateMultiplier
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
            if (neighbor.isCityCenter()) {
                addCityInfluence(influences, neighbor, CITY_INFLUENCE_ADJ)
                hasNearbyCity = true
                // Original capital bonus: +5% owner culture on adjacent tiles
                val neighborCity = neighbor.getCity()
                if (neighborCity != null && neighborCity.isOriginalCapital
                    && neighborCity.foundingCivObject == neighborCity.civ) {
                    addInfluence(influences, neighborCity.civ.civName, CAPITAL_ADJ_BONUS)
                }
            }
            for (nn in neighbor.neighbors) {
                if (nn == tile) continue
                if (nn.isCityCenter()) {
                    addCityInfluence(influences, nn, CITY_INFLUENCE_NEAR)
                    hasNearbyCity = true
                }
            }
        }
        if (!hasNearbyCity) {
            val closestCityDist = tile.tileMap.gameInfo.civilizations
                .filter { it.isAlive() && !it.isBarbarian }
                .flatMap { it.cities }
                .minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) }
                ?: Int.MAX_VALUE
            if (closestCityDist >= 5) {
                addInfluence(influences, "Barbarians", BARBARIAN_NO_CITY_5)
            } else if (closestCityDist >= 4) {
                addInfluence(influences, "Barbarians", BARBARIAN_NO_CITY_4)
            } else if (closestCityDist >= 3) {
                addInfluence(influences, "Barbarians", BARBARIAN_NO_CITY_3)
            }
        }

        // Terrain-based barbarian pressure
        if (tile.baseTerrain == "Desert" || tile.baseTerrain == "Flood plains") {
            addInfluence(influences, "Barbarians", BARBARIAN_DESERT)
        }
        if (tile.baseTerrain == "Tundra" || tile.baseTerrain == "Snow") {
            addInfluence(influences, "Barbarians", BARBARIAN_TUNDRA)
        }
        if (tile.terrainFeatures.contains("Jungle")) {
            addInfluence(influences, "Barbarians", BARBARIAN_JUNGLE)
        }

        // Global civilizational crisis: extra barbarian pressure on unowned tiles too
        if (isGlobalCrisisActive(tile.tileMap.gameInfo)) {
            addInfluence(influences, "Barbarians", CRISIS_BARBARIAN_ALL)
        }

        // Tile diffusion: only from OWNED tiles (unowned wilderness doesn't project)
        //    Mountains and coasts are cultural barriers — they don't project influence
        for (neighbor in tile.neighbors) {
            if (neighbor.cultureMap.isEmpty()) continue
            if (neighbor.getOwner() == null) continue
            if (isCulturalBarrier(neighbor)) continue  // barriers receive but don't project
            for ((civName, share) in neighbor.cultureMap) {
                addInfluence(influences, civName, share * PASSIVE_SPREAD)
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

    private fun addInfluence(map: HashMap<String, Float>, civName: String, amount: Float) {
        map[civName] = (map[civName] ?: 0f) + amount
    }

    /** Add city influence using 25% owner + 75% city center composition split. */
    private fun addCityInfluence(influences: HashMap<String, Float>, cityTile: Tile, rate: Float) {
        val city = cityTile.getCity() ?: return
        val (localRatio, nationalRatio) = getCultureSplit(city)
        val localName = getCultureName(city)  // city name, or founding civ name for conquered capitals
        val nationalName = city.civ.civName
        // 25% goes directly to the city's culture identity (split local/national)
        if (localRatio > 0f) addInfluence(influences, localName, rate * CITY_OWNER_PROJECTION * localRatio)
        if (nationalRatio > 0f) addInfluence(influences, nationalName, rate * CITY_OWNER_PROJECTION * nationalRatio)
        // 75% goes proportional to the city center tile's cultural composition
        if (cityTile.cultureMap.isNotEmpty()) {
            for ((civName, share) in cityTile.cultureMap) {
                addInfluence(influences, civName, rate * CITY_COMPOSITION_PROJECTION * share)
            }
        } else {
            if (localRatio > 0f) addInfluence(influences, localName, rate * CITY_COMPOSITION_PROJECTION * localRatio)
            if (nationalRatio > 0f) addInfluence(influences, nationalName, rate * CITY_COMPOSITION_PROJECTION * nationalRatio)
        }
    }

    /** Normalize all entries so they sum to 1.0. Remove tiny entries. */
    private fun normalizeAll(map: HashMap<String, Float>) {
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

            if (targetCiv != null && targetCiv.isAlive()) {
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

    /** TW: Decolonization — overseas colonies far from capital receive barbarian pressure
     *  starting from Modern era. Cities must be 15+ tiles from capital AND not connected by land.
     *  +10% barbarian on city center, +5% on all city tiles per turn. */
    fun processDecolonization(civ: Civilization, pressureMultiplier: Float = 1f) {
        val eraNumber = civ.getEraNumber()
        if (eraNumber < DECOLONIZATION_ERA) {
            decolonizationLandCache = null
            return
        }

        val capital = civ.getCapital() ?: return
        val capitalTile = capital.getCenterTile()

        // BFS to find all land tiles reachable from capital
        val landConnected = HashSet<HexCoord>()
        val queue = ArrayDeque<Tile>()
        queue.add(capitalTile)
        landConnected.add(capitalTile.position)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (neighbor in current.neighbors) {
                if (neighbor.position !in landConnected && neighbor.isLand) {
                    landConnected.add(neighbor.position)
                    queue.add(neighbor)
                }
            }
        }

        // Cache for use in updateGraceAndRebellion
        decolonizationLandCache = civ.civName to landConnected

        for (city in civ.cities.toList()) {
            if (city == capital) continue
            val cityTile = city.getCenterTile()
            val distance = cityTile.aerialDistanceTo(capitalTile)
            if (distance < DECOLONIZATION_DISTANCE) continue
            if (cityTile.position in landConnected) continue // connected by land = not overseas

            // This city qualifies for decolonization pressure
            // +10% barbarian on city center (scaled by pressureMultiplier)
            cityTile.cultureMap["Barbarians"] = (cityTile.cultureMap["Barbarians"] ?: 0f) + DECOLONIZATION_CITY_PRESSURE * pressureMultiplier
            normalizeAll(cityTile.cultureMap)

            // +5% barbarian on all city tiles
            for (tile in city.getTiles()) {
                if (tile == cityTile) continue
                if (isExcludedFromCulture(tile)) continue
                tile.cultureMap["Barbarians"] = (tile.cultureMap["Barbarians"] ?: 0f) + DECOLONIZATION_TILE_PRESSURE * pressureMultiplier
                normalizeAll(tile.cultureMap)
            }
        }
    }

    /** Fast check if a city tile is eligible for decolonization.
     *  Uses cached land-connectivity set computed once per civ per turn in processDecolonization.
     *  Fallback: checks distance only (BFS is done in processDecolonization). */
    @Readonly
    private fun isDecolonizationEligibleFast(tile: Tile, civ: Civilization): Boolean {
        val capital = civ.getCapital() ?: return false
        val capitalTile = capital.getCenterTile()
        if (tile.aerialDistanceTo(capitalTile) < DECOLONIZATION_DISTANCE) return false
        // Use the cached set if available, otherwise just check distance (conservative)
        val cached = decolonizationLandCache
        return if (cached != null && cached.first == civ.civName)
            tile.position !in cached.second
        else true // distance qualifies, assume overseas if no cache
    }

    // Per-turn cache: (civName, set of land-connected positions from capital)
    private var decolonizationLandCache: Pair<String, HashSet<HexCoord>>? = null

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

    /** Barbarian decay on a city: convert it into an independent city-state.
     *  75% of barbarian culture on the city center and tiles within 2 range transfers to the new city-state. */
    private fun convertCityToCityState(city: com.unciv.logic.city.City, tile: Tile, currentOwner: Civilization) {
        val gameInfo = currentOwner.gameInfo
        val ruleset = gameInfo.ruleset
        val cityName = city.name

        // Find an unused city-state nation
        val usedNations = gameInfo.civilizations.map { it.civName }.toSet()
        val availableCsNation = ruleset.nations.values.firstOrNull {
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

        // Transfer 75% of barbarian culture to the new city-state on city center + tiles within 2
        val csCivName = newCsCiv.civName
        for (nearbyTile in tile.getTilesInDistance(2)) {
            val barbShare = nearbyTile.cultureMap["Barbarians"] ?: 0f
            if (barbShare > 0.01f) {
                val transferred = barbShare * 0.75f
                nearbyTile.cultureMap["Barbarians"] = barbShare - transferred
                nearbyTile.cultureMap[csCivName] = (nearbyTile.cultureMap[csCivName] ?: 0f) + transferred
                normalizeAll(nearbyTile.cultureMap)
            }
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
            "[$cityName] has broken away and become the city-state of [${newCsCiv.civName}]!",
            tile.position,
            NotificationCategory.War,
            NotificationIcon.Death
        )
        for (otherCiv in gameInfo.civilizations.filter {
            it.isAlive() && it != currentOwner && it != newCsCiv && !it.isBarbarian
        }) {
            otherCiv.addNotification(
                "[$cityName] has broken away from [${currentOwner.civName}] and become the city-state of [${newCsCiv.civName}]!",
                tile.position,
                NotificationCategory.General,
                NotificationIcon.Culture
            )
        }
    }

    /** Spawn a barbarian unit on a tile that just entered rebellion, if unoccupied. */
    private fun spawnRebellionBarbarian(tile: Tile, owner: Civilization) {
        if (tile.militaryUnit != null) return
        val gameInfo = owner.gameInfo
        val barbCiv = gameInfo.getBarbarianCivilization()
        val unitToSpawn = gameInfo.ruleset.units.values
            .filter { it.isMilitary && !it.isWaterUnit && it.isBuildable(barbCiv) }
            .maxByOrNull { it.strength }
            ?: return
        barbCiv.units.placeUnitNearTile(tile.position, unitToSpawn)
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
