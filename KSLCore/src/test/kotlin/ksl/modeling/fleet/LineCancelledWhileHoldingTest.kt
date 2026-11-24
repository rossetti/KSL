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
import ksl.modeling.entity.KSLProcessBuilder
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
 *  A line cycle cancelled while its vehicle holds at one of its stops releases the vehicle at once, and nothing
 *  more is boarded for it: whether the vehicle holds for a load (`HoldUntilFull`) or for a departure time (a
 *  `Serve` instruction's `departNotBefore`).
 */
class LineCancelledWhileHoldingTest {

    /** Holds at Terminal for a departure at 30 on the first visit, and serves every other stop at once. */
    private class DepartAtThirty : StopControlIfc {
        private var firstVisit = true
        override suspend fun KSLProcessBuilder.instruct(vehicle: FleetVehicle, stop: TourStop, tour: Tour): StopInstruction =
            if (stop.location == "Terminal" && firstVisit) {
                firstVisit = false
                StopInstruction.Serve(departNotBefore = 30.0)
            } else StopInstruction.Serve()
    }

    /**
     *  A trailer at Terminal, 200 from Hub at speed 10. A line runs Terminal to Hub, holding at Terminal until 30:
     *  for a load ([holdForLoad]) or for a departure time. The line is cancelled at 5. A shipment for Hub comes to
     *  Terminal at [shipmentAt]; with [secondLineAt], a second line is posted then.
     */
    private class Shop(
        parent: ModelElement,
        val holdForLoad: Boolean,
        val shipmentAt: Double,
        val secondLineAt: Double?
    ) : ProcessModel(parent, "Shop") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Corridor")
            .link("TH", "Terminal", "Hub", length = 200.0, zoneLength = 50.0, beginDirection = 0.0)
            .link("HT", "Hub", "Terminal", length = 200.0, zoneLength = 50.0, beginDirection = 180.0)
            .build()

        init {
            spatialModel = network
        }

        val fleet = AgvSystem(this, network, name = "Fleet")
        val terminal = Stop(fleet, "Terminal")
        val hub = Stop(fleet, "Hub")

        private fun line(name: String) = Line(
            name,
            listOf(
                if (holdForLoad) LineStop(terminal, DoInOrder(BoardWaiting(terminal), HoldUntilFull(terminal, ConstantRV(30.0))))
                else LineStop(terminal, BoardWaiting(terminal)),
                LineStop(hub)
            ),
            cyclic = false
        )

        val trailer = AgvVehicle(fleet, TransporterPlacement.At("Terminal"), ConstantRV(10.0), name = "Trailer", loadCapacity = 3)
            .also { if (!holdForLoad) it.stopControl = DepartAtThirty() }

        val results = mutableListOf<TransitResult>()
        private var first: Dispatcher.LineTask? = null

        private inner class Shipment : Entity("Shipment") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(terminal.location)
                results.add(rideFrom(terminal, "Hub"))
            }
        }

        override fun initialize() {
            first = fleet.dispatcher.postLine(line("First"))
            schedule(::cancelFirst, 5.0)
            schedule(::shipmentComes, shipmentAt)
            secondLineAt?.let { schedule(::postSecond, it) }
        }

        private fun cancelFirst(event: KSLEvent<Nothing>) = fleet.dispatcher.cancel(first!!)
        private fun shipmentComes(event: KSLEvent<Nothing>) = activate(Shipment().p)
        private fun postSecond(event: KSLEvent<Nothing>) {
            fleet.dispatcher.postLine(line("Second"))
        }
    }

    private fun run(holdForLoad: Boolean, shipmentAt: Double, secondLineAt: Double?): Shop {
        val m = Model("LineCancelled$holdForLoad${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(m, holdForLoad, shipmentAt, secondLineAt)
        m.lengthOfReplication = 200.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("a cycle cancelled while its vehicle holds for a load boards nothing more")
    fun holdForLoadEndsAtCancellation() {
        val shop = run(holdForLoad = true, shipmentAt = 10.0, secondLineAt = null)
        assertEquals(0.0, shop.terminal.numBoarded.value, "the shipment boarded for a cancelled cycle")
        assertTrue(shop.results.isEmpty())
    }

    @Test
    @DisplayName("a cycle cancelled while its vehicle holds for a departure time lets the vehicle go at once")
    fun holdForDepartureEndsAtCancellation() {
        // released at 5, the trailer takes the second line at 7 and carries the shipment that came at 6
        val shop = run(holdForLoad = false, shipmentAt = 6.0, secondLineAt = 7.0)
        val ride = shop.results.single()
        assertTrue(ride.totalTime < 30.0, "the shipment took ${ride.totalTime}: the trailer held to 30 for a cancelled cycle")
    }
}
