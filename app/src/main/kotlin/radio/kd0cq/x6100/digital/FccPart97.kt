// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Before this, one table decided every digital transmission: US Advanced,
// data only, and it was not a setting. Voice PTT, Tune and a moved TX offset
// never asked it. A public release has to follow the class the operator
// actually holds, and it has to refuse to key until they say which class
// that is.
//
// This file is the FCC table the app uses. It is 47 CFR 97.301 (where each
// class may transmit, ITU Region 2) intersected with 97.305 (which emission
// is authorized there). Shared VHF and UHF are paragraph (a), Extra HF is
// (b), Advanced HF is (c), General HF is (d), and Technician and Novice HF
// are (e). It does not open a socket and it does not key the
// radio. The screen reads the operator's choice and calls [FccPart97.gate]
// before any line that would put a carrier on the air.
//
// The edges are the legal allocation, not the voluntary band plan. A 6 m
// contact on 50.100 MHz is legal for every class even when the band plan
// would rather see CW there. Regional limits that are not in the national
// allocation (parts of 420–430 MHz, for example) stay the operator's to know.
// 219–220 MHz is a point-to-point data allocation this radio's face does not
// offer, so it is not a segment here.

package radio.kd0cq.x6100.digital

import radio.kd0cq.x6100.net.PanelMode

/**
 * A US license class the operator can pick in Setup.
 *
 * Nothing is selected until they pick one. The wire value is what Setup
 * stores. An empty or unknown value is not a class.
 */
enum class LicenseClass(val wire: String, val label: String) {
    TECHNICIAN("technician", "Technician"),
    GENERAL("general", "General"),
    ADVANCED("advanced", "Advanced"),
    EXTRA("extra", "Extra"),
    ;

