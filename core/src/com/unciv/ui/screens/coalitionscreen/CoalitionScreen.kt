package com.unciv.ui.screens.coalitionscreen

import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Array as GdxArray
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.diplomacy.coalition.CoalitionManager
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize

/** Compose a coalition proposal: pick a target, select invitees among known
 *  civs at peace with the target, attach an optional gold bribe per invitee.
 *  Submission registers a [com.unciv.logic.diplomacy.coalition.CoalitionProposal]
 *  on [com.unciv.logic.GameInfo.coalitionProposals] and dispatches alerts. */
class CoalitionScreen(
    private val playerCiv: Civilization
) : BaseScreen(), RecreateOnResize {

    private data class InviteeRow(
        val civ: Civilization,
        val checkbox: CheckBox,
        val bribeField: UncivTextField.Integer
    )

    private val knownCivs: List<Civilization> =
        playerCiv.getKnownCivs()
            .filter { it.isAlive() && !it.isBarbarian && !it.isCityState && !it.isSpectator() && it != playerCiv }
            .toList()

    private var selectedTarget: Civilization? = null
    private val inviteeRows = mutableListOf<InviteeRow>()

    private val mainTable = Table(skin)
    private val inviteeListTable = Table(skin)
    private val targetSelector = SelectBox<String>(skin)
    private val summaryLabel = "".toLabel()

    init {
        buildLayout()
    }

    private fun buildLayout() {
        mainTable.defaults().pad(5f)
        mainTable.add("Form a coalition".toLabel(fontSize = 24)).colspan(2).row()
        mainTable.addSeparator()

        mainTable.add("Target:".toLabel()).right()
        val items = GdxArray<String>()
        items.add("(choose)")
        for (c in knownCivs) items.add(c.civName)
        targetSelector.items = items
        targetSelector.selected = "(choose)"
        targetSelector.onChange { onTargetChanged() }
        mainTable.add(targetSelector).left().row()

        mainTable.add("Invitees:".toLabel(fontSize = 18)).colspan(2).left().row()
        inviteeListTable.defaults().pad(3f)
        val scroll = ScrollPane(inviteeListTable, skin)
        mainTable.add(scroll).colspan(2).width(stage.width * 0.6f).height(stage.height * 0.5f).row()

        mainTable.add(summaryLabel).colspan(2).row()

        val proposeButton = "Propose coalition".toTextButton()
        proposeButton.onClick { onPropose() }
        val cancelButton = "Cancel".toTextButton()
        cancelButton.onClick { game.popScreen() }
        cancelButton.keyShortcuts.add(KeyCharAndCode.ESC)

        val buttonRow = Table()
        buttonRow.add(proposeButton).pad(5f)
        buttonRow.add(cancelButton).pad(5f)
        mainTable.add(buttonRow).colspan(2).row()

        mainTable.setFillParent(true)
        mainTable.align(Align.top)
        stage.addActor(mainTable)
        updateSummary()
    }

    private fun onTargetChanged() {
        selectedTarget = knownCivs.firstOrNull { it.civName == targetSelector.selected }
        rebuildInviteeList()
        updateSummary()
    }

    private fun rebuildInviteeList() {
        inviteeListTable.clear()
        inviteeRows.clear()
        val target = selectedTarget ?: return
        val potential = knownCivs.filter { it != target && !it.isAtWarWith(target) }
        if (potential.isEmpty()) {
            inviteeListTable.add("No eligible invitees".toLabel()).row()
            return
        }
        inviteeListTable.add("Civilization".toLabel()).left()
        inviteeListTable.add("Invite".toLabel()).center()
        inviteeListTable.add("Bribe (gold)".toLabel()).right().row()
        for (civ in potential) {
            val checkbox = "".toCheckBox(false) { updateSummary() }
            val bribeField = UncivTextField.Integer("0", 0)
            bribeField.onChange { updateSummary() }
            inviteeListTable.add(civ.civName.toLabel()).left()
            inviteeListTable.add(checkbox).center()
            inviteeListTable.add(bribeField).right().width(120f).row()
            inviteeRows.add(InviteeRow(civ, checkbox, bribeField))
        }
    }

    private fun updateSummary() {
        val target = selectedTarget
        if (target == null) {
            summaryLabel.setText("Pick a target")
            return
        }
        val accepted = inviteeRows.filter { it.checkbox.isChecked }
        val totalBribe = accepted.sumOf { it.bribeField.intValue ?: 0 }
        val remainingGold = playerCiv.gold - totalBribe
        val cooldown = CoalitionManager.isLeaderOnCooldown(playerCiv, target)
        val cdText = if (cooldown) " — ON COOLDOWN against this target" else ""
        summaryLabel.setText(
            "${accepted.size} civs invited, ${totalBribe} gold escrowed (you have ${playerCiv.gold}, ${remainingGold} after escrow)$cdText"
        )
    }

    private fun onPropose() {
        val target = selectedTarget
        if (target == null) {
            ToastPopup("Pick a target first", stage); return
        }
        if (playerCiv.isAtWarWith(target)) {
            ToastPopup("You are already at war with [${target.civName}]", stage); return
        }
        if (CoalitionManager.isLeaderOnCooldown(playerCiv, target)) {
            ToastPopup("You can't propose another coalition against this target for now", stage); return
        }
        val accepted = inviteeRows.filter { it.checkbox.isChecked }
        if (accepted.isEmpty()) {
            ToastPopup("Invite at least one civ", stage); return
        }
        val invitees = accepted.map { it.civ }
        val bribesByName = accepted.associate { it.civ.civName to (it.bribeField.intValue ?: 0).coerceAtLeast(0) }
        val total = bribesByName.values.sum()
        if (total > playerCiv.gold) {
            ToastPopup("Not enough gold for the bribes", stage); return
        }
        val proposal = CoalitionManager.submitProposal(playerCiv, target, invitees, bribesByName)
        if (proposal == null) {
            ToastPopup("Proposal rejected by validation", stage); return
        }
        ToastPopup("Coalition proposal sent. Invitees have ${CoalitionManager.NEGOTIATION_TURNS} turns to respond.", stage)
        game.popScreen()
    }

    override fun recreate(): BaseScreen = CoalitionScreen(playerCiv)
}
