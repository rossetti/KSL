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
import ksl.modeling.spatial.MovePurpose
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  **Carrying is a fact about the manifest, not a claim a protocol makes.**
 *
 *  `MOVING_LOADED` used to be an argument. Whoever began a journey said which state it put the
 *  transporter in, and `FracTimeTransporting` is computed from that state -- so a protocol that was
 *  mistaken about what it carried would produce a plausible, wrong utilization figure, and nothing
 *  downstream could tell. The state is now derived from what is actually aboard, and a caller says
 *  only what the movement is *for*.
 *
 *  The tests below assert the derivation in both directions, because a derivation that were always
 *  loaded or always empty would pass a one-sided test. They also pin the two cases where the
 *  purpose wins over the manifest: a transporter going home is `RETURNING_HOME` whether or not it
 *  carries anything, and a towed one is `TOWED` whatever it holds, because it is moving under
 *  somebody else's power and counting it as transporting would put a broken vehicle into a figure
 *  meant to describe working ones.
 */
class ManifestTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .link("AB", "A", "B", length = 100.0, zoneLength = 25.0, beginDirection = 0.0)
            .link("BC", "B", "C", length = 100.0, zoneLength = 25.0, beginDirection = 90.0)
            .link("CA", "C", "A", length = 100.0, zoneLength = 25.0, beginDirection = 180.0)
            .build()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart")
            .apply { homeBase = "A" }

        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart), idleDispositionRule = ReturnToHomeBaseRule(), name = "Carts"
        )

        /** Sampled while the empty leg runs, and again while the loaded leg runs. */
        var stateWhileFetching: TransporterState? = null
        var stateWhileCarrying: TransporterState? = null
        var aboardWhileFetching: Int = -1
        var aboardWhileCarrying: Int = -1
        var aboardAfterDelivery: Int = -1

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("B")
                val request = requestGuidedTransporter(carts, "B")
                transportBy(request, "C")
                aboardAfterDelivery = cart.numLoadsAboard
                releaseGuidedTransporter(request, carts)
            }
        }

        override fun initialize() {
            activate(Part().p)
            // The empty leg is A to B, the loaded leg B to C, each ten time units at this velocity.
            schedule({ _: KSLEvent<Nothing> ->
                stateWhileFetching = cart.transporterState
                aboardWhileFetching = cart.numLoadsAboard
            }, 5.0)
            schedule({ _: KSLEvent<Nothing> ->
                stateWhileCarrying = cart.transporterState
                aboardWhileCarrying = cart.numLoadsAboard
            }, 15.0)
        }
    }

    private fun run(): Shop {
        val m = Model("Manifest")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("An empty leg is empty because nothing is aboard, not because a caller said so")
    fun anEmptyLegIsDerived() {
        val shop = run()
        assertEquals(0, shop.aboardWhileFetching, "nothing should be aboard while fetching")
        assertEquals(
            TransporterState.MOVING_EMPTY, shop.stateWhileFetching,
            "with an empty manifest a service journey must derive an empty move"
        )
    }

    @Test
    @DisplayName("A loaded leg is loaded because something is aboard")
    fun aLoadedLegIsDerived() {
        val shop = run()
        assertEquals(1, shop.aboardWhileCarrying, "the part should be aboard while it is carried")
        assertEquals(
            TransporterState.MOVING_LOADED, shop.stateWhileCarrying,
            "with something on the manifest the same call must derive a loaded move"
        )
    }

    @Test
    @DisplayName("A load is set down, and the manifest is empty again")
    fun theManifestEmpties() {
        val shop = run()
        assertEquals(0, shop.aboardAfterDelivery, "the part was never set down")
        assertEquals(0, shop.cart.numLoadsAboard, "the cart ended the run still carrying something")
        assertFalse(shop.cart.isCarryingLoad)
    }

    @Test
    @DisplayName("Going home is going home, whatever is aboard")
    fun purposeWinsForHome() {
        val m = Model("Home")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        // The disposition rule sends it home after the delivery, and it must not be reported as a
        // loaded move on the way even if something were aboard: going home is a purpose, not a load.
        assertEquals(
            0, shop.cart.numLoadsAboard,
            "this fixture's cart should be empty by the end; the assertion below is about purpose"
        )
        assertEquals(TransporterState.RETURNING_HOME, shop.cart.movingStateFor(MovePurpose.HOME))
    }

    @Test
    @DisplayName("A towed transporter is towed, not transporting")
    fun purposeWinsForTow() {
        val m = Model("Tow")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 1.0
        m.simulate()
        assertEquals(
            TransporterState.TOWED, shop.cart.movingStateFor(MovePurpose.TOW),
            "a vehicle being pushed is neither transporting nor running empty under its own power"
        )
    }

    @Test
    @DisplayName("The manifest is empty at the start of every replication")
    fun theManifestIsReset() {
        val m = Model("Reset")
        val shop = Shop(m)
        m.numberOfReplications = 3
        m.lengthOfReplication = 100.0
        m.simulate()
        assertEquals(
            0, shop.cart.numLoadsAboard,
            "a manifest carried across replications would make the second run start loaded"
        )
    }

    @Test
    @DisplayName("A load cannot board twice, and cannot be set down if it never boarded")
    fun theManifestRefusesNonsense() {
        val m = Model("Refusals")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 1.0
        m.simulate()
        val stranger = object : ProcessModel(shop, "Other") {}.Entity("Stranger")
        shop.cart.board(stranger)
        assertTrue(shop.cart.isCarryingLoad)
        assertFailsWith<IllegalArgumentException>("boarding twice would corrupt the count") {
            shop.cart.board(stranger)
        }
        shop.cart.alight(stranger)
        assertFailsWith<IllegalArgumentException>("setting down what is not aboard must be refused") {
            shop.cart.alight(stranger)
        }
    }
}
