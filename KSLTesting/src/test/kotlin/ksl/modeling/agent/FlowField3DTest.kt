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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 *  Phase 6.4 tests for [FlowField3D] — the 3D analog of [FlowField].
 *  Mirrors the FlowField test layout, extended to a third axis.
 */
class FlowField3DTest {

    // ── Basic queries ──────────────────────────────────────────────────────

    @Test
    fun flowField3DDistancesMatchDistanceField() {
        val g = VoxelGraph(5, 5, 5)
        val sources = setOf(Voxel(0, 0, 0))
        val field = FlowField3D(g, sources)

        val raw = g.distanceField(sources)
        assertEquals(raw, field.distances)
        assertEquals(0.0, field.distances[Voxel(0, 0, 0)])
        // Body-diagonal opposite corner has positive distance.
        assertTrue((field.distances[Voxel(4, 4, 4)] ?: 0.0) > 0.0)
    }

    @Test
    fun flowField3DArrivedAtIsTrueAtSourcesFalseElsewhere() {
        val g = VoxelGraph(10, 10, 5)
        val sources = setOf(Voxel(0, 0, 0), Voxel(9, 9, 4))
        val field = FlowField3D(g, sources)

        assertTrue(field.arrivedAt(field.centerOf(Voxel(0, 0, 0))))
        assertTrue(field.arrivedAt(field.centerOf(Voxel(9, 9, 4))))
        assertFalse(field.arrivedAt(field.centerOf(Voxel(5, 5, 2))))
        // Out-of-bounds is not arrived.
        assertFalse(field.arrivedAt(Point3D(-5.0, -5.0, -5.0)))
    }

    // ── directionAt routing ────────────────────────────────────────────────

    @Test
    fun flowField3DDirectionAtPointsTowardNearestSourceNoObstacles() {
        // Single source at (0, 0, 0). From any interior voxel, the
        // unit direction should have non-positive components on all
        // three axes.
        val g = VoxelGraph(6, 6, 6, movementRule = VoxelMovementRule.MOORE_26)
        val field = FlowField3D(g, setOf(Voxel(0, 0, 0)))

        for (col in 1..5) for (row in 1..5) for (layer in 1..5) {
            val p = field.centerOf(Voxel(col, row, layer))
            val dir = field.directionAt(p)
            assertTrue(
                dir.x <= 1e-9 && dir.y <= 1e-9 && dir.z <= 1e-9,
                "at ($col, $row, $layer) expected direction toward origin; got $dir",
            )
            // Magnitude is 1 (unit vector); we're far from the source.
            assertEquals(1.0, dir.magnitude, 1e-9, "non-unit direction at ($col, $row, $layer)")
        }
    }

    @Test
    fun flowField3DDirectionAtRoutesAroundBlockedVoxels() {
        // 7x3x1 grid: wall across column 3 except a gap at row 2.
        // Force the gradient to route up through (3, 2, 0).
        val g = VoxelGraph(7, 3, 1, movementRule = VoxelMovementRule.MOORE_26)
        g.block(Voxel(3, 0, 0))
        g.block(Voxel(3, 1, 0))
        val field = FlowField3D(g, setOf(Voxel(6, 0, 0)))

        val p = field.centerOf(Voxel(2, 0, 0))
        val dir = field.directionAt(p)
        // Optimal path goes northeast in xy to reach (3, 2, 0); +y component.
        assertTrue(dir.y > 0.0, "expected to route up around the wall; direction was $dir")
    }

    @Test
    fun flowField3DDirectionAtPicksAltitudeWhenItHelps() {
        // A 5x1x3 grid: wall at col=2, layer=0 blocks ground-level
        // travel. Only path from (0,0,0) → (4,0,0) goes up a layer.
        val g = VoxelGraph(5, 1, 3, movementRule = VoxelMovementRule.MOORE_26)
        g.block(Voxel(2, 0, 0))
        val field = FlowField3D(g, setOf(Voxel(4, 0, 0)))

        val p = field.centerOf(Voxel(1, 0, 0))
        val dir = field.directionAt(p)
        // To get past the col=2 wall at layer 0, the optimal step
        // goes diagonally up into (2, 0, 1) — so the z component is
        // positive.
        assertTrue(dir.z > 0.0, "expected to climb over the wall; direction was $dir")
    }

