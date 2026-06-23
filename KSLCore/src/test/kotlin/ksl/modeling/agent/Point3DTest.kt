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

import org.junit.jupiter.api.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Phase 6.1 tests for [Point3D]: a 3D continuous-position value
 *  type mirroring [Point2D] with the third axis added.
 */
class Point3DTest {

    @Test
    fun distanceToIsSymmetricAndPositive() {
        val a = Point3D(1.0, 2.0, 3.0)
        val b = Point3D(4.0, 6.0, 15.0)
        // Δ = (3, 4, 12); |Δ| = 13
        assertEquals(13.0, a.distanceTo(b), 1e-9)
        assertEquals(13.0, b.distanceTo(a), 1e-9)
    }

    @Test
    fun distanceToHandlesLargeMagnitudesWithoutOverflow() {
        // Inputs where dx*dx would overflow naive computation.
        val big = 1e200
        val a = Point3D(big, 0.0, 0.0)
        val b = Point3D(0.0, big, 0.0)
        // |a - b| = sqrt(2) * big
        val d = a.distanceTo(b)
        assertEquals(sqrt(2.0) * big, d, big * 1e-9)
        assertTrue(d.isFinite(), "distance should not overflow to infinity")
    }

    @Test
    fun distanceToSelfIsZero() {
        val p = Point3D(7.5, -3.2, 100.0)
        assertEquals(0.0, p.distanceTo(p))
    }

    @Test
    fun squaredDistanceToMatchesDistanceSquared() {
        val a = Point3D(1.0, 2.0, 3.0)
        val b = Point3D(4.0, 6.0, 8.0)
        val d = a.distanceTo(b)
        assertEquals(d * d, a.squaredDistanceTo(b), 1e-9)
    }

    @Test
    fun plusMinusTimesArithmetic() {
        val a = Point3D(1.0, 2.0, 3.0)
        val b = Point3D(10.0, 20.0, 30.0)
        assertEquals(Point3D(11.0, 22.0, 33.0), a + b)
        assertEquals(Point3D(-9.0, -18.0, -27.0), a - b)
        assertEquals(Point3D(2.5, 5.0, 7.5), a * 2.5)
    }

    @Test
    fun dotProductIsCommutativeAndZeroForOrthogonal() {
        val a = Point3D(1.0, 2.0, 3.0)
        val b = Point3D(4.0, 5.0, 6.0)
        assertEquals(a.dot(b), b.dot(a), 1e-9)
        // 1*4 + 2*5 + 3*6 = 32
        assertEquals(32.0, a.dot(b), 1e-9)
        // Orthogonal axes:
        val x = Point3D(1.0, 0.0, 0.0)
        val y = Point3D(0.0, 1.0, 0.0)
        assertEquals(0.0, x.dot(y))
    }

    @Test
    fun crossProductIsAntiSymmetricAndRightHanded() {
        val x = Point3D(1.0, 0.0, 0.0)
        val y = Point3D(0.0, 1.0, 0.0)
        val z = Point3D(0.0, 0.0, 1.0)
        // Right-hand rule: x × y = z.
        assertEquals(z, x.cross(y))
        // Anti-symmetric: y × x = -z. Compare components to avoid
        // signed-zero false negatives (-0.0 != 0.0 under Double.equals).
        val negZ = y.cross(x)
        assertEquals(0.0, negZ.x, 1e-9)
        assertEquals(0.0, negZ.y, 1e-9)
        assertEquals(-1.0, negZ.z, 1e-9)
        // Self-cross is zero.
        assertEquals(Point3D.ORIGIN, x.cross(x))
    }

    @Test
    fun magnitudeMatchesDistanceToOrigin() {
        val p = Point3D(3.0, 4.0, 12.0)
        assertEquals(13.0, p.magnitude, 1e-9)
        assertEquals(p.distanceTo(Point3D.ORIGIN), p.magnitude, 1e-9)
    }

    @Test
    fun magnitudeHandlesLargeMagnitudes() {
        val big = 1e200
        val p = Point3D(big, big, 0.0)
        val m = p.magnitude
        assertEquals(sqrt(2.0) * big, m, big * 1e-9)
        assertTrue(m.isFinite())
    }

    @Test
    fun normalizedProducesUnitVector() {
        val p = Point3D(3.0, 4.0, 12.0)
        val n = p.normalized()
        assertEquals(1.0, n.magnitude, 1e-9)
        // Direction is preserved (each component is the original / 13).
        assertEquals(3.0 / 13.0, n.x, 1e-9)
        assertEquals(4.0 / 13.0, n.y, 1e-9)
        assertEquals(12.0 / 13.0, n.z, 1e-9)
    }

    @Test
    fun normalizedOriginReturnsOrigin() {
        // The origin has no canonical direction; return ORIGIN rather
        // than NaN. Matches the Point2D convention.
        assertEquals(Point3D.ORIGIN, Point3D.ORIGIN.normalized())
    }

    @Test
    fun originIsZero() {
        assertEquals(0.0, Point3D.ORIGIN.x)
        assertEquals(0.0, Point3D.ORIGIN.y)
        assertEquals(0.0, Point3D.ORIGIN.z)
    }

    @Test
    fun dataClassEquality() {
        assertEquals(Point3D(1.0, 2.0, 3.0), Point3D(1.0, 2.0, 3.0))
        assertTrue(Point3D(1.0, 2.0, 3.0) != Point3D(1.0, 2.0, 4.0))
        // hashCode contract.
        assertEquals(Point3D(1.0, 2.0, 3.0).hashCode(), Point3D(1.0, 2.0, 3.0).hashCode())
    }
}
