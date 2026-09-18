package com.unciv.logic.civilization

import com.unciv.logic.GameInfo

/**
 * TW v2 — Calendar-paced science (replaces raw science → tech-cost accumulation).
 *
 * Rules:
 *  - Each turn, the major civ with the highest raw science output is the "leader".
 *  - The leader's tech progress is paced by the **calendar**: their current era is
 *    sized to finish at the era boundary defined by `startPercent` of the next era,
 *    automatically scaled by [com.unciv.models.ruleset.Speed.numTotalTurns].
 *  - Every other civ progresses at `leader_rate × (rawScience / leaderRawScience)`,
 *    so a civ producing 60% of the leader's science researches 60% as fast.
 *  - When the leader has completed its era ahead of schedule, its rate drops to 0
 *    (it waits for the calendar to catch up). When behind schedule, the rate rises.
 *
 * The displayed science/turn in the UI stays the raw value; only the science amount
 * fed into [com.unciv.logic.civilization.managers.TechManager.endTurn] is overridden.
 */
object CalendarPacedScience {

    private var cachedTurn: Int = -1
    private var cachedGameId: String = ""
    private var leaderRawScience: Float = 0f
    private var leaderEffectiveRate: Float = 0f
    private var leaderCivId: String = ""

    /** Public hook so the UI can guarantee a fresh leader snapshot before reading
     *  [getLeaderRawScience] / [getLeaderCivId] / [getLeaderEffectiveRate].
     *  ALWAYS forces a fresh rebuild — the in-turn cache may have been populated by the
     *  first AI civ that processed `tech.endTurn` (before later civs' stats refreshed). */
    fun ensureCache(gameInfo: GameInfo) {
        cachedTurn = -1
        ensureCacheInternal(gameInfo)
    }

    private fun ensureCacheInternal(gameInfo: GameInfo) {
        if (gameInfo.turns == cachedTurn && gameInfo.gameId == cachedGameId) return
        cachedTurn = gameInfo.turns
        cachedGameId = gameInfo.gameId

        var bestSci = 0f
        var bestCiv: Civilization? = null
        for (civ in gameInfo.civilizations) {
            if (!civ.isMajorCiv() || civ.isDefeated()) continue
            if (civ.cities.isEmpty()) continue
            val sci = civ.stats.statsForNextTurn.science
            if (sci > bestSci) {
                bestSci = sci
                bestCiv = civ
            }
        }
        if (bestCiv == null) {
            leaderRawScience = 0f
            leaderEffectiveRate = 0f
            leaderCivId = ""
            return
        }
        leaderRawScience = bestSci
        leaderCivId = bestCiv.civID
        leaderEffectiveRate = computeRateForCiv(bestCiv, gameInfo)
    }

    /** Returns the science rate the given civ should "spend" on research this turn,
     *  scaled by the calendar pace of the science leader.
     *
     *  TW v2 — City-states research at the leader's calendar pace (full parity). Without this they
     *  were systematically locked 2-3 eras behind major civs because they're excluded from every
     *  science-bonus modifier. Keeping them at parity means a conquering or research-savvy CS can
     *  field modern units and play a real role in the geopolitics rather than fade into irrelevance. */
    fun effectiveSciencePerTurn(civ: Civilization): Int {
        ensureCacheInternal(civ.gameInfo)
        if (leaderRawScience <= 0f) {
            // No qualifying leader yet — fall back to raw science to avoid stalling turn 0.
            return civ.stats.statsForNextTurn.science.toInt()
        }
        if (civ.civID == leaderCivId) return leaderEffectiveRate.toInt()

        val raw = civ.stats.statsForNextTurn.science
        // Edge case: a civ may temporarily out-produce the cached leader before the cache
        // is rebuilt next turn. Clamp ratio to 1.0 so they at most match the leader's rate.
        val ratio = (raw / leaderRawScience).coerceIn(0f, 1f)
        val computed = leaderEffectiveRate * ratio
        if (civ.isCityState) {
            return leaderEffectiveRate.toInt()
        }
        return computed.toInt()
    }

