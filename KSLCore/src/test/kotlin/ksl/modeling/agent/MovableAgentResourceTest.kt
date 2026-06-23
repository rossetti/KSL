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

import ksl.examples.general.agent.AutonomousDeliveryExample
import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 *  Phase 4.4 regression tests for [MovableAgentResource]: a
 *  seizable agent-layer resource whose position is tracked in a
 *  ContinuousProjection.
 */
class MovableAgentResourceTest {

    /** A model with a single forklift; entities can seize it and travel it around. */
    private open class WarehouseModel(parent: ModelElement) : AgentModel(parent, "warehouse") {
        val world: Context<AgentLike> = Context("world")
        val floor: ContinuousProjection<AgentLike> =
            ContinuousProjection(world, 0.0..100.0, 0.0..100.0)
    }

    // ── Construction wires up context and projection ────────────────────────

    @Test
    fun movableAgentResourceJoinsContextAndIsPlaced() {
        val model = Model("MovableConstructTest")
        val tm = object : WarehouseModel(model) {
            val forklift: MovableAgentResource = MovableAgentResource(
                this, floor, initPosition = Point2D(50.0, 50.0), name = "forklift",
            )
        }
        // Constructor puts the forklift in the world and at the initial position.
        // Verified via a probe agent that runs at t=0 and inspects state.
        val tm2 = object : AgentModel(model, "probe") {
            inner class Probe : Agent("probe") {
                var positionAtStart: Point2D? = null
                var inContextAtStart: Boolean = false
                val script: KSLProcess = process(isDefaultProcess = true) {
                    positionAtStart = tm.floor.positionOf(tm.forklift)
                    inContextAtStart = tm.forklift in tm.world
                }
            }
            val probe = Probe()
            override fun initialize() {
                super.initialize()
                activate(probe.script)
            }
        }
        model.lengthOfReplication = 1.0
        model.numberOfReplications = 1
        model.simulate()
        assertEquals(Point2D(50.0, 50.0), tm2.probe.positionAtStart)
        assertTrue(tm2.probe.inContextAtStart, "forklift should be in the world context")
    }

    // ── Resource semantics work as expected ─────────────────────────────────

