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

/**
 *  Shifts on a guided transporter: while it is off shift it is not allocated, it stays where it
 *  stands and keeps the zone it occupies, and a pool request for it waits; when it is back on shift
 *  the pool offers it again and the request is served.
 */
class GuidedTransporterShiftCharacterizationTest {

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
        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart")
        val carts = GuidedTransporterPoolWithQ(this, system, listOf(cart), allocationRule = ClosestByNetworkDistanceRule(), name = "Carts")
        val seen = mutableListOf<String>()
        var carriedAt = Double.NaN

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("P")
                val request = requestGuidedTransporter(carts, "P")
                carriedAt = time
                transportBy(request, "B")
                releaseGuidedTransporter(request, carts)
            }
        }

        private fun note(label: String) {
            seen.add("$label off=${cart.isOffShift} busy=${cart.numBusy} zone=${cart.frontZone?.name} waiting=${carts.waitingQ.size}")
        }

        override fun initialize() {
            seen.clear()
            carriedAt = Double.NaN
            schedule({ _: KSLEvent<Nothing> -> cart.goOffShift(); note("off") }, 0.0)
            schedule({ _: KSLEvent<Nothing> -> activate(Part().p) }, 1.0)
            schedule({ _: KSLEvent<Nothing> -> note("while off") }, 10.0)
            schedule({ _: KSLEvent<Nothing> -> cart.goOnShift() }, 20.0)
            schedule({ _: KSLEvent<Nothing> -> note("back on") }, 20.5)
        }
    }

    @Test
    @DisplayName("off shift: not offered, stays where it is with its zone, the request waits; back on: offered again and the request is served")
    fun offShiftThenBack() {
        val m = Model("GuidedShift${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        val zone = shop.seen.first().substringAfter("zone=").substringBefore(" ")
        assertEquals(
            listOf(
                "off off=true busy=0 zone=$zone waiting=0",
                "while off off=true busy=0 zone=$zone waiting=1",
                "back on off=false busy=1 zone=$zone waiting=0"
            ),
            shop.seen
        )
        // allocated at 20, it drives the 200 units from A to the pickup at P at speed 10
        assertEquals(40.0, shop.carriedAt)
    }

    /** One cart whose behavior takes it off shift at 2, while it carries a part, and back on at 60. */
    private class BehaviorShop(parent: ModelElement) : ProcessModel(parent, "BehaviorShop") {
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

        init {
            cart.behavior {
                delay(2.0)
                cart.goOffShift()
                delay(58.0)
                cart.goOnShift()
            }
        }

        inner class Part(val tag: String, val from: Double) : Entity(tag) {
            val p = process(isDefaultProcess = true) {
                delay(from)
                currentLocation = network.requireLocation("P")
                val request = requestGuidedTransporter(carts, "P")
                log.add("$tag:at pickup@$time")
                transportBy(request, "B")
                releaseGuidedTransporter(request, carts)
                log.add("$tag:done@$time zone=${cart.frontZone?.name}")
            }
        }

        override fun initialize() {
            log.clear()
            activate(Part("first", 0.0).p)
            activate(Part("second", 3.0).p)
            schedule({ _: KSLEvent<Nothing> -> log.add("at 50 off=${cart.isOffShift} busy=${cart.numBusy} zone=${cart.frontZone?.name}") }, 50.0)
        }
    }

    @Test
    @DisplayName("a behavior takes the cart off shift while it carries a part: it finishes the job, stays where it is, and the next part waits")
    fun behaviorOffShiftWhileAssigned() {
        val m = Model("GuidedBehavior${System.nanoTime()}", autoCSVReports = false)
        val shop = BehaviorShop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        val done = shop.log.first { it.startsWith("first:done") }
        val zoneAtDone = done.substringAfter("zone=")
        // the first part's cart arrives at 20 (A to P), off shift from 2 but finishing its job
        assertEquals("first:at pickup@20.0", shop.log[0])
        assertEquals("at 50 off=true busy=0 zone=$zoneAtDone", shop.log.first { it.startsWith("at 50") })
        // back on shift at 60 at B, it is allocated at once and drives the 100 units to P
        assertEquals("second:at pickup@70.0", shop.log.first { it.startsWith("second:at pickup") })
    }
}
