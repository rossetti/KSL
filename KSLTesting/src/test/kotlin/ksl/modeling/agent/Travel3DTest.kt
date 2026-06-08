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
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Phase 6.4 regression tests: the 3D continuous-movement primitives
 *  [travelTo3D] / [travelThrough3D]. Mirrors [TravelTest] for 2D,
 *  extended to a third axis.
 */
class Travel3DTest {

    private open class TravelVolumeModel(parent: ModelElement) :
        AgentModel(parent, "travel3d") {
        val context: Context<Agent> = Context("agents")
        val space: ContinuousVolume<Agent> = ContinuousVolume(
            context,
            xRange = 0.0..100.0,
            yRange = 0.0..100.0,
            zRange = 0.0..50.0,
        )
    }

    // ── Arrival time = distance / velocity ─────────────────────────────────

    @Test
    fun travelTo3DArrivesAtTheCorrectTime() {
        val model = Model("Travel3DArrivalTest")
        val tm = object : TravelVolumeModel(model) {
            var result: TravelResult? = null
            inner class Drone : Agent("drone") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    // (0,0,0) → (3,4,12), distance = 13, velocity = 2 → t = 6.5
                    result = travelTo3D(
                        agent = this@Drone,
                        space = space,
                        destination = Point3D(3.0, 4.0, 12.0),
                        velocity = 2.0,
                        stepSize = 2.0,
                    )
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
        assertEquals(0.0, r.startedAt, 1e-9)
        assertEquals(6.5, r.arrivedAt, 1e-9)
        assertEquals(13.0, r.distance, 1e-9)
        assertEquals(2.0, r.averageVelocity, 1e-9)
        // Final position is exactly the destination.
        assertEquals(Point3D(3.0, 4.0, 12.0), tm.space.positionOf(tm.drone))
    }

    @Test
    fun travelTo3DInterpolatesAlongAllThreeAxes() {
        val model = Model("Travel3DInterpTest")
        val tm = object : TravelVolumeModel(model) {
            val observations: MutableList<Point3D> = mutableListOf()
            inner class Observer : Agent("observer") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    // Sample once per simulated time unit.
                    repeat(11) {
                        observations.add(space.positionOf(drone) ?: Point3D.ORIGIN)
                        delay(1.0)
                    }
                }
            }
            inner class Drone : Agent("drone") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    travelTo3D(
                        agent = this@Drone,
                        space = space,
                        destination = Point3D(10.0, 10.0, 10.0),  // length sqrt(300)
                        velocity = 1.0,
                        stepSize = 1.0,
                    )
                }
            }
            val drone = Drone()
            val obs = Observer()
            override fun initialize() {
                super.initialize()
                context.add(drone); context.add(obs)
                space.placeAt(drone, 0.0, 0.0, 0.0)
                activate(drone.script)
                activate(obs.script)
            }
        }
        model.lengthOfReplication = 30.0
        model.numberOfReplications = 1
        model.simulate()

        // Position should be monotonic in all three components — the
        // direction vector is (1, 1, 1) / sqrt(3), so x, y, z all
        // grow together.
        var lastX = -1.0
        var lastY = -1.0
        var lastZ = -1.0
        for (p in tm.observations) {
            assertTrue(p.x >= lastX - 1e-9, "x non-monotonic")
            assertTrue(p.y >= lastY - 1e-9, "y non-monotonic")
            assertTrue(p.z >= lastZ - 1e-9, "z non-monotonic")
            lastX = p.x; lastY = p.y; lastZ = p.z
        }
        // Throughout travel, x, y, z should track each other.
        for (p in tm.observations) {
            assertTrue(abs(p.x - p.y) < 1e-9, "x and y should stay equal along the diagonal")
            assertTrue(abs(p.x - p.z) < 1e-9, "x and z should stay equal along the diagonal")
        }
    }

    @Test
    fun travelTo3DZeroDistanceReturnsImmediately() {
        val model = Model("Travel3DZeroDistTest")
        val tm = object : TravelVolumeModel(model) {
            var result: TravelResult? = null
            inner class Hoverer : Agent("hover") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    result = travelTo3D(
                        agent = this@Hoverer, space = space,
                        destination = Point3D(5.0, 5.0, 5.0),  // same as start
                        velocity = 1.0,
                    )
                }
            }
            val hover = Hoverer()
            override fun initialize() {
                super.initialize()
                context.add(hover); space.placeAt(hover, 5.0, 5.0, 5.0)
                activate(hover.script)
            }
        }
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()
        val r = tm.result!!
        assertEquals(0.0, r.distance)
        assertEquals(0.0, r.duration)
    }

    @Test
    fun travelTo3DShortHopUsesSingleStep() {
        val model = Model("Travel3DShortHop")
        val tm = object : TravelVolumeModel(model) {
            var result: TravelResult? = null
            inner class Drone : Agent("d") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    // Distance 0.05 ≤ stepSize 0.1 → single delay-and-snap.
                    result = travelTo3D(
                        agent = this@Drone, space = space,
                        destination = Point3D(0.05, 0.0, 0.0),
                        velocity = 1.0, stepSize = 0.1,
                    )
                }
            }
            val drone = Drone()
            override fun initialize() {
                super.initialize()
                context.add(drone); space.placeAt(drone, 0.0, 0.0, 0.0)
                activate(drone.script)
            }
        }
        model.lengthOfReplication = 1.0
        model.numberOfReplications = 1
        model.simulate()
        val r = tm.result!!
        assertEquals(0.05, r.distance, 1e-9)
        assertEquals(0.05, r.duration, 1e-9)
        assertEquals(Point3D(0.05, 0.0, 0.0), tm.space.positionOf(tm.drone))
    }

    // ── travelThrough3D ────────────────────────────────────────────────────

    @Test
    fun travelThrough3DVisitsWaypointsInOrder() {
        val model = Model("Travel3DWaypoints")
        val tm = object : TravelVolumeModel(model) {
            var result: TravelResult? = null
            inner class Tourist : Agent("tourist") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    // Box path: (0,0,0) → (3,0,0) → (3,4,0) → (3,4,12) → back to origin
                    // Leg lengths: 3 + 4 + 12 + sqrt(9+16+144) = 19 + 13 = 32
                    result = travelThrough3D(
                        agent = this@Tourist, space = space,
                        waypoints = listOf(
                            Point3D(3.0, 0.0, 0.0),
                            Point3D(3.0, 4.0, 0.0),
                            Point3D(3.0, 4.0, 12.0),
                            Point3D(0.0, 0.0, 0.0),
                        ),
                        velocity = 1.0, stepSize = 1.0,
                    )
                }
            }
            val tourist = Tourist()
            override fun initialize() {
                super.initialize()
                context.add(tourist); space.placeAt(tourist, 0.0, 0.0, 0.0)
                activate(tourist.script)
            }
        }
        model.lengthOfReplication = 100.0
        model.numberOfReplications = 1
        model.simulate()
        val r = tm.result!!
        assertEquals(32.0, r.distance, 1e-9)
        assertEquals(32.0, r.duration, 1e-9)
        // Back at start.
        assertEquals(Point3D(0.0, 0.0, 0.0), tm.space.positionOf(tm.tourist))
    }

    // ── Validation ─────────────────────────────────────────────────────────

    @Test
    fun travelTo3DWithZeroVelocityThrows() {
        val model = Model("Travel3DZeroVelocity")
        var caught: Throwable? = null
        val tm = object : TravelVolumeModel(model) {
            inner class Drone : Agent("d") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    try {
                        travelTo3D(
                            agent = this@Drone, space = space,
                            destination = Point3D(1.0, 0.0, 0.0),
                            velocity = 0.0,
                        )
                    } catch (e: Throwable) {
                        caught = e
                    }
                }
            }
            val drone = Drone()
            override fun initialize() {
                super.initialize()
                context.add(drone); space.placeAt(drone, 0.0, 0.0, 0.0)
                activate(drone.script)
            }
        }
        model.lengthOfReplication = 1.0
        model.numberOfReplications = 1
        model.simulate()
        assertTrue(caught is IllegalArgumentException, "expected IllegalArgumentException; got $caught")
    }

    @Test
    fun travelTo3DWithUnplacedAgentThrows() {
        val model = Model("Travel3DUnplaced")
        var caught: Throwable? = null
        val tm = object : TravelVolumeModel(model) {
            inner class Drone : Agent("d") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    try {
                        // Intentionally not placing the drone before travel.
                        travelTo3D(
                            agent = this@Drone, space = space,
                            destination = Point3D(1.0, 0.0, 0.0),
                            velocity = 1.0,
                        )
                    } catch (e: Throwable) {
                        caught = e
                    }
                }
            }
            val drone = Drone()
            override fun initialize() {
                super.initialize()
                context.add(drone)
                // NOT calling space.placeAt
                activate(drone.script)
            }
        }
        model.lengthOfReplication = 1.0
        model.numberOfReplications = 1
        model.simulate()
        assertTrue(caught is IllegalStateException, "expected IllegalStateException; got $caught")
    }

    // ── Concurrent motion interpolation visible to other agents ────────────

    @Test
    fun concurrentMotionInterpolatesForSpatialQueries() {
        // Two drones flying toward each other; at mid-travel a spatial
        // query should see them close together.
        val model = Model("Travel3DConcurrent")
        val tm = object : TravelVolumeModel(model) {
            val distances: MutableList<Double> = mutableListOf()
            inner class Drone(aName: String, val dest: Point3D) : Agent(aName) {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    travelTo3D(this@Drone, space, dest, velocity = 1.0, stepSize = 0.5)
                }
            }
            val droneA = Drone("a", Point3D(10.0, 0.0, 0.0))
            val droneB = Drone("b", Point3D(0.0, 0.0, 0.0))
            inner class Observer : Agent("observer") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    repeat(11) {
                        val pa = space.positionOf(droneA)
                        val pb = space.positionOf(droneB)
                        if (pa != null && pb != null) {
                            distances.add(pa.distanceTo(pb))
                        }
                        delay(1.0)
                    }
                }
            }
            val obs = Observer()
            override fun initialize() {
                super.initialize()
                context.add(droneA); context.add(droneB); context.add(obs)
                space.placeAt(droneA, 0.0, 0.0, 0.0)
                space.placeAt(droneB, 10.0, 0.0, 0.0)
                activate(droneA.script); activate(droneB.script); activate(obs.script)
            }
        }
        model.lengthOfReplication = 20.0
        model.numberOfReplications = 1
        model.simulate()

        // Initial distance 10. Final distance — both arrived at their
        // destinations, which are each other's starts, so final
        // distance is again 10. At the midpoint they should be very
        // close.
        assertTrue(tm.distances.isNotEmpty(), "observer should have recorded samples")
        val midIndex = tm.distances.size / 2
        // At t=5 they should be near each other.
        assertTrue(
            tm.distances[midIndex] < 2.0,
            "at midpoint drones should be close; distance was ${tm.distances[midIndex]}",
        )
    }
}
