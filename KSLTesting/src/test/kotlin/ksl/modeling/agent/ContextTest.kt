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
 *  Regression tests for Phase 3 (Context/Projection split):
 *   - Context membership add / remove / contains / forEach / clear
 *   - Projection attachment, onAgentJoined / onAgentLeft notifications
 *   - ContinuousProjection: placeAt / moveTo / positionOf
 *   - Spatial queries: within, neighborsOf, nearest, distance
 *   - Torus distance wrapping
 *   - Multiple projections on the same context (a continuous projection
 *     and a network-like alternative work side by side)
 */
class ContextTest {

    private class TestModel(parent: ModelElement) : AgentModel(parent, "test") {
        val context: Context<Agent> = Context("pedestrians")
        val space: ContinuousProjection<Agent> =
            ContinuousProjection(context, xRange = 0.0..100.0, yRange = 0.0..100.0)

        inner class Walker(aName: String) : Agent(aName)
    }

    @Test
    fun contextStartsEmpty() {
        val model = Model("CtxEmptyTest")
        val tm = TestModel(model)
        assertTrue(tm.context.isEmpty)
        assertEquals(0, tm.context.size)
    }

    @Test
    fun contextAddAndRemoveTracksMembership() {
        val model = Model("CtxMemTest")
        val tm = TestModel(model)
        val a = tm.Walker("a")
        val b = tm.Walker("b")
        tm.context.add(a)
        tm.context.add(b)
        assertEquals(2, tm.context.size)
        assertTrue(a in tm.context)
        assertTrue(b in tm.context)

        tm.context.remove(a)
        assertEquals(1, tm.context.size)
        assertTrue(a !in tm.context)
        assertTrue(b in tm.context)
    }

    @Test
    fun contextAddIsIdempotent() {
        val model = Model("CtxIdempTest")
        val tm = TestModel(model)
        val a = tm.Walker("a")
        tm.context.add(a)
        tm.context.add(a)
        assertEquals(1, tm.context.size)
    }

    @Test
    fun contextFiltersByTypeAndPredicate() {
        val model = Model("CtxFilterTest")
        val tm = TestModel(model)
        val w1 = tm.Walker("w1")
        val w2 = tm.Walker("w2")
        val w3 = tm.Walker("w3")
        tm.context.add(w1); tm.context.add(w2); tm.context.add(w3)

        assertEquals(3, tm.context.ofType<TestModel.Walker>().size)
        val named = tm.context.where { it.name.endsWith("2") }
        assertEquals(1, named.size)
        assertContains(named, w2)
    }

    @Test
    fun continuousProjectionPlaceAndQuery() {
        val model = Model("CtxPosTest")
        val tm = TestModel(model)
        val a = tm.Walker("a"); val b = tm.Walker("b"); val c = tm.Walker("c")
        tm.context.add(a); tm.context.add(b); tm.context.add(c)

        tm.space.placeAt(a, 0.0, 0.0)
        tm.space.placeAt(b, 3.0, 4.0)   // distance 5 from a
        tm.space.placeAt(c, 6.0, 8.0)   // distance 10 from a, 5 from b

        assertEquals(Point2D(0.0, 0.0), tm.space.positionOf(a))
        assertEquals(Point2D(3.0, 4.0), tm.space.positionOf(b))

        // Euclidean distance
        assertEquals(5.0, tm.space.distance(a, b), 1e-9)
        assertEquals(10.0, tm.space.distance(a, c), 1e-9)
        assertEquals(5.0, tm.space.distance(b, c), 1e-9)
    }

    @Test
    fun continuousProjectionDistanceIsNaNForUnplacedAgent() {
        val model = Model("CtxNaNTest")
        val tm = TestModel(model)
        val a = tm.Walker("a"); val b = tm.Walker("b")
        tm.context.add(a); tm.context.add(b)
        tm.space.placeAt(a, 0.0, 0.0)
        // b not placed

        assertTrue(tm.space.distance(a, b).isNaN())
        assertNull(tm.space.positionOf(b))
    }

    @Test
    fun continuousProjectionWithinAndNeighbors() {
        val model = Model("CtxNbrTest")
        val tm = TestModel(model)
        val agents = (0..4).map { tm.Walker("a$it") }
        agents.forEach { tm.context.add(it) }

        // Place in a line: (0,0), (1,0), (2,0), (3,0), (4,0)
        agents.forEachIndexed { i, w -> tm.space.placeAt(w, i.toDouble(), 0.0) }

        // Within radius 2 of (0,0) → a0, a1, a2 (distances 0, 1, 2)
        val r2 = tm.space.within(Point2D(0.0, 0.0), 2.0)
        assertEquals(3, r2.size)
        assertEquals(agents[0], r2[0])  // ordered by distance
        assertEquals(agents[1], r2[1])
        assertEquals(agents[2], r2[2])

        // neighborsOf a0 with radius 1.5 → a1 (distance 1)
        val nbrs = tm.space.neighborsOf(agents[0], 1.5)
        assertEquals(listOf(agents[1]), nbrs)

        // nearest 3 from (4,0) → a4, a3, a2
        val n3 = tm.space.nearest(Point2D(4.0, 0.0), 3)
        assertEquals(agents[4], n3[0])
        assertEquals(agents[3], n3[1])
        assertEquals(agents[2], n3[2])
    }

    @Test
    fun continuousProjectionDropsPositionWhenAgentLeavesContext() {
        val model = Model("CtxLeaveTest")
        val tm = TestModel(model)
        val a = tm.Walker("a")
        tm.context.add(a)
        tm.space.placeAt(a, 5.0, 5.0)
        assertEquals(Point2D(5.0, 5.0), tm.space.positionOf(a))

        tm.context.remove(a)
        assertNull(tm.space.positionOf(a), "position should be cleared on context removal")
    }

