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

import ksl.examples.general.agent.BuildingEvacuationExample
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.sqrt
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 *  Regression tests for the Phase 3.6 grid-graph abstraction:
 *   - Shortest path basics on uniform-cost grids (Moore + Von Neumann)
 *   - Pathfinding around obstacles
 *   - Variable-cost terrain affecting path choice
 *   - A* matches Dijkstra with admissible heuristic
 *   - Multi-source distance fields
 *   - Reachability
 *   - Torus wrapping
 *   - Movement rule (Moore vs. Von Neumann) honored
 */
class GridGraphTest {

    // ── Shortest path basics ───────────────────────────────────────────────

    @Test
    fun shortestPathSameCellIsTrivial() {
        val g = GridGraph(5, 5)
        val p = g.shortestPath(Cell(2, 2), Cell(2, 2))
        assertNotNull(p)
        assertEquals(listOf(Cell(2, 2)), p!!.nodes)
        assertEquals(0.0, p.totalWeight, 1e-9)
    }

    /**
     *  On a 5x5 uniform grid with Moore movement, the shortest path
     *  from (0,0) to (4,4) is 4 diagonal steps, total cost 4·√2.
     */
    @Test
    fun shortestPathOnUniformMooreGridUsesDiagonals() {
        val g = GridGraph(5, 5, movementRule = MovementRule.MOORE)
        val p = g.shortestPath(Cell(0, 0), Cell(4, 4))
        assertNotNull(p)
        assertEquals(5, p!!.nodes.size, "expected 4 steps = 5 cells in path")
        assertEquals(4.0 * sqrt(2.0), p.totalWeight, 1e-9)
    }

    /**
     *  Under Von Neumann (4-way) movement, no diagonals — the path
     *  from (0,0) to (4,4) is 8 orthogonal steps, total cost 8.0.
     */
    @Test
    fun shortestPathOnUniformVonNeumannGridIsOrthogonal() {
        val g = GridGraph(5, 5, movementRule = MovementRule.VON_NEUMANN)
        val p = g.shortestPath(Cell(0, 0), Cell(4, 4))
        assertNotNull(p)
        assertEquals(9, p!!.nodes.size, "expected 8 steps = 9 cells in path")
        assertEquals(8.0, p.totalWeight, 1e-9)
        // Every consecutive pair should differ by exactly one in one axis.
        for (i in 0 until p.nodes.size - 1) {
            val a = p.nodes[i]; val b = p.nodes[i + 1]
            val dc = kotlin.math.abs(a.col - b.col)
            val dr = kotlin.math.abs(a.row - b.row)
            assertEquals(1, dc + dr, "Von Neumann path should have only orthogonal steps")
        }
    }

    // ── Obstacles ──────────────────────────────────────────────────────────

    @Test
    fun shortestPathUnreachableThroughBlockedRowReturnsNull() {
        val g = GridGraph(5, 5)
        // Block the entire middle row, separating top from bottom.
        for (col in 0 until 5) g.block(Cell(col, 2))
        val p = g.shortestPath(Cell(0, 0), Cell(0, 4))
        assertNull(p, "no path through a fully-blocked row")
        assertEquals(Double.POSITIVE_INFINITY, g.shortestPathLength(Cell(0, 0), Cell(0, 4)))
    }

    @Test
    fun shortestPathAroundObstacleCostsMore() {
        val g = GridGraph(5, 5, movementRule = MovementRule.VON_NEUMANN)
        // No obstacle: from (0,0) to (4,0) is 4 cells, cost 4.
        val direct = g.shortestPathLength(Cell(0, 0), Cell(4, 0))
        assertEquals(4.0, direct, 1e-9)

        // Block the direct row except the start and target.
        for (col in 1..3) g.block(Cell(col, 0))
        val around = g.shortestPath(Cell(0, 0), Cell(4, 0))
        assertNotNull(around)
        // Must detour through row 1 at least: 0,0 → 0,1 → 1,1 → 2,1 → 3,1 → 4,1 → 4,0 = 6 steps.
        assertEquals(6.0, around!!.totalWeight, 1e-9)
    }

