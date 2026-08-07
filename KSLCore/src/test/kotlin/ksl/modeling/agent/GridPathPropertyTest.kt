/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

import ksl.utilities.random.rvariable.KSLRandom
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Differential (property-based) tests for grid pathfinding.
 *
 *  The governing property: **A\* with an admissible heuristic must return the same
 *  total cost as Dijkstra.** Since `GridGraph.shortestPath` with
 *  `GridHeuristics.ZERO` *is* Dijkstra, the library is its own reference
 *  implementation — no external oracle is needed to test heuristic admissibility.
 *
 *  Randomized grids are generated from KSL's own `RNStreamProvider` so every case
 *  is reproducible from its stream number.
 *
 *  Scope note: cell costs are held at or above 1.0 throughout. Costs *below* 1.0 are
 *  already documented on `GridGraph.setCellCost` as making the non-ZERO heuristics
 *  inadmissible (the remedy being `GridHeuristics.scaled` with `minCellCost`), so
 *  including them would confound a known-and-documented effect with the property
 *  under test.
 */
class GridPathPropertyTest {

    private class Heuristic(val label: String, val fn: (Cell, Cell) -> Double)

    /**
     *  Every non-ZERO heuristic `GridHeuristics` offers. After the removal of the
     *  Manhattan heuristic, all of these are admissible under *both* movement
     *  rules, so the sweeps below apply the whole set to both.
     */
    private val geometricHeuristics = listOf(
        Heuristic("CHEBYSHEV", GridHeuristics.CHEBYSHEV),
        Heuristic("EUCLIDEAN", GridHeuristics.EUCLIDEAN),
        Heuristic("OCTILE", GridHeuristics.OCTILE),
    )

    /**
     *  Build a reproducible random grid. Cell costs stay >= 1.0 (see class KDoc).
     */
    private fun randomGrid(
        streamNum: Int,
        columns: Int,
        rows: Int,
        rule: MovementRule,
        cornerCutting: Boolean,
        blockProbability: Double,
        varyCosts: Boolean,
    ): GridGraph {
        val rng = KSLRandom.rnStream(streamNum)
        val g = GridGraph(
            columns = columns,
            rows = rows,
            torus = false,
            movementRule = rule,
            allowCornerCutting = cornerCutting,
        )
        for (c in 0 until columns) {
            for (r in 0 until rows) {
                val cell = Cell(c, r)
                if (rng.randU01() < blockProbability) {
                    g.block(cell)
                } else if (varyCosts && rng.randU01() < 0.3) {
                    // Costs at or above 1.0 only.
                    g.setCellCost(cell, 1.0 + rng.randU01() * 4.0)
                }
            }
        }
        // Guarantee the corners are usable endpoints.
        g.unblock(Cell(0, 0))
        g.unblock(Cell(columns - 1, rows - 1))
        return g
    }

    /**
     *  Report of one heuristic disagreeing with Dijkstra on one grid.
     */
    private data class Divergence(
        val heuristic: String,
        val rule: MovementRule,
        val cornerCutting: Boolean,
        val streamNum: Int,
        val from: Cell,
        val to: Cell,
        val dijkstra: Double,
        val aStar: Double,
    ) {
        override fun toString(): String =
            "$heuristic on $rule (cornerCutting=$cornerCutting, stream=$streamNum) " +
                "$from->$to : dijkstra=%.6f aStar=%.6f (excess %.6f)"
                    .format(dijkstra, aStar, aStar - dijkstra)
    }

    /**
     *  Sweep a heuristic across randomized grids, returning every case where its
     *  A\* cost differs from the Dijkstra cost. An admissible heuristic yields none.
     */
    private fun divergences(
        heuristic: Heuristic,
        rule: MovementRule,
        cornerCutting: Boolean,
        varyCosts: Boolean,
        grids: Int = 12,
    ): List<Divergence> {
        val found = mutableListOf<Divergence>()
        for (i in 0 until grids) {
            val streamNum = 1 + i
            val size = 8 + (i % 5)
            val g = randomGrid(
                streamNum = streamNum,
                columns = size,
                rows = size,
                rule = rule,
                cornerCutting = cornerCutting,
                blockProbability = 0.15,
                varyCosts = varyCosts,
            )
            val from = Cell(0, 0)
            val to = Cell(size - 1, size - 1)
            val reference = g.shortestPath(from, to, GridHeuristics.ZERO)
            val candidate = g.shortestPath(from, to, heuristic.fn)

            assertEquals(
                reference == null, candidate == null,
                "${heuristic.label} disagreed with Dijkstra on reachability " +
                    "($rule, cornerCutting=$cornerCutting, stream=$streamNum)",
            )
            if (reference == null || candidate == null) continue

            if (kotlin.math.abs(reference.totalWeight - candidate.totalWeight) > 1e-9) {
                found.add(
                    Divergence(
                        heuristic.label, rule, cornerCutting, streamNum,
                        from, to, reference.totalWeight, candidate.totalWeight,
                    )
                )
            }
        }
        return found
    }

