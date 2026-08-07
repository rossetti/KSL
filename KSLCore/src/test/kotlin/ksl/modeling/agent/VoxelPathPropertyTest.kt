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
 *  The 3D counterpart of `GridPathPropertyTest`: A\* with an admissible heuristic
 *  must return the same total cost as Dijkstra, and `VoxelGraph.shortestPath` with
 *  `VoxelHeuristics.ZERO` *is* Dijkstra.
 *
 *  `VoxelGraph.edgeWeight` charges 1 for a face move, √2 for an edge move, and √3
 *  for a corner move, so the 3D admissibility question is the 2D one sharpened:
 *  under the default `MOORE_26` rule a corner run of k voxels truly costs k·√3
 *  ≈ 1.73k.
 *
 *  Voxel costs are held at or above 1.0 for the same reason as in the 2D suite —
 *  costs below 1.0 are separately documented as requiring `VoxelHeuristics.scaled`.
 */
class VoxelPathPropertyTest {

    private class Heuristic(val label: String, val fn: (Voxel, Voxel) -> Double)

    /**
     *  Every non-ZERO heuristic `VoxelHeuristics` offers. As in 2D, all of these
     *  are admissible under both movement rules once the Manhattan heuristic is
     *  withdrawn.
     */
    private val geometricHeuristics = listOf(
        Heuristic("CHEBYSHEV", VoxelHeuristics.CHEBYSHEV),
        Heuristic("EUCLIDEAN", VoxelHeuristics.EUCLIDEAN),
        Heuristic("OCTILE", VoxelHeuristics.OCTILE),
    )

    private fun randomGraph(
        streamNum: Int,
        size: Int,
        rule: VoxelMovementRule,
        cornerCutting: Boolean,
        blockProbability: Double,
        varyCosts: Boolean,
    ): VoxelGraph {
        val rng = KSLRandom.rnStream(streamNum)
        val g = VoxelGraph(
            columns = size,
            rows = size,
            layers = size,
            torus = false,
            movementRule = rule,
            allowCornerCutting = cornerCutting,
        )
        for (c in 0 until size) {
            for (r in 0 until size) {
                for (l in 0 until size) {
                    val v = Voxel(c, r, l)
                    if (rng.randU01() < blockProbability) {
                        g.block(v)
                    } else if (varyCosts && rng.randU01() < 0.3) {
                        g.setVoxelCost(v, 1.0 + rng.randU01() * 4.0)
                    }
                }
            }
        }
        g.unblock(Voxel(0, 0, 0))
        g.unblock(Voxel(size - 1, size - 1, size - 1))
        return g
    }

    private data class Divergence(
        val heuristic: String,
        val rule: VoxelMovementRule,
        val cornerCutting: Boolean,
        val streamNum: Int,
        val dijkstra: Double,
        val aStar: Double,
    ) {
        override fun toString(): String =
            "$heuristic on $rule (cornerCutting=$cornerCutting, stream=$streamNum) : " +
                "dijkstra=%.6f aStar=%.6f (excess %.6f)"
                    .format(dijkstra, aStar, aStar - dijkstra)
    }

    private fun divergences(
        heuristic: Heuristic,
        rule: VoxelMovementRule,
        cornerCutting: Boolean,
        varyCosts: Boolean,
        graphs: Int = 8,
    ): List<Divergence> {
        val found = mutableListOf<Divergence>()
        for (i in 0 until graphs) {
            val streamNum = 1 + i
            val size = 5 + (i % 3)
            val g = randomGraph(streamNum, size, rule, cornerCutting, 0.12, varyCosts)
            val from = Voxel(0, 0, 0)
            val to = Voxel(size - 1, size - 1, size - 1)
            val reference = g.shortestPath(from, to, VoxelHeuristics.ZERO)
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
                        reference.totalWeight, candidate.totalWeight,
                    )
                )
            }
        }
        return found
    }

    private fun sweepAll(rule: VoxelMovementRule): List<Divergence> {
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
    @DisplayName("A* equals Dijkstra for every offered heuristic under MOORE_26")
    fun everyHeuristicMatchesDijkstraUnderMoore26() {
        val all = sweepAll(VoxelMovementRule.MOORE_26)
        assertTrue(all.isEmpty(), "A* diverged from Dijkstra:\n" + all.joinToString("\n"))
    }

    @Test
    @DisplayName("A* equals Dijkstra for every offered heuristic under VON_NEUMANN_6")
    fun everyHeuristicMatchesDijkstraUnderVonNeumann6() {
        val all = sweepAll(VoxelMovementRule.VON_NEUMANN_6)
        assertTrue(all.isEmpty(), "A* diverged from Dijkstra:\n" + all.joinToString("\n"))
    }

    // ── Lower-bound guarantees ───────────────────────────────────────────────

    @Test
    @DisplayName("Every offered heuristic is a lower bound under both 3D movement rules")
    fun offeredHeuristicsAreLowerBounds() {
        for (rule in VoxelMovementRule.entries) {
            val g = VoxelGraph(6, 6, 6, movementRule = rule)
            val from = Voxel(0, 0, 0)
            for (c in 0 until 6) {
                for (r in 0 until 6) {
                    for (l in 0 until 6) {
                        val to = Voxel(c, r, l)
                        val trueCost =
                            g.shortestPath(from, to, VoxelHeuristics.ZERO)!!.totalWeight
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
    }

    /**
     *  Regression guard mirroring the 2D case (gate D7). Under `MOORE_26` a corner
     *  step costs √3 while Manhattan charges it 3, so Manhattan distance is not a
     *  lower bound and `VoxelHeuristics` no longer offers it as a heuristic. The
     *  metric itself is retained — it is exact under `VON_NEUMANN_6`.
     */
    @Test
    @DisplayName("D7 guard: Manhattan distance over-estimates true MOORE_26 cost")
    fun manhattanDistanceIsNotAdmissibleUnderMoore26() {
        val g = VoxelGraph(6, 6, 6, movementRule = VoxelMovementRule.MOORE_26)
        val from = Voxel(0, 0, 0)
        val to = Voxel(5, 5, 5)
        val trueCost = g.shortestPath(from, to, VoxelHeuristics.ZERO)!!.totalWeight
        val manhattan = from.manhattanDistanceTo(to).toDouble()
        assertTrue(
            manhattan > trueCost + 1e-9,
            "Manhattan distance is expected to over-estimate under MOORE_26; " +
                "manhattan=$manhattan trueCost=$trueCost",
        )
        val vn = VoxelGraph(6, 6, 6, movementRule = VoxelMovementRule.VON_NEUMANN_6)
        val vnCost = vn.shortestPath(from, to, VoxelHeuristics.ZERO)!!.totalWeight
        assertTrue(
            manhattan <= vnCost + 1e-9,
            "Manhattan distance should be admissible under VON_NEUMANN_6; " +
                "manhattan=$manhattan trueCost=$vnCost",
        )
    }
}