    // ── Variable terrain ───────────────────────────────────────────────────

    @Test
    fun shortestPathPrefersCheaperTerrain() {
        // 1x5 strip + a parallel strip with cheap cost.
        //   Row 0: cells (0..4) at cost 1.0 each (default).
        //   Row 1: cells (0..4) at cost 0.1 each (much cheaper).
        // Path from (0,0) to (4,0): direct = 4.0. Detour = 0.1*4 + 1.0 + 1.0 = 2.4.
        // (entry to (0,1) costs 0.1, four cheap steps, then re-entry to row 0.)
        val g = GridGraph(5, 2, movementRule = MovementRule.VON_NEUMANN)
        for (col in 0 until 5) g.setCellCost(Cell(col, 1), 0.1)
        val p = g.shortestPath(Cell(0, 0), Cell(4, 0))
        assertNotNull(p)
        // The cheap route goes (0,0) → (0,1) → (1,1) → (2,1) → (3,1) → (4,1) → (4,0).
        // Costs entered: 0.1, 0.1, 0.1, 0.1, 0.1, 1.0 = 1.5
        assertEquals(1.5, p!!.totalWeight, 1e-9)
    }

    // ── A* equivalence ─────────────────────────────────────────────────────

    @Test
    fun aStarWithAdmissibleHeuristicReturnsSamePathAsDijkstra() {
        val g = GridGraph(10, 10, movementRule = MovementRule.MOORE)
        // Add a few obstacles to make routing non-trivial.
        g.block(Cell(3, 3)); g.block(Cell(3, 4)); g.block(Cell(3, 5))
        g.block(Cell(4, 5)); g.block(Cell(5, 5))

        val dijkstra = g.shortestPath(Cell(0, 0), Cell(9, 9))
        val aStarOctile = g.shortestPath(Cell(0, 0), Cell(9, 9), GridHeuristics.OCTILE)

        assertNotNull(dijkstra); assertNotNull(aStarOctile)
        assertEquals(dijkstra!!.totalWeight, aStarOctile!!.totalWeight, 1e-9)
        // Paths may differ by tie-breaking but cost must be identical.
    }

    @Test
    fun aStarZeroHeuristicEqualsDijkstra() {
        val g = GridGraph(8, 8)
        g.block(Cell(2, 2)); g.block(Cell(5, 5))
        val dijkstra = g.shortestPath(Cell(0, 0), Cell(7, 7))
        val aStarZero = g.shortestPath(Cell(0, 0), Cell(7, 7), GridHeuristics.ZERO)
        assertEquals(dijkstra, aStarZero)
    }

    // ── Distance fields ────────────────────────────────────────────────────

    @Test
    fun distanceFieldFromSingleSource() {
        val g = GridGraph(5, 5, movementRule = MovementRule.VON_NEUMANN)
        val field = g.distanceField(Cell(0, 0))
        // (0,0) → 0.0 ; (0,1) → 1.0 ; (4,4) → 8.0 (Manhattan distance on Von Neumann uniform grid)
        assertEquals(0.0, field[Cell(0, 0)]!!, 1e-9)
        assertEquals(1.0, field[Cell(1, 0)]!!, 1e-9)
        assertEquals(1.0, field[Cell(0, 1)]!!, 1e-9)
        assertEquals(8.0, field[Cell(4, 4)]!!, 1e-9)
    }

