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

package ksl.app.swing.animation.replay

/** A position in world coordinates (z defaults to 0 for 2D). */
data class WorldPoint(val x: Double, val y: Double, val z: Double = 0.0)

/**
 * A move from one point to another over `[t0, t1]`. Straight-line by default; when [via] carries intermediate
 * waypoints (an authored functional path) the move follows the polyline start · via · end, arc-length-timed.
 */
data class MotionSegment(
    val t0: Double, val t1: Double,
    val x0: Double, val y0: Double, val z0: Double,
    val x1: Double, val y1: Double, val z1: Double,
    val via: List<WorldPoint> = emptyList()
)

/** The point at arc-length fraction [f] (0..1) along the polyline [poly]; a plain lerp when [poly] has two points. */
fun pointAlongPolyline(poly: List<WorldPoint>, f: Double): WorldPoint {
    if (poly.isEmpty()) return WorldPoint(0.0, 0.0, 0.0)
    if (poly.size == 1) return poly[0]
    fun dist(a: WorldPoint, b: WorldPoint) =
        kotlin.math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
    val lens = DoubleArray(poly.size - 1) { dist(poly[it], poly[it + 1]) }
    val total = lens.sum()
    if (total <= 1e-9) return poly.first()
    var target = f.coerceIn(0.0, 1.0) * total
    for (i in lens.indices) {
        if (target <= lens[i] || i == lens.size - 1) {
            val ff = (target / lens[i].coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
            val a = poly[i]; val b = poly[i + 1]
            return WorldPoint(a.x + ff * (b.x - a.x), a.y + ff * (b.y - a.y), a.z + ff * (b.z - a.z))
        }
        target -= lens[i]
    }
    return poly.last()
}

/**
 * Pure interpolation over a [MotionSegment]. The renderer's smooth movement: given a move's endpoints and
 * times, the position at any replay time is computed without intermediate events.
 */
object PositionInterpolator {
    /**
     * The point on [seg] at time [t]: the start point at/before `t0`, the end point at/after `t1` (so an entity
     * holds at its destination after arriving), and a blend in between — a straight lerp, or along the segment's
     * waypoints when set. A zero/negative-duration segment yields the start point.
     */
    fun pointOn(seg: MotionSegment, t: Double): WorldPoint {
        if (t <= seg.t0 || seg.t1 <= seg.t0) return WorldPoint(seg.x0, seg.y0, seg.z0)
        if (t >= seg.t1) return WorldPoint(seg.x1, seg.y1, seg.z1)
        val f = (t - seg.t0) / (seg.t1 - seg.t0)
        if (seg.via.isEmpty()) {
            return WorldPoint(
                seg.x0 + f * (seg.x1 - seg.x0),
                seg.y0 + f * (seg.y1 - seg.y0),
                seg.z0 + f * (seg.z1 - seg.z0)
            )
        }
        val poly = ArrayList<WorldPoint>(seg.via.size + 2)
        poly.add(WorldPoint(seg.x0, seg.y0, seg.z0))
        poly.addAll(seg.via)
        poly.add(WorldPoint(seg.x1, seg.y1, seg.z1))
        return pointAlongPolyline(poly, f)
    }
}

/**
 * A time-ordered sequence of [MotionSegment]s for one mover (entity or agent). [positionAt] finds
 * the segment active at the query time (or the most recent one) and interpolates — so between
 * moves the mover holds at its last destination, and before its first move it sits at that move's
 * start. Segments are added in non-decreasing `t0` order.
 */
class MotionTrack {
    private val segments = ArrayList<MotionSegment>()

    val isEmpty: Boolean get() = segments.isEmpty()

    fun add(segment: MotionSegment) {
        segments.add(segment)
    }

    /** Axis-aligned bounding box of this track's endpoints (skipping NaN), or null when it has none. */
    fun bounds(): java.awt.geom.Rectangle2D.Double? {
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        for (s in segments) {
            val pts = listOf(s.x0 to s.y0, s.x1 to s.y1) + s.via.map { it.x to it.y }
            for ((x, y) in pts) {
                if (x.isNaN() || y.isNaN()) continue
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (minX > maxX) return null // all endpoints were NaN
        return java.awt.geom.Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY)
    }

    fun positionAt(t: Double): WorldPoint? {
        if (segments.isEmpty()) return null
        if (t <= segments[0].t0) {
            val s = segments[0]
            return WorldPoint(s.x0, s.y0, s.z0)
        }
        // Largest index whose segment starts at or before t.
        var lo = 0
        var hi = segments.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (segments[mid].t0 <= t) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return PositionInterpolator.pointOn(segments[ans], t)
    }
}