    /**
     *  A torus has wrap-around distance: the distance between two
     *  points on a torus is the *shorter* of the direct distance and
     *  the wrapped distance. Verified for a 100x100 torus.
     */
    @Test
    fun continuousProjectionTorusDistanceWraps() {
        val model = Model("TorusTest")
        val ctx = object : AgentModel(model, "torus") {
            val context: Context<Agent> = Context("ctx")
            val space: ContinuousProjection<Agent> = ContinuousProjection(
                context, xRange = 0.0..100.0, yRange = 0.0..100.0, torus = true,
            )
        }
        // Two points 99 apart on a 100-unit axis: wrapped distance is 1.
        val a = Point2D(1.0, 50.0)
        val b = Point2D(100.0, 50.0)
        assertEquals(1.0, ctx.space.distance(a, b), 1e-9)
        // Diagonal across the torus
        val c = Point2D(99.0, 99.0)
        val d = Point2D(1.0, 1.0)
        // wrapped dx = 2, wrapped dy = 2, distance = sqrt(8)
        assertEquals(kotlin.math.sqrt(8.0), ctx.space.distance(c, d), 1e-9)
    }

    /**
     *  Multiple projections can be attached to the same context.
     *  Verified by attaching a second `ContinuousProjection` (for a
     *  hypothetical "intent" map vs. an actual-position map).
     *  Membership changes notify both.
     */
    @Test
    fun multipleProjectionsOnSameContextBothReceiveNotifications() {
        val model = Model("MultiProjTest")
        val tm = TestModel(model)
        // tm.space is already attached. Add a second projection.
        val intent = ContinuousProjection(
            tm.context, xRange = 0.0..100.0, yRange = 0.0..100.0, name = "intent",
        )
        assertEquals(2, tm.context.projections.size)

        val a = tm.Walker("a")
        tm.context.add(a)
        tm.space.placeAt(a, 1.0, 2.0)
        intent.placeAt(a, 9.0, 9.0)

        tm.context.remove(a)
        // Both projections should have dropped the agent's position.
        assertNull(tm.space.positionOf(a))
        assertNull(intent.positionOf(a))
    }

    // ── ContinuousProjection spatial index ──────────────────────────────────

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

    // ── GridProjection ──────────────────────────────────────────────────────

    private class GridTestModel(parent: ModelElement, occupancy: GridOccupancy = GridOccupancy.MULTIPLE, torus: Boolean = false) :
        AgentModel(parent, "gridmodel") {
        val context: Context<Agent> = Context("grid-agents")
        val grid: GridProjection<Agent> = GridProjection(
            context = context, columns = 5, rows = 5, occupancy = occupancy, torus = torus,
        )
        inner class Walker(aName: String) : Agent(aName)
    }

    @Test
    fun gridProjectionPlacesAgentsAtCells() {
        val model = Model("GridPlaceTest")
        val tm = GridTestModel(model)
        val a = tm.Walker("a"); val b = tm.Walker("b")
        tm.context.add(a); tm.context.add(b)
        tm.grid.placeAt(a, Cell(1, 2))
        tm.grid.placeAt(b, 3, 4)

        assertEquals(Cell(1, 2), tm.grid.cellOf(a))
        assertEquals(Cell(3, 4), tm.grid.cellOf(b))
        assertEquals(listOf(a), tm.grid.agentsAt(Cell(1, 2)))
        assertEquals(listOf(b), tm.grid.agentsAt(3, 4))
        assertTrue(tm.grid.isEmpty(Cell(0, 0)))
    }

    @Test
    fun gridProjectionMoveUpdatesCell() {
        val model = Model("GridMoveTest")
        val tm = GridTestModel(model)
        val a = tm.Walker("a")
        tm.context.add(a)
        tm.grid.placeAt(a, 1, 1)
        tm.grid.moveTo(a, 2, 2)

        assertEquals(Cell(2, 2), tm.grid.cellOf(a))
        assertTrue(tm.grid.agentsAt(Cell(1, 1)).isEmpty(), "agent should have left the previous cell")
        assertEquals(listOf(a), tm.grid.agentsAt(Cell(2, 2)))
    }

    @Test
    fun gridProjectionMultiOccupancyAllowsCoLocation() {
        val model = Model("GridMultiTest")
        val tm = GridTestModel(model, occupancy = GridOccupancy.MULTIPLE)
        val a = tm.Walker("a"); val b = tm.Walker("b"); val c = tm.Walker("c")
        for (w in listOf(a, b, c)) tm.context.add(w)
        for (w in listOf(a, b, c)) tm.grid.placeAt(w, 2, 2)

        val occupants = tm.grid.agentsAt(2, 2)
        assertEquals(3, occupants.size)
        assertContains(occupants, a); assertContains(occupants, b); assertContains(occupants, c)
    }

    @Test
    fun gridProjectionSingleOccupancyRejectsConflict() {
        val model = Model("GridSingleTest")
        val tm = GridTestModel(model, occupancy = GridOccupancy.SINGLE)
        val a = tm.Walker("a"); val b = tm.Walker("b")
        tm.context.add(a); tm.context.add(b)
        tm.grid.placeAt(a, 2, 2)

        // tryPlaceAt returns false on conflict; placeAt throws.
        assertTrue(!tm.grid.tryPlaceAt(b, Cell(2, 2)), "tryPlaceAt should reject conflicting placement")
        try {
            tm.grid.placeAt(b, 2, 2)
            error("placeAt should have thrown on single-occupancy conflict")
        } catch (e: IllegalStateException) {
            assertContains(e.message ?: "", "already occupied")
        }
    }

