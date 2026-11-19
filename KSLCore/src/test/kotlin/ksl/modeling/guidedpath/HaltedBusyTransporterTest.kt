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

package ksl.modeling.guidedpath

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A pool's halted transporters include one a movement gate holds while it is still allocated.
 *
 *  One cart at A on the loop A, B, P, links 100 long in 25-long zones, speed 10. A part at P asks for it at 0, is
 *  collected at 20 and carried toward A; the gate is closed at 25, and the cart stops at the next zone boundary.
 *  The halted journey returns to the part, which keeps the cart.
 */
class HaltedBusyTransporterTest {

    private class Gate(var closed: Boolean = false) : TransporterMovementGateIfc {
        override fun mayContinue(transporter: GuidedTransporter, zone: Zone): Boolean = !closed
    }

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .link("AB", "A", "B", length = 100.0, zoneLength = 25.0, beginDirection = 0.0)
            .link("BP", "B", "P", length = 100.0, zoneLength = 25.0, beginDirection = 90.0)
            .link("PA", "P", "A", length = 100.0, zoneLength = 25.0, beginDirection = 180.0)
            .build()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val gate = Gate()
        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart")
            .apply { attachMovementGate(gate) }
        val carts = GuidedTransporterPoolWithQ(this, system, listOf(cart), name = "Carts")
        val notes = mutableListOf<String>()

        inner class Part : Entity("Part") {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("P")
                val request = requestGuidedTransporter(carts, "P")
                transportBy(request, "A")
                // a halted journey returns at the halt; this part keeps the cart while it decides what to do
                delay(100.0)
                releaseGuidedTransporter(request, carts)
            }
        }

        override fun initialize() {
            notes.clear()
            gate.closed = false
            activate(Part().p)
            schedule({ _: KSLEvent<Nothing> -> gate.closed = true }, 25.0)
            schedule({ _: KSLEvent<Nothing> ->
                notes.add("halted=${cart.isHalted} busy=${cart.numBusy} listed=${carts.haltedTransporters.map { it.name }}")
            }, 30.0)
        }
    }

    @Test
    @DisplayName("a transporter a gate holds while it is allocated is listed as halted")
    fun busyHaltedIsListed() {
        val model = Model("HaltedBusy${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 40.0
        model.simulate()
        assertEquals(listOf("halted=true busy=1 listed=[Cart]"), shop.notes)
    }
}
