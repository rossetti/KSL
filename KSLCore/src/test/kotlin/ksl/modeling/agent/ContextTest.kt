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
 *  Tests for `AgentModel.Context` — membership, projection notification, the
 *  per-replication reset of runtime membership, and end-to-end smoke tests that
 *  drive a context and its projection together through real example models.
 *
 *  **Phase D.** This file was a 1274-line umbrella covering `Context` and all three
 *  projections. The projection sections now live in `ContinuousProjectionTest`,
 *  `GridProjectionTest` and `NetworkProjectionTest`; what remains is `Context`
 *  itself, which is what the file is named for.
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
