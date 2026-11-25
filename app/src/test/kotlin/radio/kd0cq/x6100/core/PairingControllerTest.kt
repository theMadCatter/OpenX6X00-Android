// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
// Written by Revolta via OpenCode.

package radio.kd0cq.x6100.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import radio.kd0cq.x6100.net.ControlInfo
import radio.kd0cq.x6100.net.MemoryPanelTokenStore
import radio.kd0cq.x6100.net.PanelEvent
import radio.kd0cq.x6100.net.PanelRole
import radio.kd0cq.x6100.net.RoleInfo

/**
 * Pairing, role and the radio's controls, as `src/remote_server.c` answers them: `pair?` for a code on
 * the radio's screen, `pair CODE NAME` for a token and a role, `auth TOKEN` at the start of every
 * later connection, and `ctl WHO MINE` for whoever holds the knobs.
 *
 * The question these tests answer is the one the PTT button asks: does this app, right now, have any
 * business pressing the key? A stranger on the network is a viewer, a viewer cannot transmit, and a
 * session the admin said no to stays that way until the radio says otherwise.
 */
class PairingControllerTest {

    private val token = "0123456789abcdef0123456789abcdef" // 32 hex: the radio's own shape
    private val lines = mutableListOf<String>()
    private val tokens = MemoryPanelTokenStore()
    private val seen = mutableListOf<Pair<PairingController.Status, String?>>()

    private fun controller() = PairingController(
        send = { line -> lines += line; true },
        tokens = tokens,
        listener = { status, why -> seen += status to why },
    )

    // ---- the code and the pairing ----

    @Test
    fun askingForACodeAsksTheRadioAndWaits() {
        val c = controller()
        assertTrue(c.askForCode())
        assertEquals(listOf("pair?"), lines)
        assertEquals(PairingController.State.CodeShown, c.status().state)
        assertFalse(c.status().paired)
    }

    @Test
    fun theCodeOnTheRadioScreenKeepsUsWaitingAndSaysSo() {
        val c = controller()
        c.askForCode()
        c.onEvent(PanelEvent.Notice("pairing code shown on the radio for 2 min"))
        assertEquals(PairingController.State.CodeShown, c.status().state)
        assertEquals("type the six digits the radio shows", c.status().lastWord)
        assertFalse(c.status().canTransmit) // a code on a screen is not a session
    }

    @Test
    fun aCodeThatIsNotSixDigitsIsNeverSent() {
        val c = controller()
        assertFalse(c.pair("12345", "Phone"))
        assertFalse(c.pair("1234567", "Phone"))
        assertFalse(c.pair("12a456", "Phone"))
        assertEquals(emptyList<String>(), lines)
        assertEquals(PairingController.State.Refused, c.status().state)
        assertEquals("a pairing code is six digits", c.status().lastWord)
    }

    @Test
    fun thePairingCarriesTheCodeAndOurNameAsTheRadioWantsIt() {
        val c = controller()
        assertTrue(c.pair("123456", "My Phone"))
        assertEquals(listOf("pair 123456 My_Phone"), lines) // spaces become underscores
    }

    @Test
    fun anEmptyNameIsTheAppsOwn() {
        val c = controller()
        assertTrue(c.pair("123456", "   "))
        assertEquals(listOf("pair 123456 Android"), lines)
    }

    @Test
    fun theRadiosAnswerKeepsTheTokenAndWhatItSaysWeMayDo() {
        val c = controller()
        c.pair("123456", "Phone")
        c.onEvent(PanelEvent.Paired(token, PanelRole.Operate, txAllowed = true))
        assertTrue(c.hasToken())
        assertEquals(token, tokens.token)
        val s = c.status()
        assertEquals(PairingController.State.Paired, s.state)
        assertTrue(s.paired)
        assertTrue(s.canOperate)
        assertTrue(s.canTransmit)
        assertNull(s.lastWord)
    }

    @Test
    fun aTokenOfTheWrongShapeIsNotKept() {
        // A token the app cannot send back is worse than none: `auth` would be noise on the wire.
        val c = controller()
        c.onEvent(PanelEvent.Paired("short", PanelRole.Admin, txAllowed = true))
        assertFalse(c.hasToken())
        assertNull(tokens.token)
        assertEquals(PairingController.State.Refused, c.status().state)
        assertFalse(c.status().canTransmit)
    }

