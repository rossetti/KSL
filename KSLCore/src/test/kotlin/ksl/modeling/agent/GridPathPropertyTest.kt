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

    private val geometricHeuristics = listOf(
        Heuristic("CHEBYSHEV", GridHeuristics.CHEBYSHEV),
        Heuristic("EUCLIDEAN", GridHeuristics.EUCLIDEAN),
        Heuristic("MANHATTAN", GridHeuristics.MANHATTAN),
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

    // ── The property, for the heuristics that should satisfy it ──────────────

    @Test
    @DisplayName("A* equals Dijkstra for CHEBYSHEV, EUCLIDEAN and OCTILE on Moore grids")
    fun admissibleHeuristicsMatchDijkstraOnMooreGrids() {
        val safe = geometricHeuristics.filter { it.label != "MANHATTAN" }
        val all = mutableListOf<Divergence>()
        for (h in safe) {
            for (cornerCutting in listOf(false, true)) {
                all += divergences(h, MovementRule.MOORE, cornerCutting, varyCosts = false)
                all += divergences(h, MovementRule.MOORE, cornerCutting, varyCosts = true)
            }
        }
        assertTrue(all.isEmpty(), "A* diverged from Dijkstra:\n" + all.joinToString("\n"))
    }

    @Test
    @DisplayName("A* equals Dijkstra for every geometric heuristic on Von Neumann grids")
    fun allGeometricHeuristicsMatchDijkstraOnVonNeumannGrids() {
        val all = mutableListOf<Divergence>()
        for (h in geometricHeuristics) {
            for (cornerCutting in listOf(false, true)) {
                all += divergences(h, MovementRule.VON_NEUMANN, cornerCutting, varyCosts = false)
                all += divergences(h, MovementRule.VON_NEUMANN, cornerCutting, varyCosts = true)
            }
        }
        assertTrue(all.isEmpty(), "A* diverged from Dijkstra:\n" + all.joinToString("\n"))
    }

    // ── Hypothesis A1-a ──────────────────────────────────────────────────────

    /**
     *  `GridHeuristics.MANHATTAN` over-estimates on a Moore grid, because
     *  `GridGraph.edgeWeight` charges √2 for a diagonal step while MANHATTAN counts
     *  it as 2. Over a pure diagonal run of k cells the true cost is k·√2 ≈ 1.414k
     *  against a heuristic estimate of 2k, so the heuristic is **not** a lower bound
     *  and A\* optimality is not guaranteed.
     *
     *  This test asserts the arithmetic directly — it holds regardless of whether any
     *  particular search happens to expose it.
     */
    @Test
    @DisplayName("A1-a: MANHATTAN over-estimates true Moore cost along a diagonal")
    fun manhattanOverEstimatesOnMooreDiagonal() {
        val g = GridGraph(10, 10, movementRule = MovementRule.MOORE)
        val from = Cell(0, 0)
        val to = Cell(9, 9)
        val trueCost = g.shortestPath(from, to, GridHeuristics.ZERO)!!.totalWeight
        val estimate = GridHeuristics.MANHATTAN(from, to)
        assertTrue(
            estimate > trueCost + 1e-9,
            "expected MANHATTAN to over-estimate on a Moore grid; " +
                "estimate=$estimate trueCost=$trueCost",
        )
    }

    /**
     *  **Characterization test for a confirmed defect — see gate D7.**
     *
     *  Being inadmissible in theory does not by itself mean a search returns a wrong
     *  answer, so this pins what actually happens: sweeping randomized Moore grids,
     *  `GridHeuristics.MANHATTAN` returns a *suboptimal* path on a substantial
     *  fraction of instances. At the time of writing, 63 of 368 reachable cases
     *  (~17%) diverged from Dijkstra, the worst by 1.757 cost units against a true
     *  optimum of 17.899 — roughly a 10% longer route.
     *
     *  This matters because `MovementRule.MOORE` is the **default**, and both
     *  `GridHeuristics` and `GridGraph.shortestPath` describe the pre-built
     *  heuristics as admissible without qualifying by movement rule. A modeler who
     *  picks MANHATTAN on a default grid silently gets non-optimal paths.
     *
     *  This test asserts the divergence *exists*, so it is a record of current
     *  behavior rather than of desired behavior. **When D7 is decided — reject the
     *  pairing, drop MANHATTAN from the pre-built set, or document it as
     *  Moore-unsafe — this test must be inverted or deleted.**
     */
    @Test
    @DisplayName("A1-a: MANHATTAN yields suboptimal paths on Moore grids (current behavior)")
    fun manhattanReturnsSuboptimalPathsOnMooreGrids() {
        val found = mutableListOf<Divergence>()
        for (cornerCutting in listOf(false, true)) {
            found += divergences(
                Heuristic("MANHATTAN", GridHeuristics.MANHATTAN),
                MovementRule.MOORE, cornerCutting, varyCosts = false, grids = 24,
            )
        }
        assertTrue(
            found.isNotEmpty(),
            "MANHATTAN no longer diverges from Dijkstra on Moore grids — if this was " +
                "fixed deliberately, delete this characterization test (gate D7).",
        )
    }

    /**
     *  The companion property: CHEBYSHEV, EUCLIDEAN and OCTILE never over-estimate
     *  the true Moore cost between the same endpoints, which is why they are safe
     *  where MANHATTAN is not.
     */
    @Test
    @DisplayName("CHEBYSHEV, EUCLIDEAN and OCTILE never over-estimate true Moore cost")
    fun safeHeuristicsAreLowerBoundsOnMooreGrids() {
        val g = GridGraph(10, 10, movementRule = MovementRule.MOORE)
        val from = Cell(0, 0)
        for (c in 0 until 10) {
            for (r in 0 until 10) {
                val to = Cell(c, r)
                val trueCost = g.shortestPath(from, to, GridHeuristics.ZERO)!!.totalWeight
                for (h in geometricHeuristics.filter { it.label != "MANHATTAN" }) {
                    val estimate = h.fn(from, to)
                    assertTrue(
                        estimate <= trueCost + 1e-9,
                        "${h.label} over-estimated $from->$to: est=$estimate true=$trueCost",
                    )
                }
            }
        }
    }
}
