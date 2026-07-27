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

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns a stream of pointer presses and moves into the pan and zoom a view should apply.
 *
 * The arithmetic lives here rather than inline in the player's event listeners because it is the part
 * that can be wrong without looking wrong: a pinch whose ratio is taken against a stale spread drifts,
 * and lifting one finger of a two-finger gesture reads as a large jump unless the baseline is retaken.
 * Neither shows up as an error — the animation simply moves oddly under a finger — so both are asserted
 * in [PointerGesturesTest] instead.
 *
 * Everything here is in the event's own client pixel space and knows nothing about a canvas, a world or a
 * transform; the player is what maps a [Change] onto its [ksl.app.animation.geom.ViewTransform].
 */
internal class PointerGestures {

    /**
     * What one move asks the view to do: translate by ([panXPx], [panYPx]), then scale by [zoomFactor]
     * about the point ([focusXPx], [focusYPx]) — the midpoint between the fingers, so the two spots being
     * pinched stay under them.
     */
    data class Change(
        val panXPx: Double,
        val panYPx: Double,
        val zoomFactor: Double,
        val focusXPx: Double,
        val focusYPx: Double
    )

    private class Track(var x: Double, var y: Double, val downX: Double, val downY: Double, val downMs: Double)

    // Insertion-ordered so that a third finger arriving does not hand the gesture to a different pair
    // mid-pinch; the two that started it keep it until one of them lifts.
    private val active = LinkedHashMap<Int, Track>()

    private var lastCentroidX = 0.0
    private var lastCentroidY = 0.0
    private var lastSpread = 0.0

    private var lastTapMs = -1.0
    private var lastTapX = 0.0
    private var lastTapY = 0.0

    /** True while any pointer is down, i.e. while the canvas should show a dragging cursor. */
    val isActive: Boolean get() = active.isNotEmpty()

    fun down(id: Int, x: Double, y: Double, atMs: Double) {
        active[id] = Track(x, y, x, y, atMs)
        retakeBaseline()
    }

    /** @return what the view should do, or null when [id] is not a pointer this gesture is tracking. */
    fun move(id: Int, x: Double, y: Double): Change? {
        val track = active[id] ?: return null
        track.x = x
        track.y = y
        val points = active.values.take(2)
        val centroidX = points.sumOf { it.x } / points.size
        val centroidY = points.sumOf { it.y } / points.size
        val spread = spreadOf(points)
        val change = Change(
            panXPx = centroidX - lastCentroidX,
            panYPx = centroidY - lastCentroidY,
            // One finger pans without zooming, and a pinch reports no ratio until both fingers have been
            // seen at once — otherwise the first move of a pinch scales against a spread of zero.
            zoomFactor = if (spread > MIN_SPREAD_PX && lastSpread > MIN_SPREAD_PX) spread / lastSpread else 1.0,
            focusXPx = centroidX,
            focusYPx = centroidY
        )
        lastCentroidX = centroidX
        lastCentroidY = centroidY
        lastSpread = spread
        return change
    }

    /**
     * Releases [id].
     *
     * @return true when this release completed a double tap. That is the touch equivalent of the
     *   double-click that resets the view, and it has to be recognised here because a page with
     *   `touch-action: none` cannot rely on the browser synthesising a `dblclick` from two taps — and
     *   without it, someone who has pinched into a corner on a tablet has no way back to the whole model.
     */
    fun up(id: Int, x: Double, y: Double, atMs: Double): Boolean {
        val track = active.remove(id)
        retakeBaseline()
        if (track == null) return false
        // Only a lone finger taps. A release that ends a pinch is not a tap however brief it was.
        if (active.isNotEmpty()) return false
        if (!isTap(track, x, y, atMs)) return false

        val paired = lastTapMs >= 0.0 &&
            atMs - lastTapMs <= DOUBLE_TAP_MS &&
            abs(x - lastTapX) <= DOUBLE_TAP_SLOP_PX &&
            abs(y - lastTapY) <= DOUBLE_TAP_SLOP_PX
        // Consume the pair, so three taps in a row are one double tap and a single, not two doubles.
        lastTapMs = if (paired) -1.0 else atMs
        lastTapX = x
        lastTapY = y
        return paired
    }

    /** The browser took the pointer away (a system gesture, a lost window). Nothing is pending after this. */
    fun cancel(id: Int) {
        active.remove(id)
        lastTapMs = -1.0
        retakeBaseline()
    }

    private fun isTap(track: Track, x: Double, y: Double, atMs: Double): Boolean =
        atMs - track.downMs <= TAP_MS && hypot(x - track.downX, y - track.downY) <= TAP_SLOP_PX

    /**
     * Re-measures the centroid and spread from wherever the pointers now are.
     *
     * Called on every press and release, because both change which pointers the centroid is taken over.
     * Without it, lifting one finger of a pinch moves the centroid to the remaining finger in a single
     * step and the next move applies that whole distance as a pan.
     */
    private fun retakeBaseline() {
        val points = active.values.take(2)
        if (points.isEmpty()) {
            lastSpread = 0.0
            return
        }
        lastCentroidX = points.sumOf { it.x } / points.size
        lastCentroidY = points.sumOf { it.y } / points.size
        lastSpread = spreadOf(points)
    }

    private fun spreadOf(points: List<Track>): Double =
        if (points.size == 2) hypot(points[1].x - points[0].x, points[1].y - points[0].y) else 0.0

    private companion object {
        /** Below this the two fingers are effectively one point and the ratio between spreads is noise. */
        const val MIN_SPREAD_PX = 1.0
        const val TAP_MS = 300.0
        const val TAP_SLOP_PX = 12.0
        const val DOUBLE_TAP_MS = 400.0
        const val DOUBLE_TAP_SLOP_PX = 40.0
    }
}
