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

/**
 * An immutable axis-aligned box in world (layout) coordinates.
 *
 * This exists so the animation replay layer carries no `java.awt.geom` dependency: the replay model and
 * its motion tracks previously reported extents as `java.awt.geom.Rectangle2D.Double`, which cannot
 * compile for a non-JVM target. A renderer that wants an AWT rectangle converts at its own boundary.
 *
 * Deliberately immutable. `Rectangle2D.Double.add` grows a box in place, and two call sites relied on
 * that; [union] returning a new box is the shape that ports, so those sites now fold instead of mutate.
 *
 * NOTE: this file is compiled twice — by KSLCore for the JVM, and by the KSLAnimationCore web module for
 * Kotlin/JS. Keep it free of JVM-only APIs, including JVM-only Kotlin stdlib members.
 *
 * @param minX the smaller x bound
 * @param minY the smaller y bound
 * @param maxX the larger x bound
 * @param maxY the larger y bound
 */
data class BoundingBox(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double
) {

    /** The extent along x. */
    val width: Double get() = maxX - minX

    /** The extent along y. */
    val height: Double get() = maxY - minY

    /** The smallest box containing both this box and [other]. */
    fun union(other: BoundingBox): BoundingBox = BoundingBox(
        minOf(minX, other.minX),
        minOf(minY, other.minY),
        maxOf(maxX, other.maxX),
        maxOf(maxY, other.maxY)
    )

    /** The smallest box containing this box and the point ([x], [y]). */
    fun including(x: Double, y: Double): BoundingBox =
        BoundingBox(minOf(minX, x), minOf(minY, y), maxOf(maxX, x), maxOf(maxY, y))

    /** This box grown by [margin] on every side. */
    fun grown(margin: Double): BoundingBox =
        BoundingBox(minX - margin, minY - margin, maxX + margin, maxY + margin)

    /** True when ([x], [y]) lies inside this box (bounds inclusive). */
    fun contains(x: Double, y: Double): Boolean = x in minX..maxX && y in minY..maxY

    companion object {

        /** The union of [a] and [b], tolerating either (or both) being null. */
        fun union(a: BoundingBox?, b: BoundingBox?): BoundingBox? = when {
            a == null -> b
            b == null -> a
            else -> a.union(b)
        }

        /**
         * The box enclosing the (x, y) pairs in [points], skipping any pair with a NaN component, or
         * null when every pair was skipped (or there were none). Coordinate-free spatial models emit NaN
         * positions that must not poison an extent, so skipping is the required behavior rather than a
         * convenience.
         */
        fun of(points: Sequence<Pair<Double, Double>>): BoundingBox? {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for ((x, y) in points) {
                if (x.isNaN() || y.isNaN()) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            return if (minX > maxX) null else BoundingBox(minX, minY, maxX, maxY)
        }
    }
}