    @Test
    fun flowField3DDirectionAtSourceReturnsZero() {
        val g = VoxelGraph(5, 5, 5)
        val field = FlowField3D(g, setOf(Voxel(2, 2, 2)))
        val dir = field.directionAt(field.centerOf(Voxel(2, 2, 2)))
        assertEquals(Point3D.ORIGIN, dir)
    }

    // ── Edge cases ─────────────────────────────────────────────────────────

    @Test
    fun flowField3DDistanceAtUnreachable() {
        // Wall sealing one half of a 5x5x1 grid.
        val g = VoxelGraph(5, 5, 1, movementRule = VoxelMovementRule.VON_NEUMANN_6)
        for (r in 0 until 5) g.block(Voxel(2, r, 0))
        val field = FlowField3D(g, setOf(Voxel(4, 2, 0)))

        val unreachable = field.distanceAt(field.centerOf(Voxel(0, 0, 0)))
        assertEquals(Double.POSITIVE_INFINITY, unreachable)
        val reachable = field.distanceAt(field.centerOf(Voxel(4, 4, 0)))
        assertTrue(reachable.isFinite())
    }

    @Test
    fun flowField3DOutOfBoundsPointBehavesDefensively() {
        val g = VoxelGraph(5, 5, 5)
        val field = FlowField3D(g, setOf(Voxel(0, 0, 0)))

        val outside = Point3D(-10.0, -10.0, -10.0)
        assertEquals(Double.POSITIVE_INFINITY, field.distanceAt(outside))
        assertEquals(Point3D.ORIGIN, field.directionAt(outside))
        assertFalse(field.arrivedAt(outside))
    }

    // ── Non-unit cellSize + shifted origin ────────────────────────────────

    @Test
    fun flowField3DNonUnitCellSizeAndOrigin() {
        // cellSize = 2.0, origin = (10, 10, 5). Voxel(0,0,0) covers
        // [10,12) × [10,12) × [5,7). Center at (11, 11, 6).
        val g = VoxelGraph(5, 5, 5)
        val field = FlowField3D(
            graph = g,
            sources = setOf(Voxel(0, 0, 0)),
            cellSize = 2.0,
            origin = Point3D(10.0, 10.0, 5.0),
        )

        // Round-trip within bounds.
        assertEquals(Point3D(11.0, 11.0, 6.0), field.centerOf(Voxel(0, 0, 0)))
        assertEquals(Voxel(0, 0, 0), field.voxelOf(Point3D(11.0, 11.0, 6.0)))
        // Off-center mapping.
        assertEquals(Voxel(1, 0, 0), field.voxelOf(Point3D(12.5, 11.0, 6.0)))
        // Before origin maps to negative-index voxels (out of bounds).
        assertEquals(Voxel(-1, -1, -1), field.voxelOf(Point3D(9.0, 9.0, 4.0)))
        assertEquals(Double.POSITIVE_INFINITY, field.distanceAt(Point3D(9.0, 9.0, 4.0)))

        // arrivedAt and direction work in the shifted system.
        assertTrue(field.arrivedAt(Point3D(11.0, 11.0, 6.0)))
        assertFalse(field.arrivedAt(Point3D(15.0, 15.0, 10.0)))
        val dir = field.directionAt(Point3D(17.0, 17.0, 11.0))
        assertNotEquals(Point3D.ORIGIN, dir)
        assertTrue(
            dir.x < 0.0 && dir.y < 0.0 && dir.z < 0.0,
            "expected direction back toward the origin voxel; got $dir",
        )
    }
}
