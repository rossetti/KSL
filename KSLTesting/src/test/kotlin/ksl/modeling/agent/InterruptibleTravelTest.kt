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
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Phase 12.6 regression tests for interruptible 2D and 3D travel:
 *  `startTravel` / `awaitTravel` plus `TravelHandle.cancel` and
 *  `redirect`.
 */
class InterruptibleTravelTest {

    // ── 2D test fixture ────────────────────────────────────────────────────

    private open class FlatModel(parent: ModelElement) : AgentModel(parent, "flat") {
        val context: Context<Agent> = Context("agents")
        val space: ContinuousProjection<Agent> = ContinuousProjection(
            context, xRange = 0.0..100.0, yRange = 0.0..100.0,
        )
    }

    private open class VolModel(parent: ModelElement) : AgentModel(parent, "vol") {
        val context: Context<Agent> = Context("agents")
        val space: ContinuousVolume<Agent> = ContinuousVolume(
            context, xRange = 0.0..100.0, yRange = 0.0..100.0, zRange = 0.0..50.0,
        )
    }

    // ── 2D: completes normally when not interrupted ────────────────────────

    @Test
    fun awaitTravelCompletesNormallyWithoutInterruption() {
        val model = Model("await-2d")
        val tm = object : FlatModel(model) {
            var result: TravelResult? = null
            var completed: Boolean = false
            inner class Walker : Agent("walker") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    val h = startTravel(
                        agent = this@Walker, space = space,
                        destination = Point2D(10.0, 0.0),
                        velocity = 2.0, stepSize = 2.0,
                    )
                    result = awaitTravel(h)
                    completed = h.isComplete
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

        val r = tm.result!!
        assertEquals(5.0, r.arrivedAt, 1e-9)   // distance 10 / velocity 2 = 5.0
        assertEquals(10.0, r.distance, 1e-9)
        assertTrue(tm.completed)
        assertEquals(Point2D(10.0, 0.0), tm.space.positionOf(tm.walker))
    }

    // ── 2D: cancel mid-travel stops the agent partway ──────────────────────

    @Test
    fun cancelStopsTravelAtCurrentInterpolatedPosition() {
        val model = Model("cancel-2d")
        val tm = object : FlatModel(model) {
            var result: TravelResult? = null
            inner class Walker : Agent("walker") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    val h = startTravel(
                        agent = this@Walker, space = space,
                        destination = Point2D(10.0, 0.0),
                        velocity = 1.0,    // 10 seconds at velocity 1
                        stepSize = 1.0,    // 10 steps of 1 second each
                    )
                    // Schedule a cancel at t = 3.0 from outside this
                    // agent's coroutine — use a Canceler agent.
                    canceler.handleRef = h
                    activate(canceler.script)
                    result = awaitTravel(h)
                }
            }
            inner class Canceler : Agent("canceler") {
                var handleRef: TravelHandle<*>? = null
                val script: KSLProcess = process(isDefaultProcess = false) {
                    delay(3.0)
                    handleRef?.cancel()
                }
            }
            val walker = Walker(); val canceler = Canceler()
            override fun initialize() {
                super.initialize()
                context.add(walker); context.add(canceler)
                space.placeAt(walker, 0.0, 0.0)
                activate(walker.script)
            }
        }
        model.lengthOfReplication = 20.0
        model.numberOfReplications = 1
        model.simulate()

