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

import ksl.modeling.fleet.policies.AppendTourPolicy
import ksl.modeling.fleet.policies.BatchedAssignmentPolicy
import ksl.modeling.fleet.policies.ConsolidatingPolicy
import ksl.modeling.fleet.policies.CheapestInsertionTourPolicy
import ksl.modeling.fleet.policies.PickUpAllThenDeliverAllPolicy
import ksl.modeling.fleet.policies.TourPolicyIfc
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  **A vehicle with room carries more than one load, on one round.**
 *
 *  This is the capability the whole phase exists for, and the assertion that matters is not that it
 *  runs but that the two loads were aboard **at the same time**. A vehicle that collected one,
 *  delivered it, then collected the other would complete both tasks and report two deliveries while
 *  having consolidated nothing — and every statistic except the manifest would look identical. So
 *  the fleet is sampled densely and the maximum ever aboard is what is asserted.
 *
 *  The layout puts both pickups on one side and both destinations on the other, so consolidating is
 *  the sensible thing to do and a policy that does it is visibly better than one that does not.
 */
class MultiLoadTourTest {

    private class Shop(
        parent: ModelElement,
        capacity: Int,
        tourPolicy: TourPolicyIfc
    ) : ProcessModel(parent, "Shop") {

        // A one-way loop: A -> B -> C -> D -> A. Both loads are collected on the AB side and set
        // down on the CD side, so one round can serve both.
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .link("AB", "A", "B", length = 100.0, zoneLength = 25.0, beginDirection = 0.0)
            .link("BC", "B", "C", length = 100.0, zoneLength = 25.0, beginDirection = 90.0)
            .link("CD", "C", "D", length = 100.0, zoneLength = 25.0, beginDirection = 180.0)
            .link("DA", "D", "A", length = 100.0, zoneLength = 25.0, beginDirection = 270.0)
            .build()

        init {
            spatialModel = network
        }

        // Batched, so both tasks reach the policy in one pass and can go to one vehicle.
        val agv = AgvSystem(
            this, network,
            assignmentPolicy = BatchedAssignmentPolicy(window = 5.0, inner = ConsolidatingPolicy()),
            name = "Agv"
        )

        init {
            agv.dispatcher.tourPolicy = tourPolicy
        }

        val cart = AgvVehicle(
            agv, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart", loadCapacity = capacity
        )

        var maxAboard: Int = 0
        var delivered: Int = 0
        val carriers = mutableSetOf<String>()

        inner class Load(aName: String, val from: String, val to: String) : Entity(aName) {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(from)
                val r = transportByFleet(agv, to, origin = from)
                carriers.add(r.vehicleName)
                delivered++
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sample(event: KSLEvent<Nothing>) {
            if (cart.numLoadsAboard > maxAboard) maxAboard = cart.numLoadsAboard
        }

        override fun initialize() {
            activate(Load("One", "A", "C").p)
            activate(Load("Two", "B", "D").p)
            var t = 0.1
            while (t < 120.0) {
                schedule(::sample, t)
                t += 0.1
            }
        }
    }

    private fun run(capacity: Int, tourPolicy: TourPolicyIfc = CheapestInsertionTourPolicy()): Shop {
        val m = Model("MultiLoad")
        val shop = Shop(m, capacity, tourPolicy)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("At capacity one the same two loads are carried one at a time")
    fun capacityOneCarriesOneAtATime() {
        // The control. Without it, an assertion that a capacity-two vehicle carried two would not
        // show that the capacity did it -- the layout might simply never present two at once.
        val shop = run(capacity = 1)
        assertEquals(2, shop.delivered, "both loads should still be delivered")
        assertEquals(
            1, shop.maxAboard,
            "a vehicle that holds one must never have two aboard, whatever the tour policy plans"
        )
    }

    @Test
    @DisplayName("At capacity two both loads ride together, on one round")
    fun capacityTwoCarriesBoth() {
        val shop = run(capacity = 2)
        assertEquals(2, shop.delivered)
        assertEquals(
            2, shop.maxAboard,
            "the two loads were never aboard at once: the vehicle made two rounds rather than one, " +
                    "which would complete both tasks and consolidate nothing"
        )
        assertEquals(
            setOf("Cart"), shop.carriers,
            "there is one vehicle, so both loads must report it as their carrier"
        )
    }

    @Test
    @DisplayName("Consolidating shortens the round, and the naive policy is the measure of it")
    fun consolidationIsWorthSomething() {
        val cheapest = run(capacity = 2, tourPolicy = CheapestInsertionTourPolicy())
        val append = run(capacity = 2, tourPolicy = AppendTourPolicy())
        assertEquals(2, cheapest.delivered)
        assertEquals(2, append.delivered)
        assertTrue(
            cheapest.cart.body.distanceTravelled <= append.cart.body.distanceTravelled + 1e-9,
            "cheapest insertion travelled ${cheapest.cart.body.distanceTravelled} against " +
                    "${append.cart.body.distanceTravelled} for appending: a tour policy that " +
                    "searches should never be beaten by one that does not, on a layout where an " +
                    "insertion exists"
        )
    }

    @Test
    @DisplayName("Collecting everything before delivering anything is a different, valid plan")
    fun theMilkRunPolicyAlsoConsolidates() {
        val shop = run(capacity = 2, tourPolicy = PickUpAllThenDeliverAllPolicy())
        assertEquals(2, shop.delivered)
        assertEquals(
            2, shop.maxAboard,
            "collecting both before delivering either is the most consolidated plan there is"
        )
    }
}
