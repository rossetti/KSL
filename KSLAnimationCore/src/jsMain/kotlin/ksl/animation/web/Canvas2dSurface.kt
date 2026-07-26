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

package ksl.animation.web

import ksl.animation.LayoutShape
import ksl.app.animation.geom.ViewTransform
import ksl.app.animation.scene.DrawCmd
import ksl.app.animation.scene.DrawSpace
import ksl.app.animation.scene.DrawSurface
import ksl.app.animation.scene.TextAnchor
import ksl.app.animation.style.RgbaColor
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLImageElement
import kotlin.math.PI

/**
 * Draws a [ksl.app.animation.scene.Scene] onto an HTML canvas.
 *
 * This is the entire browser renderer. It decides nothing about what an animation contains — the scene
 * already settled that — so it is a translation from the drawing vocabulary to the 2D context, and the
 * same animation that appears on the desktop appears here without either side re-deriving it.
 *
 * Canvas 2D rather than WebGL is a measured choice: a frame of a real trace costs a couple of
 * milliseconds for tens to hundreds of commands, which leaves ample headroom at 60fps, and it needs no
 * third-party library at all. A GPU-backed surface only becomes worthwhile for models with thousands of
 * simultaneous glyphs, and it would be another implementation of this same interface.
 */
internal class Canvas2dSurface(
    private val ctx: CanvasRenderingContext2D,
    override val widthPx: Double,
    override val heightPx: Double,
    private val images: ImageCache
) : DrawSurface {

    private var space: DrawSpace = DrawSpace.SCREEN
    private var view: ViewTransform = ViewTransform(0.0, 0.0, 1.0)

    override fun clear(color: RgbaColor) {
        ctx.save()
        ctx.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        ctx.fillStyle = color.toCssRgba()
        ctx.fillRect(0.0, 0.0, widthPx, heightPx)
        ctx.restore()
    }

    override fun beginLayer(space: DrawSpace, view: ViewTransform) {
        this.space = space
        this.view = view
        ctx.save()
    }

    override fun endLayer() {
        ctx.restore()
    }

    // Coordinates are mapped here rather than by pushing a canvas transform, because a canvas transform
    // would also scale stroke widths and text, which must stay in pixels.
    private fun px(x: Double): Double = if (space == DrawSpace.WORLD) view.toScreenX(x) else x
    private fun py(y: Double): Double = if (space == DrawSpace.WORLD) view.toScreenY(y) else y
    private fun len(extent: ksl.app.animation.scene.Extent): Double = resolveExtent(extent, space, view)

    override fun resolveImage(ref: String): Any? = images.get(ref)

    override fun draw(command: DrawCmd) {
        when (command) {
            is DrawCmd.Polyline -> {
                if (command.points.size < 2) return
                ctx.beginPath()
                ctx.moveTo(px(command.points[0].first), py(command.points[0].second))
                for (i in 1 until command.points.size) {
                    ctx.lineTo(px(command.points[i].first), py(command.points[i].second))
                }
                if (command.closed) ctx.closePath()
                command.fill?.let { ctx.fillStyle = it.toCssRgba(); ctx.fill() }
                ctx.strokeStyle = command.color.toCssRgba()
                ctx.lineWidth = command.width
                ctx.stroke()
            }

            is DrawCmd.Circle -> {
                val r = len(command.radius).coerceAtLeast(0.5)
                ctx.beginPath()
                ctx.arc(px(command.cx), py(command.cy), r, 0.0, TWO_PI)
                command.fill?.let { ctx.fillStyle = it.toCssRgba(); ctx.fill() }
                command.stroke?.let {
                    ctx.strokeStyle = it.toCssRgba()
                    ctx.lineWidth = command.strokeWidth
                    ctx.stroke()
                }
            }

            is DrawCmd.Rect -> {
                val x = px(command.x)
                val y = py(command.y)
                val w = len(command.width)
                val h = len(command.height)
                command.fill?.let { ctx.fillStyle = it.toCssRgba(); ctx.fillRect(x, y, w, h) }
                command.stroke?.let {
                    ctx.strokeStyle = it.toCssRgba()
                    ctx.lineWidth = command.strokeWidth
                    ctx.strokeRect(x, y, w, h)
                }
            }

            is DrawCmd.Glyph -> {
                val cx = px(command.cx)
                val cy = py(command.cy)
                val d = len(command.size).coerceAtLeast(1.0)
                val image = command.imageRef?.let { images.get(it) }
                if (command.shape == LayoutShape.IMAGE && image != null) {
                    ctx.drawImage(image, cx - d / 2, cy - d / 2, d, d)
                } else {
                    ctx.fillStyle = command.fill.toCssRgba()
                    fillShape(cx, cy, d, command.shape)
                }
            }

            is DrawCmd.Text -> {
                val size = len(command.size).coerceAtLeast(4.0)
                ctx.fillStyle = command.color.toCssRgba()
                ctx.font = (if (command.bold) "bold " else "") + "${size}px " + (command.family ?: DEFAULT_FONT)
                // CanvasTextAlign is an external string-valued type in Kotlin/JS, not an enum, so the
                // CSS keyword is set directly.
                ctx.asDynamic().textAlign = when (command.anchor) {
                    TextAnchor.START -> "start"
                    TextAnchor.MIDDLE -> "center"
                    TextAnchor.END -> "end"
                }
                ctx.fillText(
                    command.text,
                    px(command.x) + command.screenOffsetX,
                    py(command.y) + command.screenOffsetY
                )
            }

            is DrawCmd.Image -> {
                val image = images.get(command.ref) ?: return // missing image costs a glyph, not a frame
                ctx.drawImage(image, px(command.x), py(command.y), len(command.width), len(command.height))
            }

            is DrawCmd.ArrowHead -> {
                val mag = kotlin.math.sqrt(command.dx * command.dx + command.dy * command.dy)
                if (mag <= 0.0) return
                val angle = kotlin.math.atan2(command.dy, command.dx)
                val l = len(command.length)
                val x = px(command.x)
                val y = py(command.y)
                ctx.strokeStyle = command.color.toCssRgba()
                ctx.lineWidth = command.width
                ctx.beginPath()
                for (wing in listOf(WING, -WING)) {
                    ctx.moveTo(x, y)
                    ctx.lineTo(x + l * kotlin.math.cos(angle + wing), y + l * kotlin.math.sin(angle + wing))
                }
                ctx.stroke()
            }

            is DrawCmd.Ring -> {
                val r = len(command.radius).coerceAtLeast(0.5)
                ctx.beginPath()
                ctx.arc(px(command.cx), py(command.cy), r, 0.0, TWO_PI)
                ctx.strokeStyle = command.color.toCssRgba()
                ctx.lineWidth = command.strokeWidth
                ctx.stroke()
            }
        }
    }

    private fun fillShape(cx: Double, cy: Double, d: Double, shape: LayoutShape) {
        val half = d / 2
        when (shape) {
            LayoutShape.CIRCLE -> {
                ctx.beginPath()
                ctx.arc(cx, cy, half, 0.0, TWO_PI)
                ctx.fill()
            }
            // A declared image that could not be resolved falls back to a square, matching the desktop.
            LayoutShape.SQUARE, LayoutShape.IMAGE -> ctx.fillRect(cx - half, cy - half, d, d)
            LayoutShape.TRIANGLE -> {
                ctx.beginPath()
                ctx.moveTo(cx, cy - half)
                ctx.lineTo(cx + half, cy + half)
                ctx.lineTo(cx - half, cy + half)
                ctx.closePath()
                ctx.fill()
            }
            LayoutShape.DIAMOND -> {
                ctx.beginPath()
                ctx.moveTo(cx, cy - half)
                ctx.lineTo(cx + half, cy)
                ctx.lineTo(cx, cy + half)
                ctx.lineTo(cx - half, cy)
                ctx.closePath()
                ctx.fill()
            }
        }
    }

    private companion object {
        const val TWO_PI = 2.0 * PI
        const val WING = 2.6179938779914944 // 150 degrees, matching the desktop arrowhead
        const val DEFAULT_FONT = "sans-serif"
    }
}

/**
 * Loads and caches images referenced by a layout.
 *
 * Loading is asynchronous but drawing is not, so a reference resolves to null until its image arrives and
 * the glyph falls back to its shape for a frame or two. That is deliberate: an animation should start
 * playing immediately rather than waiting on decoration.
 *
 * A layout stores image references as paths relative to the layout file. In a browser there is no such
 * file, so [assetBase] supplies the URL prefix they resolve against.
 */
internal class ImageCache(private val assetBase: String?) {

    private val cache = HashMap<String, HTMLImageElement?>()

    /** The image for [ref] if it has finished loading, else null (and starts loading it). */
    fun get(ref: String): HTMLImageElement? {
        if (cache.containsKey(ref)) return cache[ref]?.takeIf { it.complete && it.naturalWidth > 0 }
        val element = kotlinx.browser.document.createElement("img") as HTMLImageElement
        element.src = resolve(ref)
        cache[ref] = element
        return null
    }

    private fun resolve(ref: String): String = when {
        ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("data:") || ref.startsWith("/") -> ref
        assetBase.isNullOrEmpty() -> ref
        assetBase.endsWith("/") -> assetBase + ref
        else -> "$assetBase/$ref"
    }

    fun clear() = cache.clear()
}
