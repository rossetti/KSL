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

package ksl.service.capability.render

import ksl.animation.AnimationLayout
import ksl.animation.LayoutPoint
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Renders an [AnimationLayout] to a **static preview** image — labeled, color-coded glyphs at each
 * placed element plus path polylines — so a proposed layout can be *seen* (the propose → render →
 * look → revise loop) without running or animating it.
 *
 * A fresh `Graphics2D` renderer (option (ii) of the plan's §7 E4): it depends only on the layout data
 * (KSLCore) and `java.desktop` (a JDK built-in that renders fine headless), so it adds no dependency on
 * the Swing animation module. It intentionally draws far less than the desktop `SimulationCanvas` (which
 * animates a live replay) — just the static placement.
 */
object AnimationLayoutRenderer {

    private val RESOURCE = Color(0x1f, 0x77, 0xb4)
    private val QUEUE = Color(0x2c, 0xa0, 0x2c)
    private val STATION = Color(0xff, 0x7f, 0x0e)
    private val MOVER = Color(0x94, 0x67, 0xbd)
    private val STORAGE = Color(0x8c, 0x56, 0x4b)
    private val DISPLAY = Color(0x7f, 0x7f, 0x7f)
    private val PATH = Color(0xcc, 0xcc, 0xcc)

    private data class Glyph(val pos: LayoutPoint, val text: String, val color: Color)

    /** Renders [layout] to a [BufferedImage] sized to the layout's own canvas (clamped to a sane range). */
    fun renderToImage(layout: AnimationLayout): BufferedImage {
        val w = layout.width.toInt().coerceIn(200, 4000)
        val h = layout.height.toInt().coerceIn(200, 4000)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)

        // Paths first, behind the glyphs.
        g.color = PATH
        g.stroke = BasicStroke(2f)
        for (path in layout.paths) {
            val pts = path.points
            for (i in 0 until pts.size - 1) {
                g.drawLine(pts[i].x.toInt(), pts[i].y.toInt(), pts[i + 1].x.toInt(), pts[i + 1].y.toInt())
            }
        }

        val glyphs = buildList {
            layout.resources.forEach { add(Glyph(it.position, it.resourceName, RESOURCE)) }
            layout.queues.forEach { add(Glyph(it.position, it.queueName, QUEUE)) }
            layout.stations.forEach { add(Glyph(it.position, it.label ?: it.stationName, STATION)) }
            layout.movableResources.forEach { m -> m.position?.let { add(Glyph(it, m.label ?: m.name, MOVER)) } }
            layout.storages.forEach { add(Glyph(it.position, it.label ?: it.suspensionName, STORAGE)) }
            layout.bars.forEach { add(Glyph(it.position, it.label ?: it.responseName, DISPLAY)) }
            layout.plots.forEach { add(Glyph(it.position, it.label ?: it.responseName, DISPLAY)) }
            layout.values.forEach { add(Glyph(it.position, it.label ?: it.responseName, DISPLAY)) }
            layout.summaries.forEach { add(Glyph(it.position, it.label ?: it.responseName, DISPLAY)) }
            layout.histograms.forEach { add(Glyph(it.position, it.label ?: it.responseName, DISPLAY)) }
            layout.clocks.forEach { add(Glyph(it.position, it.label ?: "clock", DISPLAY)) }
        }
        for (glyph in glyphs) drawGlyph(g, glyph)

        layout.title?.let {
            g.color = Color.DARK_GRAY
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
            g.drawString(it, 10, 20)
        }
        g.dispose()
        return img
    }

    private fun drawGlyph(g: Graphics2D, glyph: Glyph) {
        val x = glyph.pos.x.toInt()
        val y = glyph.pos.y.toInt()
        val bw = 92
        val bh = 26
        g.color = Color(glyph.color.red, glyph.color.green, glyph.color.blue, 40)
        g.fillRoundRect(x, y, bw, bh, 8, 8)
        g.color = glyph.color
        g.stroke = BasicStroke(1.5f)
        g.drawRoundRect(x, y, bw, bh, 8, 8)
        g.color = Color.BLACK
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        val text = if (glyph.text.length > 14) glyph.text.take(13) + "…" else glyph.text
        g.drawString(text, x + 6, y + 17)
    }

    /** Renders [layout] to a PNG at [path]. */
    fun renderToPng(layout: AnimationLayout, path: Path) {
        ImageIO.write(renderToImage(layout), "png", path.toFile())
    }
}
