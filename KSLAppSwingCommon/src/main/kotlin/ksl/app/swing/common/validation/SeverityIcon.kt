/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.swing.common.validation

import ksl.app.validation.ValidationSeverity
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * Java2D icon used by every validation surface: a filled red triangle
 * with `!` for [ValidationSeverity.ERROR], a hollow amber circle with
 * `!` for [ValidationSeverity.WARNING].  Color is paired with shape so
 * colorblind users never depend on color alone (scenario workflow §4).
 *
 * Icons are size-parameterised and stateless; share one instance per
 * (severity, size) pair when laying out tables.
 *
 * @param severity which icon to paint.
 * @param size pixel width and height.
 */
class SeverityIcon(
    val severity: ValidationSeverity,
    private val size: Int = DEFAULT_SIZE
) : Icon {

    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = (g.create() as Graphics2D)
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            when (severity) {
                ValidationSeverity.ERROR -> paintError(g2, x, y)
                ValidationSeverity.WARNING -> paintWarning(g2, x, y)
            }
            paintBang(g2, x, y)
        } finally {
            g2.dispose()
        }
    }

    private fun paintError(g2: Graphics2D, x: Int, y: Int) {
        val pad = 1
        val tri = Polygon(
            intArrayOf(x + size / 2, x + size - pad, x + pad),
            intArrayOf(y + pad, y + size - pad, y + size - pad),
            3
        )
        g2.color = ERROR_COLOR
        g2.fill(tri)
    }

    private fun paintWarning(g2: Graphics2D, x: Int, y: Int) {
        val pad = 1
        g2.color = WARNING_COLOR
        g2.stroke = BasicStroke(maxOf(1.5f, size / 10f))
        g2.drawOval(x + pad, y + pad, size - 2 * pad, size - 2 * pad)
    }

    private fun paintBang(g2: Graphics2D, x: Int, y: Int) {
        g2.color = when (severity) {
            ValidationSeverity.ERROR -> Color.WHITE
            ValidationSeverity.WARNING -> WARNING_COLOR
        }
        val font = Font(Font.SANS_SERIF, Font.BOLD, maxOf(8, (size * 0.6f).toInt()))
        g2.font = font
        val fm = g2.fontMetrics
        val text = "!"
        val textX = x + (size - fm.stringWidth(text)) / 2
        val textY = y + (size + fm.ascent - fm.descent) / 2 - 1
        g2.drawString(text, textX, textY)
    }

    companion object {
        /** Error red per scenario workflow §4. */
        val ERROR_COLOR: Color = Color(0xC6, 0x28, 0x28)

        /** Warning amber per scenario workflow §4. */
        val WARNING_COLOR: Color = Color(0xEF, 0x6C, 0x00)

        /** Default icon size in pixels (12) — fits a standard `JLabel`. */
        const val DEFAULT_SIZE: Int = 12
    }
}
