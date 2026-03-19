package com.unciv.ui.screens.worldscreen.mainmenu

import com.badlogic.gdx.graphics.Color
import com.unciv.logic.GameInfo
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.UncivSlider
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.worldscreen.WorldScreen

/**
 * In-game popup to adjust AI civilization bonuses in real time.
 * Sliders range from -10% (0.9) to +200% (3.0) with 5% steps.
 * Changes apply immediately to all AI city stat calculations.
 */
class AiBonusPopup(private val worldScreen: WorldScreen) : Popup(worldScreen) {
    private val gameInfo: GameInfo = worldScreen.gameInfo

    init {
        defaults().pad(5f)

        add("AI Bonuses".toLabel(fontSize = 24)).colspan(2).row()
        add("Adjust AI civilization stat multipliers".toLabel(Color.LIGHT_GRAY)).colspan(2).row()
        addSeparator()

        addSliderRow("Production", gameInfo.customAiProductionModifier) { gameInfo.customAiProductionModifier = it }
        addSliderRow("Growth", gameInfo.customAiGrowthModifier) { gameInfo.customAiGrowthModifier = it }
        addSliderRow("Gold", gameInfo.customAiGoldModifier) { gameInfo.customAiGoldModifier = it }
        addSliderRow("Science", gameInfo.customAiScienceModifier) { gameInfo.customAiScienceModifier = it }

        addSeparator()

        addCloseButton("Close") { recalculateAllAiCities() }

        pack()
        open(force = true)
    }

    private fun addSliderRow(label: String, initialValue: Float, onChanged: (Float) -> Unit) {
        add(label.toLabel()).left().padRight(10f)
        val slider = UncivSlider(
            min = 0.9f,
            max = 3.0f,
            step = 0.05f,
            initial = initialValue,
            sound = UncivSound.Silent,
            getTipText = UncivSlider.Companion::formatPercent,
            onChange = { value -> onChanged(value) }
        )
        add(slider).minWidth(250f).row()
    }

    private fun recalculateAllAiCities() {
        for (civ in gameInfo.civilizations) {
            if (civ.isHuman()) continue
            for (city in civ.cities) {
                city.cityStats.update()
            }
        }
    }
}