    private fun sweepAll(rule: MovementRule): List<Divergence> {
        val all = mutableListOf<Divergence>()
        for (h in geometricHeuristics) {
            for (cornerCutting in listOf(false, true)) {
                all += divergences(h, rule, cornerCutting, varyCosts = false)
                all += divergences(h, rule, cornerCutting, varyCosts = true)
            }
        }
        return all
    }

    // ── The governing property ───────────────────────────────────────────────

    @Test
    @DisplayName("A* equals Dijkstra for every offered heuristic on Moore grids")
    fun everyHeuristicMatchesDijkstraOnMooreGrids() {
        val all = sweepAll(MovementRule.MOORE)
        assertTrue(all.isEmpty(), "A* diverged from Dijkstra:\n" + all.joinToString("\n"))
    }

    @Test
    @DisplayName("A* equals Dijkstra for every offered heuristic on Von Neumann grids")
    fun everyHeuristicMatchesDijkstraOnVonNeumannGrids() {
        val all = sweepAll(MovementRule.VON_NEUMANN)
        assertTrue(all.isEmpty(), "A* diverged from Dijkstra:\n" + all.joinToString("\n"))
    }

    // ── Lower-bound guarantees ───────────────────────────────────────────────

    /**
     *  Every heuristic `GridHeuristics` offers must be a lower bound on the true
     *  cost, over every endpoint pair, under *both* movement rules. This is the
     *  invariant that lets a modeler pick any of them without consulting a table.
     */
    @Test
    @DisplayName("Every offered heuristic is a lower bound under both movement rules")
    fun offeredHeuristicsAreLowerBounds() {
        for (rule in MovementRule.entries) {
            val g = GridGraph(10, 10, movementRule = rule)
            val from = Cell(0, 0)
            for (c in 0 until 10) {
                for (r in 0 until 10) {
                    val to = Cell(c, r)
                    val trueCost = g.shortestPath(from, to, GridHeuristics.ZERO)!!.totalWeight
                    for (h in geometricHeuristics) {
                        val estimate = h.fn(from, to)
                        assertTrue(
                            estimate <= trueCost + 1e-9,
                            "${h.label} over-estimated $from->$to on $rule: " +
                                "est=$estimate true=$trueCost",
                        )
                    }
                }
            }
        }
    }

    /**
     *  Regression guard for the removal of the Manhattan heuristic (gate D7).
     *
     *  Manhattan distance over-estimates the true cost on a Moore grid, because
     *  `GridGraph.edgeWeight` charges √2 for a diagonal step while Manhattan
     *  charges 2. A differential sweep confirmed this was not merely theoretical:
     *  roughly 17% of randomized Moore instances returned a sub-optimal path, the
     *  worst by 1.757 against a true optimum of 17.899. `GridHeuristics` therefore
     *  no longer offers a Manhattan heuristic.
     *
     *  This test asserts the arithmetic that justified the removal, so that
     *  re-introducing such a heuristic fails here first. `Cell.manhattanDistanceTo`
     *  itself is unaffected and remains correct as a Von Neumann metric.
     */
    @Test
    @DisplayName("D7 guard: Manhattan distance over-estimates true Moore cost")
    fun manhattanDistanceIsNotAdmissibleUnderMooreMovement() {
        val g = GridGraph(10, 10, movementRule = MovementRule.MOORE)
        val from = Cell(0, 0)
        val to = Cell(9, 9)
        val trueCost = g.shortestPath(from, to, GridHeuristics.ZERO)!!.totalWeight
        val manhattan = from.manhattanDistanceTo(to).toDouble()
        assertTrue(
            manhattan > trueCost + 1e-9,
            "Manhattan distance is expected to over-estimate on a Moore grid; " +
                "manhattan=$manhattan trueCost=$trueCost",
        )
        // ...and is a valid lower bound under Von Neumann, which is why the metric
        // itself is kept even though the heuristic was withdrawn.
        val vn = GridGraph(10, 10, movementRule = MovementRule.VON_NEUMANN)
        val vnCost = vn.shortestPath(from, to, GridHeuristics.ZERO)!!.totalWeight
        assertTrue(
            manhattan <= vnCost + 1e-9,
            "Manhattan distance should be admissible under Von Neumann; " +
                "manhattan=$manhattan trueCost=$vnCost",
        )
    }
}
