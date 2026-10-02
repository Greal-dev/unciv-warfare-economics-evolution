package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.GUI
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.CivView

/**
 * TW v2 — Resource Prospecting tab.
 *
 * Helps the player decide whether to build, colonize, or conquer to obtain
 * a specific strategic/luxury resource. Lists every explored deposit grouped by
 * ownership and improvement status.
 */
class ProspectOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData? = null
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {

    class ProspectTabPersistableData(
        var selectedResource: String = ""
    ) : EmpireOverviewTabPersistableData() {
        override fun isEmpty() = selectedResource.isEmpty()
    }
    override val persistableData = (persistedData as? ProspectTabPersistableData) ?: ProspectTabPersistableData()

    /** TW: this fork-only tab works on the logic objects, not on views */
    private val tabCiv: Civilization = viewingPlayer.getCiv()

    private companion object {
        const val iconSize = 40f
        const val selectedIconSize = 48f
        const val pad = 8f
    }

    private val resourceList: List<TileResource> = gameInfo.ruleset.tileResources.values
        .filter {
            (it.resourceType == ResourceType.Strategic || it.resourceType == ResourceType.Luxury) &&
                tabCiv.canSeeResource(it)
        }
        .sortedWith(compareBy({ it.resourceType }, { it.name }))

    private val resourceRow = Table().apply { defaults().pad(pad) }
    private val resultsTable = Table().apply { defaults().pad(pad) }

    init {
        defaults().pad(pad)
        top()
        buildResourceRow()
        add(resourceRow).left().row()
        addSeparator()
        add(resultsTable).left().top()
        if (persistableData.selectedResource.isEmpty() && resourceList.isNotEmpty()) {
            persistableData.selectedResource = resourceList.first().name
        }
        refresh()
    }

    private fun buildResourceRow() {
        resourceRow.clear()
        for (resource in resourceList) {
            val isSelected = resource.name == persistableData.selectedResource
            val size = if (isSelected) selectedIconSize else iconSize
            val icon = ImageGetter.getResourcePortrait(resource.name, size).apply {
                addTooltip(resource.name, tipAlign = Align.topLeft, hideIcons = true)
                onClick {
                    persistableData.selectedResource = resource.name
                    buildResourceRow()
                    refresh()
                }
            }
            val cell = if (isSelected) {
                val wrapper = Table()
                wrapper.background = BaseScreen.skinStrings.getUiBackground(
                    "ProspectOverviewTab/SelectedResource",
                    tintColor = Color.GOLD.cpy().apply { a = 0.35f }
                )
                wrapper.add(icon).size(size).pad(3f)
                resourceRow.add(wrapper)
            } else {
                resourceRow.add(icon).size(size)
            }
        }
    }

    private enum class Category(val caption: String, val color: Color) {
        OwnUnimproved("My territory — not yet improved (build to exploit)", Color.GREEN),
        OwnImproved("My territory — already improved", Color.LIGHT_GRAY),
        Unowned("Neutral / unowned territory (colonize)", Color.CYAN),
        ForeignCiv("Foreign civilizations (conquer / trade)", Color.ORANGE),
        CityState("City-states (ally / conquer)", Color.YELLOW),
    }

    private data class Finding(val tile: Tile, val category: Category, val ownerLabel: String)

    private fun refresh() {
        resultsTable.clear()
        val resourceName = persistableData.selectedResource
        if (resourceName.isEmpty()) {
            resultsTable.add("Select a resource above.".toLabel()).left()
            return
        }
        val resource = gameInfo.ruleset.tileResources[resourceName]
            ?: return
        val tileMap = gameInfo.tileMap

        val findings = mutableListOf<Finding>()
        for (tile in tileMap.values) {
            if (tile.resource != resourceName) continue
            if (!tile.isExplored(tabCiv)) continue
            val owner = tile.getOwner()
            val category: Category
            val label: String
            when {
                owner == tabCiv -> {
                    val improved = tile.providesResources(tabCiv)
                    category = if (improved) Category.OwnImproved else Category.OwnUnimproved
                    label = tile.getCity()?.name ?: tabCiv.civName
                }
                owner == null -> {
                    category = Category.Unowned
                    val nearestCity = tabCiv.cities.minByOrNull {
                        it.getCenterTile().aerialDistanceTo(tile)
                    }
                    label = if (nearestCity != null)
                        "near [${nearestCity.name}] (${nearestCity.getCenterTile().aerialDistanceTo(tile)} tiles)"
                    else "unexplored frontier"
                }
                owner.isCityState -> {
                    category = Category.CityState
                    val cs = owner.civName
                    val rel = if (owner.allyCiv == tabCiv) " — ally"
                        else if (tabCiv.knows(owner)) ""
                        else ""
                    label = "$cs$rel"
                }
                else -> {
                    category = Category.ForeignCiv
                    label = owner.civName
                }
            }
            findings += Finding(tile, category, label)
        }

        // Header line: totals
        val totalsByCategory = findings.groupingBy { it.category }.eachCount()
        val total = findings.size
        val headerLine = Table()
        headerLine.add("[$resourceName] — $total deposits explored".toLabel(fontSize = 22)).pad(pad).row()
        resultsTable.add(headerLine).left().row()

        if (findings.isEmpty()) {
            resultsTable.add("No explored deposits found.\nSend scouts to reveal more territory.".toLabel())
                .pad(pad * 2).left().row()
            return
        }

        // Sections in fixed order
        for (category in Category.entries) {
            val rows = findings.filter { it.category == category }
            if (rows.isEmpty()) continue
            val count = totalsByCategory[category] ?: 0
            val section = Table()
            section.background = BaseScreen.skinStrings.getUiBackground(
                "ProspectOverviewTab/Section",
                tintColor = category.color.cpy().apply { a = 0.18f }
            )
            section.defaults().pad(4f)
            section.add("${category.caption}  ($count)".toLabel(fontSize = 18))
                .left().colspan(3).row()
            for (finding in rows.sortedBy { it.tile.position.toString() }) {
                val coordLabel = "(${finding.tile.position.x.toInt()}, ${finding.tile.position.y.toInt()})"
                    .toLabel().apply {
                        color = Color.LIGHT_GRAY
                    }
                val ownerLabel = finding.ownerLabel.toLabel()
                val viewButton = "View".toLabel(Color.CYAN).apply {
                    onClick {
                        GUI.resetToWorldScreen()
                        GUI.getMap().setCenterPosition(finding.tile.position, selectUnit = false)
                    }
                }
                section.add(coordLabel).left()
                section.add(ownerLabel).left().padLeft(pad)
                section.add(viewButton).padLeft(pad * 2).row()
            }
            resultsTable.add(section).left().padBottom(pad).row()
        }
    }
}
