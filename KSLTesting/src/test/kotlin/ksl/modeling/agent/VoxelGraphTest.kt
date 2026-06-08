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
import org.junit.jupiter.api.assertThrows
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Phase 6.3 tests for [VoxelGraph] — the 3D analog of [GridGraph].
 *  Verifies movement-rule neighbor counts, edge weights for the three
 *  step types, Dijkstra and A* shortest-path correctness (including
 *  routing around 3D obstacles), distance fields, and reachability.
 */
class VoxelGraphTest {

    // ── Construction ───────────────────────────────────────────────────────

    @Test
    fun constructorRejectsNonPositiveDimensions() {
        assertThrows<IllegalArgumentException> { VoxelGraph(0, 5, 5) }
        assertThrows<IllegalArgumentException> { VoxelGraph(5, 0, 5) }
        assertThrows<IllegalArgumentException> { VoxelGraph(5, 5, 0) }
    }

    @Test
    fun voxelCountIsProductOfDims() {
        val g = VoxelGraph(3, 4, 5)
        assertEquals(60, g.voxelCount)
    }

    // ── Voxel costs and blocking ───────────────────────────────────────────

    @Test
    fun voxelCostDefaultsToOne() {
        val g = VoxelGraph(5, 5, 5)
        assertEquals(1.0, g.voxelCostOf(Voxel(0, 0, 0)))
    }

    @Test
    fun setVoxelCostRejectsNegative() {
        val g = VoxelGraph(5, 5, 5)
        assertThrows<IllegalArgumentException> { g.setVoxelCost(Voxel(0, 0, 0), -0.5) }
    }

    @Test
    fun blockAndUnblock() {
        val g = VoxelGraph(5, 5, 5)
        assertTrue(g.isPassable(Voxel(2, 2, 2)))
        g.block(Voxel(2, 2, 2))
        assertFalse(g.isPassable(Voxel(2, 2, 2)))
        assertEquals(1, g.blockedCount)
        g.unblock(Voxel(2, 2, 2))
        assertTrue(g.isPassable(Voxel(2, 2, 2)))
        assertEquals(0, g.blockedCount)
    }

    @Test
    fun isPassableFalseForOutOfBounds() {
        val g = VoxelGraph(5, 5, 5)
        assertFalse(g.isPassable(Voxel(-1, 0, 0)))
        assertFalse(g.isPassable(Voxel(0, 0, 5)))
    }

    // ── Movement rules / neighbors ─────────────────────────────────────────

    @Test
    fun moore26NeighborsCountAtInterior() {
        val g = VoxelGraph(10, 10, 10, movementRule = VoxelMovementRule.MOORE_26)
        assertEquals(26, g.passableNeighbors(Voxel(5, 5, 5)).size)
    }

    @Test
    fun vonNeumann6NeighborsCountAtInterior() {
        val g = VoxelGraph(10, 10, 10, movementRule = VoxelMovementRule.VON_NEUMANN_6)
        assertEquals(6, g.passableNeighbors(Voxel(5, 5, 5)).size)
    }

    @Test
    fun mooreNeighborsAtCornerExcludesOutOfBounds() {
        val g = VoxelGraph(5, 5, 5, movementRule = VoxelMovementRule.MOORE_26)
        // Corner has only 7 neighbors (a 2×2×2 cube minus self).
        assertEquals(7, g.passableNeighbors(Voxel(0, 0, 0)).size)
    }

    @Test
    fun mooreNeighborsOnTorusWraps() {
        val g = VoxelGraph(5, 5, 5, torus = true, movementRule = VoxelMovementRule.MOORE_26)
        assertEquals(26, g.passableNeighbors(Voxel(0, 0, 0)).size)
    }

    @Test
    fun neighborsExcludeBlocked() {
        val g = VoxelGraph(5, 5, 5)
        g.block(Voxel(1, 0, 0))
        val ns = g.passableNeighbors(Voxel(0, 0, 0))
        assertFalse(Voxel(1, 0, 0) in ns)
    }

    // ── Edge weights: three step types ─────────────────────────────────────

    @Test
    fun edgeWeightOrthogonalIs1() {
        val g = VoxelGraph(5, 5, 5)
        assertEquals(1.0, g.edgeWeight(Voxel(0, 0, 0), Voxel(1, 0, 0)), 1e-9)
        assertEquals(1.0, g.edgeWeight(Voxel(0, 0, 0), Voxel(0, 1, 0)), 1e-9)
        assertEquals(1.0, g.edgeWeight(Voxel(0, 0, 0), Voxel(0, 0, 1)), 1e-9)
    }

