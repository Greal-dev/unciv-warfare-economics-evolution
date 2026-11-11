package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.logic.front.FrontResolver
import com.unciv.logic.front.FrontStance
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.worldscreen.WorldScreen

/**
 * Front mode: asks the player how hard to hit a tile, with the forecast of each intensity,
 * resolves the attack on the spot and then says what happened.
 */
class FrontAttackPopup(private val worldScreen: WorldScreen, private val division: MapUnit, private val tile: Tile) {

    fun open() {
        val popup = Popup(worldScreen)
        popup.addGoodSizedLabel("Attack on (${tile.position.x}, ${tile.position.y})").row()
        for (intensity in listOf(FrontStance.Moderate, FrontStance.Aggressive)) {
            val preview = FrontResolver.previewAttack(division, tile, intensity) ?: continue
            val verdict = if (preview.succeeds) "wins" else "fails"
            val support = if (preview.supporters > 0) ", ${preview.supporters} supporting" else ""
            popup.addGoodSizedLabel("${intensity.label}: force ${preview.pressure.toInt()} against ${preview.resistance.toInt()}$support, " +
                "$verdict. We lose about ${preview.attackerLoss}, they lose up to ${preview.defenderLoss}.").row()
            popup.addButton(intensity.label) {
                popup.close()
                launch(intensity)
            }.row()
        }
        popup.addCloseButton()
        popup.open(true)
    }

    private fun launch(intensity: FrontStance) {
        worldScreen.bottomUnitTable.selectedUnitIsFrontTargeting = false
        val result = FrontResolver.attack(division, tile, intensity)
        worldScreen.shouldUpdate = true
        if (result == null) return
        val report = Popup(worldScreen)
        report.addGoodSizedLabel(result.message).row()
        report.addCloseButton()
        report.open(true)
    }
}
