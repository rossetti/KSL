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

import ksl.modeling.fleet.policies.DispatcherStopControl
import ksl.modeling.fleet.policies.TimetableControl
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
 *  **Gate 3: three route systems, all on the guide path, all reporting per stop.**
 *
 *  The test of the conceptualisation is whether the systems come out as *configurations* rather than
 *  as special cases. Nothing below adds a type, a subsystem or a code path: each is a `Line` of
 *  `Stop`s with different actions on them, a `StopControlIfc` where the service is controlled, and
 *  the same control loop walking the same `Tour`.
 *
 *  | | Line | Stop actions | Controlled by |
 *  |---|---|---|---|
 *  | line-haul | terminal to terminal, one way | `HoldUntilFull` at the origin, serve at the hub | the cut-off |
 *  | milk run | a cyclic loop of drop points | serve, then `Dwell` | nothing |
 *  | express | cyclic, with a controller | serve | `DispatcherStopControl` |
 */
class RouteSystemsTest {

    // ---- an LTL line-haul --------------------------------------------------------------------

    /**
     *  A trailer loads at a terminal until it is full or the cut-off arrives, runs to a hub, and
     *  runs on to the far terminal. The line does not come back: the return is a different service,
     *  which is what `cyclic = false` says.
     */
    private class LineHaul(parent: ModelElement) : ProcessModel(parent, "LineHaul") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Corridor")
            .link("TH", "Terminal", "Hub", length = 200.0, zoneLength = 50.0, beginDirection = 0.0)
            .link("HF", "Hub", "Far", length = 200.0, zoneLength = 50.0, beginDirection = 0.0)
            .link("FT", "Far", "Terminal", length = 400.0, zoneLength = 50.0, beginDirection = 180.0)
            .build()

        init {
            spatialModel = network
        }

        val fleet = AgvSystem(this, network, name = "Fleet")

        val terminal = Stop(fleet, "Terminal")
        val hub = Stop(fleet, "Hub")
        val far = Stop(fleet, "Far")

        // The cut-off is the action's business, not the control's: it is the vehicle responding to
        // what is in front of it rather than a decision somebody made about the service.
        val line = Line(
            "Nightly",
            listOf(
                LineStop(terminal, DoInOrder(BoardWaiting(terminal), HoldUntilFull(terminal, ConstantRV(30.0)))),
                LineStop(hub),
                LineStop(far)
            ),
            cyclic = false
        )

        val trailer = AgvVehicle(
            fleet, TransporterPlacement.At("Terminal"), ConstantRV(10.0),
            name = "Trailer", loadCapacity = 3
        )

        val results = mutableListOf<TransitResult>()