    @Test
    fun edgeWeightFaceDiagonalIsSqrt2() {
        val g = VoxelGraph(5, 5, 5)
        // Two axes differ → face-diagonal.
        assertEquals(sqrt(2.0), g.edgeWeight(Voxel(0, 0, 0), Voxel(1, 1, 0)), 1e-9)
        assertEquals(sqrt(2.0), g.edgeWeight(Voxel(0, 0, 0), Voxel(1, 0, 1)), 1e-9)
        assertEquals(sqrt(2.0), g.edgeWeight(Voxel(0, 0, 0), Voxel(0, 1, 1)), 1e-9)
    }

    @Test
    fun edgeWeightBodyDiagonalIsSqrt3() {
        val g = VoxelGraph(5, 5, 5)
        // Three axes differ → body-diagonal.
        assertEquals(sqrt(3.0), g.edgeWeight(Voxel(0, 0, 0), Voxel(1, 1, 1)), 1e-9)
    }

    @Test
    fun edgeWeightScalesWithDestVoxelCost() {
        val g = VoxelGraph(5, 5, 5)
        g.setVoxelCost(Voxel(1, 0, 0), 5.0)
        // Orthogonal step into a cost-5 cell: weight 5.0.
        assertEquals(5.0, g.edgeWeight(Voxel(0, 0, 0), Voxel(1, 0, 0)), 1e-9)
        // Body-diagonal into a cost-5 cell: 5 * √3.
        g.setVoxelCost(Voxel(1, 1, 1), 5.0)
        assertEquals(5.0 * sqrt(3.0), g.edgeWeight(Voxel(0, 0, 0), Voxel(1, 1, 1)), 1e-9)
    }

    // ── Shortest path ──────────────────────────────────────────────────────

    @Test
    fun shortestPathSelfReturnsLengthZero() {
        val g = VoxelGraph(5, 5, 5)
        val path = g.shortestPath(Voxel(2, 2, 2), Voxel(2, 2, 2))
        assertNotNull(path)
        assertEquals(0.0, path.totalWeight)
        assertEquals(listOf(Voxel(2, 2, 2)), path.nodes)
    }

    @Test
    fun shortestPathUsesBodyDiagonalsWhenAllowed() {
        val g = VoxelGraph(10, 10, 10, movementRule = VoxelMovementRule.MOORE_26)
        // (0,0,0) → (3,3,3): three body-diagonal steps, length 3√3.
        val path = g.shortestPath(Voxel(0, 0, 0), Voxel(3, 3, 3))
        assertNotNull(path)
        assertEquals(3.0 * sqrt(3.0), path.totalWeight, 1e-9)
        assertEquals(4, path.nodes.size)
    }

    @Test
    fun shortestPathMatchesAStarWithOctileHeuristic() {
        val g = VoxelGraph(10, 10, 10, movementRule = VoxelMovementRule.MOORE_26)
        val dijkstra = g.shortestPath(Voxel(0, 0, 0), Voxel(7, 5, 3))!!
        val aStar = g.shortestPath(Voxel(0, 0, 0), Voxel(7, 5, 3), VoxelHeuristics.OCTILE)!!
        assertEquals(dijkstra.totalWeight, aStar.totalWeight, 1e-9)
    }

    @Test
    fun shortestPathVonNeumannUsesOnlyOrthogonalSteps() {
        val g = VoxelGraph(5, 5, 5, movementRule = VoxelMovementRule.VON_NEUMANN_6)
        // (0,0,0) → (2,2,2): six orthogonal steps, length 6.
        val path = g.shortestPath(Voxel(0, 0, 0), Voxel(2, 2, 2))
        assertNotNull(path)
        assertEquals(6.0, path.totalWeight, 1e-9)
    }

    @Test
    fun shortestPathRoutesAroundBlockedVoxels() {
        val g = VoxelGraph(5, 5, 1, movementRule = VoxelMovementRule.MOORE_26)
        // Wall at col=2 across all rows in layer 0; force the path
        // to go around.
        for (r in 0 until 5) g.block(Voxel(2, r, 0))
        // Path from (0,0,0) → (4,0,0) is blocked directly; should
        // return null since the layer is exactly 1 (no vertical
        // escape).
        assertNull(g.shortestPath(Voxel(0, 0, 0), Voxel(4, 0, 0)))

        // Now allow a gap at row 2.
        g.unblock(Voxel(2, 2, 0))
        val path = g.shortestPath(Voxel(0, 0, 0), Voxel(4, 0, 0))
        assertNotNull(path)
        // Path must pass through (2, 2, 0).
        assertTrue(Voxel(2, 2, 0) in path.nodes)
    }