    companion object {
        fun fromWire(wire: String?): LicenseClass? =
            entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The emission a transmission is, for 97.305.
 *
 * CW is authorized on every frequency the class may use. Data is only the
 * RTTY and data sub-bands. Phone is only the phone sub-bands. A voice PTT
 * in DIGL or DIGU is data, because that is the emission those modes carry.
 * FT8, FT4, FT2 and JS8 are data whatever the mode knob says.
 */
enum class Emission(val word: String) {
    CW("CW"),
    DATA("data"),
    PHONE("phone"),
}

/**
 * What Setup has stored.
 *
 * [check] defaults on. [license] defaults to no class. Together those two
 * defaults block every transmission until the operator picks a class, or
 * turns the check off and accepts the warning that goes with that.
 */
data class BandChoice(val check: Boolean = true, val license: LicenseClass? = null)

/**
 * The US band edges this app will enforce, and the decision to key or not.
 */
object FccPart97 {

    /**
     * The firmware's digital-mode cap, `TX_PLAYER_MAX_PWR_W` in `tx_player.h`.
     *
     * JS8 (`js8_window.c`) and FT8 (`ft8_window.c`) drop the radio to this
     * while they transmit, then put the previous power back. The panel has
     * no separate command for the cap. Voice and Tune are not capped by it.
     * 7.0 W is 70 tenths, which is the unit `set pwr` uses.
     */
    const val DIGITAL_TX_CAP_WATTS = 7.0

    const val DIGITAL_CAP_TENTHS = 70

    const val DIGITAL_CAP_NOTE =
        "FT8 and JS8 transmit at 7 W. The radio applies that cap when those modes transmit, then restores the power. Voice and Tune use the power you set, up to the radio's own maximum."

    const val TAKE_CONTROL = "Take control before transmitting."

    const val NO_TX = "This session is not allowed to transmit."

    const val CHOOSE_CLASS = "Choose a license class in Setup before transmitting."

    const val UNKNOWN_MODE = "This mode is not in the US band table."

    /**
     * Shown in Setup when the operator turns the table off.
     * The other gates stay: Take control, operate, the transmit flag, the
     * question before keying, and the unkey.
     */
    const val CHECK_OFF_WARNING =
        "Band-edge checking is off. This app will not keep a transmission inside a US band. " +
            "You are responsible for the rules where you are. Take control, the operate role and " +
            "the transmit flag are still required, and the radio still unkeys when you let go, " +
            "the link drops, the app goes to the background, or a command fails."

    /** 30 m is 200 W PEP. The X6100 is below that. The app does not pretend to enforce it. */
    const val THIRTY_METER_PEP_WATTS = 200

    /**
     * 60 m is 100 W ERP, which depends on the antenna. `set pwr` is not ERP.
     * The app does not pretend a power number is an ERP.
     */
    const val SIXTY_METER_ERP_WATTS = 100

    /**
     * Channel centers, Hz. 47 CFR 97.303(h): 5330.5, 5346.5, 5357.0, 5371.5
     * and 5403.5 kHz. The occupied bandwidth has to stay inside 2.8 kHz
     * centered on the channel, so each window is the center ± 1.4 kHz.
     * A continuous span from 5.3515 to 5.3665 MHz is not the allocation.
     */
    val SIXTY_METER_CENTERS_HZ: LongArray = longArrayOf(
        5_330_500L,
        5_346_500L,
        5_357_000L,
        5_371_500L,
        5_403_500L,
    )

    private const val SIXTY_HALF_HZ = 1_400L

    /**
     * 6 m, 2 m, 1.25 m and 70 cm. 97.301(a) gives these to every class, and
     * 97.305 authorizes CW, data and phone across each of them.
     */
    private val VHF: List<LongRange> = listOf(
        50_000_000L..54_000_000L,
        144_000_000L..148_000_000L,
        222_000_000L..225_000_000L,
        420_000_000L..450_000_000L,
    )

    /**
     * The mode knob, as an emission.
     *
     * CW and CW-R are CW. DIGL and DIGU are data. LSB, USB, AM, NFM, SAM and
     * WFM are phone. An index this build does not number is null, and a
     * transmission in that mode is refused while the check is on.
     */
    fun emissionOf(mode: Int): Emission? = when (PanelMode.fromIndex(mode)) {
        PanelMode.Cw, PanelMode.Cwr -> Emission.CW
        PanelMode.DigL, PanelMode.DigU -> Emission.DATA
        PanelMode.Lsb, PanelMode.Usb, PanelMode.Am, PanelMode.Fm, PanelMode.Sam, PanelMode.Wfm -> Emission.PHONE
        null -> null
    }

    /**
     * One class, one emission, ITU Region 2.
     *
     * Both edges of a signal have to sit in one of these ranges. The ranges
     * are inclusive: a carrier exactly on 7.125 MHz is inside a segment that
     * ends there, and a tone that continues past it is not.
     */
    fun segments(license: LicenseClass, emission: Emission): List<LongRange> {
        val hf = when (license) {
            LicenseClass.TECHNICIAN -> technician(emission)
            LicenseClass.GENERAL -> general(emission)
            LicenseClass.ADVANCED -> advanced(emission)
            LicenseClass.EXTRA -> extra(emission)
        }
        val sixty = if (license == LicenseClass.TECHNICIAN) emptyList() else sixtyMeter()
        return hf + sixty + VHF
    }

    /** True when [lowHz] and [highHz] are inside the same segment, both edges included. */
    fun covers(license: LicenseClass, emission: Emission, lowHz: Long, highHz: Long): Boolean {
        if (highHz < lowHz) return false
        return segments(license, emission).any { band -> lowHz in band && highHz in band }
    }

    /**
     * Why this transmission must not be sent, or null when it may.
     *
     * Before this, a digital word was checked against Advanced data only, and
     * turning a function off was trapped when the offset was already illegal.
     * [enabling] is false for the command that turns a transmission off. That
     * command still needs Take control. It does not need the transmit flag or
     * a legal frequency: an illegal offset, or a session whose transmit flag
     * was just cleared, must not be what leaves the radio on the air.
     *
     * A press that would key ([enabling] true) needs Take control, the
     * transmit flag, and then the table. The table is skipped when the
     * operator has turned it off. It refuses when no class is stored.
     * A missing edge is not zero.
     */
    fun gate(
        enabling: Boolean,
        choice: BandChoice,
        holdingControls: Boolean,
        txAllowed: Boolean,
        emission: Emission,
        lowHz: Long?,
        highHz: Long?,
        missing: String,
        modeKnown: Boolean = true,
    ): String? {
        if (!holdingControls) return TAKE_CONTROL
        if (!enabling) return null
        if (!txAllowed) return NO_TX
        if (!choice.check) return null
        if (choice.license == null) return CHOOSE_CLASS
        if (!modeKnown) return UNKNOWN_MODE
        if (lowHz == null || highHz == null || highHz < lowHz) return missing
        if (!covers(choice.license, emission, lowHz, highHz)) {
            return "That signal ($lowHz–$highHz Hz) is outside the US ${choice.license.label} ${emission.word} segments."
        }
        return null
    }

    private fun sixtyMeter(): List<LongRange> =
        SIXTY_METER_CENTERS_HZ.map { center -> (center - SIXTY_HALF_HZ)..(center + SIXTY_HALF_HZ) }

    private fun technician(emission: Emission): List<LongRange> = when (emission) {
        // 97.301(e): the old Novice slices, plus 28.000–28.500. No 160, 60, 30, 20, 17 or 12.
        Emission.CW -> listOf(
            3_525_000L..3_600_000L,
            7_025_000L..7_125_000L,
            21_025_000L..21_200_000L,
            28_000_000L..28_500_000L,
        )
        // Data on 10 m only, 28.000–28.300. The 80, 40 and 15 m slices are CW.
        Emission.DATA -> listOf(28_000_000L..28_300_000L)
        // Phone on 10 m only, 28.300–28.500.
        Emission.PHONE -> listOf(28_300_000L..28_500_000L)
    }

    private fun general(emission: Emission): List<LongRange> = when (emission) {
        // Every General allocation. CW is authorized on the phone segments too.
        // The gaps are frequencies General does not have: 3.600–3.800, 7.125–7.175,
        // 14.150–14.225, 21.200–21.275.
        Emission.CW -> listOf(
            1_800_000L..2_000_000L,
            3_525_000L..3_600_000L,
            3_800_000L..4_000_000L,
            7_025_000L..7_125_000L,
            7_175_000L..7_300_000L,
            10_100_000L..10_150_000L,
            14_025_000L..14_150_000L,
            14_225_000L..14_350_000L,
            18_068_000L..18_168_000L,
            21_025_000L..21_200_000L,
            21_275_000L..21_450_000L,
            24_890_000L..24_990_000L,
            28_000_000L..29_700_000L,
        )
        Emission.DATA -> listOf(
            1_800_000L..2_000_000L,
            3_525_000L..3_600_000L,
            7_025_000L..7_125_000L,
            10_100_000L..10_150_000L,
            14_025_000L..14_150_000L,
            18_068_000L..18_110_000L,
            21_025_000L..21_200_000L,
            24_890_000L..24_930_000L,
            28_000_000L..28_300_000L,
        )
        // No phone on 30 m.
        Emission.PHONE -> listOf(
            1_800_000L..2_000_000L,
            3_800_000L..4_000_000L,
            7_175_000L..7_300_000L,
            14_225_000L..14_350_000L,
            18_110_000L..18_168_000L,
            21_275_000L..21_450_000L,
            24_930_000L..24_990_000L,
            28_300_000L..29_700_000L,
        )
    }

    private fun advanced(emission: Emission): List<LongRange> = when (emission) {
        // Extra-only gaps: 3.500–3.525, 3.600–3.700, 7.000–7.025, 14.000–14.025,
        // 14.150–14.175, 21.000–21.025, 21.200–21.225.
        Emission.CW -> listOf(
            1_800_000L..2_000_000L,
            3_525_000L..3_600_000L,
            3_700_000L..4_000_000L,
            7_025_000L..7_300_000L,
            10_100_000L..10_150_000L,
            14_025_000L..14_150_000L,
            14_175_000L..14_350_000L,
            18_068_000L..18_168_000L,
            21_025_000L..21_200_000L,
            21_225_000L..21_450_000L,
            24_890_000L..24_990_000L,
            28_000_000L..29_700_000L,
        )
        Emission.DATA -> listOf(
            1_800_000L..2_000_000L,
            3_525_000L..3_600_000L,
            7_025_000L..7_125_000L,
            10_100_000L..10_150_000L,
            14_025_000L..14_150_000L,
            18_068_000L..18_110_000L,
            21_025_000L..21_200_000L,
            24_890_000L..24_930_000L,
            28_000_000L..28_300_000L,
        )
        Emission.PHONE -> listOf(
            1_800_000L..2_000_000L,
            3_700_000L..4_000_000L,
            7_125_000L..7_300_000L,
            14_175_000L..14_350_000L,
            18_110_000L..18_168_000L,
            21_225_000L..21_450_000L,
            24_930_000L..24_990_000L,
            28_300_000L..29_700_000L,
        )
    }

    private fun extra(emission: Emission): List<LongRange> = when (emission) {
        Emission.CW -> listOf(
            1_800_000L..2_000_000L,
            3_500_000L..4_000_000L,
            7_000_000L..7_300_000L,
            10_100_000L..10_150_000L,
            14_000_000L..14_350_000L,
            18_068_000L..18_168_000L,
            21_000_000L..21_450_000L,
            24_890_000L..24_990_000L,
            28_000_000L..29_700_000L,
        )
        // Data ends where the phone sub-band starts. 3.600 and 14.150 are the Extra edges.
        Emission.DATA -> listOf(
            1_800_000L..2_000_000L,
            3_500_000L..3_600_000L,
            7_000_000L..7_125_000L,
            10_100_000L..10_150_000L,
            14_000_000L..14_150_000L,
            18_068_000L..18_110_000L,
            21_000_000L..21_200_000L,
            24_890_000L..24_930_000L,
            28_000_000L..28_300_000L,
        )
        Emission.PHONE -> listOf(
            1_800_000L..2_000_000L,
            3_600_000L..4_000_000L,
            7_125_000L..7_300_000L,
            14_150_000L..14_350_000L,
            18_110_000L..18_168_000L,
            21_200_000L..21_450_000L,
            24_930_000L..24_990_000L,
            28_300_000L..29_700_000L,
        )
    }
}