    @Test
    fun multiSourceDistanceFieldUsesNearestSource() {
        val g = GridGraph(5, 5, movementRule = MovementRule.VON_NEUMANN)
        // Two sources at corners (0,0) and (4,4). The midpoint (2,2)
        // should be 4 away from each (Manhattan), so dist=4 either way.
        val field = g.distanceField(setOf(Cell(0, 0), Cell(4, 4)))
        assertEquals(0.0, field[Cell(0, 0)]!!, 1e-9)
        assertEquals(0.0, field[Cell(4, 4)]!!, 1e-9)
        assertEquals(4.0, field[Cell(2, 2)]!!, 1e-9)
        // (0,4) is distance 4 from both sources (Manhattan).
        assertEquals(4.0, field[Cell(0, 4)]!!, 1e-9)
        // (1,0) is distance 1 from source (0,0); distance 7 from (4,4); min = 1.
        assertEquals(1.0, field[Cell(1, 0)]!!, 1e-9)
    }

    @Test
    fun distanceFieldOmitsBlockedAndUnreachableCells() {
        val g = GridGraph(5, 5, movementRule = MovementRule.VON_NEUMANN)
        // Block the entire middle column, separating left from right.
        for (row in 0 until 5) g.block(Cell(2, row))
        val field = g.distanceField(Cell(0, 0))
        // (0,0) is the source: dist 0.
        assertEquals(0.0, field[Cell(0, 0)]!!, 1e-9)
        // (1,0) is one step from source: dist 1.
        assertEquals(1.0, field[Cell(1, 0)]!!, 1e-9)
        // Blocked cell: absent from field.
        assertTrue(Cell(2, 0) !in field)
        // (3,0) and beyond: unreachable, absent from field.
        assertTrue(Cell(3, 0) !in field)
        assertTrue(Cell(4, 4) !in field)
    }

    // ── Reachability ───────────────────────────────────────────────────────

    @Test
    fun reachableFromEmptyForBlockedStart() {
        val g = GridGraph(3, 3)
        g.block(Cell(1, 1))
        assertEquals(emptySet<Cell>(), g.reachableFrom(Cell(1, 1)))
    }

    @Test
    fun reachableFromFullGridWithNoBlocks() {
        val g = GridGraph(3, 3)
        val r = g.reachableFrom(Cell(1, 1))
        assertEquals(9, r.size)  // every cell reachable
    }

    @Test
    fun isReachableHandlesPartition() {
        val g = GridGraph(5, 5, movementRule = MovementRule.VON_NEUMANN)
        for (row in 0 until 5) g.block(Cell(2, row))
        assertTrue(g.isReachable(Cell(0, 0), Cell(1, 4)), "same-side reachable")
        assertTrue(!g.isReachable(Cell(0, 0), Cell(4, 0)), "cross-partition unreachable")
        assertTrue(!g.isReachable(Cell(0, 0), Cell(2, 0)), "blocked cell is unreachable")
    }

    // ── Boundary semantics ─────────────────────────────────────────────────

    @Test
    fun torusGridWrapsCoordinatesInPath() {
        val g = GridGraph(5, 5, torus = true, movementRule = MovementRule.VON_NEUMANN)
        // On a 5x5 torus, the distance from (0,0) to (4,0) going forward
        // is 4 steps, but going backward (wrapping) is just 1 step.
        val p = g.shortestPath(Cell(0, 0), Cell(4, 0))
        assertNotNull(p)
        assertEquals(1.0, p!!.totalWeight, 1e-9, "torus should wrap")
    }

