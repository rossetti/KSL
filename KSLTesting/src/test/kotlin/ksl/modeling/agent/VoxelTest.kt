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
 *  Phase 6.1 tests for [Voxel] and [VoxelMetric]: the 3D analog of
 *  [Cell] / [GridMetric].
 */
class VoxelTest {

    // ── Distance metrics ───────────────────────────────────────────────────

    @Test
    fun chebyshevDistanceIsMaxOfAxisDifferences() {
        val a = Voxel(0, 0, 0)
        val b = Voxel(3, 5, 4)
        // max(3, 5, 4) = 5
        assertEquals(5, a.chebyshevDistanceTo(b))
        assertEquals(5, b.chebyshevDistanceTo(a))
        // Self-distance is zero.
        assertEquals(0, a.chebyshevDistanceTo(a))
        // Mirror behavior: handles negative deltas via abs.
        assertEquals(7, Voxel(0, 0, 0).chebyshevDistanceTo(Voxel(-7, 3, 0)))
    }

    @Test
    fun manhattanDistanceIsSumOfAxisDifferences() {
        val a = Voxel(1, 2, 3)
        val b = Voxel(4, 6, 9)
        // |3| + |4| + |6| = 13
        assertEquals(13, a.manhattanDistanceTo(b))
        assertEquals(13, b.manhattanDistanceTo(a))
        assertEquals(0, a.manhattanDistanceTo(a))
    }

    @Test
    fun euclideanDistanceMatchesPythagoreanTriple() {
        // (0,0,0) to (1,2,2) has |Δ| = 3 (Pythagorean: 1+4+4=9 → 3).
        assertEquals(3.0, Voxel(0, 0, 0).euclideanDistanceTo(Voxel(1, 2, 2)), 1e-9)
        // (0,0,0) to (3,4,12) has |Δ| = 13.
        assertEquals(13.0, Voxel(0, 0, 0).euclideanDistanceTo(Voxel(3, 4, 12)), 1e-9)
    }

    // ── Octile3D: all step combinations ────────────────────────────────────

    @Test
    fun octile3DPureOrthogonalIsManhattan() {
        // (0,0,0) to (5,0,0): five orthogonal steps, cost 5.
        assertEquals(5.0, Voxel(0, 0, 0).octileDistanceTo(Voxel(5, 0, 0)), 1e-9)
        assertEquals(7.0, Voxel(0, 0, 0).octileDistanceTo(Voxel(0, 7, 0)), 1e-9)
        assertEquals(3.0, Voxel(0, 0, 0).octileDistanceTo(Voxel(0, 0, 3)), 1e-9)
    }

    @Test
    fun octile3DPureFaceDiagonalUsesSqrt2() {
        // (0,0,0) to (4,4,0): four face-diagonal steps (col & row change together),
        // zero orthogonal, zero body-diagonal. cost = 4 * √2.
        assertEquals(4.0 * sqrt(2.0), Voxel(0, 0, 0).octileDistanceTo(Voxel(4, 4, 0)), 1e-9)
        // Same in the col-layer plane.
        assertEquals(3.0 * sqrt(2.0), Voxel(0, 0, 0).octileDistanceTo(Voxel(3, 0, 3)), 1e-9)
        // Same in the row-layer plane.
        assertEquals(5.0 * sqrt(2.0), Voxel(0, 0, 0).octileDistanceTo(Voxel(0, 5, 5)), 1e-9)
    }

    @Test
    fun octile3DPureBodyDiagonalUsesSqrt3() {
        // (0,0,0) to (k,k,k): k body-diagonal steps, cost k * √3.
        for (k in 1..6) {
            assertEquals(
                k * sqrt(3.0),
                Voxel(0, 0, 0).octileDistanceTo(Voxel(k, k, k)),
                1e-9,
                "k=$k",
            )
        }
    }

    @Test
    fun octile3DMixedPathIsOptimalCombination() {
        // (0,0,0) to (5,7,3):
        //   dMin = 3 (layer), dMid = 5 (col), dMax = 7 (row)
        //   body-diagonal: 3 steps  →  3 * √3
        //   face-diagonal: 5 - 3 = 2 steps  →  2 * √2
        //   orthogonal:    7 - 5 = 2 steps  →  2 * 1
        val expected = 3.0 * sqrt(3.0) + 2.0 * sqrt(2.0) + 2.0
        assertEquals(expected, Voxel(0, 0, 0).octileDistanceTo(Voxel(5, 7, 3)), 1e-9)
    }

    @Test
    fun octile3DIsSymmetricAndLessThanOrEqualEuclidean() {
        // Symmetry.
        val a = Voxel(1, 2, 3)
        val b = Voxel(7, 11, 5)
        assertEquals(a.octileDistanceTo(b), b.octileDistanceTo(a), 1e-9)
        // Octile3D ≥ Euclidean (Euclidean is a lower bound for any
        // discrete-step path; octile3D is the exact min over those
        // steps).
        assertTrue(a.octileDistanceTo(b) >= a.euclideanDistanceTo(b) - 1e-9)
    }

    @Test
    fun octile3DSelfIsZero() {
        assertEquals(0.0, Voxel(1, 2, 3).octileDistanceTo(Voxel(1, 2, 3)))
    }

    // ── Data-class basics ──────────────────────────────────────────────────

    @Test
    fun dataClassEqualityAndHash() {
        assertEquals(Voxel(1, 2, 3), Voxel(1, 2, 3))
        assertTrue(Voxel(1, 2, 3) != Voxel(1, 2, 4))
        assertEquals(Voxel(1, 2, 3).hashCode(), Voxel(1, 2, 3).hashCode())
    }

    // ── Enum sanity ────────────────────────────────────────────────────────

    @Test
    fun voxelMetricEnumHasExpectedValues() {
        assertEquals(3, VoxelMetric.entries.size)
        assertEquals(VoxelMetric.CHEBYSHEV, VoxelMetric.valueOf("CHEBYSHEV"))
        assertEquals(VoxelMetric.MANHATTAN, VoxelMetric.valueOf("MANHATTAN"))
        assertEquals(VoxelMetric.EUCLIDEAN, VoxelMetric.valueOf("EUCLIDEAN"))
    }
}
