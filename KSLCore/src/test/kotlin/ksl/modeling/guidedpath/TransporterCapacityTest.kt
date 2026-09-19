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

import ksl.controls.ControlType
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  A transporter has one unit of itself to give, and the control that says so must agree.
 *
 *  [GuidedTransporter] extends [ksl.modeling.entity.Resource], so before this was fixed it
 *  inherited a capacity control bounded below at zero and unbounded above. That was not a harmless
 *  looseness. Nothing in the movement machinery reads the capacity -- a pool offers a transporter
 *  only while it is entirely free -- so a larger capacity changed no journey. What it changed was
 *  the utilization statistics, which [ksl.modeling.entity.Resource] forms as `numBusy / capacity`.
 *
 *  Measured before the fix, on the model below: identical delivery times at capacity 1, 2 and 5,
 *  and a time-averaged instantaneous utilization of 0.156, 0.078, 0.0312 -- exactly the true figure
 *  divided by the capacity. A study sweeping this control would have read flat throughput against
 *  utilization falling as `1/capacity` as a capacity effect, when nothing about the fleet had
 *  changed. A wrong answer that looks like a finding is worse than an exception, which is why the
 *  bound is now declared and enforced rather than assumed.
 *
 *  [ksl.modeling.spatial.MovableResource] narrows the same property for the same reason on the free
 *  path; this is the guided-path half of that decision.
 */
class TransporterCapacityTest {

    /** One cart on a ring, three loads: enough that the cart is busy and enough that it is not. */
    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Ring")
            .intersection("A", x = 0.0, y = 0.0)
            .intersection("B", x = 100.0, y = 0.0)
            .intersection("C", x = 100.0, y = 100.0)
            .link("AB", "A", "B", length = 100.0, zoneLength = 10.0, beginDirection = 0.0)
            .link("BC", "B", "C", length = 100.0, zoneLength = 10.0, beginDirection = 90.0)
            .link("CA", "C", "A", length = 140.0, zoneLength = 10.0, beginDirection = 225.0)
            .station("Pickup", "A")
            .station("Drop", "B")
            .build()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(10.0), 1, name = "Cart"
        )

        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart), ClosestByNetworkDistanceRule(), name = "Carts"
        )

        private inner class Load(label: String) : Entity(label) {
            val ride = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("Pickup")
                guidedTransport(carts, destination = "Drop", pickupLocation = "Pickup")
            }
        }

        override fun initialize() {
            repeat(3) { activate(Load("load$it").ride) }
        }
    }

    @Test
    @DisplayName("a transporter's capacity may be one, or zero, and nothing else")
    fun capacityIsOneOrZero() {
        val m = Model("CapacityBounds")
        val shop = Shop(m)

        // The two legitimate values: in service, and out of it.
        shop.cart.initialCapacity = 1
        assertEquals(1, shop.cart.initialCapacity)
        shop.cart.initialCapacity = 0
        assertEquals(0, shop.cart.initialCapacity)

        for (bad in listOf(2, 5, 100)) {
            val e = assertFailsWith<IllegalArgumentException>(
                "a capacity of $bad must be refused: a transporter stands on one zone"
            ) { shop.cart.initialCapacity = bad }
            assertTrue(
                e.message!!.contains("must be 0 or 1"),
                "the refusal should say what is allowed, but said: ${e.message}"
            )
        }
    }

    @Test
    @DisplayName("the capacity control declares the bound it enforces")
    fun theControlDeclaresItsBound() {
        val m = Model("CapacityControl")
        Shop(m)
        val control = assertNotNull(
            m.controls().control("Cart.initialCapacity"),
            "a transporter must expose its capacity as a control, as every resource does"
        )
        assertEquals(ControlType.INTEGER, control.type)
        assertEquals(0.0, control.lowerBound, "a transporter may be taken out of service")
        assertEquals(
            1.0, control.upperBound,
            "the control must declare the upper bound the setter enforces, or a design would " +
                    "generate points the model then refuses"
        )
    }

    @Test
    @DisplayName("utilization is reported against a capacity of one")
    fun utilizationIsAgainstOne() {
        val m = Model("CapacityUtil")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 500.0
        m.simulate()

        assertEquals(
            3.0, shop.cart.seizeCounter.value,
            "all three loads should have been carried, one at a time"
        )
        // The figure this test exists to pin. Before the bound was enforced the same model reported
        // 0.078 at capacity 2 and 0.0312 at capacity 5, for identical journeys.
        val util = shop.cart.timeAvgInstantaneousUtil.withinReplicationStatistic.weightedAverage
        assertTrue(
            util > 0.0,
            "the cart did work, so its utilization cannot be zero"
        )
        assertEquals(
            0.156, util, 1.0e-9,
            "utilization is numBusy/capacity, and a transporter's capacity is one"
        )
    }
}
