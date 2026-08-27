package com.mantra.stopwatch

/**
 * THE PALETTE, AND IT IMPORTS NOTHING.
 *
 * Not from android.*, not from compose. The colours are plain ARGB longs, so the grid can be
 * attacked by Test 1 the same way the timing model is: are they all legible on black, are there
 * no duplicates, is the count right for the grid it has to fill. Turning a long into a Compose
 * Color is one line and it happens at the point of use.
 *
 * SIX COLUMNS BY FOUR ROWS, in the manner of a swatch grid in an Adobe application: you look at
 * it, you press one, it is applied. There is no picker, no hue wheel, no hex field. A wheel
 * offers a million colours in order to find the six anybody wants.
 *
 * BOUND AT ONE END, DELIBERATELY. design-language.md 13 says a control should not offer settings
 * that defeat it, and the setting this could defeat is legibility: white digits on black is the
 * whole design, and a swatch dark enough to sink into the background would turn the app off.
 * Every colour here clears a contrast ratio of 4.5 against black, and Test 1 asserts it rather
 * than trusting the eye that chose them.
 *
 * The first row is the neutrals, ending in the two Mantra ambers that run through every other
 * app in the account. The rest are ordered warm to cool so the grid reads as a grid rather than
 * as a list of colours somebody happened to like.
 */
object Palette {

    const val COLUMNS = 6

    /** The default, and the one the app has always been: white on black. */
    const val DEFAULT: Long = 0xFFFFFFFF

    val SWATCHES: List<Long> = listOf(
        // neutrals, then the two ambers that are the account's accent everywhere
        0xFFFFFFFF, 0xFFCBD5E1, 0xFF94A3B8, 0xFFF2DDB4, 0xFFE8A64B, 0xFFFBBF24,
        // warm
        0xFFEF4444, 0xFFF97316, 0xFFFACC15, 0xFFFB7185, 0xFFF472B6, 0xFFE879F9,
        // green through cyan
        0xFFA3E635, 0xFF4ADE80, 0xFF34D399, 0xFF2DD4BF, 0xFF22D3EE, 0xFF38BDF8,
        // blue through violet
        0xFF60A5FA, 0xFF818CF8, 0xFFA78BFA, 0xFFC084FC, 0xFFD8B4FE, 0xFFE2E8F0,
    )

    /** A colour that is not in the grid cannot be chosen, so a corrupt stored value falls back. */
    fun sanitise(stored: Long): Long = if (stored in SWATCHES) stored else DEFAULT

    /**
     * Relative luminance, the WCAG definition. Used to decide two things: whether a swatch is
     * legible enough to be offered at all, and whether the tick that marks the chosen one should
     * be drawn in black or in white on top of it.
     */
    fun luminance(argb: Long): Double {
        fun channel(v: Long): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Contrast against pure black, which is the only background this app has. */
    fun contrastOnBlack(argb: Long): Double = (luminance(argb) + 0.05) / 0.05

    /**
     * The tick on the chosen swatch, drawn in whichever of black or white can be seen on it.
     *
     * The first version used a luminance threshold of 0.35, chosen by eye, and Test 1 caught it:
     * on the orange it picked white, and white on that orange has a contrast of 2.8 while black
     * has 7.5. So it computes both and takes the better one rather than guessing where the
     * crossover is. The crossover is at a luminance of about 0.179, which is not a number anybody
     * would have arrived at by looking.
     */
    fun markOn(argb: Long): Long {
        val l = luminance(argb)
        val onWhite = 1.05 / (l + 0.05)
        val onBlack = (l + 0.05) / 0.05
        return if (onBlack >= onWhite) 0xFF000000 else 0xFFFFFFFF
    }
}

/**
 * Normal or bold, and nothing between. A weight slider would be a dial with no purpose: the
 * question is only whether the strokes are heavy enough to read across a room, and the answer is
 * one of two.
 */
enum class Weight { NORMAL, BOLD }
