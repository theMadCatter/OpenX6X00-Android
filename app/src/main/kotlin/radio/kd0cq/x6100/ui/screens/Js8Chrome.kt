// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Sizes for the JS8 window on a phone, in dp. The composables read these
// numbers so a test can check the same budget the screen lays out with.
//
// Before this, the landscape control block was capped at 168 dp and the
// offset field was its own row under the TX buttons. On the S24 Ultra in
// landscape (1080 px on the short side) that cap cut the bottom of Set and
// the field on the first frame, before the operator scrolled. The portrait
// control block was capped at 220 dp and ended on the system navigation
// bar, so the North America net reminder lost its last line.
//
// The wide column now keeps one row for the offset and Set, and the scroll
// viewport is at least that stack when the column is tall enough. Portrait
// does not cap the controls. The reminder lays out at its own height, and
// the screen keeps a navigation-bar pad under it.

package radio.kd0cq.x6100.ui.screens

import kotlin.math.max

/**
 * The bottom inset under the JS8 portrait column.
 *
 * `navigationBarsPadding()` on this route was coming back as 0: the host
 * had already taken the Compose inset, and on the S24 the gesture bar is
 * still drawn over whatever ends at the bottom of that padded area. An
 * inset below the gesture floor is raised to that floor. A taller bar is kept.
 */
object ScreenBars {
    /** Gesture and 3-button bars on this phone. The pad is at least this tall. */
    const val GESTURE_FLOOR_DP = 48f

    fun bottomDp(reportedDp: Float): Float = max(reportedDp, GESTURE_FLOOR_DP)
}

object Js8Chrome {
    /** FHD+ short side, the width the S24 Ultra check was shot at. */
    const val S24_SHORT_PX = 1080

    /** 450 dpi. 1080 px / 2.8125 = 384 dp. */
    const val S24_DENSITY = 2.8125f

    const val STATUS_BAR_DP = 24
    const val NAV_BAR_DP = 48

    /** Back row plus one status line, the wide header after it was compacted. */
    const val HEADER_DP = 64

    /** DigitalSplit's own vertical padding in the wide window, top and bottom together. */
    const val VERTICAL_PADDING_DP = 8

    /** Take control, when it is on screen. The taller of the two first frames. */
    const val TAKE_CONTROL_DP = 40

    /**
     * Open / CQ / Show, one row. Show is two lines, so the row is a full
     * button rather than the 40 dp single-line key. The row is not forced
     * to this height: forcing it clipped the second line.
     */
    const val BUTTON_ROW_DP = 56

    /**
     * The offset field and Set, on the same row as the TX step keys when the
     * window is wide. Material's outlined field is 56 dp; 64 leaves the
     * outline inside the viewport.
     */
    const val OFFSET_ROW_DP = 64

    /** One decode line left under the controls when the column has room. */
    const val LIST_MIN_DP = 36

    /**
     * Portrait controls are not height-capped. 0 is that choice. A positive
     * number would bring back the block that cut the net reminder.
     */
    const val PORTRAIT_CONTROL_MAX_DP = 0

    const val PORTRAIT_HEADER_DP = 96
    const val PORTRAIT_PICTURE_DP = 100

    /** The North America sentence at 12 sp, wrapped on a 1080 px-wide phone. */
    const val REMINDER_DP = 72

    /** The line under it, about where GhostNet mode is turned on. */
    const val SETTINGS_NOTE_DP = 48

    fun s24ShortSideDp(): Float = S24_SHORT_PX / S24_DENSITY

    /** Take control, the command row, and the offset row. GhostNet and the message sit under that. */
    fun throughSetDp(takeControl: Boolean): Int =
        (if (takeControl) TAKE_CONTROL_DP else 0) + BUTTON_ROW_DP + OFFSET_ROW_DP

    /**
     * The right-hand column after the status bar, the navigation bar, the
     * header and the split's padding. [windowHeightDp] is the short side.
     */
    fun landscapeColumnDp(
        windowHeightDp: Float,
        statusBarDp: Float = STATUS_BAR_DP.toFloat(),
        navigationBarDp: Float = NAV_BAR_DP.toFloat(),
    ): Float = windowHeightDp - statusBarDp - navigationBarDp - HEADER_DP - VERTICAL_PADDING_DP

    fun s24LandscapeColumnDp(): Float = landscapeColumnDp(s24ShortSideDp())

    /**
     * Viewport for the control scroll. It stays at least [throughSetDp] when
     * the column can hold that, so Set is on the first frame. It never asks
     * for more than the column.
     */
    fun controlCapDp(columnDp: Float): Float {
        if (columnDp <= 0f) return 0f
        val through = throughSetDp(takeControl = true).toFloat()
        val withList = columnDp - LIST_MIN_DP
        return max(through, withList).coerceAtMost(columnDp).coerceAtLeast(0f)
    }

    fun setVisibleOnFirstFrame(columnDp: Float): Boolean =
        controlCapDp(columnDp) + 0.05f >= throughSetDp(takeControl = true)

    /** Room under the status bar and the gesture floor. */
    fun portraitContentDp(windowHeightDp: Float): Float =
        windowHeightDp - STATUS_BAR_DP - ScreenBars.GESTURE_FLOOR_DP

    /**
     * Top of the net reminder, then the reminder and the settings note.
     * Portrait lays this out in the column. The message field is below it.
     */
    fun portraitNoteBottomDp(): Float =
        PORTRAIT_HEADER_DP + PORTRAIT_PICTURE_DP +
            TAKE_CONTROL_DP + 48f + 40f + 56f +
            REMINDER_DP + SETTINGS_NOTE_DP

    fun portraitFooterClearsTheBar(windowHeightDp: Float): Boolean =
        PORTRAIT_CONTROL_MAX_DP == 0 && portraitContentDp(windowHeightDp) >= portraitNoteBottomDp()
}
