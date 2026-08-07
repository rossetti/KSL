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

import ksl.examples.general.agent.CorridorPedestrianExample
import ksl.examples.general.agent.GridEpidemicExample
import ksl.examples.general.agent.NetworkRumorExample
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Tests for `ContinuousProjection` — placement, movement, and the spatial-hash
 *  index behind `within` and the nearest-neighbour queries.
 *
 *  **Phase D.** This was one section of `ContextTest`, a 1274-line umbrella covering
 *  `Context` and all three projections together. Coverage was never the problem;
 *  finding it was. A reader asking what is tested about a given type had no file to
 *  open, and a gap in a 60-test umbrella is invisible in a way a gap in a per-type
 *  file is not — the most likely reason the missing `runDynamics*` and
 *  `GridGeometrySpec` coverage went unnoticed until a deliberate sweep.
 */
class ContinuousProjectionTest {

    private class TestModel(parent: ModelElement) : AgentModel(parent, "test") {
        val context: Context<Agent> = Context("pedestrians")
        val space: ContinuousProjection<Agent> =
            ContinuousProjection(context, xRange = 0.0..100.0, yRange = 0.0..100.0)

        inner class Walker(aName: String) : Agent(aName)
    }

    /**
     *  The spatial index inside ContinuousProjection must produce
     *  identical results to a naive linear scan. This test places a
     *  moderately-sized random population (200 agents, 100x100 area,
     *  default cell size) and verifies that `within(center, radius)`
     *  returns exactly the same set of agents as a hand-computed
     *  linear filter, for a range of centers and radii.
     */
    @Test
    fun continuousProjectionIndexedWithinMatchesLinearScan() {
        val model = Model("CtxIndexParityTest")
        val tm = TestModel(model)  // 100x100, default cell size = 10
        val rng = java.util.Random(42)
        val agents = (0 until 200).map { tm.Walker("a$it") }
        agents.forEach { tm.context.add(it) }
        // Place randomly in [10, 90] x [10, 90] to keep things away from
        // edges; the index is exercised in the interior.
        val placements = agents.associateWith {
            Point2D(10.0 + rng.nextDouble() * 80.0, 10.0 + rng.nextDouble() * 80.0)
        }
        for ((a, p) in placements) tm.space.placeAt(a, p)

        // Compute reference (linear scan) and compare against the
        // index-backed result for several queries.
        val queries = listOf(
            Point2D(50.0, 50.0) to 5.0,
            Point2D(50.0, 50.0) to 20.0,
            Point2D(50.0, 50.0) to 100.0,
            Point2D(10.0, 10.0) to 15.0,
            Point2D(90.0, 90.0) to 25.0,
        )
        for ((center, radius) in queries) {
            val expected = placements
                .filter { (_, p) -> center.distanceTo(p) <= radius }
                .toList()
                .sortedBy { (_, p) -> center.distanceTo(p) }
                .map { it.first }
            val actual = tm.space.within(center, radius)
            assertEquals(
                expected, actual,
                "indexed within disagrees with linear at center=$center radius=$radius",
            )
        }
        // The index should actually be populated — sanity check.
        assertTrue(tm.space.occupiedBucketCount > 1, "expected multiple buckets to be occupied")
    }

    /**
     *  Same parity check for `nearest(k)`. With distinct random
     *  positions there are no ties; the index-backed result must
     *  match the linear sort exactly.
     */
    @Test
    fun continuousProjectionIndexedNearestMatchesLinearScan() {
        val model = Model("CtxIndexNearestTest")
        val tm = TestModel(model)
        val rng = java.util.Random(123)
        val agents = (0 until 200).map { tm.Walker("a$it") }
        agents.forEach { tm.context.add(it) }
        val placements = agents.associateWith {
            Point2D(rng.nextDouble() * 100.0, rng.nextDouble() * 100.0)
        }
        for ((a, p) in placements) tm.space.placeAt(a, p)

        for (k in listOf(1, 5, 20, 200)) {
            val center = Point2D(50.0, 50.0)
            val expected = placements.entries
                .sortedBy { center.distanceTo(it.value) }
                .map { it.key }
                .take(k)
            val actual = tm.space.nearest(center, k)
            assertEquals(expected, actual, "indexed nearest disagrees with linear at k=$k")
        }
    }

