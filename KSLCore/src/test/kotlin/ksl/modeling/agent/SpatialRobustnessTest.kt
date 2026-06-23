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

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Batch 4 audit fixes for the spatial layer:
 *   - H4: heuristic scaling keeps A* admissible on sub-unit cell costs.
 *   - M2: torus deltas/distances are correct for out-of-range coords.
 *   - M8: FlowField averages tied neighbor directions (no bias) and
 *     stops on a plateau.
 *   - M11: nearest(k) parity with brute force (torus 2D + 3D).
 */
class SpatialRobustnessTest {

    private open class DyModel(parent: ModelElement, torus: Boolean = false) :
        AgentModel(parent, "dymodel") {
        val ctx: Context<Agent> = Context("agents")
        val space: ContinuousProjection<Agent> =
            ContinuousProjection(ctx, xRange = 0.0..100.0, yRange = 0.0..100.0, torus = torus, cellSize = 10.0)
    }

    private open class DyVolModel(parent: ModelElement, torus: Boolean = false) :
        AgentModel(parent, "dy3d") {
        val ctx: Context<Agent> = Context("agents")
        val space: ContinuousVolume<Agent> = ContinuousVolume(
            ctx, xRange = 0.0..100.0, yRange = 0.0..100.0, zRange = 0.0..100.0,
            torus = torus, cellSize = 10.0,
        )
    }

    // ── H4: heuristic scaling for sub-unit cell costs ─────────────────────────

    @Test
    fun minCellCostReflectsCheapestCell() {
        val g = GridGraph(4, 4)
        assertEquals(1.0, g.minCellCost, 1e-12)
        g.setCellCost(Cell(1, 1), 0.25)
        assertEquals(0.25, g.minCellCost, 1e-12)
        g.setCellCost(Cell(2, 2), 3.0) // expensive cell does not lower the min
        assertEquals(0.25, g.minCellCost, 1e-12)
    }

    @Test
    fun scaledHeuristicMatchesDijkstraOnSubUnitCosts() {
        fun build(): GridGraph = GridGraph(6, 6, movementRule = MovementRule.MOORE).apply {
            // A cheap "highway" row that an optimal path should prefer.
            for (c in 0 until 6) setCellCost(Cell(c, 3), 0.1)
        }
        val g = build()
        val start = Cell(0, 0); val goal = Cell(5, 5)
        val dijkstra = g.shortestPath(start, goal, GridHeuristics.ZERO)!!
        val scaled = g.shortestPath(
            start, goal, GridHeuristics.scaled(g.minCellCost, GridHeuristics.OCTILE),
        )!!
        assertEquals(dijkstra.totalWeight, scaled.totalWeight, 1e-9,
            "scaled heuristic must stay admissible → same optimal cost as Dijkstra")
    }

    @Test
    fun scaledByZeroDegeneratesToZeroHeuristic() {
        val h = GridHeuristics.scaled(0.0, GridHeuristics.OCTILE)
        assertEquals(0.0, h(Cell(0, 0), Cell(9, 9)), 1e-12)
    }

    @Test
    fun voxelScaledHeuristicMatchesDijkstra() {
        val g = VoxelGraph(5, 5, 5).apply {
            for (c in 0 until 5) setVoxelCost(Voxel(c, 2, 2), 0.1)
        }
        assertEquals(0.1, g.minVoxelCost, 1e-12)
        val start = Voxel(0, 0, 0); val goal = Voxel(4, 4, 4)
        val dijkstra = g.shortestPath(start, goal, VoxelHeuristics.ZERO)!!
        val scaled = g.shortestPath(
            start, goal, VoxelHeuristics.scaled(g.minVoxelCost, VoxelHeuristics.OCTILE),
        )!!
        assertEquals(dijkstra.totalWeight, scaled.totalWeight, 1e-9)
    }

    // ── M2: torus delta / distance for out-of-range coordinates ───────────────

    @Test
    fun torusDeltaIsShortestEvenWhenCoordinatesAreOutOfRange() {
        val tm = DyModel(Model("m2-delta"), torus = true) // 100-wide torus
        // from=10, to=260 (2.5 spans out): wrapped positions 10 and 60 →
        // shortest signed delta is ±50, never the naive 250 or 150.
        val d = tm.space.delta(Point2D(10.0, 50.0), Point2D(260.0, 50.0))
        assertEquals(50.0, abs(d.x), 1e-9, "out-of-range torus delta must be the shortest wrap")
        assertTrue(abs(d.x) <= 50.0, "a torus delta can never exceed half the span")
    }

