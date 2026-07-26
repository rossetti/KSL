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

package ksl.animation.geom

/** A point in device (screen) pixels. */
data class ScreenPoint(val x: Double, val y: Double)

/**
 * The world-to-screen mapping for an animation view: fit a world box into a viewport with a margin, then
 * apply zoom and pan.
 *
 * There is no y-flip — a layout's y grows downward, matching screen coordinates — so a world direction
 * maps straight to a screen direction and a heading indicator needs no correction.
 *
 * NOTE: compiled for both the JVM and Kotlin/JS. Keep it free of JVM-only APIs.
 *
 * @param originX the world x that maps to the left margin
 * @param originY the world y that maps to the top margin
 * @param baseScale screen pixels per world unit before [zoom]
 * @param margin the inset, in screen pixels, on every side
 * @param zoom a multiplier on [baseScale]
 * @param panX a horizontal offset in screen pixels
 * @param panY a vertical offset in screen pixels
 */
data class ViewTransform(
    val originX: Double,
    val originY: Double,
    val baseScale: Double,
    val margin: Double = DEFAULT_MARGIN,
    val zoom: Double = 1.0,
    val panX: Double = 0.0,
    val panY: Double = 0.0
) {

    /** Screen pixels per world unit, including [zoom]. */
    val scale: Double get() = baseScale * zoom

    fun toScreenX(worldX: Double): Double = margin + panX + (worldX - originX) * scale

    fun toScreenY(worldY: Double): Double = margin + panY + (worldY - originY) * scale

    fun toScreen(x: Double, y: Double): ScreenPoint = ScreenPoint(toScreenX(x), toScreenY(y))

    fun toWorldX(screenX: Double): Double = (screenX - margin - panX) / scale + originX

    fun toWorldY(screenY: Double): Double = (screenY - margin - panY) / scale + originY

    /** A world length in screen pixels. */
    fun lengthToScreen(worldLength: Double): Double = worldLength * scale

    /** A copy with the zoom and pan replaced. */
    fun withZoomPan(zoom: Double, panX: Double, panY: Double): ViewTransform =
        copy(zoom = zoom, panX = panX, panY = panY)

    /**
     * A copy zoomed by [factor] about the screen point ([atX], [atY]), so the world point under that
     * pixel stays under it — the behavior a mouse-wheel zoom needs.
     */
    fun zoomedAbout(factor: Double, atX: Double, atY: Double): ViewTransform {
        val wx = toWorldX(atX)
        val wy = toWorldY(atY)
        val next = copy(zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM))
        // Re-solve pan so (wx, wy) still lands on (atX, atY).
        val px = atX - next.margin - (wx - next.originX) * next.scale
        val py = atY - next.margin - (wy - next.originY) * next.scale
        return next.copy(panX = px, panY = py)
    }

    companion object {
        const val DEFAULT_MARGIN: Double = 20.0
        const val MIN_ZOOM: Double = 0.05
        const val MAX_ZOOM: Double = 40.0

        /**
         * Fits [world] into a [viewWidth] by [viewHeight] viewport, preserving aspect ratio, with [margin]
         * on each side. A degenerate world box is clamped rather than producing an infinite scale.
         */
        fun fit(
            world: BoundingBox,
            viewWidth: Double,
            viewHeight: Double,
            margin: Double = DEFAULT_MARGIN
        ): ViewTransform {
            val sx = (viewWidth - 2 * margin) / world.width.coerceAtLeast(1e-6)
            val sy = (viewHeight - 2 * margin) / world.height.coerceAtLeast(1e-6)
            val s = minOf(sx, sy).coerceAtLeast(1e-6)
            return ViewTransform(world.minX, world.minY, s, margin)
        }
    }
}