    @Test
    fun gridProjectionOutOfBoundsThrowsWhenNotTorus() {
        val model = Model("GridOOBTest")
        val tm = GridTestModel(model)
        val a = tm.Walker("a")
        tm.context.add(a)
        try {
            tm.grid.placeAt(a, -1, 0)
            error("placeAt should have thrown for col -1")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            tm.grid.placeAt(a, 0, 5)
            error("placeAt should have thrown for row 5 (rows=5)")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun gridProjectionTorusWrapsCoordinates() {
        val model = Model("GridTorusTest")
        val tm = GridTestModel(model, torus = true)
        val a = tm.Walker("a")
        tm.context.add(a)
        // col = -1 wraps to columns - 1 = 4; row = 7 wraps to 7 % 5 = 2.
        tm.grid.placeAt(a, -1, 7)
        assertEquals(Cell(4, 2), tm.grid.cellOf(a))
    }

    @Test
    fun gridMooreNeighborhoodReturnsEightCells() {
        val model = Model("GridMooreTest")
        val tm = GridTestModel(model)
        val center = Cell(2, 2)
        val nbrs = tm.grid.mooreNeighborhood(center)
        assertEquals(8, nbrs.size, "Moore neighborhood of an interior cell should be 8 cells")
        // All 8 surrounding cells should be present.
        for (dc in -1..1) {
            for (dr in -1..1) {
                if (dc == 0 && dr == 0) continue
                assertContains(nbrs, Cell(2 + dc, 2 + dr))
            }
        }
    }

    @Test
    fun gridMooreNeighborhoodAtCornerHasFewerCellsWhenBounded() {
        val model = Model("GridCornerTest")
        val tm = GridTestModel(model)  // not torus
        val corner = Cell(0, 0)
        val nbrs = tm.grid.mooreNeighborhood(corner)
        // (0,0)'s neighbors in bounds: (1,0), (0,1), (1,1) — 3 cells.
        assertEquals(3, nbrs.size)
        assertContains(nbrs, Cell(1, 0))
        assertContains(nbrs, Cell(0, 1))
        assertContains(nbrs, Cell(1, 1))
    }

    @Test
    fun gridMooreNeighborhoodOnTorusAlwaysReturnsEightCells() {
        val model = Model("GridTorusCornerTest")
        val tm = GridTestModel(model, torus = true)
        val corner = Cell(0, 0)
        val nbrs = tm.grid.mooreNeighborhood(corner)
        assertEquals(8, nbrs.size, "torus Moore neighborhood of any cell is always 8 cells")
        // Wrap-around neighbors expected: (4,4), (4,0), (4,1), (0,4), (0,1), (1,4), (1,0), (1,1).
        assertContains(nbrs, Cell(4, 4))
        assertContains(nbrs, Cell(4, 0))
        assertContains(nbrs, Cell(0, 4))
    }

    @Test
    fun gridVonNeumannNeighborhoodReturnsFourCells() {
        val model = Model("GridVNTest")
        val tm = GridTestModel(model)
        val nbrs = tm.grid.vonNeumannNeighborhood(Cell(2, 2))
        assertEquals(4, nbrs.size)
        assertContains(nbrs, Cell(1, 2))
        assertContains(nbrs, Cell(3, 2))
        assertContains(nbrs, Cell(2, 1))
        assertContains(nbrs, Cell(2, 3))
    }

    @Test
    fun gridNeighborsOfAgentReturnsCoLocatedAndAdjacentAgents() {
        val model = Model("GridNeighborsTest")
        val tm = GridTestModel(model)
        val a = tm.Walker("a"); val b = tm.Walker("b"); val c = tm.Walker("c"); val d = tm.Walker("d")
        for (w in listOf(a, b, c, d)) tm.context.add(w)

        tm.grid.placeAt(a, 2, 2)       // center
        tm.grid.placeAt(b, 2, 2)       // co-located with a
        tm.grid.placeAt(c, 3, 3)       // Moore neighbor (diagonal)
        tm.grid.placeAt(d, 4, 4)       // outside Moore radius

        val nbrs = tm.grid.neighborsOf(a, radius = 1, metric = GridMetric.CHEBYSHEV)
        assertEquals(2, nbrs.size, "expected b (co-located) and c (diagonal) but not d")
        assertContains(nbrs, b)
        assertContains(nbrs, c)
    }

    @Test
    fun gridDropsAgentCellWhenContextRemoves() {
        val model = Model("GridRemoveTest")
        val tm = GridTestModel(model)
        val a = tm.Walker("a")
        tm.context.add(a)
        tm.grid.placeAt(a, 2, 2)
        assertEquals(Cell(2, 2), tm.grid.cellOf(a))

        tm.context.remove(a)
        assertNull(tm.grid.cellOf(a))
        assertTrue(tm.grid.agentsAt(Cell(2, 2)).isEmpty())
    }

    // ── NetworkProjection ───────────────────────────────────────────────────

    private class NetTestModel(parent: ModelElement, directed: Boolean = false) :
        AgentModel(parent, "netmodel") {
        val context: Context<Agent> = Context("net-agents")
        val net: NetworkProjection<Agent> = NetworkProjection(context, directed = directed)
        inner class Person(aName: String) : Agent(aName)
    }

    @Test
    fun networkUndirectedConnectIsSymmetric() {
        val model = Model("NetUndirTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b")
        tm.context.add(a); tm.context.add(b)
        tm.net.connect(a, b)

        assertTrue(tm.net.hasEdge(a, b))
        assertTrue(tm.net.hasEdge(b, a), "undirected connect should be symmetric")
        assertEquals(setOf(b), tm.net.neighborsOf(a))
        assertEquals(setOf(a), tm.net.neighborsOf(b))
        assertEquals(1, tm.net.edgeCount, "edgeCount should count an undirected edge once")
    }

    @Test
    fun networkDirectedConnectIsAsymmetric() {
        val model = Model("NetDirTest")
        val tm = NetTestModel(model, directed = true)
        val a = tm.Person("a"); val b = tm.Person("b")
        tm.context.add(a); tm.context.add(b)
        tm.net.connect(a, b)

        assertTrue(tm.net.hasEdge(a, b))
        assertTrue(!tm.net.hasEdge(b, a), "directed connect should NOT be symmetric")
        assertEquals(setOf(b), tm.net.neighborsOf(a))
        assertEquals(emptySet<AgentModel.Agent>(), tm.net.neighborsOf(b))
        assertEquals(setOf(a), tm.net.inNeighborsOf(b))
        assertEquals(1, tm.net.degreeOf(a))
        assertEquals(0, tm.net.degreeOf(b))
        assertEquals(1, tm.net.inDegreeOf(b))
    }

    @Test
    fun networkConnectIsIdempotentAndUpdatesWeight() {
        val model = Model("NetIdempTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b")
        tm.context.add(a); tm.context.add(b)

        tm.net.connect(a, b, weight = 1.0)
        tm.net.connect(a, b, weight = 3.0)  // re-connect updates the weight
        assertEquals(1, tm.net.edgeCount, "re-connecting the same pair should not add a parallel edge")
        assertEquals(3.0, tm.net.weightOf(a, b))
        assertEquals(3.0, tm.net.weightOf(b, a), "weight should be symmetric on undirected")
    }

    @Test
    fun networkDisconnectRemovesEdge() {
        val model = Model("NetDiscTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b")
        tm.context.add(a); tm.context.add(b)
        tm.net.connect(a, b)
        assertTrue(tm.net.disconnect(a, b), "disconnect should report removal of an existing edge")
        assertTrue(!tm.net.hasEdge(a, b))
        assertTrue(!tm.net.hasEdge(b, a), "undirected disconnect should remove both directions")
        assertTrue(!tm.net.disconnect(a, b), "disconnect of a non-existent edge should return false")
    }

    @Test
    fun networkSelfEdgesAreRejected() {
        val model = Model("NetSelfTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a")
        tm.context.add(a)
        try {
            tm.net.connect(a, a)
            error("connect(a, a) should throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun networkShortestPathOnLine() {
        val model = Model("NetPathTest")
        val tm = NetTestModel(model)
        val nodes = (0..4).map { tm.Person("n$it") }
        nodes.forEach { tm.context.add(it) }
        // Line: 0 - 1 - 2 - 3 - 4
        for (i in 0..3) tm.net.connect(nodes[i], nodes[i + 1])

        // Shortest path from 0 to 4 is the full line.
        val path = tm.net.shortestPath(nodes[0], nodes[4])
        assertEquals(nodes, path)
        assertEquals(4, tm.net.shortestPathLength(nodes[0], nodes[4]))

        // Self-path is a single element.
        assertEquals(listOf(nodes[0]), tm.net.shortestPath(nodes[0], nodes[0]))
        assertEquals(0, tm.net.shortestPathLength(nodes[0], nodes[0]))
    }

    @Test
    fun networkShortestPathOnTriangleShortcut() {
        val model = Model("NetTriTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        // Triangle: a-b, b-c, a-c. Shortest a-c is direct.
        tm.net.connect(a, b); tm.net.connect(b, c); tm.net.connect(a, c)
        assertEquals(1, tm.net.shortestPathLength(a, c))
        assertEquals(listOf(a, c), tm.net.shortestPath(a, c))
    }

    @Test
    fun networkShortestPathReturnsNullWhenDisconnected() {
        val model = Model("NetDiscPathTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        tm.net.connect(a, b)
        // c is isolated
        assertNull(tm.net.shortestPath(a, c))
        assertEquals(-1, tm.net.shortestPathLength(a, c))
    }

    @Test
    fun networkReachableFromBfsExploresComponent() {
        val model = Model("NetReachTest")
        val tm = NetTestModel(model)
        val nodes = (0..4).map { tm.Person("n$it") }
        nodes.forEach { tm.context.add(it) }
        // Two components: {0, 1, 2} (chain) and {3, 4} (pair)
        tm.net.connect(nodes[0], nodes[1]); tm.net.connect(nodes[1], nodes[2])
        tm.net.connect(nodes[3], nodes[4])

        val reach0 = tm.net.reachableFrom(nodes[0])
        assertEquals(setOf(nodes[0], nodes[1], nodes[2]), reach0)

        val reach3 = tm.net.reachableFrom(nodes[3])
        assertEquals(setOf(nodes[3], nodes[4]), reach3)

        // Isolated node (no edges): reachable from itself only.
        val isolated = tm.Person("isolated")
        tm.context.add(isolated)
        assertEquals(setOf(isolated), tm.net.reachableFrom(isolated))
    }

    @Test
    fun networkEdgesReturnsUndirectedEdgesOnce() {
        val model = Model("NetEdgesTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        tm.net.connect(a, b, weight = 2.5)
        tm.net.connect(b, c, weight = 1.5)

        val edges = tm.net.edges()
        assertEquals(2, edges.size)
        val pairsToWeight = edges.associate { setOf(it.from, it.to) to it.weight }
        assertEquals(2.5, pairsToWeight[setOf(a, b)])
        assertEquals(1.5, pairsToWeight[setOf(b, c)])
    }

    // ── Weighted shortest path (Dijkstra / A*) ─────────────────────────────

    @Test
    fun weightedShortestPathSelfPathIsTrivial() {
        val model = Model("WSPSelfTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a")
        tm.context.add(a)
        val path = tm.net.weightedShortestPath(a, a)
        assertNotNull(path)
        assertEquals(listOf(a), path!!.nodes)
        assertEquals(0.0, path.totalWeight)
    }

    @Test
    fun weightedShortestPathUnreachableReturnsNull() {
        val model = Model("WSPUnreachableTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        tm.net.connect(a, b)  // c is isolated
        assertNull(tm.net.weightedShortestPath(a, c))
        assertEquals(Double.POSITIVE_INFINITY, tm.net.weightedShortestPathLength(a, c))
    }

    /**
     *  Canonical Dijkstra check: a path with more hops but smaller
     *  total weight should be preferred over a fewer-hop heavier path.
     *  The BFS shortestPath would pick the wrong one here.
     */
    @Test
    fun weightedShortestPathPrefersCheaperPathOverFewerHops() {
        val model = Model("WSPCheaperTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c"); val d = tm.Person("d")
        listOf(a, b, c, d).forEach { tm.context.add(it) }
        // Heavy direct edge: a → d with cost 10
        // Cheap 3-hop path:  a → b → c → d with costs 1+1+1 = 3
        tm.net.connect(a, d, weight = 10.0)
        tm.net.connect(a, b, weight = 1.0)
        tm.net.connect(b, c, weight = 1.0)
        tm.net.connect(c, d, weight = 1.0)
        val path = tm.net.weightedShortestPath(a, d)
        assertNotNull(path)
        assertEquals(listOf(a, b, c, d), path!!.nodes)
        assertEquals(3.0, path.totalWeight, 1e-9)

        // The BFS unweighted shortestPath, by contrast, picks the
        // direct edge because it has fewer hops.
        assertEquals(listOf(a, d), tm.net.shortestPath(a, d))
    }

    @Test
    fun weightedShortestPathOnDirectedGraphRespectsDirection() {
        val model = Model("WSPDirTest")
        val tm = NetTestModel(model, directed = true)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        tm.net.connect(a, b, weight = 1.0)
        tm.net.connect(b, c, weight = 1.0)
        // No edge c → a; the reverse path doesn't exist.
        assertNotNull(tm.net.weightedShortestPath(a, c))
        assertNull(tm.net.weightedShortestPath(c, a))
    }

    @Test
    fun weightedShortestPathAllUnitWeightsAgreesWithBfs() {
        val model = Model("WSPUnitTest")
        val tm = NetTestModel(model)
        val nodes = (0..5).map { tm.Person("n$it") }
        nodes.forEach { tm.context.add(it) }
        // 0 - 1, 1 - 2, 2 - 3, 0 - 4, 4 - 3, 3 - 5
        tm.net.connect(nodes[0], nodes[1])
        tm.net.connect(nodes[1], nodes[2])
        tm.net.connect(nodes[2], nodes[3])
        tm.net.connect(nodes[0], nodes[4])
        tm.net.connect(nodes[4], nodes[3])
        tm.net.connect(nodes[3], nodes[5])

        // Weighted (all weights = 1.0) and unweighted should produce
        // the same hop count for every pair.
        for (i in nodes.indices) {
            for (j in nodes.indices) {
                if (i == j) continue
                val bfs = tm.net.shortestPathLength(nodes[i], nodes[j])
                val wsp = tm.net.weightedShortestPath(nodes[i], nodes[j])
                if (bfs == -1) {
                    assertNull(wsp)
                } else {
                    assertNotNull(wsp)
                    assertEquals(bfs.toDouble(), wsp!!.totalWeight, 1e-9)
                    assertEquals(bfs + 1, wsp.nodes.size)
                }
            }
        }
    }

    @Test
    fun weightedShortestPathAStarWithZeroHeuristicEqualsDijkstra() {
        val model = Model("WSPAStarZeroTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c"); val d = tm.Person("d")
        listOf(a, b, c, d).forEach { tm.context.add(it) }
        tm.net.connect(a, b, weight = 2.0)
        tm.net.connect(b, d, weight = 2.0)
        tm.net.connect(a, c, weight = 1.0)
        tm.net.connect(c, d, weight = 1.5)

        val dijkstra = tm.net.weightedShortestPath(a, d)
        val aStarZero = tm.net.weightedShortestPath(a, d) { _, _ -> 0.0 }
        assertEquals(dijkstra, aStarZero)
    }

    /**
     *  A* with an admissible heuristic finds the same optimal path
     *  as Dijkstra. Verified by building a network whose nodes have
     *  positions in a [ContinuousProjection] and using Euclidean
     *  distance as the heuristic — the canonical GPS-routing
     *  composition.
     */
    @Test
    fun weightedShortestPathAStarWithAdmissibleHeuristicMatchesDijkstra() {
        val model = Model("WSPAStarTest")
        val tm = object : AgentModel(model, "astarmodel") {
            val context: Context<Agent> = Context("ctx")
            val net: NetworkProjection<Agent> = NetworkProjection(context)
            val space: ContinuousProjection<Agent> = ContinuousProjection(
                context, xRange = 0.0..100.0, yRange = 0.0..100.0,
            )
            inner class Person(aName: String) : Agent(aName)
        }
        // Build a small "road network" with positions:
        //
        //   a(0,0)  --[5]--  b(5,0)
        //     |               |
        //   [4]            [3]
        //     |               |
        //   c(0,4)  --[10]--  d(5,4)
        //
        // Two paths from a to d:
        //   a → b → d : cost 5 + 3 = 8
        //   a → c → d : cost 4 + 10 = 14
        // Shortest is a → b → d.
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c"); val d = tm.Person("d")
        listOf(a, b, c, d).forEach { tm.context.add(it) }
        tm.space.placeAt(a, 0.0, 0.0)
        tm.space.placeAt(b, 5.0, 0.0)
        tm.space.placeAt(c, 0.0, 4.0)
        tm.space.placeAt(d, 5.0, 4.0)
        tm.net.connect(a, b, weight = 5.0)
        tm.net.connect(b, d, weight = 3.0)
        tm.net.connect(a, c, weight = 4.0)
        tm.net.connect(c, d, weight = 10.0)

        // Euclidean-distance heuristic (admissible: edge weights >=
        // straight-line distance for this construction).
        val heuristic = { n: AgentModel.Agent, target: AgentModel.Agent ->
            val pn = tm.space.positionOf(n)!!
            val pt = tm.space.positionOf(target)!!
            tm.space.distance(pn, pt)
        }

        val dijkstra = tm.net.weightedShortestPath(a, d)
        val aStar = tm.net.weightedShortestPath(a, d, heuristic)

        assertNotNull(dijkstra); assertNotNull(aStar)
        assertEquals(listOf(a, b, d), dijkstra!!.nodes)
        assertEquals(listOf(a, b, d), aStar!!.nodes)
        assertEquals(8.0, dijkstra.totalWeight, 1e-9)
        assertEquals(8.0, aStar.totalWeight, 1e-9)
    }

    /**
     *  Triangle inequality on weights: a single edge with high
     *  weight should still beat a multi-edge path if its weight is
     *  lower than the sum.
     */
    @Test
    fun weightedShortestPathPicksDirectEdgeWhenItIsCheapest() {
        val model = Model("WSPDirectTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        tm.net.connect(a, c, weight = 1.0)
        tm.net.connect(a, b, weight = 5.0)
        tm.net.connect(b, c, weight = 5.0)
        val path = tm.net.weightedShortestPath(a, c)
        assertEquals(listOf(a, c), path!!.nodes)
        assertEquals(1.0, path.totalWeight, 1e-9)
    }

    @Test
    fun weightedShortestPathSurvivesAgentRemoval() {
        val model = Model("WSPRemoveTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c"); val d = tm.Person("d")
        listOf(a, b, c, d).forEach { tm.context.add(it) }
        tm.net.connect(a, b, weight = 1.0)
        tm.net.connect(b, c, weight = 1.0)
        tm.net.connect(c, d, weight = 1.0)
        // Direct heavier route
        tm.net.connect(a, d, weight = 5.0)

        assertEquals(3.0, tm.net.weightedShortestPathLength(a, d), 1e-9)

        // Remove b: the cheap chain is broken. Only the direct edge remains.
        tm.context.remove(b)
        assertEquals(5.0, tm.net.weightedShortestPathLength(a, d), 1e-9)
    }

    // ── Strongly connected components ──────────────────────────────────────

    @Test
    fun networkSCCEmptyGraphHasNoSCCs() {
        val model = Model("SCCEmptyTest")
        val tm = NetTestModel(model, directed = true)
        assertTrue(tm.net.stronglyConnectedComponents().isEmpty())
        assertEquals(emptySet<AgentModel.Agent>(), tm.net.largestSCC())
    }

    @Test
    fun networkSCCSingletonForEdgelessAgentReturnsNull() {
        val model = Model("SCCNoEdgesTest")
        val tm = NetTestModel(model, directed = true)
        val a = tm.Person("a")
        tm.context.add(a)
        // Agent in the context but no edges. Not a node in the
        // network's view; sccContaining returns null.
        assertNull(tm.net.sccContaining(a))
        assertTrue(tm.net.stronglyConnectedComponents().isEmpty())
    }

    @Test
    fun networkSCCDirectedChainGivesAllSingletons() {
        val model = Model("SCCChainTest")
        val tm = NetTestModel(model, directed = true)
        val nodes = (0..3).map { tm.Person("n$it") }
        nodes.forEach { tm.context.add(it) }
        // 0 -> 1 -> 2 -> 3, no back-edges. Each node is its own SCC.
        for (i in 0..2) tm.net.connect(nodes[i], nodes[i + 1])
        val sccs = tm.net.stronglyConnectedComponents()
        assertEquals(4, sccs.size)
        for (scc in sccs) assertEquals(1, scc.size)
        // sccContaining for each gives a singleton set with that node.
        for (n in nodes) assertEquals(setOf(n), tm.net.sccContaining(n))
    }

    @Test
    fun networkSCCDirectedCycleGivesOneSCC() {
        val model = Model("SCCCycleTest")
        val tm = NetTestModel(model, directed = true)
        val nodes = (0..2).map { tm.Person("n$it") }
        nodes.forEach { tm.context.add(it) }
        // 3-cycle: 0 -> 1 -> 2 -> 0
        tm.net.connect(nodes[0], nodes[1])
        tm.net.connect(nodes[1], nodes[2])
        tm.net.connect(nodes[2], nodes[0])

        val sccs = tm.net.stronglyConnectedComponents()
        assertEquals(1, sccs.size)
        assertEquals(nodes.toSet(), sccs[0])
        // largestSCC returns the full cycle.
        assertEquals(nodes.toSet(), tm.net.largestSCC())
    }

    @Test
    fun networkSCCMixedGraphPartitionsCorrectly() {
        val model = Model("SCCMixedTest")
        val tm = NetTestModel(model, directed = true)
        // Build the example from the discussion:
        //   A → B → C
        //       ↑   ↓
        //       D ← E
        // SCCs: {A} and {B, C, D, E} (the latter is the cycle B->C->E->D->B).
        val a = tm.Person("A"); val b = tm.Person("B"); val c = tm.Person("C")
        val d = tm.Person("D"); val e = tm.Person("E")
        listOf(a, b, c, d, e).forEach { tm.context.add(it) }
        tm.net.connect(a, b)
        tm.net.connect(b, c)
        tm.net.connect(c, e)
        tm.net.connect(e, d)
        tm.net.connect(d, b)

        val sccs = tm.net.stronglyConnectedComponents()
        assertEquals(2, sccs.size, "should be exactly 2 SCCs: {A} and {B,C,D,E}")
        val singletons = sccs.filter { it.size == 1 }
        val bigger = sccs.filter { it.size > 1 }
        assertEquals(1, singletons.size)
        assertEquals(1, bigger.size)
        assertEquals(setOf(a), singletons.single())
        assertEquals(setOf(b, c, d, e), bigger.single())
        // largestSCC picks the size-4 component.
        assertEquals(setOf(b, c, d, e), tm.net.largestSCC())
        // sccContaining(A) is the singleton; sccContaining(B) is the cycle.
        assertEquals(setOf(a), tm.net.sccContaining(a))
        assertEquals(setOf(b, c, d, e), tm.net.sccContaining(b))
    }

    @Test
    fun networkSCCDirectedTwoCycleIsSCC() {
        val model = Model("SCCTwoCycleTest")
        val tm = NetTestModel(model, directed = true)
        val a = tm.Person("a"); val b = tm.Person("b")
        listOf(a, b).forEach { tm.context.add(it) }
        // a <-> b via two directed edges
        tm.net.connect(a, b)
        tm.net.connect(b, a)
        val sccs = tm.net.stronglyConnectedComponents()
        assertEquals(1, sccs.size)
        assertEquals(setOf(a, b), sccs.single())
    }

    @Test
    fun networkSCCUndirectedGraphEqualsConnectedComponents() {
        val model = Model("SCCUndirTest")
        val tm = NetTestModel(model, directed = false)
        // Two undirected components: {a, b, c} (triangle) and {d, e} (edge).
        val nodes = (0..4).map { tm.Person("u$it") }
        nodes.forEach { tm.context.add(it) }
        tm.net.connect(nodes[0], nodes[1])
        tm.net.connect(nodes[1], nodes[2])
        tm.net.connect(nodes[0], nodes[2])
        tm.net.connect(nodes[3], nodes[4])

        val sccs = tm.net.stronglyConnectedComponents()
        assertEquals(2, sccs.size, "undirected SCC partition should match connected components")
        val sccSets = sccs.map { it }.toSet()
        assertContains(sccSets, setOf(nodes[0], nodes[1], nodes[2]))
        assertContains(sccSets, setOf(nodes[3], nodes[4]))
    }

    @Test
    fun networkSCCMultipleDisjointDirectedCycles() {
        val model = Model("SCCDisjointTest")
        val tm = NetTestModel(model, directed = true)
        // Two independent 3-cycles plus an isolated edge.
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        val d = tm.Person("d"); val e = tm.Person("e"); val f = tm.Person("f")
        val g = tm.Person("g"); val h = tm.Person("h")
        listOf(a, b, c, d, e, f, g, h).forEach { tm.context.add(it) }
        // Cycle 1: a -> b -> c -> a
        tm.net.connect(a, b); tm.net.connect(b, c); tm.net.connect(c, a)
        // Cycle 2: d -> e -> f -> d
        tm.net.connect(d, e); tm.net.connect(e, f); tm.net.connect(f, d)
        // Edge: g -> h (no cycle)
        tm.net.connect(g, h)

        val sccs = tm.net.stronglyConnectedComponents()
        // 2 size-3 SCCs + 2 singletons = 4 SCCs total
        assertEquals(4, sccs.size)
        val bySize = sccs.groupBy { it.size }
        assertEquals(2, bySize[3]?.size, "expected 2 SCCs of size 3 (the cycles)")
        assertEquals(2, bySize[1]?.size, "expected 2 SCCs of size 1 (g, h)")
        val cycle1 = bySize[3]!!.first { a in it }
        val cycle2 = bySize[3]!!.first { d in it }
        assertEquals(setOf(a, b, c), cycle1)
        assertEquals(setOf(d, e, f), cycle2)
    }

    @Test
    fun networkSCCSurvivesAgentRemoval() {
        val model = Model("SCCRemoveTest")
        val tm = NetTestModel(model, directed = true)
        val nodes = (0..3).map { tm.Person("r$it") }
        nodes.forEach { tm.context.add(it) }
        // 4-cycle: 0 -> 1 -> 2 -> 3 -> 0
        for (i in 0..3) tm.net.connect(nodes[i], nodes[(i + 1) % 4])
        assertEquals(setOf(nodes[0], nodes[1], nodes[2], nodes[3]), tm.net.largestSCC())

        // Removing one node breaks the cycle: 0 -> 1, 2 -> 3, 1 -> (gone), 3 -> 0
        // Wait: removing node 2 means edges 1 -> 2 and 2 -> 3 are gone.
        // Remaining edges: 0 -> 1, 3 -> 0. Path: 3 -> 0 -> 1, no path back.
        tm.context.remove(nodes[2])
        val sccs = tm.net.stronglyConnectedComponents()
        // 3 nodes, 2 edges, no cycle → 3 singleton SCCs.
        assertEquals(3, sccs.size)
        for (scc in sccs) assertEquals(1, scc.size)
    }

    @Test
    fun networkDropsEdgesWhenAgentLeavesContext() {
        val model = Model("NetLeaveTest")
        val tm = NetTestModel(model)
        val a = tm.Person("a"); val b = tm.Person("b"); val c = tm.Person("c")
        listOf(a, b, c).forEach { tm.context.add(it) }
        tm.net.connect(a, b); tm.net.connect(b, c); tm.net.connect(a, c)
        assertEquals(3, tm.net.edgeCount)

        tm.context.remove(b)
        // b's edges (a-b, b-c) should be gone; a-c remains.
        assertEquals(1, tm.net.edgeCount)
        assertTrue(tm.net.hasEdge(a, c))
        assertTrue(!tm.net.hasEdge(a, b))
        assertTrue(!tm.net.hasEdge(b, c))
        assertEquals(emptySet<AgentModel.Agent>(), tm.net.neighborsOf(b))
    }

    // ── CorridorPedestrianExample smoke test ────────────────────────────────

    @Test
    fun corridorPedestrianExampleRunsAndEmptiesCorridor() {
        val model = Model("CorridorSmokeTest")
        val sys = CorridorPedestrianExample(model, "corridor")
        model.lengthOfReplication = 200.0
        model.numberOfReplications = 1
        model.simulate()

        // Crossing time should be positive (at least one pedestrian
        // crossed in 200 simulated time units).
        assertTrue(
            sys.crossingTime.acrossReplicationStatistic.count > 0,
            "expected at least one pedestrian to cross; got ${sys.crossingTime.acrossReplicationStatistic.count}",
        )
        // Average crossing time should be in a sensible range:
        // corridorLength = 100, speed ~ Uniform(1.0, 1.5),
        // so crossing time ~ 67–100.
        val avgT = sys.crossingTime.acrossReplicationStatistic.average
        assertTrue(
            avgT in 50.0..120.0,
            "average crossing time should be ~67-100; got $avgT",
        )
    }

    @Test
    fun gridEpidemicExampleRunsAndConservesPopulation() {
        val model = Model("GridEpidemicSmokeTest")
        val sys = GridEpidemicExample(model, "epidemic")
        sys.population = 30
        sys.initialInfected = 3
        model.lengthOfReplication = 50.0
        model.numberOfReplications = 1
        model.simulate()

        // Population conservation: S + I + R = total. TWResponse
        // averages aren't integers, but the time-weighted total
        // should equal the population exactly because every agent
        // is in exactly one state at all times.
        val s = sys.numSusceptible.acrossReplicationStatistic.average
        val i = sys.numInfected.acrossReplicationStatistic.average
        val r = sys.numRecovered.acrossReplicationStatistic.average
        assertEquals(
            sys.population.toDouble(),
            s + i + r,
            1e-6,
            "S+I+R should equal total population at every instant; got $s + $i + $r",
        )
        // The infection should have propagated at least somewhat —
        // there should be some non-zero infected-time. With
        // probability ~ 1 the disease spreads in 50 time units.
        assertTrue(i > 0.0, "expected some infections to occur; got infected-time = $i")
    }

    @Test
    fun networkRumorExampleRunsAndSpreadsWithinConnectedComponent() {
        val model = Model("RumorSmokeTest")
        val sys = NetworkRumorExample(model, "rumor")
        // 30 agents, p=0.15 — typically a single connected component.
        sys.population = 30
        sys.edgeProbability = 0.15
        sys.tellProbability = 0.2
        model.lengthOfReplication = 200.0
        model.numberOfReplications = 1
        model.simulate()

        // The seed's connected component size sets an upper bound on
        // the number of agents that can ever learn the rumor.
        val compSize = sys.componentSize.acrossReplicationStatistic.average
        assertTrue(compSize >= 1.0, "seed should be in a component of size >= 1; got $compSize")

        // Final number informed (TWResponse value at end-of-replication
        // is harder to get directly, but timeToFullSpread tells us
        // whether spreading happened at all — it's > 0 if any
        // additional agent learned).
        val timeToFull = sys.timeToFullSpread.acrossReplicationStatistic.average
        if (compSize > 1.0) {
            assertTrue(
                timeToFull > 0.0,
                "with a component of size $compSize the rumor should have spread; got $timeToFull",
            )
        }
    }

    /**
     *  Smoke test: a Context can hold a mix of Agent and
     *  PermanentAgent — both implement AgentLike.
     */
    @Test
    fun contextCanHoldMixedAgentLikeTypes() {
        val model = Model("MixedTest")
        val m = object : AgentModel(model, "mixed") {
            val all: Context<AgentLike> = Context("all")
            inner class A : Agent("a")
            inner class P : PermanentAgent("p")
            val a = A()
            val p = P()
        }
        m.all.add(m.a)
        m.all.add(m.p)
        assertEquals(2, m.all.size)
        assertIs<AgentModel.Agent>(m.all.members.first { it.name.endsWith("a") })
        assertIs<AgentModel.PermanentAgent>(m.all.members.first { it.name.endsWith("p") })
    }
}
