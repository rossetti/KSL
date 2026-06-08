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

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 *  A 2D point, used by the agent layer's spatial projections.
 *
 *  Defined here as a lightweight data class independent of KSL's
 *  existing [ksl.modeling.spatial.LocationIfc] hierarchy, which
 *  couples locations to a `SpatialModel`. Phase 3 of the agent layer
 *  introduces its own Context/Projection model that doesn't depend on
 *  `SpatialModel`; integration between the two systems is deferred
 *  to a future phase.
 *
 *  Equality note: as a `data class`, `==` uses `Double.equals` per
 *  component, which follows IEEE-754 corner cases — `Point2D(-0.0, 0.0)
 *  != Point2D(0.0, 0.0)`, and any point containing `NaN` is not equal
 *  to itself. For position comparisons prefer [distanceTo] with a
 *  tolerance rather than `==`.
 */
data class Point2D(val x: Double, val y: Double) {

    /**
     *  Euclidean distance to [other]. Uses [Math.hypot] for numerical
     *  stability when one coordinate is much larger than the other.
     */
    fun distanceTo(other: Point2D): Double = hypot(x - other.x, y - other.y)

    /**
     *  Squared Euclidean distance to [other]. Faster than [distanceTo]
     *  when only comparison is needed (no square root).
     */
    fun squaredDistanceTo(other: Point2D): Double {
        val dx = x - other.x
        val dy = y - other.y
        return dx * dx + dy * dy
    }

    operator fun plus(other: Point2D): Point2D = Point2D(x + other.x, y + other.y)
    operator fun minus(other: Point2D): Point2D = Point2D(x - other.x, y - other.y)
    operator fun times(scalar: Double): Point2D = Point2D(x * scalar, y * scalar)

    /**
     *  Magnitude of this point treated as a vector from the origin.
     */
    val magnitude: Double
        get() = sqrt(x * x + y * y)

    /**
     *  Unit vector in the same direction as this point (treated as a
     *  vector from the origin). Returns the origin if this point is
     *  the origin (no canonical direction).
     */
    fun normalized(): Point2D {
        val m = magnitude
        if (m == 0.0) return ORIGIN
        return Point2D(x / m, y / m)
    }

    companion object {
        val ORIGIN: Point2D = Point2D(0.0, 0.0)
    }
}
