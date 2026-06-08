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

package ksl.modeling.agent

import kotlin.math.sqrt

/**
 *  A 3D point, used by the agent layer's volumetric projections
 *  (introduced in Phase 6 for UAV / drone modeling).
 *
 *  Mirrors [Point2D] in shape — same arithmetic operators, same
 *  `magnitude` / `normalized()` / distance helpers — extended to a
 *  third axis. The third coordinate is named [z] and conventionally
 *  represents altitude in drone models, but the type is geometry-
 *  agnostic: any 3D Cartesian context works.
 *
 *  Defined as a lightweight data class independent of KSL's
 *  existing [ksl.modeling.spatial.LocationIfc] hierarchy, matching
 *  the design decision in [Point2D]. Integration with a 3D
 *  spatial-layer model is deferred (see §13.5 of the agent-based-
 *  modeling design doc — the GPS / lat-lon use case is unsupported
 *  by this phase).
 *
 *  Equality note: as a `data class`, `==` uses `Double.equals` per
 *  component, which follows IEEE-754 corner cases — `-0.0` is not equal
 *  to `0.0`, and any point containing `NaN` is not equal to itself.
 *  For position comparisons prefer [distanceTo] with a tolerance.
 */
data class Point3D(val x: Double, val y: Double, val z: Double) {

    /**
     *  Euclidean distance to [other]. Uses a numerically-stable form
     *  that avoids overflow for very large coordinates: factor out
     *  the largest absolute component, then take the root of the sum
     *  of squared ratios.
     */
    fun distanceTo(other: Point3D): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        // hypot3-style: scale by the largest |component| so the sum
        // of squares can't overflow even if one component is huge.
        val ax = if (dx < 0.0) -dx else dx
        val ay = if (dy < 0.0) -dy else dy
        val az = if (dz < 0.0) -dz else dz
        val m = maxOf(ax, ay, az)
        if (m == 0.0) return 0.0
        val rx = dx / m; val ry = dy / m; val rz = dz / m
        return m * sqrt(rx * rx + ry * ry + rz * rz)
    }

    /**
     *  Squared Euclidean distance to [other]. Faster than [distanceTo]
     *  when only comparison is needed (no square root).
     */
    fun squaredDistanceTo(other: Point3D): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }

    operator fun plus(other: Point3D): Point3D =
        Point3D(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Point3D): Point3D =
        Point3D(x - other.x, y - other.y, z - other.z)

    operator fun times(scalar: Double): Point3D =
        Point3D(x * scalar, y * scalar, z * scalar)

    /**
     *  Dot product with [other], treating both as vectors from the
     *  origin.
     */
    fun dot(other: Point3D): Double =
        x * other.x + y * other.y + z * other.z

    /**
     *  Cross product with [other], treating both as vectors from the
     *  origin. Returns the right-hand-rule perpendicular: `(this ×
     *  other)`. Order matters — `a × b = -(b × a)`.
     */
    fun cross(other: Point3D): Point3D = Point3D(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    /**
     *  Magnitude of this point treated as a vector from the origin.
     */
    val magnitude: Double
        get() {
            val ax = if (x < 0.0) -x else x
            val ay = if (y < 0.0) -y else y
            val az = if (z < 0.0) -z else z
            val m = maxOf(ax, ay, az)
            if (m == 0.0) return 0.0
            val rx = x / m; val ry = y / m; val rz = z / m
            return m * sqrt(rx * rx + ry * ry + rz * rz)
        }

    /**
     *  Unit vector in the same direction as this point (treated as a
     *  vector from the origin). Returns the origin if this point is
     *  the origin (no canonical direction).
     */
    fun normalized(): Point3D {
        val m = magnitude
        if (m == 0.0) return ORIGIN
        return Point3D(x / m, y / m, z / m)
    }

    companion object {
        val ORIGIN: Point3D = Point3D(0.0, 0.0, 0.0)
    }
}
