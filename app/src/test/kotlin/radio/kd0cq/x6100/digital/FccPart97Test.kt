// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The band table used to be one Advanced data list, with 60 m drawn as a
// continuous span. These tests lock the class edges and the five 60 m
// channels, and the rule that turning a transmission off is not trapped by
// an illegal frequency.

package radio.kd0cq.x6100.digital

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FccPart97Test {

    @Test
    fun noClassBlocksAndTheOffSwitchDoesNotRemoveTheOtherGates() {
        val legal = 7_107_000L to 7_107_050L
        assertEquals(
            FccPart97.CHOOSE_CLASS,
            gate(BandChoice(check = true, license = null), legal),
        )
        assertNull(gate(BandChoice(check = false, license = null), 3_844_000L to 3_844_050L))
        assertEquals(
            FccPart97.TAKE_CONTROL,
            FccPart97.gate(
                enabling = true,
                choice = BandChoice(check = false, license = null),
                holdingControls = false,
                txAllowed = true,
                emission = Emission.PHONE,
                lowHz = 3_844_000L,
                highHz = 3_844_050L,
                missing = "missing",
            ),
        )
        assertEquals(
            FccPart97.NO_TX,
            FccPart97.gate(
                enabling = true,
                choice = BandChoice(check = false, license = LicenseClass.EXTRA),
                holdingControls = true,
                txAllowed = false,
                emission = Emission.DATA,
                lowHz = legal.first,
                highHz = legal.second,
                missing = "missing",
            ),
        )
    }

    @Test
    fun turningATransmissionOffIsNotTrappedByTheBandOrTheTransmitFlag() {
        assertNull(
            FccPart97.gate(
                enabling = false,
                choice = BandChoice(check = true, license = null),
                holdingControls = true,
                txAllowed = false,
                emission = Emission.DATA,
                lowHz = 3_844_000L,
                highHz = 3_844_050L,
                missing = "missing",
            ),
        )
        assertEquals(
            FccPart97.TAKE_CONTROL,
            FccPart97.gate(
                enabling = false,
                choice = BandChoice(check = true, license = LicenseClass.EXTRA),
                holdingControls = false,
                txAllowed = true,
                emission = Emission.DATA,
                lowHz = 7_107_000L,
                highHz = 7_107_050L,
                missing = "missing",
            ),
        )
        assertEquals(
            "missing",
            FccPart97.gate(
                enabling = true,
                choice = BandChoice(check = true, license = LicenseClass.EXTRA),
                holdingControls = true,
                txAllowed = true,
                emission = Emission.DATA,
                lowHz = null,
                highHz = null,
                missing = "missing",
            ),
        )
        assertEquals(
            FccPart97.UNKNOWN_MODE,
            FccPart97.gate(
                enabling = true,
                choice = BandChoice(check = true, license = LicenseClass.GENERAL),
                holdingControls = true,
                txAllowed = true,
                emission = Emission.PHONE,
                lowHz = 7_100_000L,
                highHz = 7_100_000L,
                missing = "missing",
                modeKnown = false,
            ),
        )
    }

    @Test
    fun technicianHasCwSlicesDataAndPhoneOnlyOnTenMetersAndAllOfVhf() {
        val tech = LicenseClass.TECHNICIAN
        assertTrue(covers(tech, Emission.CW, 7_050_000L))
        assertFalse(covers(tech, Emission.DATA, 7_050_000L))
        assertFalse(covers(tech, Emission.PHONE, 7_050_000L))
        assertTrue(covers(tech, Emission.CW, 3_550_000L))
        assertFalse(covers(tech, Emission.DATA, 3_550_000L))
        assertTrue(covers(tech, Emission.CW, 21_100_000L))
        assertFalse(covers(tech, Emission.CW, 14_074_000L))
        assertFalse(covers(tech, Emission.CW, 1_900_000L))
        assertFalse(covers(tech, Emission.DATA, 10_120_000L))
        assertTrue(covers(tech, Emission.DATA, 28_074_000L, 28_074_050L))
        assertFalse(covers(tech, Emission.DATA, 28_400_000L))
        assertFalse(covers(tech, Emission.PHONE, 28_100_000L))
        assertTrue(covers(tech, Emission.PHONE, 28_400_000L))
        assertFalse(covers(tech, Emission.PHONE, 28_600_000L))
        assertTrue(covers(tech, Emission.CW, 28_400_000L))
        assertTrue(covers(tech, Emission.PHONE, 146_520_000L))
        assertTrue(covers(tech, Emission.DATA, 50_100_000L, 50_100_050L))
        assertFalse(covers(tech, Emission.DATA, 5_357_000L))
    }

    @Test
    fun generalAdvancedAndExtraKeepTheirOwnPhoneAndDataEdges() {
        assertFalse(covers(LicenseClass.GENERAL, Emission.DATA, 7_010_000L))
        assertFalse(covers(LicenseClass.ADVANCED, Emission.DATA, 7_010_000L))
        assertTrue(covers(LicenseClass.EXTRA, Emission.DATA, 7_010_000L, 7_010_050L))
        assertTrue(covers(LicenseClass.ADVANCED, Emission.DATA, 7_025_000L, 7_025_050L))
        assertTrue(covers(LicenseClass.GENERAL, Emission.DATA, 7_107_000L, 7_107_050L))

        assertTrue(covers(LicenseClass.GENERAL, Emission.PHONE, 3_844_000L))
        assertFalse(covers(LicenseClass.GENERAL, Emission.DATA, 3_844_000L))
        assertFalse(covers(LicenseClass.ADVANCED, Emission.DATA, 3_500_000L))
        assertTrue(covers(LicenseClass.EXTRA, Emission.DATA, 3_500_000L, 3_500_050L))
        assertFalse(covers(LicenseClass.GENERAL, Emission.PHONE, 3_750_000L))
        assertTrue(covers(LicenseClass.ADVANCED, Emission.PHONE, 3_750_000L))
        assertFalse(covers(LicenseClass.ADVANCED, Emission.PHONE, 3_650_000L))
        assertTrue(covers(LicenseClass.EXTRA, Emission.PHONE, 3_650_000L))
        assertFalse(covers(LicenseClass.EXTRA, Emission.DATA, 3_650_000L))

        assertFalse(covers(LicenseClass.GENERAL, Emission.PHONE, 7_150_000L))
        assertTrue(covers(LicenseClass.GENERAL, Emission.PHONE, 7_180_000L))
        assertTrue(covers(LicenseClass.ADVANCED, Emission.PHONE, 7_150_000L))

        assertFalse(covers(LicenseClass.GENERAL, Emission.PHONE, 14_200_000L))
        assertTrue(covers(LicenseClass.GENERAL, Emission.PHONE, 14_230_000L))
        assertFalse(covers(LicenseClass.ADVANCED, Emission.PHONE, 14_160_000L))
        assertTrue(covers(LicenseClass.ADVANCED, Emission.PHONE, 14_180_000L))
        assertFalse(covers(LicenseClass.ADVANCED, Emission.DATA, 14_010_000L))
        assertTrue(covers(LicenseClass.EXTRA, Emission.DATA, 14_010_000L, 14_010_050L))
        assertTrue(covers(LicenseClass.EXTRA, Emission.PHONE, 14_160_000L))

        assertTrue(covers(LicenseClass.GENERAL, Emission.DATA, 10_120_000L, 10_120_050L))
        assertTrue(covers(LicenseClass.GENERAL, Emission.CW, 10_120_000L))
        assertFalse(covers(LicenseClass.GENERAL, Emission.PHONE, 10_120_000L))
        assertFalse(covers(LicenseClass.EXTRA, Emission.PHONE, 10_120_000L))
        assertFalse(covers(LicenseClass.TECHNICIAN, Emission.CW, 10_120_000L))
    }

    @Test
    fun aSignalMustSitInsideOneSegment() {
        assertFalse(covers(LicenseClass.GENERAL, Emission.CW, 3_599_000L, 3_601_000L))
        assertTrue(covers(LicenseClass.GENERAL, Emission.CW, 3_600_000L, 3_600_000L))
        assertFalse(covers(LicenseClass.GENERAL, Emission.PHONE, 3_600_000L))
        assertFalse(covers(LicenseClass.ADVANCED, Emission.DATA, 7_124_900L, 7_125_100L))
        assertTrue(covers(LicenseClass.ADVANCED, Emission.DATA, 7_124_950L, 7_125_000L))
        assertFalse(FccPart97.covers(LicenseClass.EXTRA, Emission.DATA, 100L, 50L))
    }

    @Test
    fun sixtyMetersIsFiveChannelsNotAContinuousSpan() {
        val center = 5_357_000L
        assertTrue(covers(LicenseClass.GENERAL, Emission.DATA, center, center + 50))
        assertTrue(covers(LicenseClass.EXTRA, Emission.PHONE, center))
        assertTrue(covers(LicenseClass.ADVANCED, Emission.CW, center - 1_400, center - 1_400))
        assertFalse(covers(LicenseClass.ADVANCED, Emission.DATA, center - 1_401, center - 1_401))
        // Inside the old 5.3515–5.3665 MHz approximation, and outside every channel.
        assertFalse(covers(LicenseClass.ADVANCED, Emission.DATA, 5_360_000L, 5_360_050L))
        assertFalse(covers(LicenseClass.GENERAL, Emission.DATA, 5_360_000L, 5_360_050L))
        assertFalse(covers(LicenseClass.EXTRA, Emission.DATA, 5_360_000L, 5_360_050L))
        assertFalse(UsAdvanced.dataCovers(5_360_000L, 5_360_050L))
        assertTrue(UsAdvanced.dataCovers(center, center + 50))
        assertEquals(5, FccPart97.SIXTY_METER_CENTERS_HZ.size)
    }

    @Test
    fun theModeKnobSelectsTheEmissionTheVoiceCheckUses() {
        assertEquals(Emission.CW, FccPart97.emissionOf(4))
        assertEquals(Emission.CW, FccPart97.emissionOf(5))
        assertEquals(Emission.DATA, FccPart97.emissionOf(1))
        assertEquals(Emission.DATA, FccPart97.emissionOf(3))
        assertEquals(Emission.PHONE, FccPart97.emissionOf(0))
        assertEquals(Emission.PHONE, FccPart97.emissionOf(2))
        assertEquals(Emission.PHONE, FccPart97.emissionOf(6))
        assertEquals(Emission.PHONE, FccPart97.emissionOf(7))
        assertNull(FccPart97.emissionOf(99))
        assertEquals(7.0, FccPart97.DIGITAL_TX_CAP_WATTS, 0.0)
        assertEquals(70, FccPart97.DIGITAL_CAP_TENTHS)
    }

    private fun covers(license: LicenseClass, emission: Emission, hz: Long, high: Long = hz): Boolean =
        FccPart97.covers(license, emission, hz, high)

    private fun gate(choice: BandChoice, span: Pair<Long, Long>): String? = FccPart97.gate(
        enabling = true,
        choice = choice,
        holdingControls = true,
        txAllowed = true,
        emission = Emission.DATA,
        lowHz = span.first,
        highHz = span.second,
        missing = "missing",
    )
}
