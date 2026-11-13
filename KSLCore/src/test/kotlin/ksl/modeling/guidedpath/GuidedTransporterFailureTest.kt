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

import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 *  A guided transporter that fails: while failed it is not dispatched and a pool request for it waits;
 *  its repair offers it to the pool again. One that fails while assigned, under the default rule,
 *  finishes its job and is held from the pool until the repair.
 */
class GuidedTransporterFailureTest {

    private class Shop(parent: ModelElement, val schedule: Shop.() -> Unit) : ProcessModel(parent, "Shop") {
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
        val carts = GuidedTransporterPoolWithQ(this, system, listOf(cart), allocationRule = ClosestByNetworkDistanceRule(), name = "Carts")
        val log = mutableListOf<String>()

        inner class Part(val tag: String, val from: Double) : Entity(tag) {
            val p = process(isDefaultProcess = true) {
                delay(from)
                currentLocation = network.requireLocation("P")
                val request = requestGuidedTransporter(carts, "P")
                log.add("$tag:at pickup@$time")
                transportBy(request, "B")
                releaseGuidedTransporter(request, carts)
                log.add("$tag:done@$time")
            }
        }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        override fun initialize() {
            log.clear()
            schedule()
        }
    }

    private fun run(schedule: Shop.() -> Unit): Shop {
        val m = Model("GuidedFailure${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(m, schedule)
        m.numberOfReplications = 1
        m.lengthOfReplication = 300.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("failed while idle: not dispatched, the request waits, and the repair offers it to the pool")
    fun failedWhileIdle() {
        val shop = run {
            at(0.0) { cart.fail() }
            activate(Part("part", 1.0).p)
            at(10.0) { log.add("at 10 dispatchable=${cart.isDispatchable} waiting=${carts.waitingQ.size}") }
            at(20.0) { cart.repair() }
        }
        assertEquals("at 10 dispatchable=false waiting=1", shop.log[0])
        // allocated at the repair at 20, it drives the 200 units from A to the pickup at P at speed 10
        assertEquals("part:at pickup@40.0", shop.log[1])
    }

    @Test
    @DisplayName("failed while assigned: it finishes the job, is held from the pool, and serves the next part after the repair")
    fun failedWhileAssigned() {
        val shop = run {
            activate(Part("first", 0.0).p)
            activate(Part("second", 3.0).p)
            at(2.0) { cart.fail() }
            at(50.0) { log.add("at 50 failed=${cart.isFailed} busy=${cart.numBusy} dispatchable=${cart.isDispatchable}") }
            at(60.0) { cart.repair() }
        }
        assertEquals("first:at pickup@20.0", shop.log[0])
        // the loop runs one way, so P to B is the 200 units by A
        assertEquals("first:done@40.0", shop.log[1])
        assertEquals("at 50 failed=true busy=0 dispatchable=false", shop.log[2])
        // repaired at 60 at B, it drives the 100 units to P
        assertEquals("second:at pickup@70.0", shop.log[3])
        assertFalse(shop.cart.isFailed)
    }
}
