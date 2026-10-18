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
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 *  A [MovableAgentResource] moved by the agent verbs, `travelTo` and `startTravel`/`awaitTravel`, counts those
 *  trips on its odometers. Before R1.8 only fleet-commanded travel was counted, so a mover moved this way
 *  reported that it had never travelled.
 */
class AgentTravelOdometerTest {

    private class Mover(parent: ModelElement, private val trip: suspend ksl.modeling.entity.KSLProcessBuilder.(Mover) -> Unit) :
        AgentModel(parent, "Mover") {
        val world: Context<AgentLike> = Context("world")
        val floor: ContinuousProjection<AgentLike> = ContinuousProjection(world, 0.0..100.0, 0.0..100.0)
        val mover = MovableAgentResource(this, floor, initPosition = Point2D(50.0, 50.0), name = "mover")
        var distance = Double.NaN
        var operating = Double.NaN
        var handle: TravelHandle<MovableAgentResource>? = null
        var cancelAt = Double.NaN

        inner class Driver : Entity() {
            val script: KSLProcess = process {
                val a = seize(mover)
                trip(this@Mover)
                release(a)
                distance = mover.distanceTravelled
                operating = mover.operatingTime
            }
        }

        inner class Canceller : Entity() {
            val script: KSLProcess = process { delay(cancelAt); handle?.cancel() }
        }

        override fun initialize() {
            super.initialize()
            handle = null
            activate(Driver().script)
            if (!cancelAt.isNaN()) activate(Canceller().script)
        }
    }

    private fun odometers(
        cancelAt: Double = Double.NaN,
        trip: suspend ksl.modeling.entity.KSLProcessBuilder.(Mover) -> Unit
    ): Pair<Double, Double> {
        val m = Model("Odometers", autoCSVReports = false)
        val model = Mover(m, trip)
        model.cancelAt = cancelAt
        m.numberOfReplications = 2
        m.lengthOfReplication = 100.0
        m.simulate()
        return model.distance to model.operating
    }

    @Test
    fun travelToIsCounted() {
        val (distance, operating) = odometers { travelTo(it.mover, it.floor, Point2D(80.0, 50.0), velocity = 2.0) }
        assertEquals(30.0, distance, 1e-9, "the trip is 30 long")
        assertEquals(15.0, operating, 1e-9, "at velocity 2 it takes 15")
    }

    @Test
    fun awaitTravelIsCounted() {
        val (distance, operating) = odometers {
            val handle = startTravel(it.mover, it.floor, Point2D(80.0, 50.0), velocity = 2.0)
            awaitTravel(handle)
        }
        assertEquals(30.0, distance, 1e-9)
        assertEquals(15.0, operating, 1e-9)
    }

    @Test
    fun aCancelledTravelCountsWhatWasCovered() {
        // steps of 1 take 0.5 each; cancelled at 3.2, the trip stops at the end of the step in progress, at 3.5
        val (distance, operating) = odometers(cancelAt = 3.2) {
            val handle = startTravel(it.mover, it.floor, Point2D(80.0, 50.0), velocity = 2.0, stepSize = 1.0)
            it.handle = handle
            awaitTravel(handle)
        }
        assertEquals(7.0, distance, 1e-9, "seven steps of 1 were covered")
        assertEquals(3.5, operating, 1e-9)
    }
}