    @Test
    fun forgettingThePairingTellsTheRadioAndWipesWhatWeHeard() {
        val c = controller()
        c.onEvent(PanelEvent.Paired(token, PanelRole.Admin, txAllowed = true))
        c.onEvent(PanelEvent.Role(RoleInfo(PanelRole.Admin, true, "Phone")))
        c.forgetPairing()
        assertEquals(listOf("unpair"), lines)
        assertFalse(c.hasToken())
        val s = c.status()
        assertEquals(PairingController.State.Unpaired, s.state)
        assertEquals(PanelRole.View, s.role)
        assertFalse(s.canTransmit)
        assertEquals("", s.sessionName)
    }

    // ---- what the role lets us do ----

    @Test
    fun aStrangerOnTheNetworkIsAViewerAndCannotPressTheKey() {
        val s = controller().status()
        assertEquals(PairingController.State.Unpaired, s.state)
        assertEquals(PanelRole.View, s.role)
        assertFalse(s.canOperate)
        assertFalse(s.canTransmit)
    }

    @Test
    fun aSessionTheAdminRefusedTxToStillCannotPressTheKey() {
        val c = controller()
        c.onEvent(PanelEvent.Paired(token, PanelRole.Admin, txAllowed = false))
        assertTrue(c.status().canOperate) // the frequency and the mode are ours to change
        assertFalse(c.status().canTransmit) // and the key is not
    }

    @Test
    fun aViewerRoleEndsTheRightToTransmitEvenAfterAPairing() {
        val c = controller()
        c.onEvent(PanelEvent.Paired(token, PanelRole.Operate, txAllowed = true))
        assertTrue(c.status().canTransmit)
        c.onEvent(PanelEvent.Role(RoleInfo(PanelRole.View, true, "Phone")))
        assertFalse(c.status().canOperate)
        assertFalse(c.status().canTransmit) // the role is the word, not the pairing
    }

    @Test
    fun theRoleLineNamesTheSessionTheAdminSees() {
        val c = controller()
        c.onEvent(PanelEvent.Paired(token, PanelRole.Operate, txAllowed = true))
        c.onEvent(PanelEvent.Role(RoleInfo(PanelRole.Operate, true, "Kitchen phone")))
        assertEquals("Kitchen phone", c.status().sessionName)
        assertEquals(PanelRole.Operate, c.status().role)
    }

    @Test
    fun authSaysWhoWeAreOnANewConnection() {
        tokens.token = token
        val c = controller()
        assertTrue(c.auth())
        assertEquals(listOf("auth $token"), lines)
    }

    @Test
    fun authWithoutAPairingAsksForOneInstead() {
        val c = controller()
        assertFalse(c.auth())
        assertEquals(emptyList<String>(), lines)
        assertEquals("this radio is not paired yet", c.status().lastWord)
    }

    @Test
    fun aTokenTheRadioDoesNotKnowIsDroppedAtOnce() {
        // "err unknown session: pair this browser again": keeping a token the radio has thrown away
        // would make every connection after it a viewer's, with no way to see why.
        tokens.token = token
        val c = controller()
        c.auth()
        c.onEvent(PanelEvent.Failure("unknown session: pair this browser again"))
        assertFalse(c.hasToken())
        assertNull(tokens.token)
        assertEquals(PairingController.State.Unpaired, c.status().state)
        assertFalse(c.status().canTransmit)
    }

    @Test
    fun aRevokedSessionIsForgottenTheSameWay() {
        tokens.token = token
        val c = controller()
        c.onEvent(PanelEvent.Paired(token, PanelRole.Admin, txAllowed = true))
        c.onEvent(PanelEvent.Failure("session revoked"))
        assertFalse(c.hasToken())
        assertEquals(PairingController.State.Unpaired, c.status().state)
    }

    @Test
    fun aRefusedCodeIsSaidInWordsTheOperatorCanActOn() {
        val c = controller()
        c.pair("123456", "Phone")
        c.onEvent(PanelEvent.Failure("pairing refused: no code is showing"))
        assertEquals(PairingController.State.Refused, c.status().state)
        assertEquals("the radio did not accept that code", c.status().lastWord)
    }

