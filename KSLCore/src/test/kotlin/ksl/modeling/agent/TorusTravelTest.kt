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

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 *  The travel verbs on a torus go the short way round, the distance the projection itself measures. Before
 *  R1.8 they measured and moved along the straight line between the raw points: on a floor 100 wide, a trip
 *  from x = 95 to x = 5 went 90 across the middle instead of 10 across the edge.
 */
class TorusTravelTest {

    /** One agent at (95, 50) on a 100 by 100 floor, and a probe that reports where it is at [probeAt], between steps of 1. */
    private class Floor(
        parent: ModelElement,
        torus: Boolean,
        private val probeAt: Double,
        private val trip: suspend KSLProcessBuilder.(Floor) -> TravelResult
    ) : AgentModel(parent, "Floor") {
        val world: Context<AgentLike> = Context("world")
        val floor: ContinuousProjection<AgentLike> =
            ContinuousProjection(world, 0.0..100.0, 0.0..100.0, torus = torus)
        val mover = MovableAgentResource(this, floor, initPosition = Point2D(95.0, 50.0), name = "mover")
        var result: TravelResult? = null
        var distanceToEdge = Double.NaN

        inner class Driver : Entity() {
            val script: KSLProcess = process {
                val a = seize(mover)
                result = trip(this@Floor)
                release(a)
            }
        }

        inner class Probe : Entity() {
            val script: KSLProcess = process {
                delay(probeAt)
                distanceToEdge = floor.distance(floor.positionOf(mover)!!, Point2D(0.0, 50.0))
            }
        }

        override fun initialize() {
            super.initialize()
            result = null
            activate(Driver().script)
            activate(Probe().script)
        }
    }

    private fun travel(
        torus: Boolean,
        probeAt: Double = 2.75,
        trip: suspend KSLProcessBuilder.(Floor) -> TravelResult
    ): Floor {
        val m = Model("TorusTravel", autoCSVReports = false)
        val model = Floor(m, torus, probeAt, trip)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        return model
    }

    private val toFive: suspend KSLProcessBuilder.(Floor) -> TravelResult =
        { travelTo(it.mover, it.floor, Point2D(5.0, 50.0), velocity = 2.0) }

    @Test
    fun travelToGoesTheShortWayRound() {
        val model = travel(torus = true, trip = toFive)
        val r = model.result!!
        assertEquals(10.0, r.distance, 1e-9)
        assertEquals(5.0, r.duration, 1e-9)
        assertEquals(0.0, model.distanceToEdge, 1e-9, "half way, by the step ending at 2.5, the agent is at the edge")
        assertEquals(Point2D(5.0, 50.0), model.floor.positionOf(model.mover))
        assertEquals(10.0, model.mover.distanceTravelled, 1e-9, "the odometer counts the trip it made")
    }

    @Test
    fun awaitTravelGoesTheShortWayRound() {
        val model = travel(torus = true) {
            awaitTravel(startTravel(it.mover, it.floor, Point2D(5.0, 50.0), velocity = 2.0))
        }
        val r = model.result!!
        assertEquals(10.0, r.distance, 1e-9)
        assertEquals(5.0, r.duration, 1e-9)
        assertEquals(0.0, model.distanceToEdge, 1e-9)
        assertEquals(Point2D(5.0, 50.0), model.floor.positionOf(model.mover))
    }

    @Test
    fun aPlaneIsUnchanged() {
        val model = travel(torus = false, probeAt = 22.75, trip = toFive)
        val r = model.result!!
        assertEquals(90.0, r.distance, 1e-9)
        assertEquals(45.0, r.duration, 1e-9)
        assertEquals(50.0, model.distanceToEdge, 1e-9, "half way, by the step ending at 22.5, the agent is in the middle")
    }

    /** One agent at (50, 50, 45) in a 100 by 100 by 50 volume that wraps, travelling to (50, 50, 5). */
    private class Volume(parent: ModelElement, private val interruptible: Boolean) : AgentModel(parent, "Volume") {
        val context: Context<Agent> = Context("agents")
        val space: ContinuousVolume<Agent> =
            ContinuousVolume(context, 0.0..100.0, 0.0..100.0, 0.0..50.0, torus = true)
        var result: TravelResult? = null

        inner class Drone : Agent("drone") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                val destination = Point3D(50.0, 50.0, 5.0)
                result = if (interruptible) {
                    awaitTravel3D(startTravel3D(this@Drone, space, destination, velocity = 2.0))
                } else {
                    travelTo3D(this@Drone, space, destination, velocity = 2.0)
                }
            }
        }

        val drone = Drone()

        override fun initialize() {
            super.initialize()
            result = null
            context.add(drone)
            space.placeAt(drone, 50.0, 50.0, 45.0)
            activate(drone.script)
        }
    }

    @Test
    fun travelIn3DGoesTheShortWayRound() {
        for (interruptible in listOf(false, true)) {
            val m = Model("TorusTravel3D", autoCSVReports = false)
            val model = Volume(m, interruptible)
            m.numberOfReplications = 1
            m.lengthOfReplication = 100.0
            m.simulate()
            val r = model.result!!
            assertEquals(10.0, r.distance, 1e-9, "interruptible = $interruptible")
            assertEquals(5.0, r.duration, 1e-9, "interruptible = $interruptible")
        }
    }
}
