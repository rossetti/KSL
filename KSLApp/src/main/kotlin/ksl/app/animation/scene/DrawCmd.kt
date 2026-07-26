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
import ksl.app.animation.geom.BoundingBox
import ksl.app.animation.style.RgbaColor

/**
 * Whether a layer's coordinates are world (layout) units or screen pixels.
 *
 * The distinction is load-bearing rather than cosmetic. A queue's members must move and scale with the
 * animation, while the clock, the legend and an element's text label must stay put and stay legible
 * however far the view is zoomed. A renderer that keeps this implicit ends up swapping transforms
 * part-way through painting a frame; declaring it per layer means a drawing surface can be a dumb
 * executor of commands.
 */
enum class DrawSpace { WORLD, SCREEN }

/**
 * A length that a drawing surface resolves to pixels.
 *
 * Some sizes must grow with the view and some must not, and mixing them up is very visible: a queue's
 * spacing has to track its glyphs, while a station's marker dot should stay the same size at any zoom.
 * A world extent may also declare a pixel floor, so an object in a large world stays visible rather than
 * collapsing to a sub-pixel speck.
 */
sealed interface Extent {

    /** A length in world units, never drawn smaller than [minPx] pixels. */
    data class World(val value: Double, val minPx: Double = 0.0) : Extent

    /** A length already in screen pixels, unaffected by zoom. */
    data class Px(val value: Double) : Extent

    companion object {
        fun world(value: Double, minPx: Double = 0.0): Extent = World(value, minPx)
        fun px(value: Double): Extent = Px(value)
    }
}

/** Where a text command's anchor sits relative to the text. */
enum class TextAnchor { START, MIDDLE, END }

/**
 * One primitive to draw. The set is deliberately small — it is the union of what the existing renderers
 * actually emit, arrived at by porting them rather than by design.
 *
 * [Glyph] is kept symbolic rather than being reduced to a [Circle] or [Rect] when the scene is built.
 * Shape is per-object-class layout data, so leaving it unresolved lets each surface pick its own best
 * primitive, and lets an image reference fall back to the shape without the scene needing to know
 * whether that image ever loaded.
 */
sealed interface DrawCmd {

    /** A connected run of line segments. [width] is a stroke width in pixels. */
    data class Polyline(
        val points: List<Pair<Double, Double>>,
        val color: RgbaColor,
        val width: Double = 1.0,
        val closed: Boolean = false,
        val fill: RgbaColor? = null
    ) : DrawCmd

    /** A circle centered at ([cx], [cy]) of radius [radius]. */
    data class Circle(
        val cx: Double,
        val cy: Double,
        val radius: Extent,
        val fill: RgbaColor? = null,
        val stroke: RgbaColor? = null,
        val strokeWidth: Double = 1.0
    ) : DrawCmd

    /** An axis-aligned rectangle whose top-left corner is ([x], [y]). */
    data class Rect(
        val x: Double,
        val y: Double,
        val width: Extent,
        val height: Extent,
        val fill: RgbaColor? = null,
        val stroke: RgbaColor? = null,
        val strokeWidth: Double = 1.0
    ) : DrawCmd

    /**
     * An entity, agent or mover glyph of [shape], centered at ([cx], [cy]) with diameter [size]. When
     * [imageRef] is set and the surface can resolve it, the image is drawn instead of the shape.
     */
    data class Glyph(
        val cx: Double,
        val cy: Double,
        val size: Extent,
        val shape: LayoutShape,
        val fill: RgbaColor,
        val imageRef: String? = null
    ) : DrawCmd

    /**
     * A single line of text with its baseline anchored at ([x], [y]).
     *
     * [screenOffsetX] and [screenOffsetY] nudge the text by a fixed number of pixels after the anchor has
     * been mapped to the screen. An element's label needs exactly this: it sits at its glyph's world
     * position but must keep a constant gap from it, which a world-unit offset cannot express because the
     * gap would grow and shrink with the zoom.
     */
    data class Text(
        val x: Double,
        val y: Double,
        val text: String,
        val color: RgbaColor,
        val size: Extent = Extent.Px(11.0),
        val family: String? = null,
        val anchor: TextAnchor = TextAnchor.START,
        val bold: Boolean = false,
        val screenOffsetX: Double = 0.0,
        val screenOffsetY: Double = 0.0
    ) : DrawCmd

    /** An image filling the rectangle whose top-left corner is ([x], [y]); skipped if it cannot load. */
    data class Image(
        val x: Double,
        val y: Double,
        val width: Extent,
        val height: Extent,
        val ref: String
    ) : DrawCmd

    /** An arrowhead at ([x], [y]) pointing along the direction ([dx], [dy]). */
    data class ArrowHead(
        val x: Double,
        val y: Double,
        val dx: Double,
        val dy: Double,
        val color: RgbaColor,
        val length: Extent = Extent.Px(7.0),
        val width: Double = 1.0
    ) : DrawCmd

    /** A stroked ring — an expanding, fading highlight at a location where something happened. */
    data class Ring(
        val cx: Double,
        val cy: Double,
        val radius: Extent,
        val color: RgbaColor,
        val strokeWidth: Double = 2.0
    ) : DrawCmd
}

/**
 * A named group of commands sharing a coordinate space.
 *
 * The name is not decoration: layers are named after the animation concept they draw (`queues`,
 * `resources`, `agents`), which is what lets a test assert about one concern at a time and lets a
 * reviewer compare a scene against the renderer it was ported from, layer by layer.
 */
data class Layer(val name: String, val space: DrawSpace, val commands: List<DrawCmd>) {
    val isEmpty: Boolean get() = commands.isEmpty()
}

/**
 * A complete, fully resolved description of one animation frame: what to draw, in what order, in which
 * coordinate space — and nothing about how.
 *
 * Being plain data is the point. A scene can be asserted against in a test with no display, no toolkit
 * and no image comparison, which is how renderer behavior gets pinned down; and the same scene can be
 * handed to a desktop canvas, a browser canvas or an offscreen image without any of them re-deciding
 * what an animation looks like.
 */
data class Scene(
    val layers: List<Layer>,
    val worldBounds: BoundingBox,
    val simTime: Double
) {
    /** Total number of commands across every layer. */
    val commandCount: Int get() = layers.sumOf { it.commands.size }

    /** The layer called [name], or null. */
    fun layer(name: String): Layer? = layers.firstOrNull { it.name == name }

    /** The commands of the layer called [name], or empty when there is no such layer. */
    fun commandsOf(name: String): List<DrawCmd> = layer(name)?.commands ?: emptyList()

    /** The layers that actually carry commands, in order — what a surface needs to walk. */
    fun nonEmptyLayers(): List<Layer> = layers.filter { !it.isEmpty }
}