    // ---- the controls ----

    @Test
    fun theControlLineSaysWhoseKnobsTheseAre() {
        val c = controller()
        val change = c.onEvent(PanelEvent.ControlHeld(ControlInfo("local", mine = false)))
        assertNotNull(change)
        assertTrue(change!!.someoneElse)
        assertFalse(change.takenFromUs)
        assertFalse(c.control.isOurs)
        assertEquals("local", c.status().control.holder)
    }

    @Test
    fun losingTheControlsIsTheChangeTheKeyCaresAbout() {
        val c = controller()
        c.onEvent(PanelEvent.ControlHeld(ControlInfo("browser Phone", mine = true)))
        assertTrue(c.control.isOurs)
        val change = c.onEvent(PanelEvent.ControlHeld(ControlInfo("tci", mine = false)))
        assertTrue(change!!.takenFromUs)
        assertFalse(c.control.isOurs)
        // And a line that says the same thing twice is not a change at all.
        val again = c.onEvent(PanelEvent.ControlHeld(ControlInfo("tci", mine = false)))
        assertFalse(again!!.takenFromUs)
        assertFalse(again.givenToUs)
    }

    @Test
    fun nobodysHoldingThemIsFreeAndNotOurs() {
        val c = controller()
        val change = c.onEvent(PanelEvent.ControlHeld(ControlInfo("none", mine = false)))
        assertTrue(c.control.isFree)
        assertFalse(change!!.isOurs)
        assertFalse(change.someoneElse) // free is not the same as taken
    }

    @Test
    fun aFreshSocketForgetsWhoHeldThem() {
        // The radio answers `hello` before anything else on a new connection, and the controls it
        // announced on the last one are not necessarily what it will say on this one.
        val c = controller()
        c.onEvent(PanelEvent.ControlHeld(ControlInfo("browser Phone", mine = true)))
        val change = c.onEvent(PanelEvent.Hello("1.0", 12345L))
        assertTrue(change!!.takenFromUs)
        assertTrue(c.control.isFree)
    }

    @Test
    fun aLinkThatDroppedKeepsThePairingAndLosesTheRole() {
        val c = controller()
        c.onEvent(PanelEvent.Paired(token, PanelRole.Operate, txAllowed = true))
        c.onEvent(PanelEvent.ControlHeld(ControlInfo("browser Phone", mine = true)))
        c.linkLost("the link to the radio dropped")
        assertTrue(c.hasToken()) // the pairing is for the next connection, which we will make
        val s = c.status()
        assertEquals(PairingController.State.Unpaired, s.state)
        assertEquals(PanelRole.View, s.role)
        assertFalse(s.canTransmit) // and until the radio repeats the role, we are a viewer
        assertTrue(s.control.free)
        assertEquals("the link to the radio dropped", s.lastWord)
    }

    @Test
    fun aLineAboutSomethingElseChangesNothing() {
        val c = controller()
        c.onEvent(PanelEvent.Paired(token, PanelRole.Operate, txAllowed = true))
        val before = c.status()
        assertNull(c.onEvent(PanelEvent.Meter(-4.5)))
        assertNull(c.onEvent(PanelEvent.Transmitting(true))) // the PTT machine reads that one
        assertNull(c.onEvent(PanelEvent.Failure("ptt: this key is controlled by the front panel")))
        assertEquals(before, c.status())
    }

    @Test
    fun theScreenHearsEveryChangeWithTheWordForIt() {
        val c = controller()
        c.askForCode()
        c.pair("123456", "Phone")
        c.onEvent(PanelEvent.Paired(token, PanelRole.Operate, txAllowed = true))
        c.onEvent(PanelEvent.Failure("unknown session: pair this browser again"))
        assertEquals(
            listOf(
                PairingController.State.CodeShown,
                PairingController.State.CodeShown,
                PairingController.State.Paired,
                PairingController.State.Unpaired,
            ),
            seen.map { it.first.state },
        )
        assertEquals("unknown session: pair this browser again", seen.last().second)
    }
}
