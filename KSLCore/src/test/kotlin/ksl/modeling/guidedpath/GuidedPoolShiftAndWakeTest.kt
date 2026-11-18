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
import ksl.modeling.entity.Resource
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A guided transporter pool and its one cart at the moments R1.9's shifts and failures meet it: a return to
 *  shift that raises no capacity, the list of halted carts, and a woken request that is abandoned before it
 *  takes the cart.
 */
class GuidedPoolShiftAndWakeTest {

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
        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart), allocationRule = ClosestByNetworkDistanceRule(), name = "Carts"
        )
        val setups = mutableListOf<Shop.() -> Unit>()
        var gotCartAt = Double.NaN
        val notes = mutableListOf<String>()
        var second: Part? = null

        /** Asks for the cart at P from [from], records when it has it, holds it for [hold], then lets it go. */
        inner class Part(
            tag: String, val from: Double, val hold: Double, val record: Boolean, val thenEndSecond: String? = null
        ) : Entity(tag) {
            val p = process(isDefaultProcess = true) {
                delay(from)
                currentLocation = network.requireLocation("P")
                val request = requestGuidedTransporter(carts, "P")
                if (record) gotCartAt = time
                delay(hold)
                releaseGuidedTransporter(request, carts)
                when (thenEndSecond) {
                    // the release wakes Second, which is then ended before it takes the cart
                    "terminate" -> second!!.terminateProcess()
                    "removeAndTerminate" -> carts.myWaitingQ.removeAndTerminate(carts.myWaitingQ.peekNext()!!)
                }
            }
        }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        override fun initialize() {
            gotCartAt = Double.NaN
            notes.clear()
            for (s in setups) s()
        }
    }

    private fun run(setup: Shop.() -> Unit): Shop {
        val model = Model("GuidedPool${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        shop.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 200.0
        model.simulate()
        return shop
    }

    @Test
    @DisplayName("a woken request ended before it takes the cart: the request behind it is given the cart at once")
    fun abandonedWakeIsOfferedOn() {
        for (how in listOf("terminate", "removeAndTerminate")) {
            val shop = run {
                activate(Part("First", 0.0, 50.0, record = false, thenEndSecond = how).p)
                second = Part("Second", 1.0, 1.0, record = false).also { activate(it.p) }
                activate(Part("Third", 2.0, 1.0, record = true).p)
            }
            // First has the cart at P from 20 to 70; at 70 it is Third's, already at P
            assertEquals(70.0, shop.gotCartAt, how)
        }
    }

    @Test
    @DisplayName("back on shift after its capacity was put back directly: the cart is offered to its pool")
    fun backOnShiftOffersTheCartWhenTheCapacityDidNotRise() {
        val shop = run {
            at(0.0) { cart.goOffShift() }
            activate(Part("Part", 1.0, 1.0, record = true).p)
            at(5.0) { cart.changeCapacity(cart.CapacityChangeNotice(1, Double.POSITIVE_INFINITY)) }
            at(20.0) { cart.goOnShift() }
        }
        // allocated at 20 and at P, 200 from A at 10, at 40
        assertEquals(40.0, shop.gotCartAt)
    }

    @Test
    @DisplayName("an idle cart that is off shift or failed is not listed as halted")
    fun offShiftOrFailedCartIsNotHalted() {
        val shop = run {
            at(0.0) { cart.goOffShift() }
            at(10.0) { notes.add("off shift: ${carts.haltedTransporters.size}") }
            at(20.0) { cart.goOnShift() }
            at(30.0) { cart.fail() }
            at(40.0) { notes.add("failed: ${carts.haltedTransporters.size}") }
        }
        assertEquals(listOf("off shift: 0", "failed: 0"), shop.notes)
    }

    /** A plain resource that counts the calls of its hook for units that come back. */
    private class CountingResource(parent: ModelElement) : Resource(parent, "Counting") {
        var restored = 0

        override fun afterUnitsRestored() {
            restored++
        }
    }

    private class HookModel(parent: ModelElement, val direct: Boolean) : ModelElement(parent, "hooks") {
        val r = CountingResource(this)
        val notes = mutableListOf<String>()

        override fun initialize() {
            notes.clear()
            schedule({ _: KSLEvent<Nothing> -> r.goOffShift() }, 1.0)
            if (direct) schedule({ _: KSLEvent<Nothing> -> r.changeCapacity(r.CapacityChangeNotice(1, Double.POSITIVE_INFINITY)) }, 5.0)
            schedule({ _: KSLEvent<Nothing> ->
                val before = r.restored
                r.goOnShift()
                notes.add("calls at the return: ${r.restored - before}")
            }, 10.0)
        }
    }

    @Test
    @DisplayName("the hook for units that come back is called once at a return to shift, whether or not the capacity rises there")
    fun oneHookCallAtAReturnToShift() {
        for (direct in listOf(false, true)) {
            val model = Model("Hooks${System.nanoTime()}", autoCSVReports = false)
            val m = HookModel(model, direct)
            model.numberOfReplications = 1
            model.lengthOfReplication = 20.0
            model.simulate()
            assertEquals(listOf("calls at the return: 1"), m.notes, if (direct) "capacity put back directly" else "capacity raised by the return")
        }
    }
}
