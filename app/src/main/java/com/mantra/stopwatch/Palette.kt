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

    // TWO GRID SHAPES FOR ONE LIST. Forty-eight divides by both, so the same swatches lay out as
    // eight rows of six standing up and four rows of twelve on their side. A grid that keeps one
    // column count in both orientations is either a stack of tiny cells in landscape or a slab
    // taller than the screen, and the second of those is what v5 shipped: on a landscape phone
    // the panel was taller than the display, covered everything, and left no way back out.
    const val COLUMNS_PORTRAIT = 6
    const val COLUMNS_LANDSCAPE = 12

    /** The default, and the one the app has always been: white on black. */
    const val DEFAULT: Long = 0xFFFFFFFF

    val SWATCHES: List<Long> = listOf(
        // neutrals, then the ambers that are the account's accent everywhere
        0xFFFFFFFF, 0xFFE2E8F0, 0xFFCBD5E1, 0xFF94A3B8, 0xFFD6D3D1, 0xFFA8A29E,
        0xFFF2DDB4, 0xFFE8A64B, 0xFFFBBF24, 0xFFFDE68A, 0xFFFCD34D, 0xFFFEF3C7,
        // warm
        0xFFEF4444, 0xFFF87171, 0xFFFCA5A5, 0xFFF97316, 0xFFFB923C, 0xFFFDBA74,
        0xFFFACC15, 0xFFFDE047, 0xFFEAB308, 0xFFFB7185, 0xFFFDA4AF, 0xFFF43F5E,
        // pink through violet
        0xFFF472B6, 0xFFF9A8D4, 0xFFE879F9, 0xFFD8B4FE, 0xFFC084FC, 0xFFA78BFA,
        0xFF818CF8, 0xFFA5B4FC, 0xFF60A5FA, 0xFF93C5FD, 0xFF38BDF8, 0xFF7DD3FC,
        // cyan through green
        0xFF22D3EE, 0xFF67E8F9, 0xFF2DD4BF, 0xFF5EEAD4, 0xFF34D399, 0xFF6EE7B7,
        0xFF4ADE80, 0xFF86EFAC, 0xFFA3E635, 0xFFBEF264, 0xFFD9F99D, 0xFFCCFBF1,
    )

    /** A colour that is not in the grid cannot be chosen, so a corrupt stored value falls back. */
    fun sanitise(stored: Long): Long = if (stored in SWATCHES) stored else DEFAULT

    /**
     * THE COLOUR THE DIGITS FLASH WHEN A COMMAND REGISTERS.
     *
     * A second is a long time to wait to find out whether anything heard you. The digits take
     * this colour for about a seventh of a second, which is long enough to see and too short to
     * read as a change of state.
     *
     * IT CANNOT SIMPLY BE WHITE. White is the default digit colour and the flash has to be a
     * DIFFERENCE — flashing white digits white is no flash at all, and that is exactly the case
     * a fixed colour would get wrong for most people, since most people never change it.
     *
     * So the rule is: flash white unless the digits are already near white, in which case flash
     * the Mantra amber. Both are far from every swatch in the grid, both clear the contrast
     * floor by a wide margin, and between them they cover the whole palette.
     */
    fun flashOf(current: Long): Long = BLACK

    /**
     * THE FLASH IS THE BACKGROUND, so the digits VANISH for a moment rather than brightening.
     *
     * v18 flashed white, or amber when the digits were already white. It worked and it was the
     * wrong direction: a screen that is deliberately black with one bright thing on it, in a dark
     * room, punishes you for looking at it every time a command lands. Baba's words were that it
     * burns his eyes, and on a screen designed to be readable across a room in the dark that is
     * not a small complaint.
     *
     * Taking the digits AWAY is the same event and costs nothing to look at. It is also better
     * information: a blink is unmistakable at the edge of vision, where a brightening of something
     * already bright is not.
     *
     * AND IT NEEDS NO RULE ABOUT WHICH COLOUR. The white flash had to check whether the digits
     * were already near white, because flashing white digits white is no flash at all. Black is
     * the one colour nothing in the palette can be — every swatch clears a contrast ratio of 4.5
     * against it, asserted in Test 1 — so the flash is always a difference by construction rather
     * than by a threshold somebody has to keep right.
     */
    const val BLACK: Long = 0xFF000000

    /** The accent that runs through every other app in the account. */
    const val ACCENT: Long = 0xFFE8A64B

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