    @Test
    fun shortestPathReturnsNullForUnreachable() {
        val g = VoxelGraph(5, 5, 5)
        g.block(Voxel(0, 0, 0))
        assertNull(g.shortestPath(Voxel(0, 0, 0), Voxel(4, 4, 4)))
    }

    @Test
    fun shortestPathLengthOnSelf() {
        val g = VoxelGraph(5, 5, 5)
        assertEquals(0.0, g.shortestPathLength(Voxel(1, 2, 3), Voxel(1, 2, 3)))
    }

    @Test
    fun shortestPathLengthInfinityForUnreachable() {
        val g = VoxelGraph(5, 5, 5)
        g.block(Voxel(0, 0, 0))
        assertEquals(Double.POSITIVE_INFINITY, g.shortestPathLength(Voxel(0, 0, 0), Voxel(4, 4, 4)))
    }

    // ── A* heuristic admissibility (path lengths must match) ──────────────

    @Test
    fun allHeuristicsProduceSamePathLengthOnUniformCostGrid() {
        val g = VoxelGraph(8, 8, 8, movementRule = VoxelMovementRule.MOORE_26)
        val a = Voxel(0, 0, 0)
        val b = Voxel(6, 4, 7)
        val lZero = g.shortestPathLength(a, b, VoxelHeuristics.ZERO)
        val lManh = g.shortestPathLength(a, b, VoxelHeuristics.MANHATTAN)
        val lCheb = g.shortestPathLength(a, b, VoxelHeuristics.CHEBYSHEV)
        val lOct = g.shortestPathLength(a, b, VoxelHeuristics.OCTILE)
        val lEucl = g.shortestPathLength(a, b, VoxelHeuristics.EUCLIDEAN)
        // All heuristics must produce the same optimal path length
        // (they may explore different node orderings).
        for (l in listOf(lManh, lCheb, lOct, lEucl)) {
            assertEquals(lZero, l, 1e-9)
        }
    }

    // ── Distance fields ────────────────────────────────────────────────────

    @Test
    fun distanceFieldSingleSourceHasZeroAtSource() {
        val g = VoxelGraph(5, 5, 5)
        val field = g.distanceField(Voxel(2, 2, 2))
        assertEquals(0.0, field[Voxel(2, 2, 2)])
    }

    @Test
    fun distanceFieldCoversAllReachable() {
        val g = VoxelGraph(5, 5, 5)
        val field = g.distanceField(Voxel(0, 0, 0))
        // Every voxel in a fully-passable 5×5×5 grid is reachable.
        assertEquals(125, field.size)
    }

    @Test
    fun distanceFieldMultiSourceUsesNearest() {
        val g = VoxelGraph(10, 1, 1)
        // Two sources, one at each end of a 10-long line.
        val field = g.distanceField(setOf(Voxel(0, 0, 0), Voxel(9, 0, 0)))
        // Midpoints should have distance ~4–5 from nearest end.
        assertEquals(4.0, field[Voxel(4, 0, 0)])
        assertEquals(4.0, field[Voxel(5, 0, 0)])
    }

    @Test
    fun distanceFieldOmitsUnreachable() {
        val g = VoxelGraph(5, 5, 1)
        // Wall along col=2, all rows.
        for (r in 0 until 5) g.block(Voxel(2, r, 0))
        val field = g.distanceField(Voxel(0, 0, 0))
        // Voxels with col >= 3 are unreachable in this 1-layer grid.
        assertFalse(Voxel(3, 0, 0) in field)
        assertFalse(Voxel(4, 4, 0) in field)
        // Voxels with col <= 1 are reachable.
        assertTrue(Voxel(0, 0, 0) in field)
        assertTrue(Voxel(1, 4, 0) in field)
    }

    // ── Reachability ───────────────────────────────────────────────────────

    @Test
    fun reachableFromCoversAllPassable() {
        val g = VoxelGraph(3, 3, 3)
        assertEquals(27, g.reachableFrom(Voxel(0, 0, 0)).size)
    }

    @Test
    fun reachableFromEmptyForBlocked() {
        val g = VoxelGraph(3, 3, 3)
        g.block(Voxel(0, 0, 0))
        assertTrue(g.reachableFrom(Voxel(0, 0, 0)).isEmpty())
    }

    @Test
    fun isReachableTrueForConnected() {
        val g = VoxelGraph(5, 5, 5)
        assertTrue(g.isReachable(Voxel(0, 0, 0), Voxel(4, 4, 4)))
    }

    @Test
    fun isReachableFalseAcrossWall() {
        val g = VoxelGraph(5, 5, 1)
        for (r in 0 until 5) g.block(Voxel(2, r, 0))
        assertFalse(g.isReachable(Voxel(0, 0, 0), Voxel(4, 0, 0)))
    }
}
