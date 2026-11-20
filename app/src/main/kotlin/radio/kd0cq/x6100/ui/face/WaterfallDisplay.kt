// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Waterfall colour and geometry, with no Android types so the unit tests can run it.
//
// The phone used to paint each FFT bin as one bitmap column and then stretch that bitmap
// to the view. On a landscape phone that is about two pixels per bin, so the waterfall
// was a field of hard blocks, and a new noise percentile every frame made the colours pump.
//
// This follows the firmware (wf_palettes.c, waterfall_view.c, theme.c):
// - Classic and Gauss are the 256-entry tables. Grey, Fire, Ice and Rainbow are the same
//   stops theme.c ramps with theme_ramp_palette, steps = 0 (smooth).
// - A pixel samples the bins on either side of its frequency, the way row_pixels does.
// - The colour window eases toward the newest row instead of jumping to it.
//
// The Panel socket cannot ask for a bigger FFT or a faster spectrum. remote_server.c
// always sends WATERFALL_NFFT (1024) int8 bins, one frame each time the DSP has a new
// one, and it skips a frame while 16 KB is still queued. There is no rate or size command.

package radio.kd0cq.x6100.ui.face

import kotlin.math.roundToInt

/** The waterfall palettes the radio offers on its Display page, in the firmware's order. */
enum class WfColormap(val label: String) {
    /** wf_palette_legacy. The radio calls this Classic. */
    CLASSIC("Classic"),

    /** wf_palette_gauss. RF.Guru's theme uses this table, so it is the default. */
    GAUSS("Gauss"),

    /** Two stops, black to white. */
    GREY("Grey"),

    /** Black, red, orange, yellow, white. */
    FIRE("Fire"),

    /** Black through blue to white. */
    ICE("Ice"),

    /** Black, blue, cyan, green, yellow, red. */
    RAINBOW("Rainbow"),
    ;

