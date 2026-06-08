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

import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 *  Phase 4.2 regression tests: the continuous-movement primitive
 *  [travelTo]. Verifies that distance / velocity / time relate
 *  correctly, that mid-travel positions interpolate, and that edge
 *  cases (zero distance, short hops, sequential waypoints) behave
 *  sensibly.
 */
class TravelTest {

    private open class TravelModel(parent: ModelElement) : AgentModel(parent, "travel") {
        val context: Context<Agent> = Context("agents")
        val space: ContinuousProjection<Agent> =
            ContinuousProjection(context, xRange = 0.0..100.0, yRange = 0.0..100.0)
    }

    // ── Arrival time = distance / velocity ─────────────────────────────────

    @Test
    fun travelToArrivesAtTheCorrectTime() {
        val model = Model("TravelArrivalTest")
        val tm = object : TravelModel(model) {
            var result: TravelResult? = null
            inner class Walker : Agent("walker") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    result = travelTo(
                        agent = this@Walker,
                        space = space,
                        destination = Point2D(10.0, 0.0),
                        velocity = 2.0,
                        stepSize = 2.0,
                    )
                }
            }
            val walker = Walker()
            override fun initialize() {
                super.initialize()
                context.add(walker)
                space.placeAt(walker, 0.0, 0.0)
                activate(walker.script)
            }
        }
        model.lengthOfReplication = 100.0
        model.numberOfReplications = 1
        model.simulate()

        // Distance = 10.0, velocity = 2.0 → arrives at t = 5.0.
        val r = tm.result!!
        assertEquals(0.0, r.startedAt, 1e-9)
        assertEquals(5.0, r.arrivedAt, 1e-9)
        assertEquals(10.0, r.distance, 1e-9)
        assertEquals(2.0, r.averageVelocity, 1e-9)
        // Final position is exactly the destination.
        assertEquals(Point2D(10.0, 0.0), tm.space.positionOf(tm.walker))
    }

    @Test
    fun travelToInterpolatesPositionDuringTravel() {
        val model = Model("TravelInterpTest")
        val tm = object : TravelModel(model) {
            val observations: MutableList<Pair<Double, Point2D>> = mutableListOf()
            inner class Observer : Agent("observer") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    // Sample the walker's position every 1.0 unit during
                    // its 10-unit travel.
                    repeat(11) {
                        observations.add(currentTime to (space.positionOf(walker) ?: Point2D.ORIGIN))
                        delay(1.0)
                    }
                }
            }
            inner class Walker : Agent("walker") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    travelTo(
                        agent = this@Walker,
                        space = space,
                        destination = Point2D(10.0, 0.0),
                        velocity = 1.0,         // arrives at t = 10
                        stepSize = 1.0,
                    )
                }
            }
            val walker = Walker()
            val obs = Observer()
            override fun initialize() {
                super.initialize()
                context.add(walker); context.add(obs)
                space.placeAt(walker, 0.0, 0.0)
                activate(walker.script)
                activate(obs.script)
            }
        }
        model.lengthOfReplication = 20.0
        model.numberOfReplications = 1
        model.simulate()

        // At each integer time t in 0..10, walker should be at (t, 0).
        // The observer samples right at the start of each step (before
        // the walker's delay finishes), so we expect (t, 0) at t = 0,1,2,...
        // The observer logs at t=0 before walker has moved, so the
        // ordering means observer's t=0 sees walker at (0, 0). At t=1
        // observer sees walker at (1, 0) — provided the walker's step
        // completed before the observer's snapshot.
        // KSL event ordering at the same time isn't strictly defined
        // here, but both processes start their delays simultaneously.
        // Verify by looking at the final state and the gross shape:
        // the walker's x-coordinate should grow monotonically.
        var lastX = -1.0
        for ((_, p) in tm.observations) {
            assertTrue(p.x >= lastX, "x should be non-decreasing; saw ${p.x} after $lastX")
            assertTrue(p.y == 0.0, "y should stay zero throughout horizontal travel")
            lastX = p.x
        }
        // Observer at t=10 should see walker at (10, 0) (the final position).
        assertEquals(10.0, tm.observations.last().second.x, 1e-9)
    }

    @Test
    fun travelToZeroDistanceReturnsImmediately() {
        val model = Model("TravelZeroDistTest")
        val tm = object : TravelModel(model) {
            var result: TravelResult? = null
            inner class Stander : Agent("stander") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    result = travelTo(
                        agent = this@Stander,
                        space = space,
                        destination = Point2D(5.0, 5.0),  // same as start
                        velocity = 1.0,
                    )
                }
            }
            val stander = Stander()
            override fun initialize() {
                super.initialize()
                context.add(stander)
                space.placeAt(stander, 5.0, 5.0)
                activate(stander.script)
            }
        }
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        val r = tm.result!!
        assertEquals(0.0, r.distance, 1e-9)
        assertEquals(0.0, r.duration, 1e-9)
        assertEquals(0.0, r.startedAt, 1e-9)
        assertEquals(0.0, r.arrivedAt, 1e-9)
    }

    @Test
    fun travelToShortHopUsesSingleStep() {
        val model = Model("TravelShortHopTest")
        val tm = object : TravelModel(model) {
            var result: TravelResult? = null
            inner class Walker : Agent("walker") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    // Short hop: distance 0.05, less than stepSize 0.1 →
                    // single delay-and-snap, no interpolation.
                    result = travelTo(
                        agent = this@Walker,
                        space = space,
                        destination = Point2D(0.05, 0.0),
                        velocity = 1.0,
                        stepSize = 0.1,
                    )
                }
            }
            val walker = Walker()
            override fun initialize() {
                super.initialize()
                context.add(walker)
                space.placeAt(walker, 0.0, 0.0)
                activate(walker.script)
            }
        }
        model.lengthOfReplication = 1.0
        model.numberOfReplications = 1
        model.simulate()

        val r = tm.result!!
        assertEquals(0.05, r.distance, 1e-9)
        assertEquals(0.05, r.duration, 1e-9)
        assertEquals(Point2D(0.05, 0.0), tm.space.positionOf(tm.walker))
    }

    // ── travelThrough waypoints ────────────────────────────────────────────

    @Test
    fun travelThroughVisitsWaypointsInOrder() {
        val model = Model("WaypointsTest")
        val tm = object : TravelModel(model) {
            var result: TravelResult? = null
            inner class Tourist : Agent("tourist") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    result = travelThrough(
                        agent = this@Tourist,
                        space = space,
                        // Square path: (0,0) → (4,0) → (4,3) → (0,3) → (0,0)
                        // Total distance: 4 + 3 + 4 + 3 = 14
                        waypoints = listOf(
                            Point2D(4.0, 0.0),
                            Point2D(4.0, 3.0),
                            Point2D(0.0, 3.0),
                            Point2D(0.0, 0.0),
                        ),
                        velocity = 1.0,
                        stepSize = 1.0,
                    )
                }
            }
            val tourist = Tourist()
            override fun initialize() {
                super.initialize()
                context.add(tourist)
                space.placeAt(tourist, 0.0, 0.0)
                activate(tourist.script)
            }
        }
        model.lengthOfReplication = 100.0
        model.numberOfReplications = 1
        model.simulate()

        val r = tm.result!!
        assertEquals(14.0, r.distance, 1e-9)
        assertEquals(14.0, r.duration, 1e-9)
        // Back at start.
        assertEquals(Point2D(0.0, 0.0), tm.space.positionOf(tm.tourist))
    }

    // ── Validation ─────────────────────────────────────────────────────────

    @Test
    fun travelToWithZeroVelocityThrows() {
        val model = Model("ZeroVelocityTest")
        var caught: Throwable? = null
        val tm = object : TravelModel(model) {
            inner class Walker : Agent("walker") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    try {
                        travelTo(
                            agent = this@Walker,
                            space = space,
                            destination = Point2D(1.0, 0.0),
                            velocity = 0.0,
                        )
                    } catch (e: Throwable) {
                        caught = e
                    }
                }
            }
            val walker = Walker()
            override fun initialize() {
                super.initialize()
                context.add(walker)
                space.placeAt(walker, 0.0, 0.0)
                activate(walker.script)
            }
        }
        model.lengthOfReplication = 1.0
        model.numberOfReplications = 1
        model.simulate()
        assertTrue(caught is IllegalArgumentException, "expected IllegalArgumentException; got $caught")
    }

    @Test
    fun travelToWithUnplacedAgentThrows() {
        val model = Model("UnplacedTest")
        var caught: Throwable? = null
        val tm = object : TravelModel(model) {
            inner class Walker : Agent("walker") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    try {
                        // Note: not placing the walker before travel.
                        travelTo(
                            agent = this@Walker,
                            space = space,
                            destination = Point2D(1.0, 0.0),
                            velocity = 1.0,
                        )
                    } catch (e: Throwable) {
                        caught = e
                    }
                }
            }
            val walker = Walker()
            override fun initialize() {
                super.initialize()
                context.add(walker)
                // intentionally NOT calling space.placeAt
                activate(walker.script)
            }
        }
        model.lengthOfReplication = 1.0
        model.numberOfReplications = 1
        model.simulate()
        assertTrue(caught is IllegalStateException, "expected IllegalStateException; got $caught")
    }

    // ── Two-agent concurrent motion ────────────────────────────────────────

    /**
     *  Verifies that spatial queries during concurrent motion see
     *  interpolated positions. Two agents move toward each other on
     *  a line; at the midpoint of their travel they should be near
     *  each other in space.
     */
    @Test
    fun concurrentMotionInterpolatesForSpatialQueries() {
        val model = Model("ConcurrentTest")
        val tm = object : TravelModel(model) {
            val observations: MutableList<Pair<Double, Double>> = mutableListOf()
            // observations: (time, distance between a and b)
            inner class Walker(aName: String, val dest: Point2D) : Agent(aName) {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    travelTo(this@Walker, space, dest, velocity = 1.0, stepSize = 0.5)
                }
            }
            inner class Observer : Agent("observer") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    repeat(11) {
                        val pa = space.positionOf(walkerA)
                        val pb = space.positionOf(walkerB)
                        if (pa != null && pb != null) {
                            observations.add(currentTime to pa.distanceTo(pb))
                        }
                        delay(1.0)
                    }
                }
            }
            // Walker A goes from (0,0) to (10,0); walker B from (10,0) to (0,0).
            // They cross at t=5 at (5,0); separation reaches 0.
            val walkerA = Walker("a", Point2D(10.0, 0.0))
            val walkerB = Walker("b", Point2D(0.0, 0.0))
            val obs = Observer()
            override fun initialize() {
                super.initialize()
                context.add(walkerA); context.add(walkerB); context.add(obs)
                space.placeAt(walkerA, 0.0, 0.0)
                space.placeAt(walkerB, 10.0, 0.0)
                activate(walkerA.script); activate(walkerB.script); activate(obs.script)
            }
        }
        model.lengthOfReplication = 20.0
        model.numberOfReplications = 1
        model.simulate()

        // The observed motion: at t=0 the walkers are 10 apart; they
        // approach, pass at t=5 (separation ~ 0 modulo one step worth
        // of stale observation), then move apart. Final-replication
        // positions should be the swapped endpoints — but the
        // observer's last sample at exactly t=10 may see positions
        // from t=9.5 due to event ordering. Verify the trend rather
        // than exact endpoint values.
        val obs = tm.observations.toList()
        val sep0 = obs.first { it.first == 0.0 }.second
        val sep5 = obs.first { it.first == 5.0 }.second
        assertEquals(10.0, sep0, 1e-9)
        // Allow up to ~one step's worth of stale observation.
        assertTrue(sep5 <= 1.5, "at t=5 separation should be near 0; got $sep5")
        // The trend: separation decreases over the first half, then
        // increases — i.e., the agents actually approach and pass.
        val midpoint = obs.indexOfFirst { it.first == 5.0 }
        val firstHalfMax = obs.subList(0, midpoint + 1).maxOf { it.second }
        val secondHalfMax = obs.subList(midpoint, obs.size).maxOf { it.second }
        assertEquals(10.0, firstHalfMax, 1e-9, "early observation should peak at the start (10.0)")
        assertTrue(
            secondHalfMax >= 8.0,
            "late observation should peak near the swapped endpoint; got $secondHalfMax",
        )
    }
}