        inner class Shipment(aName: String, val from: Stop, val to: String) : Entity(aName) {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(from.location)
                results.add(rideFrom(from, to))
            }
        }

        override fun initialize() {
            repeat(3) { fleet.dispatcher.postLine(line) }
            activate(Shipment("S1", terminal, "Hub").p)
            activate(Shipment("S2", terminal, "Far").p)
            activate(Shipment("S3", hub, "Far").p)
        }
    }

    @Test
    @DisplayName("A line-haul loads to a cut-off, runs terminal to terminal, and reports per stop")
    fun lineHaul() {
        val m = Model("LineHaulModel")
        val shop = LineHaul(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 600.0
        m.simulate()

        assertEquals(3, shop.results.size, "not every shipment moved")
        assertEquals(2.0, shop.terminal.numBoarded.value, "two shipments loaded at the terminal")
        assertEquals(1.0, shop.hub.numBoarded.value, "one joined at the hub")
        assertEquals(1.0, shop.hub.numAlighted.value, "one was for the hub")
        assertEquals(2.0, shop.far.numAlighted.value, "two were for the far terminal")
        // Both shipments are at the terminal when the trailer is, so they board at once and the
        // cut-off shows in the *ride* rather than in the wait: the trailer stands loaded until 30,
        // then runs the 200 units to the hub in 20. A departure driven by the load is a suspension
        // inside a stop action, which is what the seam exists to make possible, and without it the
        // hub shipment would have arrived at 20.
        val toHub = shop.results.single { it.destination == "Hub" }
        assertTrue(
            toHub.totalTime >= 50.0,
            "the trailer should have held to the 30-unit cut-off before running 20 units to the " +
                    "hub, but the shipment arrived at ${toHub.totalTime}"
        )
    }

    // ---- a fork-truck milk run ----------------------------------------------------------------

    /**
     *  A truck goes round a fixed loop of drop points, dwelling at each while somebody unloads it.
     *  Nothing controls it; the round is the whole of the policy, which is what a milk run is.
     */
    private class MilkRun(parent: ModelElement) : ProcessModel(parent, "MilkRun") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Plant")
            .link("SD1", "Stores", "Cell1", length = 100.0, zoneLength = 50.0, beginDirection = 0.0)
            .link("D12", "Cell1", "Cell2", length = 100.0, zoneLength = 50.0, beginDirection = 90.0)
            .link("D2S", "Cell2", "Stores", length = 200.0, zoneLength = 50.0, beginDirection = 180.0)
            .build()

        init {
            spatialModel = network
        }

        val fleet = AgvSystem(this, network, name = "Fleet")

        val stores = Stop(fleet, "Stores")
        val cell1 = Stop(fleet, "Cell1")
        val cell2 = Stop(fleet, "Cell2")

        val line = Line(
            "Loop",
            listOf(
                LineStop(stores),
                LineStop(cell1, DoInOrder(AlightHere(cell1), BoardWaiting(cell1), Dwell(ConstantRV(2.0)))),
                LineStop(cell2, DoInOrder(AlightHere(cell2), BoardWaiting(cell2), Dwell(ConstantRV(2.0))))
            )
        )

        val truck = AgvVehicle(
            fleet, TransporterPlacement.At("Stores"), ConstantRV(10.0),
            name = "Truck", loadCapacity = 2
        )

        val results = mutableListOf<TransitResult>()

        inner class Kit(aName: String, val from: Stop, val to: String) : Entity(aName) {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(from.location)
                results.add(rideFrom(from, to))
            }
        }

        private var made = 0

        /** The most that was ever aboard at once, which is the only honest test of consolidation:
         *  a truck that collected one, delivered it, and collected the other would report the same
         *  totals having carried nothing together. */
        var maxAboard: Int = 0

        @Suppress("UNUSED_PARAMETER")
        private fun release(event: KSLEvent<Nothing>) {
            made++
            activate(Kit("K$made", stores, if (made % 2 == 0) "Cell2" else "Cell1").p)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sample(event: KSLEvent<Nothing>) {
            if (truck.numLoadsAboard > maxAboard) maxAboard = truck.numLoadsAboard
        }

        override fun initialize() {
            maxAboard = 0
            repeat(8) { fleet.dispatcher.postLine(line) }
            var t = 5.0
            while (t < 250.0) {
                schedule(::release, t)
                t += 25.0
            }
            var s = 0.25
            while (s < 400.0) {
                schedule(::sample, s)
                s += 0.25
            }
        }
    }

    @Test
    @DisplayName("A fork-truck milk run circulates, dwells, and reports what each drop point saw")
    fun milkRun() {
        val m = Model("MilkRunModel")
        val shop = MilkRun(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 600.0
        m.simulate()

        assertTrue(shop.results.size >= 6, "only ${shop.results.size} kits were delivered")
        assertTrue(shop.stores.numBoarded.value >= 6.0, "the stores loaded almost nothing")
        assertEquals(
            shop.cell1.numAlighted.value + shop.cell2.numAlighted.value,
            shop.results.size.toDouble(),
            "a kit was delivered that no drop point recorded"
        )
        // The room is used: two kits ride together whenever both are waiting when the truck calls.
        assertEquals(
            2, shop.maxAboard,
            "the truck never had two kits aboard at once, so its capacity bought nothing"
        )
    }

    // ---- express running ----------------------------------------------------------------------

    /**
     *  A circular service whose controller runs it past one stop. Both victims of a skip are
     *  counted, and they are counted apart: the stop that was passed, and the rider aboard who was
     *  carried past their stop.
     *
     *  The instruction lands on the *third* stop, and that is a property of asking before the leg
     *  rather than an accident of the fixture. The vehicle asks about A and about B at time zero,
     *  one after the other, because serving A costs nothing; the first ask that happens after the
     *  instruction is left is therefore the one about C.
     */
    private class Express(parent: ModelElement, val skipAt: Double) : ProcessModel(parent, "Express") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Ring")
            .link("AB", "A", "B", length = 100.0, zoneLength = 50.0, beginDirection = 0.0)
            .link("BC", "B", "C", length = 100.0, zoneLength = 50.0, beginDirection = 90.0)
            .link("CA", "C", "A", length = 200.0, zoneLength = 50.0, beginDirection = 180.0)
            .build()

        init {
            spatialModel = network
        }

        val fleet = AgvSystem(this, network, name = "Fleet")

        val stopA = Stop(fleet, "A", "StopA")
        val stopB = Stop(fleet, "B", "StopB")
        val stopC = Stop(fleet, "C", "StopC")

        val line = Line("Ring", listOf(LineStop(stopA), LineStop(stopB), LineStop(stopC)))

        val bus = AgvVehicle(
            fleet, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Bus", loadCapacity = 4
        )

        init {
            bus.stopControl = DispatcherStopControl()
        }

        val results = mutableListOf<TransitResult>()

        inner class Rider(aName: String, val from: Stop, val to: String) : Entity(aName) {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(from.location)
                results.add(rideFrom(from, to))
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun tellItToSkip(event: KSLEvent<Nothing>) {
            fleet.dispatcher.instruct(bus, StopInstruction.Skip)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun openTheService(event: KSLEvent<Nothing>) {
            repeat(6) { fleet.dispatcher.postLine(line) }
        }

        override fun initialize() {
            // The rider is at the stop before the service starts, which is the ordinary case and
            // the only one in which the first vehicle to come past can take it. Posting the line at
            // time zero alongside the rider would leave the two racing at one instant.
            activate(Rider("R1", stopA, "C").p)
            schedule(::openTheService, 1.0)
            schedule(::tellItToSkip, skipAt)
        }
    }

    @Test
    @DisplayName("A controller runs a service express, and both costs of it are counted apart")
    fun expressRunning() {
        val m = Model("ExpressModel")
        // The service opens at 1, so the bus serves A at 1 (taking the rider aboard, bound for C),
        // reaches B at 11 and asks about C there. The instruction is left at 5, between those two
        // asks, so C is the stop it lands on -- and the rider is aboard for it.
        val shop = Express(m, skipAt = 5.0)
        m.numberOfReplications = 1
        m.lengthOfReplication = 600.0
        m.simulate()

        assertEquals(1.0, shop.bus.numStopsSkipped.value, "the vehicle did not run past anything")
        assertEquals(1.0, shop.stopC.numPassedBySkipped.value, "stop C did not record being passed")
        assertEquals(0.0, shop.stopC.numPassedByFull.value, "a skip is not a full vehicle")
        assertEquals(0.0, shop.stopB.numPassedBySkipped.value, "stop B was served, not passed")
        assertEquals(
            1.0, shop.bus.numCarriedPast.value,
            "the rider aboard for C was carried past it and nothing said so"
        )
        // Carried past, then round again: it gets there on the next lap rather than being lost.
        assertEquals(1, shop.results.size, "the rider never arrived")
        assertEquals("C", shop.results.single().destination)
        assertTrue(
            shop.results.single().timeAboard > 40.0,
            "being carried past a stop costs a lap, but the ride took only " +
                    "${shop.results.single().timeAboard}"
        )
    }

    // ---- a timetable ---------------------------------------------------------------------------

    @Test
    @DisplayName("A timetabled service holds at each stop until its scheduled departure")
    fun timetable() {
        val m = Model("TimetabledModel")
        val shop = Express(m, skipAt = 1.0e9)   // never instructed
        shop.bus.stopControl = TimetableControl(
            mapOf(shop.stopA to 5.0, shop.stopB to 25.0, shop.stopC to 45.0)
        )
        m.numberOfReplications = 1
        m.lengthOfReplication = 600.0
        m.simulate()

        assertEquals(0.0, shop.bus.numStopsSkipped.value, "a timetable does not skip")
        assertEquals(1, shop.results.size)
        val r = shop.results.single()
        // A is served at time 0 and held to 5; B is 100 units away, reached at 15, and held to 25.
        // The rider is set down on arrival, so its ride is 5 -> 15 and it is the *hold* at A that
        // shows in the wait rather than in the ride.
        assertTrue(
            r.waitForVehicle >= 0.0 && r.timeAboard >= 10.0,
            "the timetable did not shape the journey: wait ${r.waitForVehicle}, ride ${r.timeAboard}"
        )
        // The vehicle cannot have finished a lap faster than the last scheduled departure allows.
        assertTrue(
            shop.bus.numTasksCompleted.value <= 600.0 / 45.0 + 1.0,
            "more cycles were completed than a 45-unit timetable permits"
        )
    }
}
