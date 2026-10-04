package com.unciv.logic.front

import org.junit.Assert.assertEquals
import org.junit.Test

class FrontAutomationTest {
    @Test
    fun `superiority picks an offensive and parity holds the line`() {
        assertEquals(FrontStance.Aggressive, FrontAutomation.chooseStance(2.5f, 100, true))
        assertEquals(FrontStance.Moderate, FrontAutomation.chooseStance(1.5f, 100, true))
        assertEquals(FrontStance.Defensive, FrontAutomation.chooseStance(1.0f, 100, true))
        assertEquals(FrontStance.Defensive, FrontAutomation.chooseStance(0.5f, 100, true))
    }

    @Test
    fun `a worn division in contact is relieved whatever the balance`() {
        assertEquals(FrontStance.Withdrawal, FrontAutomation.chooseStance(3f, FrontAutomation.RELIEVE_BELOW - 1, true))
        assertEquals(FrontStance.Aggressive, FrontAutomation.chooseStance(3f, FrontAutomation.RELIEVE_BELOW + 20, true))
    }

    @Test
    fun `a division out of contact rests until it is fit, without oscillating`() {
        assertEquals(FrontStance.Defensive, FrontAutomation.chooseStance(3f, 30, false))
        assertEquals(FrontStance.Defensive, FrontAutomation.chooseStance(3f, 70, false, FrontStance.Defensive))
        assertEquals(FrontStance.Defensive, FrontAutomation.chooseStance(3f, 70, false, FrontStance.Withdrawal))
        assertEquals(FrontStance.Aggressive, FrontAutomation.chooseStance(3f, FrontAutomation.REST_UNTIL, false, FrontStance.Defensive))
    }

    @Test
    fun `a division already on the offensive and still fit keeps pushing out of contact`() {
        assertEquals(FrontStance.Aggressive, FrontAutomation.chooseStance(3f, 70, false, FrontStance.Aggressive))
    }
}
