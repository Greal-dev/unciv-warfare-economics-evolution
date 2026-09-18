package com.unciv.logic.map

import com.unciv.logic.GameInfo
import com.unciv.models.ruleset.nation.Nation

/**
 * TW v2 — synthetic CS nations.
 *
 * The vanilla ruleset ships with ~35 CS nations (Vanilla) / ~47 (G&K). Past that
 * pool, [SpontaneousCityStateSpawner] would refuse to spawn because every CS name
 * is already taken. To let the world keep populating organically far beyond that
 * cap, we *clone* an existing CS nation under a new name suffixed with a Roman
 * numeral (Geneva, Geneva II, Geneva III, …). The clone shares the original's
 * visuals (colors, flag icon) but rotates the city-name list so each derived CS
 * founds its capital with a distinct name.
 *
 * Persistence: clones are not serialized. On save load we re-register every
 * clone whose civ is still alive by scanning [GameInfo.civilizations] for civ
 * names absent from the ruleset and parsing back the Roman suffix.
 */
object SyntheticCityStateNations {

    private val ROMAN_NUMERALS = listOf(
        "II","III","IV","V","VI","VII","VIII","IX","X",
        "XI","XII","XIII","XIV","XV","XVI","XVII","XVIII","XIX","XX",
        "XXI","XXII","XXIII","XXIV","XXV","XXVI","XXVII","XXVIII","XXIX","XXX",
        "XXXI","XXXII","XXXIII","XXXIV","XXXV","XXXVI","XXXVII","XXXVIII","XXXIX","XL",
        "XLI","XLII","XLIII","XLIV","XLV","XLVI","XLVII","XLVIII","XLIX","L"
    )

    /** Pick the next free CS nation name. If the ruleset's CS pool is exhausted,
     *  clone an existing CS nation under a Roman-numeral suffix and register the
     *  clone in [GameInfo.ruleset] so subsequent lookups succeed. */
    fun pickOrSynthesizeCityStateNation(gameInfo: GameInfo): Nation? {
        val usedNames = gameInfo.civilizations.map { it.civName }.toHashSet()
        val fresh = gameInfo.ruleset.nations.values.firstOrNull {
            it.isCityState && it.name !in usedNames
        }
        if (fresh != null) return fresh

        val baseCandidates = gameInfo.ruleset.nations.values.filter { it.isCityState }
        if (baseCandidates.isEmpty()) return null
        // Pick the base with the FEWEST existing clones to spread the visuals out
        val base = baseCandidates.minBy { countExistingClones(gameInfo, it.name) }
        return cloneWithNextSuffix(gameInfo, base)
    }

    /** Walk the civilization list at load time and re-create any clone nation
     *  that the ruleset doesn't know about yet. Must run after the ruleset has
     *  been (re)loaded and before [com.unciv.logic.civilization.Civilization.setNationTransient]
     *  is called on each civ — see [com.unciv.logic.GameInfo.setTransients]. */
    fun reregisterMissingClones(gameInfo: GameInfo) {
        for (civInfo in gameInfo.civilizations) {
            if (civInfo.civName in gameInfo.ruleset.nations) continue
            val (baseName, suffix) = parseSuffix(civInfo.civName) ?: continue
            val baseNation = gameInfo.ruleset.nations[baseName] ?: continue
            if (!baseNation.isCityState) continue
            registerClone(gameInfo, baseNation, civInfo.civName, suffix)
        }
    }

    private fun cloneWithNextSuffix(gameInfo: GameInfo, base: Nation): Nation? {
        val existing = gameInfo.ruleset.nations.keys
        val suffix = ROMAN_NUMERALS.firstOrNull { "${base.name} $it" !in existing } ?: return null
        val newName = "${base.name} $suffix"
        return registerClone(gameInfo, base, newName, suffix)
    }

    private fun registerClone(gameInfo: GameInfo, base: Nation, newName: String, suffix: String): Nation {
        val clone = copyNation(base, newName)
        val shift = ROMAN_NUMERALS.indexOf(suffix) + 1
        if (base.cities.isNotEmpty() && shift > 0) {
            val rotated = ArrayList<String>(base.cities.size)
            for (i in base.cities.indices) {
                rotated.add(base.cities[(i + shift) % base.cities.size])
            }
            clone.cities = rotated
        }
        gameInfo.ruleset.nations[newName] = clone
        return clone
    }

    private fun countExistingClones(gameInfo: GameInfo, baseName: String): Int {
        val prefix = "$baseName "
        return gameInfo.ruleset.nations.keys.count { it.startsWith(prefix) }
    }

    private fun parseSuffix(civName: String): Pair<String, String>? {
        for (numeral in ROMAN_NUMERALS) {
            val tail = " $numeral"
            if (civName.endsWith(tail)) {
                return civName.dropLast(tail.length) to numeral
            }
        }
        return null
    }

    private fun copyNation(src: Nation, newName: String): Nation {
        val n = Nation()
        n.name = newName
        n.leaderName = src.leaderName
        n.cityStateType = src.cityStateType
        n.preferredVictoryType = src.preferredVictoryType
        n.outerColor = src.outerColor
        n.innerColor = src.innerColor
        n.uniqueName = src.uniqueName
        n.uniqueText = src.uniqueText
        n.cities = ArrayList(src.cities)
        n.spyNames = ArrayList(src.spyNames)
        n.uniques = ArrayList(src.uniques)
        n.startBias = ArrayList(src.startBias)
        n.personality = src.personality
        n.favoredReligion = src.favoredReligion
        n.originRuleset = src.originRuleset
        n.civilopediaText = src.civilopediaText
        n.startIntroPart1 = src.startIntroPart1
        n.startIntroPart2 = src.startIntroPart2
        n.declaringWar = src.declaringWar
        n.attacked = src.attacked
        n.defeated = src.defeated
        n.denounced = src.denounced
        n.declaringFriendship = src.declaringFriendship
        n.introduction = src.introduction
        n.tradeRequest = src.tradeRequest
        n.neutralHello = src.neutralHello
        n.hateHello = src.hateHello
        return n
    }
}
