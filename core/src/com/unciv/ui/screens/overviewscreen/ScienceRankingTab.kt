package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.civilization.CalendarPacedScience
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts

/**
 * TW v2 — Science ranking tab.
 *
 * Shows every known major civ's raw science/turn, the ratio against the science leader,
 * and the calendar-paced effective science the civ actually spends on research this turn.
 * The leader is highlighted gold. Unmet civs appear as "?" rows for completeness.
 */
class ScienceRankingTab(
    viewingPlayer: Civilization,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData? = null
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {

    private companion object {
        const val pad = 8f
    }

    init {
        defaults().pad(pad)
        top()
        build()
    }

    private fun build() {
        clear()

        // Header / explanatory line
        val header = "Science ranking — leader sets calendar pace, others scale by ratio".toLabel()
        add(header).colspan(6).left().row()
        addSeparator()

        // Force the leader cache to be built for the current turn — otherwise the cached
        // leader-raw value may still be 0 and every ratio would collapse to the fallback 100%.
        CalendarPacedScience.ensureCache(gameInfo)

        // Snapshot all major civs known to the viewer
        data class Row(val civ: Civilization, val raw: Float, val effective: Int, val ratio: Float)
        val leaderRaw = CalendarPacedScience.getLeaderRawScience()
        val leaderId = CalendarPacedScience.getLeaderCivId()

        val rows = gameInfo.civilizations.asSequence()
            .filter { it.isMajorCiv() && !it.isDefeated() && it.cities.isNotEmpty() }
            .filter { it == viewingPlayer || viewingPlayer.knows(it) }
            .map { civ ->
                val raw = civ.stats.statsForNextTurn.science
                val ratio = if (leaderRaw > 0f) (raw / leaderRaw).coerceIn(0f, 1f) else 1f
                val effective = CalendarPacedScience.effectiveSciencePerTurn(civ)
                Row(civ, raw, effective, ratio)
            }
            .sortedByDescending { it.raw }
            .toList()

        // Column headers
        add("Civ".toLabel()).left().padRight(pad)
        add("Era".toLabel()).left().padRight(pad)
        add("Raw / turn".toLabel()).right().padRight(pad)
        add("Ratio".toLabel()).right().padRight(pad)
        add("Bar".toLabel()).left().padRight(pad)
        add("Effective / turn".toLabel()).right().row()
        addSeparator()

        for (row in rows) {
            val isLeader = row.civ.civID == leaderId
            val nameColor = when {
                isLeader -> Color.GOLD
                row.civ == viewingPlayer -> Color.CYAN
                else -> Color.WHITE
            }
            val nameText = if (isLeader) "${row.civ.civName} ★" else row.civ.civName
            add(nameText.toLabel(nameColor)).left().padRight(pad)
            add(row.civ.getEra().name.toLabel()).left().padRight(pad)
            add(("${row.raw.toInt()} ${Fonts.science}").toLabel()).right().padRight(pad)
            val ratioPct = (row.ratio * 100f).toInt()
            add("$ratioPct%".toLabel()).right().padRight(pad)
            add(makeBar(row.ratio)).left().padRight(pad)
            add(("${row.effective} ${Fonts.science}").toLabel()).right().row()
        }

        if (rows.isEmpty()) {
            add("No civilization producing science yet.".toLabel()).colspan(6).row()
        }

        addSeparator()

        // Footer: leader effective rate & explanatory note
        val effLeaderRate = CalendarPacedScience.getLeaderEffectiveRate().toInt()
        add(("Leader calendar pace: $effLeaderRate ${Fonts.science}/turn").toLabel()).colspan(6).left().row()
        add("(Each civ researches at leader's pace × ratio.)".toLabel()).colspan(6).left().row()
    }

    private fun makeBar(ratio: Float): Table {
        val barWidth = 160f
        val barHeight = 14f
        val table = Table()
        val filled = (barWidth * ratio).coerceAtLeast(2f)

        // Empty background
        val bg = Table()
        bg.background = com.unciv.ui.screens.basescreen.BaseScreen.skinStrings.getUiBackground(
            "ScienceRankingTab/Bar/Bg",
            tintColor = Color.DARK_GRAY.cpy().apply { a = 0.5f }
        )
        bg.add().size(barWidth, barHeight)

        // Filled portion overlaid
        val fg = Table()
        fg.background = com.unciv.ui.screens.basescreen.BaseScreen.skinStrings.getUiBackground(
            "ScienceRankingTab/Bar/Fill",
            tintColor = when {
                ratio >= 0.75f -> Color.GOLD
                ratio >= 0.40f -> Color.CYAN
                else -> Color.ORANGE
            }
        )
        fg.add().size(filled, barHeight)

        // Stack via overlapping cells — use Table.stack
        val stack = com.badlogic.gdx.scenes.scene2d.ui.Stack()
        stack.add(bg)
        stack.add(fg)
        table.add(stack).size(barWidth, barHeight)
        return table
    }
}
