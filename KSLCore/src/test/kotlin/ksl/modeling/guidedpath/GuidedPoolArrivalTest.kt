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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  An entity that asks a guided transporter pool for a transporter in the instant one is given back does not take
 *  it from the waiting request that the give-back woke.
 *
 *  One cart at A on the loop A, B, P, links 100 long, speed 10. H has the cart from 0, at A; R has waited for it
 *  since 1. At 10 H gives the cart back, which wakes R, and in the same step asks for it again with a request
 *  priority more urgent than R's resumption.
 */
class GuidedPoolArrivalTest {

    private class Shop(parent: ModelElement, val againPriority: Int) : ProcessModel(parent, "Shop") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .link("AB", "A", "B", length = 100.0, zoneLength = 25.0, beginDirection = 0.0)
            .link("BP", "B", "P", length = 100.0, zoneLength = 25.0, beginDirection = 90.0)
            .link("PA", "P", "A", length = 100.0, zoneLength = 25.0, beginDirection = 180.0)
            .build()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart")
        val carts = GuidedTransporterPoolWithQ(this, system, listOf(cart), name = "Carts")
        val log = mutableListOf<String>()

        inner class Holder : Entity("H") {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("A")
                val first = requestGuidedTransporter(carts, "A")
                log.add("H got cart@$time")
                delay(10.0)
                releaseGuidedTransporter(first, carts)
                val again = requestGuidedTransporter(carts, "A", requestPriority = againPriority)
                log.add("H got cart again@$time")
                delay(5.0)
                releaseGuidedTransporter(again, carts)
            }
        }

        inner class Waiter : Entity("R") {
            val p: KSLProcess = process(isDefaultProcess = true) {
                delay(1.0)
                currentLocation = network.requireLocation("A")
                val request = requestGuidedTransporter(carts, "A")
                log.add("R got cart@$time")
                delay(5.0)
                releaseGuidedTransporter(request, carts)
            }
        }

        override fun initialize() {
            log.clear()
            activate(Holder().p)
            activate(Waiter().p)
        }
    }

    @Test
    @DisplayName("an entity that gives the cart back and asks for it again in the same step waits behind the request it woke")
    fun askingAgainWaitsBehindTheWokenRequest() {
        for (againPriority in listOf(ProcessModel.TRANSPORT_REQUEST_PRIORITY, -20)) {
            val model = Model("GuidedArrival${System.nanoTime()}", autoCSVReports = false)
            val shop = Shop(model, againPriority)
            model.numberOfReplications = 1
            model.lengthOfReplication = 100.0
            val error = try {
                model.simulate()
                null
            } catch (e: Throwable) {
                e
            }
            assertNull(error, "priority $againPriority: the run stopped: $error")
            assertEquals(listOf("H got cart@0.0", "R got cart@10.0", "H got cart again@15.0"), shop.log, "priority $againPriority")
        }
    }
}