    fun next(): WfColormap {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

/** What the Screen tab asks for. Maps onto `picture sharp|balanced|fast`, which do exist. */
enum class ScreenPicture { SHARP, BALANCED, FAST }

/**
 * The colour scale, remembered across frames.
 *
 * A raw percentile from one FFT jumps by a few dB every frame, and the whole waterfall
 * then shimmers. [follow] is the fraction of that jump taken this frame, so the floor and
 * the ceiling glide. The first frame is taken whole, or the display would fade in from black.
 */
class WaterfallLeveler(private val follow: Float = 0.18f) {
    var floor: Int = -120
        private set
    var ceiling: Int = -120 + RadioFace.WATERFALL_MIN_SPAN_DB
        private set

    private var primed = false

    fun reset() {
        primed = false
    }

    /** [newest] is the row that just arrived. Older rows stay on the scale they were painted with. */
    fun update(newest: ByteArray): Pair<Int, Int> {
        val (rawFloor, rawCeiling) = RadioFace.waterfallWindow(listOf(newest))
        if (!primed) {
            floor = rawFloor
            ceiling = rawCeiling
            primed = true
            return floor to ceiling
        }
        floor = ease(floor, rawFloor)
        ceiling = ease(ceiling, rawCeiling)
        if (ceiling < floor + RadioFace.WATERFALL_MIN_SPAN_DB) {
            ceiling = floor + RadioFace.WATERFALL_MIN_SPAN_DB
        }
        return floor to ceiling
    }

    private fun ease(current: Int, target: Int): Int {
        if (current == target) return current
        val delta = target - current
        val step = (delta * follow).roundToInt()
        val moved = if (step == 0) delta / kotlin.math.abs(delta) else step
        return current + moved
    }
}

object WaterfallDisplay {

    private const val GAUSS_HEX =
        "00002100002400002800012b00012d00013000013400013600023a00023c00024000024200034400034800034a00044f" +
        "00045100055300055700065900065b00075e000862000864000966000968000a6c000b6e000c6f000d71000d73000f76" +
        "00107800117900127b00137c00147d001680001881001982001a83001c84001d85001f85002286002387002587002787" +
        "002987002a87012c87012e87013087013287013486013985013b84013d8402408302428202448102478002497f034c7e" +
        "034e7c03517b035479045678045976045c75055e7305617105647006676e076c6a076f68087266087464097762097a60" +
        "0a7d5e0b7f5c0b825a0c84580d87560e8a540f8c52108e5011914e12934c13954a149748159a47169c45179e43199f41" +
        "1aa13f1ba33d1da53c1da53c1ea63a20a83822a93723aa3525ab3327ac3229ad302bae2f2daf2e2faf2c31b02b33b02a" +
        "35b12938b1283ab1273db1263fb12542b12444b02347b0224aaf214daf214fae204fae2052ad1f55ad1f58ac1e5bab1e" +
        "5fa91d62a81d65a71d68a61d6ca41c6fa31c72a11c76a01c799e1c7d9c1c7d9c1c809b1c84991c87971d8a951d8e941d" +
        "91921d95901e988e1e9c8c1e9f8a1fa3891fa3891fa68720a98521ad8321b08122b38022b77e23ba7c24bd7b25c07926" +
        "c37726c37726c67627c87428cb7329ce722ad0702bd36f2cd56e2ed86d2fda6c30da6c30dc6b31de6a32e06934e26935" +
        "e46836e56738e76739e76739e9663bea663ceb663eec663fed6641ee6643ef6644f06646f06646f16648f1674af2674b" +
        "f2684df3684ff36951f36a53f36a53f36b55f36c57f36d5af36e5cf36f5ef37060f37262f37262f27365f27567f2766a" +
        "f1786cf17a6ff07c71f07c71f07e74ef8077ef8279ee847cee877fed8982ed8982ed8c85ed8e88ec918bec938eec9691" +
        "eb9994eb9994eb9c98eb9f9beba29eeba5a2eba9a5eba9a5ebaca9ebafadebb3b0ebb6b4ebbab8ecbebcecbebcecc1c0" +
        "edc5c4eec9c8efcdccefd1d0efd1d0f0d5d5f2dad9f3deddf4e2e2f6e7e6f6e7e6f7ebebf9f0f0fbf5f5fcfafaffffff"

    private const val CLASSIC_HEX =
        "00000000000400000800000c00000e00001200001600001800001c00001e00002200002400002600002a00002c000030" +
        "00003200003400003800003a00003c00003e00004100004400004600004800004c00004e000050000051000054000058" +
        "00005900005c00005e00006000006100006600006800006900006c00006e00007000007100007600007800007900007c" +
        "00007e00007f03007d06007b0900790b00770e007514007117006f19006d1c006b1f00692200672500652700632a0061" +
        "2d005f30005d33005b3500593800573b00553e005341005143004f46004d4c00494f00475100455400435700415a003f" +
        "5d003c5f003b6200386500376800346b00336d003070002f73002c76002b7900287b00277e002481002384002087001f" +
        "89001c8c001b8f00188f00189200179500149700139a00109d000fa0000ca3000ba50008a80007ab0004ae0003b10000" +
        "b20100b30500b40800b50c00b61000b61300b71700b81a00b91e00ba2200ba2500ba2500bb2900bc2c00bd3000be3400" +
        "be3700bf3b00c03e00c14200c24600c24900c34d00c45000c55400c65800c65800c65b00c75f00c86200c96600ca6a00" +
        "ca6d00cb7100cc7400cd7800ce7c00ce7f00ce7f00cf8300d08600d18a00d28e00d29100d39500d49800d59c00d6a000" +
        "d6a300d6a300d7a700d8aa00d9ae00dab200dab500dbb900dcbc00ddc000dec400dec400dec700dfcb00e0ce00e1d200" +
        "e2d600e2d900e3dd00e3dd00e4e000e5e400e5e503e6e607e6e60be6e60fe7e712e7e717e7e717e8e81be8e81fe8e823" +
        "e9e927e9e92beaea2feaea33eaea33eaea37ebeb3bebeb3fecec43ecec47ecec4beded4feded4feded52eeee57eeee5b" +
        "eeee5fefef63efef67efef67f0f06bf0f06ff0f073f1f177f1f17bf2f27ff2f27ff2f283f2f287f3f38bf3f38ff4f492" +
        "f4f497f4f497f4f49bf5f59ff5f5a3f6f6a7f6f6abf6f6abf6f6aff7f7b3f7f7b7f8f8bbf8f8bff8f8c3f8f8c3f9f9c7" +
        "f9f9cbfafacffafad2fafad7fafad7fbfbdbfbfbdffcfce3fcfce7fcfcebfcfcebfdfdeffdfdf3fefef7fefefbffffff"

    /** Firmware MAX_HEIGHT. A phone waterfall is shorter than the radio's 480, and we keep that many rows. */
    const val MAX_ROWS = 480

    private val gauss: IntArray by lazy { decode(GAUSS_HEX) }
    private val classic: IntArray by lazy { decode(CLASSIC_HEX) }
    private val grey: IntArray by lazy { ramp(intArrayOf(0x000000, 0xFFFFFF)) }
    private val fire: IntArray by lazy {
        ramp(intArrayOf(0x000000, 0x400000, 0xA00000, 0xFF4000, 0xFFA000, 0xFFFF60, 0xFFFFFF))
    }
    private val ice: IntArray by lazy {
        ramp(intArrayOf(0x000000, 0x001040, 0x0040A0, 0x0090FF, 0x40E0FF, 0xFFFFFF))
    }
    private val rainbow: IntArray by lazy {
        ramp(intArrayOf(0x000000, 0x0000A0, 0x0080FF, 0x00E0A0, 0x80FF00, 0xFFE000, 0xFF4000, 0xFF0000))
    }

    fun palette(which: WfColormap): IntArray = when (which) {
        WfColormap.CLASSIC -> classic
        WfColormap.GAUSS -> gauss
        WfColormap.GREY -> grey
        WfColormap.FIRE -> fire
        WfColormap.ICE -> ice
        WfColormap.RAINBOW -> rainbow
    }

    /** 0xRRGGBB pairs from wf_palettes.c, with the alpha byte set so a bitmap can store them. */
    fun decode(hex: String): IntArray {
        val out = IntArray(hex.length / 6)
        var i = 0
        var p = 0
        while (p + 6 <= hex.length) {
            out[i++] = (0xFF000000.toInt()) or hex.substring(p, p + 6).toInt(16)
            p += 6
        }
        return out
    }

    /**
     * theme_ramp_palette with steps 0: index i is placed linearly through the stops.
     * The integer arithmetic matches the firmware, including the last index landing on the last stop.
     */
    fun ramp(stops: IntArray, steps: Int = 0): IntArray {
        val n = stops.size
        require(n >= 2)
        val out = IntArray(256)
        for (i in 0 until 256) {
            val lvl = if (steps > 1) (i * steps / 256) * 255 / (steps - 1) else i
            var pos = lvl * (n - 1)
            var seg = pos / 255
            var t = pos % 255
            if (seg >= n - 1) {
                seg = n - 2
                t = 255
            }
            var rgb = 0
            for (sh in intArrayOf(0, 8, 16)) {
                val a = (stops[seg] shr sh) and 0xFF
                val b = (stops[seg + 1] shr sh) and 0xFF
                rgb = rgb or (((a + (b - a) * t / 255) and 0xFF) shl sh)
            }
            out[i] = 0xFF000000.toInt() or rgb
        }
        return out
    }

    /**
     * Where [hz] sits in a row, in bin units. 0 is the left edge of the span (bin 0) and [bins]
     * is the right edge, one bin past the last sample. That is dsp.h: bin i is at
     * centre − width/2 + i * width / n.
     */
    fun binAtHz(hz: Float, centreHz: Int, widthHz: Int, bins: Int): Float {
        if (widthHz <= 0 || bins <= 0) return 0f
        val left = centreHz - widthHz / 2f
        return (hz - left) / widthHz * bins
    }

    /** The frequency at the centre of pixel [pixel] of a span drawn across [pixels]. */
    fun hzAtPixel(pixel: Int, pixels: Int, centreHz: Int, widthHz: Int): Float {
        if (pixels <= 0 || widthHz <= 0) return centreHz.toFloat()
        val left = centreHz - widthHz / 2f
        return left + (pixel + 0.5f) * widthHz / pixels
    }

    /**
     * Linear sample. Index 0 is bin 0 and the last bin is held at the right edge.
     * A negative index or one past the last bin is outside this row (the radio paints its noise there).
     */
    fun sampleBin(db: ByteArray, index: Float): Float? {
        if (db.isEmpty()) return null
        if (index < 0f || index > db.size.toFloat()) return null
        if (db.size == 1) return db[0].toFloat()
        val clamped = index.coerceAtMost((db.size - 1).toFloat())
        val i = clamped.toInt().coerceIn(0, db.size - 2)
        val frac = clamped - i
        val a = db[i].toInt()
        val b = db[i + 1].toInt()
        return a + (b - a) * frac
    }

    /** Firmware waterfall_data: (db − min) * 255 / (max − min), truncated, held inside 0..255. */
    fun paletteIndex(db: Float, floor: Int, ceiling: Int): Int {
        val span = (ceiling - floor).coerceAtLeast(1)
        val scaled = (db - floor) / span * 255f
        if (scaled <= 0f) return 0
        if (scaled >= 255f) return 255
        return scaled.toInt()
    }

    fun colorAt(
        db: ByteArray,
        rowCentreHz: Int,
        rowWidthHz: Int,
        viewCentreHz: Int,
        viewWidthHz: Int,
        pixel: Int,
        pixels: Int,
        floor: Int,
        ceiling: Int,
        colors: IntArray,
    ): Int {
        val hz = hzAtPixel(pixel, pixels, viewCentreHz, viewWidthHz)
        val index = binAtHz(hz, rowCentreHz, rowWidthHz, db.size)
        val sample = sampleBin(db, index)
        val level = if (sample == null) 0 else paletteIndex(sample, floor, ceiling)
        return colors[level.coerceIn(0, colors.lastIndex)]
    }

    /**
     * Sharp is what we ask for. The radio repeats an unchanged screen about once a second
     * (STILL_MS), and that gap is not a slow link. A run of gaps that are neither a still
     * repeat nor a moving frame (the encoder skips a frame while the last JPEG is still queued)
     * means the sharp picture is not keeping up, and Fast is the mode that exists for that.
     * Balanced is left as the operator set it.
     */
    fun nextScreenPicture(current: ScreenPicture, gapsMs: List<Long>): ScreenPicture {
        if (current != ScreenPicture.SHARP || gapsMs.size < 4) return current
        val recent = gapsMs.takeLast(4)
        val still = recent.all { it in 850L..1250L }
        if (still) return current
        val lagging = recent.count { it in 450L..900L || it > 1400L }
        return if (lagging >= 3) ScreenPicture.FAST else current
    }
}
