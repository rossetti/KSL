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

import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  Load capacity, and what it now means.
 *
 *  `loadCapacity` began as a settable property that **nothing read**: a modeller could ask for three
 *  and get one, with no error, no warning and no way to tell. That was refused at construction while
 *  the seam was unfilled, on the principle that a capacity the subsystem cannot honour is a promise
 *  it does not keep. The seam is filled, so the refusal is gone and the number does what it says.
 *
 *  Capacity is a **constructor parameter** rather than a `var`, which is what its two siblings
 *  already were: `lengthInZones` and `physicalLength` are physical properties of a vehicle, fixed
 *  when it is built, and capacity is the third.
 *
 *  What a capacity above one actually buys is in `MultiLoadTourTest`; this class pins the property
 *  itself and the one value that is still nonsense.
 */
class LoadCapacityTest {

    private companion object {
        fun loop(name: String): GuidedPathNetwork = GuidedPathNetwork.builder(name)
            .link("Out", "A", "B", length = 100.0, zoneLength = 20.0, beginDirection = 0.0)
            .link("Back", "B", "A", length = 100.0, zoneLength = 20.0, beginDirection = 180.0)
            .build()
    }

    private class Shop(parent: ModelElement, capacity: Int) : ModelElement(parent, "Shop") {
        val network = loop("Net")

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val vehicle = AgvVehicle(
            agv, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart", loadCapacity = capacity
        )
    }

    @Test
    @DisplayName("a capacity of one is the default and is accepted")
    fun oneIsTheDefault() {
        val m = Model("Default")
        val shop = Shop(m, 1)
        assertEquals(1, shop.vehicle.loadCapacity)

        // And the default really is one, so no existing model has to say so.
        val plain = AgvVehicle(shop.agv, TransporterPlacement.At("B"), ConstantRV(10.0), name = "Plain")
        assertEquals(1, plain.loadCapacity)
    }

    @Test
    @DisplayName("a capacity above one is accepted, and the vehicle reports the room it has")
    fun aCapacityAboveOneIsAccepted() {
        val m = Model("Three")
        val shop = Shop(m, 3)
        assertEquals(3, shop.vehicle.loadCapacity)
        assertEquals(3, shop.vehicle.spareCapacity, "an empty vehicle has all of its capacity spare")
        assertEquals(0, shop.vehicle.numLoadsAboard)
    }

    @Test
    @DisplayName("a capacity below one is refused as the nonsense it is")
    fun aCapacityBelowOneIsRefused() {
        val m = Model("TooFew")
        val thrown = assertFailsWith<IllegalArgumentException> { Shop(m, 0) }
        assertTrue("at least one load" in (thrown.message ?: ""), thrown.message ?: "")
    }
}
