package com.unciv.ui.screens.mapeditorscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.TileMap
import com.unciv.ui.components.NonTransformGroup
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.max

/**
 * TW v2 — Static minimap for the map editor.
 *
 * Renders every tile as a colored hexagon, no civ borders, no fog of war.
 * Click on a tile recenters the editor map there. Drawn over a dark backdrop
 * with a thin border so it remains visible above the main map.
 */
class MapEditorMinimap(
    private val mapHolder: EditorMapHolder,
    targetSize: Float
) : Table() {

    private val tileMap: TileMap = mapHolder.tileMap
    private val tilesGroup = NonTransformGroup()
    private val padding = 6f

    init {
        background = BaseScreen.skinStrings.getUiBackground(
            "MapEditor/Minimap",
            tintColor = Color.BLACK.cpy().apply { a = 0.65f }
        )

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (tile in tileMap.values) {
            val v = HexMath.hex2WorldCoords(tile.position)
            if (v.x < minX) minX = v.x
            if (v.x > maxX) maxX = v.x
            if (v.y < minY) minY = v.y
            if (v.y > maxY) maxY = v.y
        }
        val spanX = max(1f, maxX - minX)
        val spanY = max(1f, maxY - minY)
        val tileSize = (targetSize / max(spanX / 2f + 1f, spanY / 2f + 1f)).coerceAtLeast(2f)

        for (tile in tileMap.values) {
            val img: Image = ImageGetter.getImage("OtherIcons/Hexagon")
            val v = HexMath.hex2WorldCoords(tile.position)
            img.setSize(tileSize, tileSize)
            // Shift so the group's local coords start at 0
            img.setPosition(
                (v.x - minX) * 0.5f * tileSize,
                (v.y - minY) * 0.5f * tileSize
            )
            img.color = tile.getBaseTerrain().getColor().cpy()
            img.onClick { mapHolder.setCenterPosition(tile.position, blink = true) }
            tilesGroup.addActor(img)
        }

        val groupWidth = spanX * 0.5f * tileSize + tileSize
        val groupHeight = spanY * 0.5f * tileSize + tileSize
        tilesGroup.setSize(groupWidth, groupHeight)

        add(tilesGroup).size(groupWidth, groupHeight).pad(padding)
        pack()
    }
}