    @Test
    fun torusDistanceIsShortestEvenWhenCoordinatesAreOutOfRange() {
        val tm = DyModel(Model("m2-dist"), torus = true)
        // 10 and 260 → wrapped 10 and 60 → distance 50.
        val dist = tm.space.distance(Point2D(10.0, 50.0), Point2D(260.0, 50.0))
        assertEquals(50.0, dist, 1e-9)
    }

    @Test
    fun torusDelta3DIsShortestForOutOfRangeCoordinates() {
        val tm = DyVolModel(Model("m2-3d"), torus = true)
        val d = tm.space.delta(Point3D(10.0, 10.0, 10.0), Point3D(260.0, 10.0, 10.0))
        assertEquals(50.0, abs(d.x), 1e-9)
    }

    // ── M8: FlowField tie averaging + plateau stop ────────────────────────────

    @Test
    fun flowFieldAveragesTiedNeighborDirections() {
        val g = GridGraph(3, 3, movementRule = MovementRule.VON_NEUMANN)
        val field = FlowField(g, setOf(Cell(0, 0)), cellSize = 1.0, origin = Point2D.ORIGIN)
        // At Cell(1,1) the two best neighbors (1,0) and (0,1) tie. The
        // averaged direction is the 45° diagonal toward the source, not an
        // axis snapped by iteration order.
        val dir = field.directionAt(field.centerOf(Cell(1, 1)))
        assertEquals(1.0, dir.magnitude, 1e-9, "direction must be a unit vector")
        assertEquals(dir.x, dir.y, 1e-9, "tied up+left must average to a symmetric diagonal")
        assertTrue(dir.x < 0.0 && dir.y < 0.0, "diagonal should point toward the source; got $dir")
    }

    @Test
    fun flowFieldStopsOnAFlatPlateau() {
        val g = GridGraph(3, 3, movementRule = MovementRule.VON_NEUMANN)
        // A zero-cost row makes the whole row equidistant (flat) from the source.
        for (c in 0 until 3) g.setCellCost(Cell(c, 1), 0.0)
        val field = FlowField(g, setOf(Cell(0, 1)), cellSize = 1.0, origin = Point2D.ORIGIN)
        // Cell(2,1) is on the flat row (field value 0) but is not a source:
        // no neighbor strictly improves, so the agent stops.
        val dir = field.directionAt(field.centerOf(Cell(2, 1)))
        assertEquals(Point2D.ORIGIN, dir, "a flat plateau yields a stop")
    }

    // ── M11: nearest(k) parity with brute force ───────────────────────────────

    @Test
    fun nearestOnTorusMatchesBruteForce() {
        val tm = DyModel(Model("m11-2d"), torus = true)
        val rng = java.util.Random(2024)
        val agents = (0 until 150).map { tm.Agent("a$it") }
        agents.forEach { tm.ctx.add(it) }
        val placements = agents.associateWith {
            Point2D(rng.nextDouble() * 100.0, rng.nextDouble() * 100.0)
        }
        for ((a, p) in placements) tm.space.placeAt(a, p)

        for (k in listOf(1, 5, 20)) {
            val center = Point2D(50.0, 50.0)
            val expected = placements.entries
                .sortedBy { tm.space.distance(center, it.value) }
                .map { it.key }.take(k)
            val actual = tm.space.nearest(center, k)
            assertEquals(expected, actual, "torus nearest(k=$k) must match brute force")
        }
    }

    @Test
    fun nearest3DMatchesBruteForce() {
        val tm = DyVolModel(Model("m11-3d"))
        val rng = java.util.Random(99)
        val agents = (0 until 120).map { tm.Agent("a$it") }
        agents.forEach { tm.ctx.add(it) }
        val placements = agents.associateWith {
            Point3D(rng.nextDouble() * 100.0, rng.nextDouble() * 100.0, rng.nextDouble() * 100.0)
        }
        for ((a, p) in placements) tm.space.placeAt(a, p)

        for (k in listOf(1, 7, 30)) {
            val center = Point3D(50.0, 50.0, 50.0)
            val expected = placements.entries
                .sortedBy { tm.space.distance(center, it.value) }
                .map { it.key }.take(k)
            val actual = tm.space.nearest(center, k)
            assertEquals(expected, actual, "3D nearest(k=$k) must match brute force")
        }
    }
}
