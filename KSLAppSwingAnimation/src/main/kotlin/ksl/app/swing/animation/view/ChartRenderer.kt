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

package ksl.app.swing.animation.view

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D

/**
 * Pure Java2D drawing of the layout's live displays — a value [bar] bound to a response and a
 * [timeSeries] plot of a response over time — into a screen-space rectangle. No Swing widgets and
 * no replay/layout coupling, so the drawing can be verified offscreen in a test and reused by the
 * canvas for any positioned bar/plot.
 */
object ChartRenderer {

    /**
     * A horizontal bar filling `value/maxValue` of [rect], with a border and a "[label]: value"
     * caption above it. The fill is clamped to the rectangle.
     */
    fun bar(g2: Graphics2D, rect: Rectangle2D, value: Double, maxValue: Double, color: Color, label: String?) {
        val frac = if (maxValue > 0.0) (value / maxValue).coerceIn(0.0, 1.0) else 0.0
        g2.color = Color.WHITE
        g2.fill(rect)
        g2.color = color
        g2.fill(Rectangle2D.Double(rect.x, rect.y, rect.width * frac, rect.height))
        g2.color = Color.DARK_GRAY
        g2.stroke = BasicStroke(1.0f)
        g2.draw(rect)
        if (label != null) {
            g2.drawString("%s: %.1f".format(label, value), rect.x.toFloat(), (rect.y - 2).toFloat())
        }
    }

    /**
     * A line plot of `(time, value)` [samples] inside [rect]. The x-axis spans `[t - window, t]`
     * (or the samples' own time span when [window] is null), and the y-axis spans `[0, yMax]`
     * (auto-scaled to the data when [yMax] is null). Draws a border and an optional [label].
     */
    fun timeSeries(
        g2: Graphics2D,
        rect: Rectangle2D,
        samples: List<Pair<Double, Double>>,
        currentTime: Double,
        window: Double?,
        yMax: Double?,
        color: Color,
        label: String?
    ) {
        g2.color = Color.WHITE
        g2.fill(rect)
        g2.color = Color.DARK_GRAY
        g2.stroke = BasicStroke(1.0f)
        g2.draw(rect)
        if (label != null) g2.drawString(label, rect.x.toFloat(), (rect.y - 2).toFloat())
        if (samples.isEmpty()) return

        val tMax = currentTime
        val tMin = if (window != null) currentTime - window else samples.first().first
        val tSpan = (tMax - tMin).takeIf { it > 0.0 } ?: 1.0
        val vMax = (yMax ?: samples.maxOf { it.second }).takeIf { it > 0.0 } ?: 1.0

        fun sx(t: Double) = rect.x + ((t - tMin) / tSpan).coerceIn(0.0, 1.0) * rect.width
        fun sy(v: Double) = rect.y + rect.height - (v / vMax).coerceIn(0.0, 1.0) * rect.height

        g2.color = color
        g2.stroke = BasicStroke(1.5f)
        val visible = samples.filter { it.first >= tMin }
        var prev: Pair<Double, Double>? = null
        for (s in visible) {
            prev?.let { p ->
                g2.drawLine(sx(p.first).toInt(), sy(p.second).toInt(), sx(s.first).toInt(), sy(s.second).toInt())
            }
            prev = s
        }
    }
}
