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

package ksl.animation.scene

import ksl.animation.geom.ViewTransform
import ksl.animation.style.RgbaColor

/**
 * A device that can execute [DrawCmd]s — one implementation per rendering technology.
 *
 * An implementation is meant to be a translator and nothing more: it decides how to put a circle on a
 * device, never which circles an animation has. Everything about *what* an animation looks like belongs
 * to the scene it is handed.
 *
 * A surface receives commands in the coordinate space the current layer declared, and is responsible for
 * resolving an [Extent] to pixels — see [resolveExtent], which every implementation should use so the
 * world/pixel and floor rules stay identical across surfaces.
 */
interface DrawSurface {

    /** The drawable width in pixels. */
    val widthPx: Double

    /** The drawable height in pixels. */
    val heightPx: Double

    /** Fills the whole surface with [color]. */
    fun clear(color: RgbaColor)

    /**
     * Begins a layer drawn in [space], using [view] for world-to-screen mapping. Implementations
     * typically save device state here and restore it in [endLayer].
     */
    fun beginLayer(space: DrawSpace, view: ViewTransform)

    /** Draws one command, in the space established by the enclosing [beginLayer]. */
    fun draw(command: DrawCmd)

    /** Ends the current layer. */
    fun endLayer()

    /**
     * Resolves an image reference to something this device can draw, or null when it is unavailable — in
     * which case a [DrawCmd.Glyph] falls back to its shape and a [DrawCmd.Image] is skipped. Returning
     * null rather than throwing is deliberate: a missing image should cost a glyph, not a frame.
     */
    fun resolveImage(ref: String): Any? = null

    /** Resolves [extent] to pixels for the given [space] and [view], applying any pixel floor. */
    fun resolveExtent(extent: Extent, space: DrawSpace, view: ViewTransform): Double = when (extent) {
        is Extent.Px -> extent.value
        is Extent.World ->
            if (space == DrawSpace.SCREEN) extent.value.coerceAtLeast(extent.minPx)
            else view.lengthToScreen(extent.value).coerceAtLeast(extent.minPx)
    }
}

/**
 * Walks a [Scene] into a [DrawSurface]. Every renderer shares this, so layer order and the
 * begin/draw/end protocol cannot drift between them.
 */
object SceneRenderer {

    /**
     * Clears [surface] to [background], then draws every non-empty layer of [scene] in order using [view].
     */
    fun render(
        scene: Scene,
        surface: DrawSurface,
        view: ViewTransform,
        background: RgbaColor = RgbaColor.WHITE
    ) {
        surface.clear(background)
        for (layer in scene.nonEmptyLayers()) {
            surface.beginLayer(layer.space, view)
            for (command in layer.commands) surface.draw(command)
            surface.endLayer()
        }
    }
}
