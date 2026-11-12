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

import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.MovableResource
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Pins today's shifts on the two non-guided vehicle kinds that keep their own copy of the shift
 *  functions: what a new replication starts with after one that ended off shift, and what
 *  `goOnShift` restores when the initial capacity was changed after construction.
 */
class ShiftCharacterizationTest {

    private class ShiftModel(parent: ModelElement, desk: Int) : AgentModel(parent, "shifts") {
        private val speed = ConstantRV(10.0)
        private val dm = DistancesModel()
        private val locA = dm.Location("A")

        init {
            dm.addDistance(locA, dm.Location("B"), 100.0, symmetric = true)
            dm.defaultVelocity = speed
            spatialModel = dm
        }

        val agentDesk = AgentResource(this, "Desk", capacity = desk)
        val truck = MovableResource(this, locA, speed, "Truck")
        val seen = mutableListOf<String>()
        val setups = mutableListOf<ShiftModel.() -> Unit>()

        fun at(t: Double, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t)
        }

        fun state(): String =
            "desk off=${agentDesk.isOffShift} cap=${agentDesk.capacity}; truck off=${truck.isOffShift} cap=${truck.capacity}"

        override fun initialize() {
            super.initialize()
            for (s in setups) s()
        }
    }

    private fun run(desk: Int = 2, reps: Int = 1, setup: ShiftModel.() -> Unit): ShiftModel {
        val model = Model("Shifts${System.nanoTime()}", autoCSVReports = false)
        val m = ShiftModel(model, desk)
        m.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = 20.0
        model.simulate()
        return m
    }

    @Test
    @DisplayName("a replication that ends off shift: the next starts on shift with the initial capacity")
    fun offShiftAtEndOfReplication() {
        val m = run(reps = 3) {
            at(1.0) { seen.add("start " + state()) }
            at(5.0) { agentDesk.goOffShift(); truck.goOffShift(); seen.add("off " + state()) }
        }
        assertEquals(
            List(3) {
                listOf(
                    "start desk off=false cap=2; truck off=false cap=1",
                    "off desk off=true cap=0; truck off=true cap=0"
                )
            }.flatten(),
            m.seen
        )
    }

    @Test
    @DisplayName("today: goOnShift on an AgentResource restores the capacity it was constructed with, not a changed initial capacity")
    fun restoresConstructedCapacity() {
        val model = Model("Restore${System.nanoTime()}", autoCSVReports = false)
        val m = ShiftModel(model, desk = 2)
        m.agentDesk.initialCapacity = 3
        m.setups.add {
            at(1.0) { seen.add("start cap=${agentDesk.capacity}") }
            at(2.0) { agentDesk.goOffShift() }
            at(3.0) { agentDesk.goOnShift(); seen.add("back cap=${agentDesk.capacity}, onShiftCapacity=${agentDesk.onShiftCapacity}") }
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()
        assertEquals(listOf("start cap=3", "back cap=2, onShiftCapacity=2"), m.seen)
    }
}