    @Test
    fun nonTorusOutOfBoundsThrowsOnNormalize() {
        val g = GridGraph(5, 5, torus = false)
        try {
            g.shortestPath(Cell(-1, 0), Cell(0, 0))
            error("expected IllegalArgumentException for out-of-range cell")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ── Block / unblock ────────────────────────────────────────────────────

    @Test
    fun unblockRestoresReachability() {
        val g = GridGraph(5, 5, movementRule = MovementRule.VON_NEUMANN)
        for (row in 0 until 5) g.block(Cell(2, row))
        assertTrue(!g.isReachable(Cell(0, 0), Cell(4, 0)))
        g.unblock(Cell(2, 2))  // open one cell
        assertTrue(g.isReachable(Cell(0, 0), Cell(4, 0)), "single opening restores connectivity")
    }

    @Test
    fun blockedCountTracksBlockedCells() {
        val g = GridGraph(5, 5)
        assertEquals(0, g.blockedCount)
        g.block(Cell(0, 0)); g.block(Cell(1, 1)); g.block(Cell(2, 2))
        assertEquals(3, g.blockedCount)
        g.unblock(Cell(1, 1))
        assertEquals(2, g.blockedCount)
        // Idempotent: unblocking a non-blocked cell is a no-op.
        g.unblock(Cell(4, 4))
        assertEquals(2, g.blockedCount)
    }

    // ── Movement-rule sanity ───────────────────────────────────────────────

    @Test
    fun mooreMovementHasEightNeighbors() {
        val g = GridGraph(5, 5, movementRule = MovementRule.MOORE)
        assertEquals(8, g.passableNeighbors(Cell(2, 2)).size)
    }

    @Test
    fun vonNeumannMovementHasFourNeighbors() {
        val g = GridGraph(5, 5, movementRule = MovementRule.VON_NEUMANN)
        assertEquals(4, g.passableNeighbors(Cell(2, 2)).size)
    }

    @Test
    fun blockedNeighborsAreExcluded() {
        val g = GridGraph(5, 5, movementRule = MovementRule.VON_NEUMANN)
        g.block(Cell(2, 1))
        val nbrs = g.passableNeighbors(Cell(2, 2))
        // Without the block, Von Neumann gives 4 neighbors. Blocking (2,1) leaves 3.
        assertEquals(3, nbrs.size)
        assertTrue(Cell(2, 1) !in nbrs)
        assertContains(nbrs, Cell(1, 2))
        assertContains(nbrs, Cell(3, 2))
        assertContains(nbrs, Cell(2, 3))
    }

    // ── Cell-cost API ──────────────────────────────────────────────────────

    @Test
    fun cellCostOfDefaultsToOne() {
        val g = GridGraph(3, 3)
        assertEquals(1.0, g.cellCostOf(Cell(0, 0)))
        assertEquals(1.0, g.cellCostOf(Cell(2, 2)))
    }

    @Test
    fun setCellCostStoresAndReturns() {
        val g = GridGraph(3, 3)
        g.setCellCost(Cell(1, 1), 5.0)
        assertEquals(5.0, g.cellCostOf(Cell(1, 1)))
        // Setting back to 1.0 removes the explicit entry (implementation detail
        // — the observable behavior is identical to the default).
        g.setCellCost(Cell(1, 1), 1.0)
        assertEquals(1.0, g.cellCostOf(Cell(1, 1)))
    }

    @Test
    fun setCellCostNegativeThrows() {
        val g = GridGraph(3, 3)
        try {
            g.setCellCost(Cell(0, 0), -1.0)
            error("expected IllegalArgumentException for negative cost")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ── BuildingEvacuationExample smoke test ────────────────────────────────

    @Test
    fun buildingEvacuationExampleEmptiesBuilding() {
        val model = Model("EvacuationSmokeTest")
        val sys = BuildingEvacuationExample(model, "evac")
        sys.population = 20
        model.lengthOfReplication = 100.0
        model.numberOfReplications = 1
        model.simulate()

        // 20 pedestrians on a 15x15 grid with a doorway gap. Over
        // 100 time units they should all evacuate (worst-case path
        // is ~21 octile steps from far corner to nearest exit).
        assertEquals(
            0, sys.pedestrians.size,
            "all pedestrians should have evacuated; ${sys.pedestrians.size} remain",
        )
        // Average evacuation time is positive and bounded by the
        // grid's diameter (~21 octile steps + the queueing delay
        // at the doorway).
        val avg = sys.evacuationTime.acrossReplicationStatistic.average
        assertTrue(avg > 0.0, "average evacuation time should be positive; got $avg")
        assertTrue(avg < 30.0, "average evacuation time should be < 30; got $avg")
    }
}
