package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.GUI
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.ui.components.extensions.toPercent
import com.unciv.ui.popups.ConfirmPopup
import yairm210.purity.annotations.Readonly
import kotlin.random.Random

object UnitActionsPillage {

    internal fun getPillageActions(unit: MapUnit, tile: Tile): Sequence<UnitAction> {
        val pillageAction = getPillageAction(unit, tile)
            ?: return emptySequence()
        if (pillageAction.action == null || unit.civ.isAIOrAutoPlaying())
            return sequenceOf(pillageAction)
        val isScorchedEarth = tile.getOwner() == unit.civ && isEnemyNearby(unit, tile)
        val confirmText = if (isScorchedEarth)
            "Are you sure you want to scorch this [${tile.getImprovementToPillageName()!!}]? (Heals 30 HP)"
        else
            "Are you sure you want to pillage this [${tile.getImprovementToPillageName()!!}]?"
        val confirmButton = if (isScorchedEarth) "Scorch" else "Pillage"
        return sequenceOf(UnitAction(UnitActionType.Pillage, 65f, pillageAction.title) {
            ConfirmPopup(
                GUI.getWorldScreen(),
                confirmText,
                confirmButton,
                true
            ) {
                (pillageAction.action)()
                GUI.setUpdateWorldOnNextRender()
            }.open()
        })
    }

    internal fun getPillageAction(unit: MapUnit, tile: Tile): UnitAction? {
        val improvementName = unit.currentTile.getImprovementToPillageName()
        if (unit.isCivilian() || improvementName == null) return null
        val isScorchedEarth = tile.getOwner() == unit.civ && isEnemyNearby(unit, tile)
        if (tile.getOwner() == unit.civ && !isScorchedEarth) return null
        val titleText = if (isScorchedEarth) "Scorched Earth [$improvementName]"
            else "${UnitActionType.Pillage} [$improvementName]"
        return UnitAction(
            UnitActionType.Pillage, 65f,
            title = titleText,
            action = {
                val pillagedImprovement = unit.currentTile.getImprovementToPillageName()!!
                val pillagingImprovement = unit.currentTile.canPillageTileImprovement()

                if (isScorchedEarth) {
                    // Scorched earth: no loot, heal 30 HP, notify own civ
                    val pillageText = "Our [${unit.baseUnit.name}] has scorched [$pillagedImprovement] to deny it to the enemy"
                    val icon = "ImprovementIcons/$pillagedImprovement"
                    unit.civ.addNotification(
                        pillageText,
                        tile.position,
                        NotificationCategory.War,
                        icon,
                        NotificationIcon.War
                    )
                } else {
                    val pillageText = "An enemy [${unit.baseUnit.name}] has pillaged our [$pillagedImprovement]"
                    val icon = "ImprovementIcons/$pillagedImprovement"
                    tile.getOwner()?.addNotification(
                        pillageText,
                        tile.position,
                        NotificationCategory.War,
                        icon,
                        NotificationIcon.War,
                        unit.baseUnit.name
                    )
                    pillageLooting(tile, unit)
                }

                tile.setPillaged()  // Also triggers reassignPopulation
                if (tile.resource != null) tile.getOwner()?.cache?.updateCivResources()

                val freePillage = unit.hasUnique(UniqueType.NoMovementToPillage, checkCivInfoUniques = true)
                if (!freePillage) unit.useMovementPoints(1f)

                if (isScorchedEarth) {
                    // Scorched earth always heals 30 HP
                    unit.healBy(30)
                } else if (pillagingImprovement) {
                    var healAmount = 25f
                    for (unique in unit.getMatchingUniques(UniqueType.PercentHealthFromPillaging, checkCivInfoUniques = true)) {
                        healAmount *= unique.params[0].toPercent()
                    }
                    unit.healBy(healAmount.toInt())
                }

                if (tile.getImprovementToPillage()?.hasUnique(UniqueType.DestroyedWhenPillaged) == true) {
                    tile.removeImprovement()
                }

            }.takeIf { unit.hasMovement() && canPillage(unit, tile) }
        )
    }