    /** Calendar-paced rate.
     *
     *  Three regimes (in order):
     *   1. **On schedule / behind:** `calendarRate = remainingCost / turnsRemaining` —
     *      automatically rises when behind (catch-up boost).
     *   2. **Ahead inside the era:** floor at 50% of the era's nominal pace so the leader
     *      keeps making discoveries even when racing ahead — just half as fast.
     *   3. **Era fully completed before calendar:** switch to 50% of the next era's nominal
     *      pace (so research never stops). When the leader has reached the final era and
     *      finished it, rate drops to 0. */
    private fun computeRateForCiv(civ: Civilization, gameInfo: GameInfo): Float {
        val era = civ.tech.era
        val totalTurns = gameInfo.speed.numTotalTurns()
        val nextEra = gameInfo.ruleset.eras.values.find { it.eraNumber == era.eraNumber + 1 }

        val eraStartPercent = era.startPercent
        val eraEndPercent = nextEra?.startPercent ?: 100
        val eraStartTurn = (eraStartPercent / 100f * totalTurns).toInt()
        val eraEndTurn = (eraEndPercent / 100f * totalTurns).toInt()
        val nominalEraDuration = maxOf(1, eraEndTurn - eraStartTurn)
        val turnsRemaining = maxOf(1, eraEndTurn - gameInfo.turns)

        var remainingEraCost = 0
        var totalEraCost = 0
        for (tech in gameInfo.ruleset.technologies.values) {
            if (tech.era() != era.name) continue
            val cost = civ.tech.costOfTech(tech.name)
            totalEraCost += cost
            if (!civ.tech.isResearched(tech.name)) remainingEraCost += cost
        }

        val baseRate: Float = if (remainingEraCost > 0) {
            val rawCalendarRate = remainingEraCost.toFloat() / turnsRemaining.toFloat()
            val nominalRate = totalEraCost.toFloat() / nominalEraDuration.toFloat()
            // TW v2 — cap the catch-up spike at 2× nominal: as turnsRemaining → 1 near the end of an
            // era, rawCalendarRate explodes (e.g. 5000 cost / 1 turn = 5000/turn), pulling every civ
            // with it via the leader-relative scaling. 2× nominal is already an aggressive catch-up.
            val calendarRate = minOf(rawCalendarRate, 2f * nominalRate)
            // 50% floor when ahead of schedule — leader keeps discovering, but slower.
            maxOf(calendarRate, 0.5f * nominalRate)
        } else {
            // Current era fully researched ahead of schedule → continue into next era at 50% pace.
            if (nextEra == null) return 0f
            val nextNextEra = gameInfo.ruleset.eras.values.find { it.eraNumber == nextEra.eraNumber + 1 }
            val nextEraEndPercent = nextNextEra?.startPercent ?: 100
            val nextEraStartTurn = (nextEra.startPercent / 100f * totalTurns).toInt()
            val nextEraEndTurn = (nextEraEndPercent / 100f * totalTurns).toInt()
            val nextNominalDuration = maxOf(1, nextEraEndTurn - nextEraStartTurn)
            var nextEraTotalCost = 0
            for (tech in gameInfo.ruleset.technologies.values) {
                if (tech.era() != nextEra.name) continue
                nextEraTotalCost += civ.tech.costOfTech(tech.name)
            }
            if (nextEraTotalCost == 0) return 0f
            0.5f * (nextEraTotalCost.toFloat() / nextNominalDuration.toFloat())
        }

        // TW v2 — smooth the discontinuity when crossing an era boundary: bound effective pacing to
        // 2.5× this civ's own raw science. Without this, the next era's higher per-tech cost makes
        // the paced rate jump by ~10× overnight (observed in play: displayed 4 turns / actual 10 in
        // the old era, displayed 40 / actual 3-4 in the new era). 2.5× still allows a real catch-up.
        val rawForCiv = civ.stats.statsForNextTurn.science
        return if (rawForCiv > 0f) minOf(baseRate, 2.5f * rawForCiv) else baseRate
    }

    /** Diagnostic helper — exposes the cached leader civ ID for UI / logs. */
    fun getLeaderCivId(): String = leaderCivId
    fun getLeaderRawScience(): Float = leaderRawScience
    fun getLeaderEffectiveRate(): Float = leaderEffectiveRate
}