    @Test
    fun movableAgentResourceCanBeSeizedAndReleased() {
        val model = Model("SeizeReleaseTest")
        val tm = object : WarehouseModel(model) {
            val forklift: MovableAgentResource = MovableAgentResource(
                this, floor, initPosition = Point2D(50.0, 50.0), name = "forklift",
            )
            val seizeTimes: MutableList<Double> = mutableListOf()
            val releaseTimes: MutableList<Double> = mutableListOf()

            inner class Driver(aName: String) : Agent(aName) {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    val a = seize(forklift)
                    seizeTimes.add(currentTime)
                    delay(2.0)
                    release(a)
                    releaseTimes.add(currentTime)
                }
            }
            val d1 = Driver("d1"); val d2 = Driver("d2"); val d3 = Driver("d3")

            override fun initialize() {
                super.initialize()
                activate(d1.script); activate(d2.script); activate(d3.script)
            }
        }
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        // Capacity 1 + 2-unit holds → serial: d1 seizes at 0/releases at 2,
        // d2 seizes at 2/releases at 4, d3 seizes at 4/releases at 6.
        assertEquals(listOf(0.0, 2.0, 4.0), tm.seizeTimes)
        assertEquals(listOf(2.0, 4.0, 6.0), tm.releaseTimes)
    }

    // ── Spatial queries find the moving resource ────────────────────────────

    @Test
    fun spatialQueriesSeeTheMovableResource() {
        val model = Model("SpatialQueryTest")
        val tm = object : WarehouseModel(model) {
            val forklift: MovableAgentResource = MovableAgentResource(
                this, floor, initPosition = Point2D(50.0, 50.0), name = "forklift",
            )
            val cargo: MovableAgentResource = MovableAgentResource(
                this, floor, initPosition = Point2D(60.0, 50.0), name = "cargo",
            )
            var withinResult: List<AgentLike>? = null
            var nearestResult: List<AgentLike>? = null
            inner class Probe : Agent("probe") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    withinResult = floor.within(Point2D(50.0, 50.0), radius = 15.0)
                    nearestResult = floor.nearest(Point2D(50.0, 50.0), 2)
                }
            }
            val probe = Probe()
            override fun initialize() {
                super.initialize()
                activate(probe.script)
            }
        }
        model.lengthOfReplication = 1.0
        model.numberOfReplications = 1
        model.simulate()
        // Both forklift and cargo are within 15 of (50, 50).
        assertTrue(tm.withinResult!!.size >= 2, "expected to find both movable resources")
        assertContains(tm.withinResult!!, tm.forklift)
        assertContains(tm.withinResult!!, tm.cargo)
        // The nearest 2 should be forklift (distance 0) and cargo (distance 10).
        assertEquals(tm.forklift, tm.nearestResult!![0])
        assertEquals(tm.cargo, tm.nearestResult!![1])
    }

    // ── travelTo moves the resource through the projection ──────────────────

    @Test
    fun seizedResourceCanBeMovedViaTravelTo() {
        val model = Model("TravelMovableTest")
        val tm = object : WarehouseModel(model) {
            val forklift: MovableAgentResource = MovableAgentResource(
                this, floor, initPosition = Point2D(0.0, 0.0), name = "forklift",
            )
            var arrivedAt: Double = Double.NaN
            inner class Driver : Agent("driver") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    val a = seize(forklift)
                    // Drive the forklift from (0,0) to (10,0) at velocity 2 → 5 time units.
                    val result = travelTo(
                        agent = forklift, space = floor,
                        destination = Point2D(10.0, 0.0),
                        velocity = 2.0, stepSize = 2.0,
                    )
                    arrivedAt = result.arrivedAt
                    release(a)
                }
            }
            val driver = Driver()
            override fun initialize() {
                super.initialize()
                activate(driver.script)
            }
        }
        model.lengthOfReplication = 20.0
        model.numberOfReplications = 1
        model.simulate()
        assertEquals(5.0, tm.arrivedAt, 1e-9)
        // Final position is the destination.
        assertEquals(Point2D(10.0, 0.0), tm.floor.positionOf(tm.forklift))
        // And the resource is back to idle (no current allocations).
        assertTrue(!tm.forklift.isBusy, "forklift should be released after travel")
    }

    // ── Off-shift behavior works ────────────────────────────────────────────

    @Test
    fun movableAgentResourceCanGoOffShift() {
        val model = Model("OffShiftMovableTest")
        val tm = object : WarehouseModel(model) {
            val forklift: MovableAgentResource = MovableAgentResource(
                this, floor, initPosition = Point2D(50.0, 50.0), name = "forklift",
            )
            var driverSeizedAt: Double = Double.NaN
            inner class Manager : Agent("manager") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    delay(0.1)
                    forklift.goOffShift()
                    delay(5.0)
                    forklift.goOnShift()
                }
            }
            inner class Driver : Agent("driver") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    // Try to seize at t=1, but forklift is off-shift; should wait until 5.1.
                    delay(1.0)
                    val a = seize(forklift)
                    driverSeizedAt = currentTime
                    release(a)
                }
            }
            val mgr = Manager(); val drv = Driver()
            override fun initialize() {
                super.initialize()
                activate(mgr.script); activate(drv.script)
            }
        }
        model.lengthOfReplication = 20.0
        model.numberOfReplications = 1
        model.simulate()
        // Forklift goes off-shift at 0.1, on-shift at 5.1. Driver tries
        // to seize at 1.0, waits until on-shift. Seizes at 5.1.
        assertEquals(5.1, tm.driverSeizedAt, 1e-9)
    }

    // ── AutonomousDeliveryExample (Phase 4.5) smoke test ────────────────────

    @Test
    fun autonomousDeliveryExampleProcessesOrdersEndToEnd() {
        val model = Model("DeliverySmokeTest")
        val sys = AutonomousDeliveryExample(model, "delivery")
        // Tighter timing for the test: orders every 4 time units on
        // average, 200-unit replication.
        sys.orderArrivalRV =
            ksl.utilities.random.rvariable.ExponentialRV(4.0, streamNum = 7, streamProvider = model.streamProvider)
        model.lengthOfReplication = 200.0
        model.numberOfReplications = 1
        model.simulate()

        // Some orders should have been processed: with mean inter-arrival
        // 4.0 and 200-unit run, ~50 orders arrive. Some may still be in
        // flight at end-of-run; verify at least a healthy fraction
        // completed.
        val delivered = sys.numOrdersDelivered.acrossReplicationStatistic.average
        assertTrue(
            delivered > 10.0,
            "expected at least 10 orders delivered; got $delivered",
        )

        // Average delivery time should be positive and bounded.
        // A delivery covers at most ~3 * (depot-to-anywhere distance) ~ 3 * 60 = 180 distance
        // at velocity 5 → ~36 time units, plus pickup+dropoff service time = ~40.
        // Account for queueing when all three trucks are busy.
        val avg = sys.deliveryTime.acrossReplicationStatistic.average
        assertTrue(avg > 0.0, "delivery time should be positive; got $avg")
        // Allow up to 4x the no-queue estimate for queueing delays.
        assertTrue(
            avg < 200.0,
            "delivery time should be bounded; got $avg (queueing may inflate this)",
        )

        // All trucks should have returned to depot by some point. Final
        // positions may or may not be exactly at depot if a delivery is
        // in progress, but they should be IN the projection.
        for (truck in sys.trucks) {
            assertTrue(
                sys.space.positionOf(truck) != null,
                "truck ${truck.name} should still be tracked in the projection",
            )
        }
    }
}