    private fun pillageLooting(tile: Tile, unit: MapUnit) {
        val closestCity = unit.civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
        val improvement = tile.getImprovementToPillage()!!

        // Accumulate the loot
        var pillageYield = Stats()
        val stateForConditionals = unit.cache.state
        val random = Random(unit.civ.gameInfo.turns * unit.getTile().position.hashCode().toLong())
        for (unique in improvement.getMatchingUniques(UniqueType.PillageYieldRandom, stateForConditionals)) {
            for ((stat, value) in unique.stats) {
                var yieldsToAdd = Stats()
                // Unique text says "approximately [X]", so we add 0..X twice - think an RPG's 2d12
                yieldsToAdd.add(stat, (random.nextInt((value + 1).toInt()) + random.nextInt((value + 1).toInt()).toFloat()))
                if (unique.isModifiedByGameSpeed())
                    yieldsToAdd *= unit.civ.gameInfo.speed.modifier
                if (unique.isModifiedByGameProgress())
                    yieldsToAdd *= unique.getGameProgressModifier(unit.civ)
                pillageYield.add(yieldsToAdd)
            }
        }
        for (unique in improvement.getMatchingUniques(UniqueType.PillageYieldFixed, stateForConditionals)) {
            var yieldsToAdd = unique.stats
            if (unique.isModifiedByGameSpeed())
                yieldsToAdd *= unit.civ.gameInfo.speed.modifier
            if (unique.isModifiedByGameProgress())
                yieldsToAdd *= unique.getGameProgressModifier(unit.civ)
            pillageYield.add(yieldsToAdd)
        }

        //Multiply according to uniques
        for (unique in unit.getMatchingUniques(UniqueType.PercentYieldFromPillaging, checkCivInfoUniques = true)) {
            pillageYield *= unique.params[0].toPercent()
        }

        // Please no notification when there's no loot
        if (pillageYield.isEmpty()) return

        // Distribute the loot and keep record what went to civ/city for the notification(s)
        val globalPillageYield = Stats()
        val toCityPillageYield = Stats()
        for ((stat, value) in pillageYield) {
            if (stat in Stat.statsWithCivWideField) {
                unit.civ.addStat(stat, value.toInt())
                globalPillageYield[stat] += value
            }
            else if (closestCity != null) {
                closestCity.addStat(stat, value.toInt())
                toCityPillageYield[stat] += value
            }
        }

        // Now tell the user about the swag
        fun Stats.notify(suffix: String) {
            if (isEmpty()) return
            val text = "We have looted [${toStringWithoutIcons()}] from a [${improvement.name}]" + suffix
            unit.civ.addNotification(text, tile.position, NotificationCategory.War, "ImprovementIcons/${improvement.name}", NotificationIcon.War)
        }
        toCityPillageYield.notify(" which has been sent to [${closestCity?.name}]")
        globalPillageYield.notify("")
    }

    // Public - used in UnitAutomation
    @Readonly
    fun canPillage(unit: MapUnit, tile: Tile): Boolean {
        if (unit.isTransported) return false
        if (!tile.canPillageTile()) return false
        if (unit.hasUnique(UniqueType.CannotPillage, checkCivInfoUniques = true)) return false
        val tileOwner = tile.getOwner()
        // Scorched earth: allow pillaging own territory if enemy unit nearby
        if (tileOwner == unit.civ) return isEnemyNearby(unit, tile)
        // Can't pillage friendly tiles, just like you can't attack them - it's an 'act of war' thing
        return tileOwner == null || unit.civ.isAtWarWith(tileOwner)
    }

    /** Check if any enemy military unit is within 2 tiles */
    @Readonly
    private fun isEnemyNearby(unit: MapUnit, tile: Tile): Boolean {
        return tile.getTilesInDistance(2).any { nearbyTile ->
            nearbyTile.militaryUnit != null && unit.civ.isAtWarWith(nearbyTile.militaryUnit!!.civ)
        }
    }
}
