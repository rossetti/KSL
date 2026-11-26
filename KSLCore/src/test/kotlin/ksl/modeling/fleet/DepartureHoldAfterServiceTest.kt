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
package ksl.modeling.fleet

import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.spatial.DistancesModel
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A stop's departure time holds the vehicle after its action whether the action completed the stop's task (a
 *  set-down) or not (a pickup); only a task taken back or cancelled lets it go early.
 */
class DepartureHoldAfterServiceTest {

    /**
     *  One van at A, 1 from B at speed 1. The first load goes A to B at 0; a second goes B to A at 2. The van holds
     *  until 10 at the stop [holdAt] names: the first load's set-down at B, or its pickup at A.
     */
    private class Shop(parent: ModelElement, val holdAtPickup: Boolean) : ProcessModel(parent, "Shop") {
        val distances = DistancesModel().apply { addDistance("A", "B", 1.0, symmetric = true) }

        init {
            spatialModel = distances
        }

        val fleet = FreePathFleet(this, distances, name = "Fleet")
        val van = FreePathVehicle(fleet, "A", ConstantRV(1.0), name = "Van").apply {
            stopControl = object : StopControlIfc {
                private var held = false
                override suspend fun KSLProcessBuilder.instruct(vehicle: FleetVehicle, stop: TourStop, tour: Tour): StopInstruction {
                    val here = if (holdAtPickup) stop.location == "A" && stop.action.loadChange > 0
                        else stop.location == "B" && stop.action.loadChange < 0
                    return if (here && !held) {
                        held = true
                        StopInstruction.Serve(departNotBefore = 10.0)
                    } else StopInstruction.Serve()
                }
            }
        }
        val delivered = mutableListOf<String>()

        inner class Load(val tag: String, val from: Double, val origin: String, val destination: String) : Entity(tag) {
            val p = process {
                delay(from)
                currentLocation = distances.location(origin)!!
                transportByFleet(fleet, destination, origin)
                delivered.add("$tag@$time")
            }
        }

        override fun initialize() {
            activate(Load("First", 0.0, "A", "B").p)
            activate(Load("Second", 2.0, "B", "A").p)
        }
    }

    private fun run(holdAtPickup: Boolean): Shop {
        val model = Model("DepartureHold${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model, holdAtPickup)
        model.lengthOfReplication = 30.0
        model.simulate()
        return shop
    }

    @Test
    @DisplayName("a set-down that completes its task still holds the vehicle until the stop's departure time")
    fun setDownKeepsItsHold() {
        // First is set down at B at 1; the van holds there until 10, then carries Second, waiting at B since 2, to A by 11
        assertEquals(listOf("First@1.0", "Second@11.0"), run(holdAtPickup = false).delivered)
    }

    @Test
    @DisplayName("a pickup holds the vehicle until the stop's departure time")
    fun pickupKeepsItsHold() {
        // First is picked up at A at 0 and the van leaves at 10, so First is at B by 11; Second, at B since 2, by 12
        assertEquals(listOf("First@11.0", "Second@12.0"), run(holdAtPickup = true).delivered)
    }
}