        val r = tm.result!!
        // The walker should have stopped at or near t = 3.0 (the
        // cancel time), with position near (3.0, 0.0). At velocity 1
        // and stepSize 1, the cancel arrives mid-step; the integration
        // loop completes the current step before observing the cancel.
        // Allow a slight overshoot of one step.
        assertTrue(
            tm.walker.let { tm.space.positionOf(it)!!.x in 2.5..4.5 },
            "walker should be roughly mid-route; was at ${tm.space.positionOf(tm.walker)}",
        )
        assertTrue(r.distance < 10.0, "distance should be less than full travel; was ${r.distance}")
        assertTrue(r.distance > 0.0, "should have made some progress; was ${r.distance}")
        assertTrue(r.arrivedAt < 10.0, "should have ended before full arrival time; was ${r.arrivedAt}")
    }

    // ── 2D: redirect mid-travel changes the destination ────────────────────

    @Test
    fun redirectChangesDestinationMidTravel() {
        val model = Model("redirect-2d")
        val tm = object : FlatModel(model) {
            var result: TravelResult? = null
            var finalPos: Point2D? = null
            inner class Walker : Agent("walker") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    val h = startTravel(
                        agent = this@Walker, space = space,
                        destination = Point2D(10.0, 0.0),
                        velocity = 1.0, stepSize = 1.0,
                    )
                    director.handleRef = h
                    activate(director.script)
                    result = awaitTravel(h)
                    finalPos = space.positionOf(this@Walker)
                }
            }
            inner class Director : Agent("director") {
                var handleRef: TravelHandle<*>? = null
                val script: KSLProcess = process(isDefaultProcess = false) {
                    // At t = 3, redirect to (0, 5) — perpendicular direction.
                    delay(3.0)
                    handleRef?.redirect(Point2D(0.0, 5.0))
                }
            }
            val walker = Walker(); val director = Director()
            override fun initialize() {
                super.initialize()
                context.add(walker); context.add(director)
                space.placeAt(walker, 0.0, 0.0)
                activate(walker.script)
            }
        }
        model.lengthOfReplication = 30.0
        model.numberOfReplications = 1
        model.simulate()

        val r = tm.result!!
        // Should end up at the new destination (0, 5) — though the
        // integration may finish slightly off due to floating point.
        assertEquals(0.0, tm.finalPos!!.x, 1e-9)
        assertEquals(5.0, tm.finalPos!!.y, 1e-9)
        // Total distance includes the prefix toward (10, 0) plus the
        // re-routed leg toward (0, 5). At velocity 1 with redirect at
        // t = 3, the walker covered ~3 units east before redirecting;
        // then ~sqrt(9 + 25) = ~5.83 units back northwest.
        assertTrue(r.distance > 3.0, "should have at least the pre-redirect leg; was ${r.distance}")
        assertTrue(r.distance > 8.0, "should include the post-redirect leg; was ${r.distance}")
    }

    // ── 2D: handle reports correct lifecycle states ────────────────────────

    @Test
    fun handleStatesProgressFromActiveToComplete() {
        val model = Model("states-2d")
        val tm = object : FlatModel(model) {
            var handleRef: TravelHandle<*>? = null
            var activeMid: Boolean = false
            inner class Walker : Agent("walker") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    val h = startTravel(
                        agent = this@Walker, space = space,
                        destination = Point2D(10.0, 0.0),
                        velocity = 1.0, stepSize = 1.0,
                    )
                    handleRef = h
                    activeMid = h.isActive   // immediately after start
                    awaitTravel(h)
                }
            }
            val walker = Walker()
            override fun initialize() {
                super.initialize()
                context.add(walker); space.placeAt(walker, 0.0, 0.0)
                activate(walker.script)
            }
        }
        model.lengthOfReplication = 20.0
        model.numberOfReplications = 1
        model.simulate()

        val h = tm.handleRef!!
        assertTrue(tm.activeMid, "handle should be active right after startTravel")
        assertTrue(h.isComplete, "handle should be complete after await")
        assertFalse(h.isCanceled)
        assertFalse(h.isActive)
    }

    // ── 2D: startTravel validates inputs ───────────────────────────────────

    @Test
    fun startTravelRejectsNonPositiveVelocityAndStepSize() {
        val model = Model("validate-2d")
        var caughtVelocity: Throwable? = null
        var caughtStep: Throwable? = null
        val tm = object : FlatModel(model) {
            inner class Walker : Agent("walker") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    try {
                        startTravel(
                            agent = this@Walker, space = space,
                            destination = Point2D(1.0, 0.0),
                            velocity = 0.0,
                        )
                    } catch (e: Throwable) { caughtVelocity = e }
                    try {
                        startTravel(
                            agent = this@Walker, space = space,
                            destination = Point2D(1.0, 0.0),
                            velocity = 1.0,
                            stepSize = -0.5,
                        )
                    } catch (e: Throwable) { caughtStep = e }
                }
            }
            val walker = Walker()
            override fun initialize() {
                super.initialize()
                context.add(walker); space.placeAt(walker, 0.0, 0.0)
                activate(walker.script)
            }
        }
        model.lengthOfReplication = 1.0
        model.numberOfReplications = 1
        model.simulate()
        assertTrue(caughtVelocity is IllegalArgumentException)
        assertTrue(caughtStep is IllegalArgumentException)
    }

    // ── 3D: completes normally ─────────────────────────────────────────────

    @Test
    fun awaitTravel3DCompletesNormallyWithoutInterruption() {
        val model = Model("await-3d")
        val tm = object : VolModel(model) {
            var result: TravelResult? = null
            inner class Drone : Agent("drone") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    val h = startTravel3D(
                        agent = this@Drone, space = space,
                        destination = Point3D(3.0, 4.0, 12.0),    // dist 13
                        velocity = 2.0, stepSize = 2.0,
                    )
                    result = awaitTravel3D(h)
                }
            }
            val drone = Drone()
            override fun initialize() {
                super.initialize()
                context.add(drone)
                space.placeAt(drone, 0.0, 0.0, 0.0)
                activate(drone.script)
            }
        }
        model.lengthOfReplication = 100.0
        model.numberOfReplications = 1
        model.simulate()

        val r = tm.result!!
        assertEquals(6.5, r.arrivedAt, 1e-9)   // 13 / 2 = 6.5
        assertEquals(13.0, r.distance, 1e-9)
        assertEquals(Point3D(3.0, 4.0, 12.0), tm.space.positionOf(tm.drone))
    }

    // ── 3D: cancel mid-travel ──────────────────────────────────────────────

    @Test
    fun cancelStopsTravel3DAtCurrentPosition() {
        val model = Model("cancel-3d")
        val tm = object : VolModel(model) {
            var result: TravelResult? = null
            inner class Drone : Agent("drone") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    val h = startTravel3D(
                        agent = this@Drone, space = space,
                        destination = Point3D(0.0, 0.0, 10.0),    // straight up
                        velocity = 1.0, stepSize = 1.0,
                    )
                    canceler.handleRef = h
                    activate(canceler.script)
                    result = awaitTravel3D(h)
                }
            }
            inner class Canceler : Agent("canceler") {
                var handleRef: TravelHandle3D<*>? = null
                val script: KSLProcess = process(isDefaultProcess = false) {
                    delay(4.0)
                    handleRef?.cancel()
                }
            }
            val drone = Drone(); val canceler = Canceler()
            override fun initialize() {
                super.initialize()
                context.add(drone); context.add(canceler)
                space.placeAt(drone, 0.0, 0.0, 0.0)
                activate(drone.script)
            }
        }
        model.lengthOfReplication = 20.0
        model.numberOfReplications = 1
        model.simulate()

        val r = tm.result!!
        val finalPos = tm.space.positionOf(tm.drone)!!
        // Cancel at t = 4 stops the drone partway up the z-axis.
        // x and y should be exactly zero (motion is straight up).
        assertEquals(0.0, finalPos.x, 1e-9)
        assertEquals(0.0, finalPos.y, 1e-9)
        // z is roughly 4 (the cancel time at velocity 1).
        assertTrue(finalPos.z in 3.5..5.5, "drone should be partway up z; was $finalPos")
        assertTrue(r.distance < 10.0, "should not have covered the full distance; was ${r.distance}")
    }

    // ── 3D: redirect mid-flight ────────────────────────────────────────────

    @Test
    fun redirect3DChangesDestinationMidFlight() {
        val model = Model("redirect-3d")
        val tm = object : VolModel(model) {
            var result: TravelResult? = null
            var finalPos: Point3D? = null
            inner class Drone : Agent("drone") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    val h = startTravel3D(
                        agent = this@Drone, space = space,
                        destination = Point3D(10.0, 0.0, 0.0),
                        velocity = 1.0, stepSize = 1.0,
                    )
                    director.handleRef = h
                    activate(director.script)
                    result = awaitTravel3D(h)
                    finalPos = space.positionOf(this@Drone)
                }
            }
            inner class Director : Agent("director") {
                var handleRef: TravelHandle3D<*>? = null
                val script: KSLProcess = process(isDefaultProcess = false) {
                    // At t = 3, redirect to a point upward instead.
                    delay(3.0)
                    handleRef?.redirect(Point3D(0.0, 0.0, 5.0))
                }
            }
            val drone = Drone(); val director = Director()
            override fun initialize() {
                super.initialize()
                context.add(drone); context.add(director)
                space.placeAt(drone, 0.0, 0.0, 0.0)
                activate(drone.script)
            }
        }
        model.lengthOfReplication = 30.0
        model.numberOfReplications = 1
        model.simulate()

        val r = tm.result!!
        // Should arrive at the new destination (0, 0, 5).
        assertEquals(0.0, tm.finalPos!!.x, 1e-9)
        assertEquals(0.0, tm.finalPos!!.y, 1e-9)
        assertEquals(5.0, tm.finalPos!!.z, 1e-9)
        // Total distance includes both legs.
        assertTrue(r.distance > 3.0, "should include pre-redirect leg; was ${r.distance}")
    }

    // ── 3D: handle states ──────────────────────────────────────────────────

    @Test
    fun handle3DStatesProgressFromActiveToComplete() {
        val model = Model("states-3d")
        val tm = object : VolModel(model) {
            var handleRef: TravelHandle3D<*>? = null
            var activeMid: Boolean = false
            inner class Drone : Agent("drone") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    val h = startTravel3D(
                        agent = this@Drone, space = space,
                        destination = Point3D(0.0, 0.0, 5.0),
                        velocity = 1.0, stepSize = 1.0,
                    )
                    handleRef = h
                    activeMid = h.isActive
                    awaitTravel3D(h)
                }
            }
            val drone = Drone()
            override fun initialize() {
                super.initialize()
                context.add(drone); space.placeAt(drone, 0.0, 0.0, 0.0)
                activate(drone.script)
            }
        }
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        val h = tm.handleRef!!
        assertTrue(tm.activeMid)
        assertTrue(h.isComplete)
        assertFalse(h.isCanceled)
    }
}
