/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.animation.style

/**
 * An immutable sRGB color with an alpha channel, in the 0..255 range on every channel.
 *
 * A layout stores colors as hex strings, and each renderer used to parse them into whatever its own
 * toolkit needed. This is the parsed form, so the parsing and the palette-assignment rules live in one
 * place and a renderer converts only at its own boundary — `java.awt.Color` for the desktop canvas, a
 * CSS color for a browser canvas.
 *
 * NOTE: compiled for both the JVM and Kotlin/JS. Keep it free of JVM-only APIs.
 *
 * @param r the red channel, 0..255
 * @param g the green channel, 0..255
 * @param b the blue channel, 0..255
 * @param a the alpha channel, 0..255 (255 = opaque)
 */
data class RgbaColor(val r: Int, val g: Int, val b: Int, val a: Int = 255) {

    /** This color as a CSS `rgba(...)` function, which every browser drawing surface accepts. */
    fun toCssRgba(): String {
        val alpha = a / 255.0
        // Trim to three decimals without relying on platform number formatting.
        val rounded = (alpha * 1000.0).toInt() / 1000.0
        return "rgba($r,$g,$b,$rounded)"
    }

    /** This color as `#rrggbb`, dropping alpha. */
    fun toHex(): String = "#" + hex2(r) + hex2(g) + hex2(b)

    /** A copy with the alpha channel replaced. */
    fun withAlpha(alpha: Int): RgbaColor = copy(a = alpha.coerceIn(0, 255))

    /** This color scaled toward transparency by [factor] (1.0 keeps it, 0.0 makes it invisible). */
    fun fade(factor: Double): RgbaColor = withAlpha((a * factor).toInt())

    private fun hex2(v: Int): String {
        val s = v.coerceIn(0, 255).toString(16)
        return if (s.length == 1) "0$s" else s
    }

    companion object {

        val BLACK = RgbaColor(0, 0, 0)
        val WHITE = RgbaColor(255, 255, 255)
        val GRAY = RgbaColor(128, 128, 128)
        val DARK_GRAY = RgbaColor(64, 64, 64)
        val RED = RgbaColor(255, 0, 0)

        /**
         * The default categorical palette (matplotlib's "tab10" order), used to give an entity or agent
         * type a stable color when the layout does not declare one.
         */
        val TAB10: List<RgbaColor> = listOf(
            RgbaColor(0x1f, 0x77, 0xb4), RgbaColor(0xff, 0x7f, 0x0e),
            RgbaColor(0x2c, 0xa0, 0x2c), RgbaColor(0xd6, 0x27, 0x28),
            RgbaColor(0x94, 0x67, 0xbd), RgbaColor(0x8c, 0x56, 0x4b),
            RgbaColor(0xe3, 0x77, 0xc2), RgbaColor(0x17, 0xbe, 0xcf),
        )

        /**
         * Parses `#rrggbb` or `#aarrggbb` (the leading `#` optional), falling back to [GRAY] on anything
         * malformed. Failing soft is deliberate: a typo in a hand-authored layout should show up as a gray
         * glyph, not stop an animation from rendering.
         */
        fun parse(hex: String): RgbaColor {
            val s = hex.removePrefix("#")
            return try {
                when (s.length) {
                    6 -> RgbaColor(
                        s.substring(0, 2).toInt(16),
                        s.substring(2, 4).toInt(16),
                        s.substring(4, 6).toInt(16)
                    )
                    8 -> RgbaColor(
                        s.substring(2, 4).toInt(16),
                        s.substring(4, 6).toInt(16),
                        s.substring(6, 8).toInt(16),
                        s.substring(0, 2).toInt(16)
                    )
                    else -> GRAY
                }
            } catch (e: NumberFormatException) {
                GRAY
            }
        }
    }
}
