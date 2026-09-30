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

package ksl.app.animation.scene

import ksl.animation.LayoutShape
import ksl.app.animation.geom.ViewTransform
import ksl.app.animation.style.RgbaColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Executes [DrawCmd]s on a Java2D [Graphics2D]: the JVM counterpart of the web player's canvas surface, and a
 * line-for-line translation of it, so a scene looks the same on the desktop, in an exported image and in a
 * browser. JVM-only; the browser build does not compile it.
 *
 * Like the canvas surface it maps coordinates itself rather than installing a transform on the graphics, so
 * stroke widths and text stay in pixels at any zoom.
 *
 * @param g2 the graphics to draw on; its state is saved per layer and restored after
 * @param widthPx the drawable width
 * @param heightPx the drawable height
 * @param images resolves an image reference to a loaded image, or null when it is unavailable
 */
class Java2dSurface(
    private val g2: Graphics2D,
    override val widthPx: Double,
    override val heightPx: Double,
    private val images: (String) -> BufferedImage? = { null }
) : DrawSurface {

    private var space = DrawSpace.WORLD
    private var view = ViewTransform(0.0, 0.0, 1.0)
    private var saved: Graphics2D? = null

    override fun clear(color: RgbaColor) {
        g2.color = color.awt()
        g2.fill(Rectangle2D.Double(0.0, 0.0, widthPx, heightPx))
    }

    override fun beginLayer(space: DrawSpace, view: ViewTransform) {
        this.space = space
        this.view = view
        saved = g2.create() as Graphics2D
    }

    override fun endLayer() {
        saved?.let {
            g2.color = it.color
            g2.stroke = it.stroke
            g2.font = it.font
            it.dispose()
        }
        saved = null
    }

    private fun px(x: Double): Double = if (space == DrawSpace.WORLD) view.toScreenX(x) else x
    private fun py(y: Double): Double = if (space == DrawSpace.WORLD) view.toScreenY(y) else y
    private fun len(extent: Extent): Double = resolveExtent(extent, space, view)

    override fun resolveImage(ref: String): Any? = images(ref)

    override fun draw(command: DrawCmd) {
        when (command) {
            is DrawCmd.Polyline -> {
                if (command.points.size < 2) return
                val path = Path2D.Double()
                path.moveTo(px(command.points[0].first), py(command.points[0].second))
                for (i in 1 until command.points.size) path.lineTo(px(command.points[i].first), py(command.points[i].second))
                if (command.closed) path.closePath()
                command.fill?.let { g2.color = it.awt(); g2.fill(path) }
                g2.color = command.color.awt()
                g2.stroke = BasicStroke(command.width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(path)
            }

            is DrawCmd.Circle -> {
                val r = len(command.radius).coerceAtLeast(0.5)
                val shape = Ellipse2D.Double(px(command.cx) - r, py(command.cy) - r, 2 * r, 2 * r)
                command.fill?.let { g2.color = it.awt(); g2.fill(shape) }
                command.stroke?.let {
                    g2.color = it.awt()
                    g2.stroke = BasicStroke(command.strokeWidth.toFloat())
                    g2.draw(shape)
                }
            }

            is DrawCmd.Rect -> {
                val w = len(command.width)
                val h = len(command.height)
                val x = px(command.x) - if (command.centered) w / 2 else 0.0
                val y = py(command.y) - if (command.centered) h / 2 else 0.0
                val shape = Rectangle2D.Double(x, y, w, h)
                command.fill?.let { g2.color = it.awt(); g2.fill(shape) }
                command.stroke?.let {
                    g2.color = it.awt()
                    g2.stroke = BasicStroke(command.strokeWidth.toFloat())
                    g2.draw(shape)
                }
            }

            is DrawCmd.Glyph -> {
                val cx = px(command.cx)
                val cy = py(command.cy)
                val d = len(command.size).coerceAtLeast(1.0)
                val image = command.imageRef?.let { images(it) }
                if (command.shape == LayoutShape.IMAGE && image != null) {
                    g2.drawImage(image, (cx - d / 2).toInt(), (cy - d / 2).toInt(), d.toInt().coerceAtLeast(1), d.toInt().coerceAtLeast(1), null)
                } else {
                    g2.color = command.fill.awt()
                    g2.fill(glyphShape(cx, cy, d, command.shape))
                }
            }

            is DrawCmd.Text -> {
                val size = len(command.size).coerceAtLeast(4.0)
                g2.color = command.color.awt()
                g2.font = Font(command.family ?: Font.SANS_SERIF, if (command.bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size.toFloat())
                val width = g2.fontMetrics.stringWidth(command.text).toDouble()
                val x = px(command.x) + command.screenOffsetX - when (command.anchor) {
                    TextAnchor.START -> 0.0
                    TextAnchor.MIDDLE -> width / 2
                    TextAnchor.END -> width
                }
                g2.drawString(command.text, x.toFloat(), (py(command.y) + command.screenOffsetY).toFloat())
            }

            is DrawCmd.Image -> {
                val image = images(command.ref) ?: return
                g2.drawImage(image, px(command.x).toInt(), py(command.y).toInt(), len(command.width).toInt(), len(command.height).toInt(), null)
            }

            is DrawCmd.ArrowHead -> {
                if (sqrt(command.dx * command.dx + command.dy * command.dy) <= 0.0) return
                val angle = atan2(command.dy, command.dx)
                val l = len(command.length)
                val x = px(command.x)
                val y = py(command.y)
                g2.color = command.color.awt()
                g2.stroke = BasicStroke(command.width.toFloat())
                for (wing in listOf(WING, -WING)) {
                    g2.draw(Line2D.Double(x, y, x + l * cos(angle + wing), y + l * sin(angle + wing)))
                }
            }

            is DrawCmd.Ring -> {
                val r = len(command.radius).coerceAtLeast(0.5)
                g2.color = command.color.awt()
                g2.stroke = BasicStroke(command.strokeWidth.toFloat())
                g2.draw(Ellipse2D.Double(px(command.cx) - r, py(command.cy) - r, 2 * r, 2 * r))
            }
        }
    }

    private fun glyphShape(cx: Double, cy: Double, d: Double, shape: LayoutShape): java.awt.Shape {
        val half = d / 2
        return when (shape) {
            LayoutShape.CIRCLE -> Ellipse2D.Double(cx - half, cy - half, d, d)
            // A declared image that could not be resolved falls back to a square, as on every surface.
            LayoutShape.SQUARE, LayoutShape.IMAGE -> Rectangle2D.Double(cx - half, cy - half, d, d)
            LayoutShape.TRIANGLE -> Path2D.Double().apply {
                moveTo(cx, cy - half); lineTo(cx + half, cy + half); lineTo(cx - half, cy + half); closePath()
            }
            LayoutShape.DIAMOND -> Path2D.Double().apply {
                moveTo(cx, cy - half); lineTo(cx + half, cy); lineTo(cx, cy + half); lineTo(cx - half, cy); closePath()
            }
        }
    }

    private fun RgbaColor.awt(): Color = Color(r, g, b, a)

    private companion object {
        const val WING = 2.6179938779914944 // 150 degrees, matching the canvas surface
    }
}
