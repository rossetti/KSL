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
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A guided transport with no suspension name names its loading step after the transporter. Before R1.8 it
 *  read "null:loading", which a layout could not bind to.
 */
class TransportStepNamesTest {

    private class OneTrip(parent: ModelElement, private val tripName: String?) : ProcessModel(parent, "OneTrip") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Line")
            .intersection("A").intersection("B")
            .link("L1", "A", "B", length = 40.0, zoneLength = 10.0)
            .link("L2", "B", "A", length = 40.0, zoneLength = 10.0)
            .build()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        private val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart1")
        private val pool = GuidedTransporterPoolWithQ(this, system, listOf(cart), name = "Carts")
        private lateinit var part: Part
        var nameDuringLoading: String? = "unread"

        inner class Part : Entity() {
            val flow: KSLProcess = process {
                currentLocation = network.requireLocation("A")
                guidedTransport(
                    pool, destination = "B", pickupLocation = "A",
                    loadingDelay = ConstantRV(5.0), suspensionName = tripName
                )
            }
        }

        inner class Probe : Entity() {
            val look: KSLProcess = process {
                delay(2.0)
                nameDuringLoading = part.currentSuspendName
            }
        }

        override fun initialize() {
            part = Part()
            activate(part.flow)
            activate(Probe().look)
        }
    }

    private fun nameDuringLoading(name: String?): String? {
        val m = Model("StepNames", autoCSVReports = false)
        val trip = OneTrip(m, name)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        return trip.nameDuringLoading
    }

    @Test
    fun anUnnamedTransportNamesItsLoadingStepAfterTheTransporter() {
        assertEquals("Cart1:loading", nameDuringLoading(null))
    }

    @Test
    fun aNamedTransportKeepsItsName() {
        assertEquals("Pickup:loading", nameDuringLoading("Pickup"))
    }
}
