package com.unciv.ui.screens.territoryscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyShortcutDispatcherVeto
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.components.tilegroups.TileGroupMap
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize

/**
 * Read-only full-screen heatmap that colors every tile by its dominant culture.
 * Useful for playtesting cultural pressure: shows where rebellions are about to
 * happen, where barbarian no-man's-lands persist, and how borders are evolving
 * culturally under voluntary trade and assimilation.
 */
class CultureHeatmapScreen(
    private val viewingCiv: Civilization
) : BaseScreen(), RecreateOnResize {

    private val tileGroups = ArrayList<TileGroup>()
    private val scrollPane = TerritoryExchangeMapHolder()
    private val sidePanel = Table()
    private val legendPanel = Table()

    init {
        setupMap()
        buildSidePanel()
        buildLegend()
        stage.addActor(sidePanel)
        stage.addActor(legendPanel)
        updateColors()
    }

    private fun setupMap() {
        val tileSetStrings = TileSetStrings(viewingCiv.gameInfo.ruleset, game.settings)
        for (tile in viewingCiv.gameInfo.tileMap.values) {
            val tg = TileGroup(tile, tileSetStrings)
            tg.isForceVisible = true
            tileGroups.add(tg)
        }
        val tileGroupMap = TileGroupMap(scrollPane, tileGroups)
        scrollPane.actor = tileGroupMap
        scrollPane.setSize(stage.width, stage.height)
        scrollPane.setCancelTouchFocus(false)
        stage.addActor(scrollPane)
        scrollPane.layout()
        scrollPane.scrollPercentX = 0.5f
        scrollPane.scrollPercentY = 0.5f
        scrollPane.updateVisualScroll()
    }

    override fun getShortcutDispatcherVetoer() =
        KeyShortcutDispatcherVeto.createTileGroupMapDispatcherVetoer()

    private fun updateColors() {
        for (tg in tileGroups) {
            val tile = tg.tile
            tg.update(viewingCiv)
            tg.layerUnitFlag.isVisible = false
            tg.layerCityButton.isVisible = false
            tg.layerMisc.removeHexOutline()
            tg.layerMisc.hideTerrainOverlay()

            if (tile.isOcean) {
                tg.layerTerrain.color.a = 0.4f
                continue
            }

            val map = tile.cultureMap
            if (map.isEmpty()) {
                tg.layerTerrain.color.a = 0.5f
                continue
            }

            val (dominantName, dominantShare) = map.maxByOrNull { it.value }!!.toPair()
            val color = colorForCulture(dominantName)
            // Saturation reflects dominance: 100% culture -> opaque overlay, 33% -> faint
            val alpha = (dominantShare * 0.7f).coerceIn(0.15f, 0.7f)
            tg.layerMisc.overlayTerrain(color, alpha)
        }
    }

    private fun colorForCulture(cultureName: String): Color {
        if (cultureName == "Barbarians") return Color.DARK_GRAY
        // Civ name OR a city-state local culture (e.g. "Roman" with culture variant) — strip suffixes
        val baseCivName = cultureName.substringBefore(":")
        val civ = viewingCiv.gameInfo.civilizations.firstOrNull { it.civName == baseCivName }
        return if (civ != null) Color(civ.nation.getOuterColor()) else Color.LIGHT_GRAY
    }

    private fun buildSidePanel() {
        sidePanel.clear()
        sidePanel.defaults().pad(8f)
        sidePanel.background = skinStrings.getUiBackground(
            "General/Border", tintColor = Color(0f, 0f, 0f, 0.7f)
        )
        sidePanel.add("Culture Heatmap".toLabel(fontSize = 24)).colspan(1).row()
        sidePanel.add("Tiles colored by dominant culture".toLabel(Color.LIGHT_GRAY)).row()
        sidePanel.addSeparator()
        sidePanel.add("Saturation = strength".toLabel()).left().row()
        sidePanel.add("Gray = barbarians dominant".toLabel(Color.GRAY)).left().row()
        sidePanel.addSeparator()

        val closeBtn = "Close".toTextButton()
        closeBtn.keyShortcuts.add(KeyCharAndCode.BACK)
        closeBtn.onActivation { game.popScreen() }
        sidePanel.add(closeBtn).pad(4f).row()

        sidePanel.pack()
        sidePanel.setPosition(stage.width - 10f, stage.height / 2, Align.right)
    }

    private fun buildLegend() {
        legendPanel.clear()
        legendPanel.defaults().pad(4f)
        legendPanel.background = skinStrings.getUiBackground(
            "General/Border", tintColor = Color(0f, 0f, 0f, 0.7f)
        )
        legendPanel.add("Civilizations".toLabel(fontSize = 16)).colspan(2).row()
        for (civ in viewingCiv.gameInfo.civilizations) {
            if (!civ.isAlive() || civ.isBarbarian || civ.isSpectator()) continue
            val swatch = "  ".toLabel()
            swatch.color = Color(civ.nation.getOuterColor())
            legendPanel.add(swatch).width(20f).height(20f)
            legendPanel.add(civ.civName.toLabel()).left().row()
        }
        legendPanel.add("  ".toLabel().also { it.color = Color.DARK_GRAY })
            .width(20f).height(20f)
        legendPanel.add("Barbarians".toLabel()).left().row()

        legendPanel.pack()
        legendPanel.setPosition(10f, stage.height / 2, Align.left)
    }

    override fun recreate(): BaseScreen = CultureHeatmapScreen(viewingCiv)
}
