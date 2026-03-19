package com.unciv.ui.screens.territoryscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyShortcutDispatcherVeto
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.components.tilegroups.TileGroupMap
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize

/**
 * Full-screen map UI for exchanging territory between two civilizations.
 *
 * Click an [otherCiv] tile to mark it for taking (red outline).
 * Click a [playerCiv] tile to mark it for giving (green outline).
 * Click again to deselect.
 */
class TerritoryExchangeScreen(
    private val playerCiv: Civilization,
    private val otherCiv: Civilization
) : BaseScreen(), RecreateOnResize {

    companion object {
        private val TAKE_COLOR: Color = Color.RED
        private val GIVE_COLOR: Color = Color.GREEN
        private const val DIM_ALPHA = 0.35f
    }

    private val tilesToTake = mutableSetOf<Tile>()   // otherCiv -> playerCiv
    private val tilesToGive = mutableSetOf<Tile>()   // playerCiv -> otherCiv

    private val tileGroups = ArrayList<TileGroup>()
    private val scrollPane = TerritoryExchangeMapHolder()
    private val sidePanel = Table()

    private val playerPositions: Set<HexCoord>
    private val otherPositions: Set<HexCoord>

    init {
        playerPositions = playerCiv.cities.flatMap { it.tiles }.toHashSet()
        otherPositions = otherCiv.cities.flatMap { it.tiles }.toHashSet()

        setupMap()
        buildSidePanel()
        stage.addActor(sidePanel)
        updateVisuals()
    }

    // ── Map setup ──

    private fun setupMap() {
        val tileSetStrings = TileSetStrings(playerCiv.gameInfo.ruleset, game.settings)

        // Collect tiles owned by either civ + 1-ring context
        val owned = mutableSetOf<Tile>()
        for (city in playerCiv.cities + otherCiv.cities)
            for (pos in city.tiles)
                owned.add(city.civ.gameInfo.tileMap[pos])

        val all = mutableSetOf<Tile>()
        all.addAll(owned)
        for (tile in owned)
            for (n in tile.neighbors)
                all.add(n)

        for (tile in all) {
            val tg = TileGroup(tile, tileSetStrings)
            tg.isForceVisible = true
            tileGroups.add(tg)
        }

        val tileGroupMap = TileGroupMap(scrollPane, tileGroups)
        scrollPane.actor = tileGroupMap
        scrollPane.setSize(stage.width, stage.height)
        // Critical: don't cancel child touch focus when panning starts,
        // otherwise tile click listeners get killed on any mouse movement
        scrollPane.setCancelTouchFocus(false)
        stage.addActor(scrollPane)

        // TileGroupMap steals layer children from TileGroups for rendering.
        // In libGDX 1.14, Group.hit() falls through to Actor.hit() when no child is hit,
        // so empty TileGroups ARE hittable if touchable=enabled (the default).
        // Attach click handlers on each TileGroup.
        for (tg in tileGroups) {
            tg.onClick { handleTileClick(tg.tile) }
        }

        scrollPane.layout()
        scrollPane.scrollPercentX = 0.5f
        scrollPane.scrollPercentY = 0.5f
        scrollPane.updateVisualScroll()
    }

    override fun getShortcutDispatcherVetoer() =
        KeyShortcutDispatcherVeto.createTileGroupMapDispatcherVetoer()

    // ── Click handling ──

    private fun handleTileClick(tile: Tile) {
        if (tile.isCityCenter()) return

        val pos = tile.position
        when {
            otherPositions.contains(pos) -> toggleTile(tile, tilesToTake)
            playerPositions.contains(pos) -> toggleTile(tile, tilesToGive)
            else -> return
        }
        updateVisuals()
        updateCounts()
    }

    private fun toggleTile(tile: Tile, set: MutableSet<Tile>) {
        if (set.contains(tile)) {
            set.remove(tile)
        } else {
            set.add(tile)
        }
    }

    // ── Visual update ──

    private fun updateVisuals() {
        val playerColor = Color(playerCiv.nation.getOuterColor())
        val otherColor = Color(otherCiv.nation.getOuterColor())

        for (tg in tileGroups) {
            val tile = tg.tile
            val pos = tile.position

            tg.update(playerCiv)
            tg.layerUnitFlag.isVisible = false
            tg.layerCityButton.isVisible = false
            tg.layerMisc.removeHexOutline()
            tg.layerMisc.hideTerrainOverlay()

            when {
                tilesToTake.contains(tile) -> {
                    tg.layerMisc.overlayTerrain(otherColor, 0.4f)
                    tg.layerMisc.addHexOutline(TAKE_COLOR)
                }
                tilesToGive.contains(tile) -> {
                    tg.layerMisc.overlayTerrain(playerColor, 0.4f)
                    tg.layerMisc.addHexOutline(GIVE_COLOR)
                }
                otherPositions.contains(pos) -> {
                    tg.layerMisc.overlayTerrain(otherColor, 0.3f)
                }
                playerPositions.contains(pos) -> {
                    tg.layerMisc.overlayTerrain(playerColor, 0.3f)
                }
                else -> {
                    tg.layerTerrain.color.a = DIM_ALPHA
                }
            }
        }
    }

    // ── Side panel ──

    private fun buildSidePanel() {
        sidePanel.clear()
        sidePanel.defaults().pad(8f)
        sidePanel.background = skinStrings.getUiBackground(
            "General/Border", tintColor = Color(0f, 0f, 0f, 0.7f)
        )

        sidePanel.add("Territory Exchange".toLabel(fontSize = 24)).colspan(2).row()
        sidePanel.add("${playerCiv.civName} ↔ ${otherCiv.civName}".toLabel(Color.LIGHT_GRAY))
            .colspan(2).row()
        sidePanel.addSeparator()

        sidePanel.add("Click their tile = Take".toLabel(TAKE_COLOR)).colspan(2).left().row()
        sidePanel.add("Click your tile = Give".toLabel(GIVE_COLOR)).colspan(2).left().row()
        sidePanel.addSeparator()

        sidePanel.add("Taking: 0 tiles".toLabel().also { it.name = "takeCount" })
            .colspan(2).left().row()
        sidePanel.add("Giving: 0 tiles".toLabel().also { it.name = "giveCount" })
            .colspan(2).left().row()
        if (otherCiv.isCityState) {
            sidePanel.add("Influence: 0".toLabel().also { it.name = "influenceChange" })
                .colspan(2).left().row()
        }
        sidePanel.addSeparator()

        val confirmBtn = "Confirm".toTextButton()
        confirmBtn.onClick { confirmExchange() }
        sidePanel.add(confirmBtn).pad(4f)

        val cancelBtn = "Cancel".toTextButton()
        cancelBtn.keyShortcuts.add(KeyCharAndCode.BACK)
        cancelBtn.onActivation { game.popScreen() }
        sidePanel.add(cancelBtn).pad(4f).row()

        sidePanel.pack()
        positionSidePanel()
    }

    private fun positionSidePanel() {
        sidePanel.setPosition(stage.width - 10f, stage.height / 2, Align.right)
    }

    private fun updateCounts() {
        val takeLabel = sidePanel.findActor<com.badlogic.gdx.scenes.scene2d.ui.Label>("takeCount")
        val giveLabel = sidePanel.findActor<com.badlogic.gdx.scenes.scene2d.ui.Label>("giveCount")
        takeLabel?.setText("Taking: ${tilesToTake.size} tiles".tr())
        giveLabel?.setText("Giving: ${tilesToGive.size} tiles".tr())
        if (otherCiv.isCityState) {
            val influenceLabel = sidePanel.findActor<com.badlogic.gdx.scenes.scene2d.ui.Label>(
                "influenceChange"
            )
            val change = getInfluenceChange().toInt()
            val sign = if (change > 0) "+" else ""
            influenceLabel?.setText("Influence: $sign$change".tr())
        }
        sidePanel.pack()
        positionSidePanel()
    }

    // ── Transfer execution ──

    private fun getInfluenceChange(): Float {
        if (!otherCiv.isCityState) return 0f
        return tilesToGive.size * 30f - tilesToTake.size * 50f
    }

    private fun confirmExchange() {
        if (tilesToTake.isEmpty() && tilesToGive.isEmpty()) {
            ToastPopup("No tiles selected", this)
            return
        }

        val message = buildString {
            if (tilesToTake.isNotEmpty()) append("Take ${tilesToTake.size} tiles from ${otherCiv.civName}\n")
            if (tilesToGive.isNotEmpty()) append("Give ${tilesToGive.size} tiles to ${otherCiv.civName}\n")
            val inf = getInfluenceChange()
            if (otherCiv.isCityState && inf != 0f) {
                val sign = if (inf > 0) "+" else ""
                append("Influence: $sign${inf.toInt()}\n")
            }
            append("\nConfirm exchange?")
        }

        ConfirmPopup(this, message, "Confirm", isConfirmPositive = true) {
            executeTransfers()
            game.popScreen()
        }.open()
    }

    private fun executeTransfers() {
        for (tile in tilesToTake) {
            val city = findNearestCity(tile, playerCiv, tilesToTake) ?: continue
            city.expansion.takeOwnership(tile)
        }
        for (tile in tilesToGive) {
            val city = findNearestCity(tile, otherCiv, tilesToGive) ?: continue
            city.expansion.takeOwnership(tile)
        }
        if (otherCiv.isCityState) {
            val inf = getInfluenceChange()
            if (inf != 0f)
                otherCiv.getDiplomacyManager(playerCiv)?.addInfluence(inf)
        }
        playerCiv.cache.updateOurTiles()
        otherCiv.cache.updateOurTiles()
    }

    private fun findNearestCity(
        tile: Tile,
        receivingCiv: Civilization,
        batch: Set<Tile>
    ): com.unciv.logic.city.City? {
        return receivingCiv.cities
            .filter { city ->
                tile.neighbors.any { it.position in city.tiles } ||
                    tile.neighbors.any { n ->
                        n in batch && n.neighbors.any { it.position in city.tiles }
                    }
            }
            .minByOrNull { city ->
                val dx = city.location.x - tile.position.x
                val dy = city.location.y - tile.position.y
                dx * dx + dy * dy + dx * dy
            }
    }

    // ── Lifecycle ──

    override fun recreate(): BaseScreen = TerritoryExchangeScreen(playerCiv, otherCiv)
}
