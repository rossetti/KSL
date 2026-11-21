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
import ksl.modeling.fleet.AgvTestNetworks
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.ParkInPlaceRule
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A guided transporter given back to its pool while it cannot be dispatched (failed, or off shift) has its pool's
 *  idle rule applied when it is restored, as one given back while dispatchable has it applied at once.
 */
class TransporterRestoredDispositionTest {

    /** Two transporters, each with a home; the pool sends an idle one home. C2 carries P to AltDrop by about 27. */
    private class Floor(parent: ModelElement, val outage: String) : ProcessModel(parent, "Floor") {
        val network = AgvTestNetworks.ringWithTwoParks()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val c1 = GuidedTransporter(system, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "C1")
        val c2 = GuidedTransporter(system, TransporterPlacement.At(AgvTestNetworks.SECOND_DEPOT), ConstantRV(10.0), name = "C2")
        val pool = GuidedTransporterPoolWithQ(this, system, listOf(c1, c2), allocationRule = ClosestByNetworkDistanceRule(), name = "Carts")
        var at200: String? = null

        init {
            c1.homeBase = AgvTestNetworks.DEPOT
            c2.homeBase = AgvTestNetworks.SECOND_DEPOT
            pool.idleDispositionRule = ReturnToHomeBaseRule()
        }

        inner class Part : Entity("P") {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(AgvTestNetworks.NEAR)
                val request = requestGuidedTransporter(pool, AgvTestNetworks.NEAR)
                transportBy(request, AgvTestNetworks.ALT_DROP, loadingDelay = ConstantRV(0.0), unLoadingDelay = ConstantRV(0.0))
                releaseGuidedTransporter(request, pool)
            }
        }

        private fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        override fun initialize() {
            at200 = null
            activate(Part().p)
            when (outage) {
                "failed" -> { at(20.0) { c2.fail() }; at(40.0) { c2.repair() } }
                "offShift" -> { at(20.0) { c2.goOffShift() }; at(40.0) { c2.goOnShift() } }
            }
            at(200.0) { at200 = "${c2.transporterState}@${c2.positionNow.name}" }
        }
    }

    private fun stateAt200(outage: String): String? {
        val model = Model("Floor${System.nanoTime()}", autoCSVReports = false)
        val floor = Floor(model, outage)
        model.numberOfReplications = 1
        model.lengthOfReplication = 300.0
        val error = try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
        assertNull(error, "the run stopped: $error")
        return floor.at200
    }

    @Test
    @DisplayName("a transporter given back while failed, or off shift, is sent home when it is restored")
    fun sentHomeWhenRestored() {
        val home = stateAt200("none")
        assertEquals(home, stateAt200("failed"), "failed and repaired")
        assertEquals(home, stateAt200("offShift"), "off shift and back")
    }

    /** One transporter, away from home and idle, halted at 60; at 70 its halt is dropped as a new journey is issued. */
    private class HaltDropped(parent: ModelElement) : ProcessModel(parent, "Halted") {
        val network = AgvTestNetworks.ringWithTwoParks()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val c = GuidedTransporter(system, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "C")
        val pool = GuidedTransporterPoolWithQ(this, system, listOf(c), name = "Carts")
        var stateAfterClear: TransporterState? = null

        init {
            c.homeBase = AgvTestNetworks.DEPOT
            pool.idleDispositionRule = ReturnToHomeBaseRule()
        }

        private fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        override fun initialize() {
            stateAfterClear = null
            at(1.0) { c.sendTo(AgvTestNetworks.FAR) }
            at(60.0) { c.halt() }
            at(70.0) { c.clearHalt(); stateAfterClear = c.transporterState }
        }
    }

    @Test
    @DisplayName("a halt dropped for a new journey does not set off the idle rule, which would compete with that journey")
    fun haltDroppedForANewJourney() {
        val model = Model("Halted${System.nanoTime()}", autoCSVReports = false)
        val m = HaltDropped(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        assertEquals(TransporterState.IDLE, m.stateAfterClear, "no journey home was started")
    }

    /** One transporter in two pools: the first sends idle transporters home, the second parks them. */
    private class SharedCart(parent: ModelElement, val firstRule: String) : ProcessModel(parent, "Shared") {
        val network = AgvTestNetworks.ringWithTwoParks()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val c = GuidedTransporter(system, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "C")
        val first = GuidedTransporterPoolWithQ(this, system, listOf(c), name = "First")
        val second = GuidedTransporterPoolWithQ(this, system, listOf(c), name = "Second")
        var deliveredAt = Double.NaN
        var stateAfterRepair: TransporterState? = null

        init {
            c.homeBase = AgvTestNetworks.SECOND_DEPOT
            first.idleDispositionRule = if (firstRule == "home") ReturnToHomeBaseRule() else ParkInPlaceRule()
        }

        inner class Part : Entity("P") {
            val p: KSLProcess = process(isDefaultProcess = true) {
                delay(1.0)
                currentLocation = network.requireLocation(AgvTestNetworks.NEAR)
                val request = requestGuidedTransporter(second, AgvTestNetworks.NEAR)
                transportBy(request, AgvTestNetworks.ALT_DROP, loadingDelay = ConstantRV(0.0), unLoadingDelay = ConstantRV(0.0))
                deliveredAt = time
                releaseGuidedTransporter(request, second)
            }
        }

        override fun initialize() {
            deliveredAt = Double.NaN
            activate(Part().p)
            schedule({ _: KSLEvent<Nothing> -> c.fail() }, 0.0)
            schedule({ _: KSLEvent<Nothing> -> c.repair(); stateAfterRepair = c.transporterState }, 5.0)
        }
    }

    private fun sharedCart(firstRule: String): SharedCart {
        val model = Model("Shared${System.nanoTime()}", autoCSVReports = false)
        val m = SharedCart(model, firstRule)
        model.numberOfReplications = 1
        model.lengthOfReplication = 200.0
        model.simulate()
        return m
    }

    @Test
    @DisplayName("a transporter restored while another of its pools has a request waiting serves it, and is not sent home first")
    fun sharedTransporterServesTheWaitingRequest() {
        val parked = sharedCart("park")
        val home = sharedCart("home")
        assertEquals(parked.stateAfterRepair, home.stateAfterRepair, "the cart is not sent home while a request waits for it")
        assertEquals(parked.deliveredAt, home.deliveredAt)
    }
}
