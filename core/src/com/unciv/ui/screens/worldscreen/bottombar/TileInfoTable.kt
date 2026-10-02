package com.unciv.ui.screens.worldscreen.bottombar

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.RoadConnectBuyMode
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.map.tile.TileImprovementBuyer
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.ui.objectdescriptions.TileDescription
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addBorderAllowOpacity
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.civilopediascreen.FormattedLine.IconDisplay
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.DebugUtils
import com.unciv.view.CivView
import com.unciv.view.TileView

class TileInfoTable(private val worldScreen: WorldScreen) : Table(BaseScreen.skin) {
    var civView: CivView = worldScreen.selectedGameView.civView

    init {
        background = BaseScreen.skinStrings.getUiBackground(
            "WorldScreen/TileInfoTable",
            tintColor = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f)
        )
    }

    internal fun updateTileTable(selectedTileView: TileView?) {
        clearChildren()
        pad(5f)

        // A retained selection may still carry the previous spectator or civilization perspective.
        val tileView = selectedTileView?.let { civView.gameView.getTile(it) }

        // TW v2 — Road plan panel overrides the regular tile info while active.
        if (RoadConnectBuyMode.active && RoadConnectBuyMode.civ == worldScreen.viewingCiv) {
            add(makeRoadPlanPanel()).padBottom(5f).row()
        }

        if (tileView != null && (DebugUtils.VISIBLE_MAP || civView.hasExplored(tileView))) {
            val tile = tileView.tile
            add(getStatsTable(tileView)).left().row()
            add(MarkupRenderer.render(TileDescription.toMarkup(tileView, civView), padding = 0f, iconDisplay = IconDisplay.None) {
                worldScreen.openCivilopedia(it)
            } ).padTop(5f).row()
            if (DebugUtils.VISIBLE_MAP) add(tileView.position().toPrettyString().toLabel()).colspan(2).pad(5f)
            if (DebugUtils.SHOW_TILE_IMAGE_LOCATIONS){
                val imagesString = "Images: " + worldScreen.mapHolder.tileGroups[tileView]!!.layerTerrain.tileBaseImages.joinToString{"\n"+it.name}
                add(imagesString.toLabel())
            }

            // TW v2: gold-purchase improvements (Workers replacement)
            if (!RoadConnectBuyMode.active) {
                val repairButton = makeRepairButton(tile)
                if (repairButton != null) add(repairButton).padTop(5f).row()
                val buyButton = makeBuyImprovementButton(tile)
                if (buyButton != null) add(buyButton).padTop(5f).row()
                val removeButton = makeRemoveImprovementButton(tile)
                if (removeButton != null) add(removeButton).padTop(5f).row()
                val connectRoadButton = makeConnectRoadButton(tile)
                if (connectRoadButton != null) add(connectRoadButton).padTop(5f).row()
                val autoButton = makeAutoImprovementsButton()
                if (autoButton != null) add(autoButton).padTop(5f).row()
            }
        }

        pack()
        addBorderAllowOpacity(1f, Color.WHITE)
    }

    /** TW v2 — Floating panel shown while [RoadConnectBuyMode] is active: tile count,
     *  running cost, Confirm + Cancel. */
    private fun makeRoadPlanPanel(): Table {
        val panel = Table()
        val improvement = RoadConnectBuyMode.improvement
        val civ = RoadConnectBuyMode.civ
        val total = RoadConnectBuyMode.totalPrice()
        val canAfford = civ != null && civ.gold >= total
        val label = "Road plan: ${RoadConnectBuyMode.tiles.size} tile(s) — $total ${com.unciv.ui.components.fonts.Fonts.gold}"
        panel.add(label.toLabel()).colspan(2).padBottom(3f).row()
        if (civ != null && !canAfford) {
            panel.add("Not enough gold (${civ.gold} available)".toLabel(Color.RED)).colspan(2).padBottom(3f).row()
        }
        val confirmBtn = "Confirm".toTextButton()
        if (!canAfford || RoadConnectBuyMode.tiles.isEmpty()) confirmBtn.color = Color.GRAY
        confirmBtn.onClick {
            if (!canAfford || RoadConnectBuyMode.tiles.isEmpty()) return@onClick
            val bought = RoadConnectBuyMode.confirm()
            if (bought > 0) worldScreen.shouldUpdate = true
        }
        val cancelBtn = "Cancel".toTextButton()
        cancelBtn.onClick {
            RoadConnectBuyMode.cancel()
            worldScreen.shouldUpdate = true
        }
        panel.add(confirmBtn).padRight(5f)
        panel.add(cancelBtn)
        return panel
    }

    /** TW v2 — "Auto-improvements" toggle: each turn, gold above the reserve is spent
     *  by [com.unciv.logic.automation.civilization.ImprovementPurchaseAutomation] on
     *  prioritized infrastructure. Opens a popup to toggle on/off and edit reserve. */
    private fun makeAutoImprovementsButton(): Table? {
        val viewingCiv = worldScreen.viewingCiv
        if (!viewingCiv.isHuman()) return null
        val table = Table()
        val state = if (viewingCiv.autoImprovementsEnabled) "ON" else "OFF"
        val label = "Auto-improvements: $state (reserve ${viewingCiv.autoImprovementsReserve} ${com.unciv.ui.components.fonts.Fonts.gold})"
        val btn = label.toTextButton()
        btn.onClick { showAutoImprovementsPopup(viewingCiv) }
        table.add(btn)
        return table
    }

    private fun showAutoImprovementsPopup(civ: Civilization) {
        val popup = Popup(worldScreen)
        popup.add("Automatic improvements".toLabel()).colspan(2).padBottom(10f).row()
        popup.add("Spends gold above the reserve on:".toLabel()).colspan(2).padBottom(2f).row()
        popup.add("  1) Repairs (skip if enemy ≤2 tiles)".toLabel()).colspan(2).padBottom(2f).row()
        popup.add("  2) Resource extractors, Farms, forest clearance for farm".toLabel()).colspan(2).padBottom(2f).row()
        popup.add("  3) Upgrade Farm → resource extractor if resource appears".toLabel()).colspan(2).padBottom(2f).row()
        popup.add("  4) Trading posts (where Farm unbuildable)".toLabel()).colspan(2).padBottom(2f).row()
        popup.add("  5) Lumber Mill / Windmill only if no farm possible".toLabel()).colspan(2).padBottom(8f).row()

        val toggleBtn = (if (civ.autoImprovementsEnabled) "Disable" else "Enable").toTextButton()
        toggleBtn.onClick {
            civ.autoImprovementsEnabled = !civ.autoImprovementsEnabled
            worldScreen.shouldUpdate = true
            popup.close()
        }
        popup.add(toggleBtn).colspan(2).fillX().padBottom(5f).row()

        popup.add("Reserve (gold floor):".toLabel()).colspan(2).padTop(8f).row()
        val reserveTable = Table()
        for (amount in listOf(0, 100, 1000, 10000)) {
            val rb = "$amount".toTextButton()
            if (civ.autoImprovementsReserve == amount) rb.color = Color.GOLD
            rb.onClick {
                civ.autoImprovementsReserve = amount
                worldScreen.shouldUpdate = true
                popup.close()
            }
            reserveTable.add(rb).padRight(3f)
        }
        popup.add(reserveTable).colspan(2).padBottom(5f).row()

        // Custom amount input — any integer ≥ 0
        popup.add("Custom amount:".toLabel()).colspan(2).padTop(8f).row()
        val customField = com.unciv.ui.components.widgets.UncivTextField.Numeric(
            "Reserve", civ.autoImprovementsReserve, integerOnly = true)
        val applyBtn = "Apply".toTextButton()
        applyBtn.onClick {
            val v = customField.value?.toInt() ?: return@onClick
            civ.autoImprovementsReserve = v.coerceAtLeast(0)
            worldScreen.shouldUpdate = true
            popup.close()
        }
        popup.add(customField).fillX().padRight(5f)
        popup.add(applyBtn).padBottom(5f).row()

        popup.addCloseButton()
        popup.open()
    }

    /** TW v2 — "Connect road…" button: opens a small popup to pick Road / Railroad
     *  and enters [RoadConnectBuyMode]. */
    private fun makeConnectRoadButton(tile: Tile): Table? {
        val viewingCiv = worldScreen.viewingCiv
        if (!viewingCiv.isHuman()) return null
        val ruleset = tile.ruleset
        val roadOptions = listOf("Road", "Railroad").mapNotNull { ruleset.tileImprovements[it] }
            .filter { imp ->
                val tech = imp.techRequired
                tech == null || viewingCiv.tech.isResearched(tech)
            }
        if (roadOptions.isEmpty()) return null
        val table = Table()
        val btn = "Connect road...".toTextButton()
        btn.onClick {
            val popup = Popup(worldScreen)
            popup.add("Pick the road type to plan".toLabel()).colspan(2).padBottom(8f).row()
            for (imp in roadOptions) {
                val choiceBtn = imp.name.toTextButton()
                choiceBtn.onClick {
                    RoadConnectBuyMode.start(viewingCiv, imp)
                    worldScreen.shouldUpdate = true
                    popup.close()
                }
                popup.add(choiceBtn).colspan(2).fillX().padBottom(3f).row()
            }
            popup.addCloseButton()
            popup.open()
        }
        table.add(btn)
        return table
    }

    /** TW v2 — "Remove improvement (free)" — undoes a tile improvement at zero cost.
     *  Visible only to the human owner of the tile, when there is a removable improvement. */
    private fun makeRemoveImprovementButton(tile: Tile): Table? {
        val viewingCiv = worldScreen.viewingCiv
        if (!viewingCiv.isHuman()) return null
        if (!TileImprovementBuyer.canRemoveImprovement(tile, viewingCiv)) return null
        val table = Table()
        val improvementName = tile.improvement ?: return null
        val btn = "Remove improvement [${improvementName}] (free)".toTextButton()
        btn.onClick {
            if (TileImprovementBuyer.removeImprovement(tile, viewingCiv)) {
                worldScreen.shouldUpdate = true
            }
        }
        table.add(btn)
        return table
    }

    /** TW v2 — returns a "Repair (X gold)" button if the tile is pillaged. */
    private fun makeRepairButton(tile: Tile): Table? {
        val viewingCiv = worldScreen.viewingCiv
        if (!viewingCiv.isHuman()) return null
        if (!tile.isPillaged()) return null
        val price = TileImprovementBuyer.computeRepairPrice(tile, viewingCiv) ?: return null
        val canAfford = viewingCiv.gold >= price
        val table = Table()
        val btn = "Repair improvement ($price ${com.unciv.ui.components.fonts.Fonts.gold})".toTextButton()
        if (!canAfford) btn.color = Color.GRAY
        btn.onClick {
            if (!canAfford) return@onClick
            if (TileImprovementBuyer.repair(tile, viewingCiv)) {
                worldScreen.shouldUpdate = true
            }
        }
        table.add(btn)
        return table
    }

    /** TW v2 — returns a "Buy improvement..." button (always shown for current human player). */
    private fun makeBuyImprovementButton(tile: Tile): Table? {
        val viewingCiv = worldScreen.viewingCiv
        if (!viewingCiv.isHuman()) return null

        val purchasable = collectPurchasableImprovements(tile, viewingCiv)
        val table = Table()
        val label = if (purchasable.isEmpty())
            "Buy improvement (no options)"
        else
            "Buy improvement... (${purchasable.size} options)"
        val button = label.toTextButton()
        button.onClick { showBuyPopup(tile, viewingCiv) }
        table.add(button)
        return table
    }

    private fun collectPurchasableImprovements(tile: Tile, civ: Civilization): List<Pair<String, Int>> {
        val ruleset = tile.ruleset
        val context = GameContext(civInfo = civ, tile = tile)
        val out = mutableListOf<Pair<String, Int>>()
        for (improvement in ruleset.tileImprovements.values) {
            if (improvement.hasUnique(com.unciv.models.ruleset.unique.UniqueType.Unbuildable, context)) continue
            val techRequired = improvement.techRequired
            if (techRequired != null && !civ.tech.isResearched(techRequired)) continue
            if (improvement.name.startsWith("Remove ")) {
                if (improvement.name !in setOf("Remove Forest", "Remove Jungle", "Remove Marsh")) continue
            }
            // Pricing gates outside-territory (only roads/rails/forts allowed there) and
            // computes the distance multiplier. No city work-radius gate.
            val price = TileImprovementBuyer.computePrice(tile, improvement, civ) ?: continue
            // Eligibility check (terrain, resource, tech) — no gold gate so unaffordable
            // options still show up greyed out in the popup.
            if (!tile.improvementFunctions.canBuildImprovement(improvement, context)) continue
            out.add(improvement.name to price)
        }
        return out.sortedBy { it.second }
    }

    private fun showBuyPopup(tile: Tile, civ: Civilization) {
        val popup = Popup(worldScreen)
        popup.add("Buy improvement (${civ.gold} ${com.unciv.ui.components.fonts.Fonts.gold} available)".toLabel()).colspan(2).padBottom(10f).row()
        val purchasable = collectPurchasableImprovements(tile, civ)
        if (purchasable.isEmpty()) {
            popup.add("No improvement available".toLabel()).row()
        } else {
            for ((name, price) in purchasable) {
                val canAfford = civ.gold >= price
                val priceText = "$price ${com.unciv.ui.components.fonts.Fonts.gold}"
                val btn = "$name — $priceText".toTextButton()
                if (!canAfford) btn.color = Color.GRAY
                btn.onClick {
                    if (!canAfford) return@onClick
                    val improvement = tile.ruleset.tileImprovements[name] ?: return@onClick
                    if (TileImprovementBuyer.buy(tile, improvement, civ)) {
                        worldScreen.shouldUpdate = true
                        popup.close()
                    }
                }
                popup.add(btn).colspan(2).fillX().padBottom(3f).row()
            }
        }
        popup.addCloseButton()
        popup.open()
    }

    private fun getStatsTable(tileView: TileView): Table {
        val table = Table()
        table.defaults().pad(2f)
        
        for ((key, value) in tileView.getTileStats(civView)) {
            table.add((key.character + value.toInt().toString()).toLabel())
                .align(Align.left).padRight(5f)
        }
        table.touchable = Touchable.enabled
        table.onClick {
            Popup(worldScreen).apply {
                for ((name, stats) in tileView.getTileStatsBreakdown(civView))
                    add("${name.tr()}: {${stats.clone()}}".toLabel()).row()
                addCloseButton()
            }.open()
        }
        return table
    }

    override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
}
