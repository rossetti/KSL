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

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.fleet.policies.BatchedAssignmentPolicy
import ksl.modeling.fleet.policies.ChargeWhenLowDisposition
import ksl.modeling.fleet.policies.ContractNetAssignmentPolicy
import ksl.modeling.fleet.policies.ParkInPlaceDisposition
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The contract-net policy auctions a pass's tasks one after another, so an award made in an early auction can go
 * stale during a later one: its load is terminated, or its vehicle reaches a charger. A stale award is dropped
 * when the auctions end, and its task stays on the board for a later pass; the other awards stand.
 *
 * The layout is the ring of `WorkWhileChargingTest`, 40 foot links A to B to C to A with a charger at C, plus a 40
 * foot link from B back to A and a 100 foot link each way between A and D; every vehicle moves at 10 feet per minute.
 * Both parts ask at 1 to go from A to D. The batching window puts them on one pass, whose two auctions are open 2
 * minutes each; the second is open from 3 to 5, when the cart reaches the charger or the first part is terminated.
 */
class ContractNetStaleAwardTest {

    private class Shop(parent: ModelElement, val killFirstAt: Double?, lowCharge: Boolean, val tags: List<String>) : ProcessModel(parent, "Shop") {
        val net = GuidedPathNetwork.builder("Ring")
            .intersection("A").intersection("B").intersection("C").intersection("S").intersection("D")
            .link("AB", "A", "B", length = 40.0, zoneLength = 10.0)
            .link("BC", "B", "C", length = 40.0, zoneLength = 10.0)
            .link("CA", "C", "A", length = 40.0, zoneLength = 10.0)
            .link("CS", "C", "S", length = 10.0, zoneLength = 10.0)
            .link("SC", "S", "C", length = 10.0, zoneLength = 10.0)
            .link("BA", "B", "A", length = 40.0, zoneLength = 10.0)
            .link("AD", "A", "D", length = 100.0, zoneLength = 10.0)
            .link("DA", "D", "A", length = 100.0, zoneLength = 10.0)
            .build()

        init {
            spatialModel = net
        }

        val agv = AgvSystem(
            this, net, assignmentPolicy = BatchedAssignmentPolicy(0.5, ContractNetAssignmentPolicy(2.0)), name = "Agv"
        ).also { it.addCharger("C") }

        // low on charge, it starts from B for the charger at C and is charging by 5; fully charged, it parks at B
        val cart = AgvVehicle(
            agv, TransporterPlacement.At("B"), ConstantRV(10.0), name = "Cart",
            battery = Battery(capacity = 1000.0, chargePerDistance = 0.1, chargingRate = 10.0, initialCharge = if (lowCharge) 500.0 else 1000.0)
        ).apply { dispositionPolicy = ChargeWhenLowDisposition(threshold = 0.6) }

        // parked at D, 100 feet from A: the cart, nearer on its way to C or parked at B, wins the first part
        val other = AgvVehicle(agv, TransporterPlacement.At("D"), ConstantRV(10.0), name = "Other")
            .apply { dispositionPolicy = ParkInPlaceDisposition() }

        val delivered = linkedMapOf<String, String>()
        val parts = linkedMapOf<String, Part>()

        inner class Part(val tag: String) : Entity(tag) {
            val p: KSLProcess = process {
                delay(1.0)
                currentLocation = net.requireLocation("A")
                val result = transportByFleet(agv, destination = "D", origin = "A")
                delivered[tag] = result.vehicleName
            }
        }

        override fun initialize() {
            super.initialize()
            delivered.clear()
            parts.clear()
            for (tag in tags) Part(tag).also { parts[tag] = it; activate(it.p) }
            if (killFirstAt != null) {
                schedule({ _: KSLEvent<Nothing> -> parts.getValue("P1").terminateProcess() }, killFirstAt)
            }
        }
    }

    private fun run(killFirstAt: Double? = null, lowCharge: Boolean = true, tags: List<String> = listOf("P1", "P2")): Shop {
        val m = Model("ContractNetStale${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(m, killFirstAt, lowCharge, tags)
        m.numberOfReplications = 1
        m.lengthOfReplication = 300.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("the first award's vehicle reaches a charger during the second auction: that award is dropped, and both parts are carried")
    fun earlierWinnerStartsCharging() {
        val s = run()
        assertEquals("Other", s.delivered["P2"], "the second auction's award stands: ${s.delivered}")
        assertEquals(setOf("P1", "P2"), s.delivered.keys, "the first part waits on the board and is carried later")
    }

    @Test
    @DisplayName("the first award's load is terminated during the second auction: that award is dropped, and the second part is carried")
    fun earlierAwardsLoadIsTerminated() {
        val s = run(killFirstAt = 4.5, lowCharge = false)
        assertEquals(mapOf("P2" to "Other"), s.delivered)
    }
}