    /**
     *  The index must keep its bucket bookkeeping coherent through
     *  many move operations. Move 100 agents around at random
     *  positions for 100 steps, then verify (a) total agent count is
     *  stable, (b) every agent's bucket contains exactly that agent
     *  (no leaked entries from prior buckets), (c) the `within`
     *  query still produces correct results.
     */
    @Test
    fun continuousProjectionIndexedMovesKeepBookkeepingCoherent() {
        val model = Model("CtxMoveTest")
        val tm = TestModel(model)
        val rng = java.util.Random(7)
        val agents = (0 until 100).map { tm.Walker("m$it") }
        agents.forEach { tm.context.add(it) }
        for (a in agents) {
            tm.space.placeAt(a, rng.nextDouble() * 100.0, rng.nextDouble() * 100.0)
        }
        // 100 rounds of random moves.
        repeat(100) {
            for (a in agents) {
                tm.space.moveTo(a, rng.nextDouble() * 100.0, rng.nextDouble() * 100.0)
            }
        }
        assertEquals(100, tm.space.size, "size should be stable through moves")

        // Verify that within(p, large radius) returns exactly the population,
        // confirming no agent was lost from the index.
        val all = tm.space.within(Point2D(50.0, 50.0), 200.0)
        assertEquals(100, all.size, "all agents should still be findable after many moves")
        assertEquals(agents.toSet(), all.toSet())
    }

    /**
     *  Torus wrapping: a query at one edge of the domain should find
     *  agents at the opposite edge thanks to bucket wrap. Verifies
     *  the spatial-index torus path produces the same results as the
     *  distance function would predict.
     */
    @Test
    fun continuousProjectionIndexedTorusWrapsAcrossBoundary() {
        val model = Model("CtxTorusIdxTest")
        val tm = object : AgentModel(model, "torusidx") {
            val context: Context<Agent> = Context("ctx")
            val space: ContinuousProjection<Agent> = ContinuousProjection(
                context = context,
                xRange = 0.0..100.0,
                yRange = 0.0..100.0,
                torus = true,
                cellSize = 10.0,
            )
            inner class Walker(aName: String) : Agent(aName)
        }
        val a = tm.Walker("a"); val b = tm.Walker("b"); val c = tm.Walker("c")
        tm.context.add(a); tm.context.add(b); tm.context.add(c)
        tm.space.placeAt(a, 1.0, 50.0)    // near left edge
        tm.space.placeAt(b, 99.0, 50.0)   // near right edge — distance 2 from a on a torus
        tm.space.placeAt(c, 50.0, 50.0)   // mid — distance ~49 from a

        // Query at the very left edge with radius 5 should find a (distance 1)
        // and b (distance 3 across the wrap) but NOT c.
        val nearLeft = tm.space.within(Point2D(0.0, 50.0), 5.0)
        assertEquals(2, nearLeft.size, "expected a and b across torus wrap; got $nearLeft")
        assertTrue(a in nearLeft && b in nearLeft, "expected both a and b; got $nearLeft")
        assertTrue(c !in nearLeft, "c is far away even across the torus")
    }

    /**
     *  The user-supplied cellSize parameter must be respected and
     *  affect bucket structure. Test by configuring a coarse vs.
     *  fine cell size and verifying the bucket count differs as
     *  expected.
     */
    @Test
    fun continuousProjectionRespectsCustomCellSize() {
        val model = Model("CtxCellSizeTest")
        val coarse = object : AgentModel(model, "coarse") {
            val context: Context<Agent> = Context("ctx-coarse")
            val space = ContinuousProjection(context, 0.0..100.0, 0.0..100.0, cellSize = 50.0)
            inner class Walker(aName: String) : Agent(aName)
        }
        val fine = object : AgentModel(model, "fine") {
            val context: Context<Agent> = Context("ctx-fine")
            val space = ContinuousProjection(context, 0.0..100.0, 0.0..100.0, cellSize = 5.0)
            inner class Walker(aName: String) : Agent(aName)
        }
        // Place identical agents in both.
        val rng = java.util.Random(99)
        val placements = (0 until 50).map {
            rng.nextDouble() * 100.0 to rng.nextDouble() * 100.0
        }
        for ((i, p) in placements.withIndex()) {
            val cw = coarse.Walker("c$i"); coarse.context.add(cw)
            coarse.space.placeAt(cw, p.first, p.second)
            val fw = fine.Walker("f$i"); fine.context.add(fw)
            fine.space.placeAt(fw, p.first, p.second)
        }
        // Coarse: 50.0-cell grid on 100x100 = 2x2 = 4 cells max. With 50
        // agents spread uniformly, at most 4 buckets occupied.
        // Fine: 5.0-cell grid = 20x20 = 400 cells. Most agents in
        // distinct cells.
        assertTrue(
            coarse.space.occupiedBucketCount <= 4,
            "coarse grid should have at most 4 buckets; got ${coarse.space.occupiedBucketCount}",
        )
        assertTrue(
            fine.space.occupiedBucketCount > coarse.space.occupiedBucketCount,
            "fine grid should have more occupied buckets",
        )
    }
}
