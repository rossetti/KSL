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

import ksl.modeling.guidedpath.exceptions.GuidedPathDeadlockException
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Two closures that trap each other, and the ordering that stops them.
 *
 *  §18.2 of the design record concluded that all-or-nothing acquisition keeps a non-vehicle holder
 *  a sink in the wait-for graph, so no cycle could run through one. That conflated two different
 *  things: **having no `awaitedZone`** with **having no outgoing edge**. A closure that is still
 *  *pending* waits on every vehicle occupying the zones it has reserved, and the reservation is what
 *  a refused vehicle waits on. Neither edge is removed by holding nothing.
 *
 *  §18.3's termination argument failed on the same point: "the zones beyond the region are not
 *  reserved, so a vehicle inside can always continue" is true of one pending closure and false of
 *  two abutting ones.
 *
 *  Both halves of the answer are tested here. **The escape rule** orders the closures strictly and
 *  lets a vehicle out of an older reservation through a younger one, which makes the trap
 *  unreachable and terminates by induction on that order. **The detector** still matters, because
 *  the escape rule only helps a vehicle that is escaping a reservation -- one blocked by another
 *  *vehicle* is escaping nothing, and a cycle can still run through a reservation that way.
 */
class PendingClosureCycleTest {

    private class Crew(id: String) : ZoneHolderIfc {
        override val name: String = id
        override val awaitedZone: Zone? get() = null
    }

    private class Log : ZoneHoldActionIfc {
        val granted = mutableListOf<String>()
        override fun holdBegan(allocation: ZoneAllocation) {
            granted.add(allocation.holder.name)
        }
        override fun holdEnded(allocation: ZoneAllocation) {}
    }

    // ---- prevention: two closures reserving abutting halves of a loop --------------------------

    /**
     *  A one-way loop of two links, two twelve-foot zones each, so the zones cycle
     *
     *      L1.Zone1 -> L1.Zone2 -> I2 -> L2.Zone1 -> L2.Zone2 -> I1 -> back to L1.Zone1
     *
     *  Closure A reserves `L1.Zone1`, `L1.Zone2` and the junction `I2`; closure B reserves
     *  `L2.Zone1`, `L2.Zone2` and the junction `I1`. Between them they reserve the whole loop, and
     *  neither can be granted while a cart stands in its region. Including the junctions is what
     *  removes the escape: a cart stepping onto an unreserved junction would leave the region behind
     *  it and let the closure through.
     *
     *  Cart1 starts inside A and Cart2 inside B, and each is sent the long way round, so each must
     *  pass through the other's region. Without the escape rule the old exit rule advanced each cart
     *  as far as the junction inside its own region and then the other closure refused it, and
     *  nothing ever moved again.
     *
     *  Note what this arrangement necessarily *also* contains. The trap needs the two regions to
     *  cover the whole loop between them -- otherwise one cart simply drives out through unreserved
     *  space and the trap never forms -- so there is nowhere for a journey to end that is not inside
     *  a reservation. A vehicle parked inside a pending region keeps it from draining, which is the
     *  separate hazard §18.3 already names. So the older closure gets its region here and the
     *  younger one does not, and the test says so rather than leaving it to be wondered about.
     */
    private class Loop(parent: ModelElement) : ModelElement(parent, "Loop") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .intersection("I1", x = 0.0, y = 0.0)
            .intersection("I2", x = 24.0, y = 0.0)
            .link("L1", "I1", "I2", length = 24.0, zoneLength = 12.0)
            .link("L2", "I2", "I1", length = 24.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart1 = GuidedTransporter(
            system, TransporterPlacement.OnZone("L1.Zone2"), ConstantRV(12.0), 1, name = "Cart1"
        )
        val cart2 = GuidedTransporter(
            system, TransporterPlacement.OnZone("L2.Zone2"), ConstantRV(12.0), 1, name = "Cart2"
        )
        val crewA = Crew("CrewA")
        val crewB = Crew("CrewB")
        val log = Log()
        val arrived = mutableListOf<String>()

        init {
            cart1.attachArrivalListener { arrived.add(it.name) }
            cart2.attachArrivalListener { arrived.add(it.name) }
        }

        private fun zones(vararg names: String) = names.map { network.zone(it)!! }

        override fun initialize() {
            arrived.clear()
            log.granted.clear()
            schedule({ _: KSLEvent<Nothing> ->
                // CrewA asks first, so it is the older reservation and CrewB is the one that gives
                // way. Both in the same instant, which is why the order is a sequence number and
                // not the clock.
                system.holdZonesFor(crewA, zones("L1.Zone1", "L1.Zone2", "I2"), 5.0, log)
                system.holdZonesFor(crewB, zones("L2.Zone1", "L2.Zone2", "I1"), 5.0, log)
            }, 0.5)
            schedule({ _: KSLEvent<Nothing> ->
                cart1.sendTo("I1")
                cart2.sendTo("I2")
            }, 1.0)
        }
    }

    @Test
    fun `a vehicle is let out of an older reservation through a younger one`() {
        val m = Model("EscapeOlderReservation")
        val loop = Loop(m)
        loop.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()

        // What prevention guarantees, and the whole of it: the trap is unreachable, so the older
        // reservation gets its region. Under the old rule neither closure was ever granted and
        // neither cart ever moved again.
        assertEquals(
            0.0, loop.system.numDeadlocksDetected.value, 0.0,
            "the trap is unreachable now: nothing should deadlock here"
        )
        assertEquals(
            listOf("CrewA"), loop.log.granted,
            "the older reservation is the one that gives way to nobody, so it is granted"
        )
        assertTrue(
            loop.arrived.contains("Cart1"),
            "Cart1 was let out of CrewA's region through CrewB's reservation, which is the rule " +
                    "under test; without it, it never left the junction inside CrewA's half"
        )
        assertFalse(loop.system.isHoldingZones(loop.crewA), "and CrewA's timed hold ran out")
        assertNull(
            loop.network.zone("I2")!!.closingFor, "CrewA's reservation was taken up and cleared"
        )

        // And what it does not guarantee, for a reason that is a modelling error rather than a
        // mechanism defect. Cart1's journey *ends* on I1, which is inside CrewB's region, so that
        // region never empties and CrewB waits out the run. §18.3 names this case: it is the same
        // trap as sending a vehicle to a junction another vehicle is parked on. It is reported at
        // the end of the replication rather than silently, which is the difference that matters.
        assertTrue(
            loop.system.isWaitingForZones(loop.crewB),
            "CrewB still waits, because a vehicle finished its journey inside its region"
        )
    }

    // ---- detection: the cycle the escape rule does not remove ----------------------------------

    /**
     *  One link of three zones, with a closure over the **first and third** -- a closure's zones
     *  need not be contiguous, and an aisle plus a cross-aisle is an ordinary reason for that.
     *
     *  Cart2 stands on `Zone1`, inside the closure, and wants `Zone2`. Cart1 stands on `Zone2`,
     *  outside the closure, and wants `Zone3`, which the closure has reserved.
     *
     *  The escape rule does not help either of them. Cart2 is blocked by a *vehicle*, so it is not
     *  escaping a reservation and has nowhere to be let through to. Cart1 is not inside any
     *  reservation, so nothing lets it in. The closure waits on Cart2, Cart2 waits on Cart1, Cart1
     *  waits on the closure: a cycle that runs through a reservation and survives prevention.
     */
    private class Aisle(parent: ModelElement) : ModelElement(parent, "Aisle") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Aisle")
            .link("L1", "A", "B", length = 36.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart1 = GuidedTransporter(
            system, TransporterPlacement.OnZone("L1.Zone2"), ConstantRV(12.0), 1, name = "Cart1"
        )
        val cart2 = GuidedTransporter(
            system, TransporterPlacement.OnZone("L1.Zone1"), ConstantRV(12.0), 1, name = "Cart2"
        )
        val crew = Crew("Crew")
        val log = Log()

        override fun initialize() {
            schedule({ _: KSLEvent<Nothing> ->
                system.requestZones(
                    crew, listOf(network.zone("L1.Zone1")!!, network.zone("L1.Zone3")!!), log
                )
            }, 0.5)
            schedule({ _: KSLEvent<Nothing> ->
                cart1.sendTo("B")
                cart2.sendTo("B")
            }, 1.0)
        }
    }

    @Test
    fun `a cycle running through a reservation is still detected, and names it`() {
        val m = Model("CycleThroughReservation")
        val aisle = Aisle(m)
        aisle.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0

        val e = assertFailsWith<GuidedPathDeadlockException> { m.simulate() }
        val report = e.report

        assertEquals(
            setOf("Cart1", "Cart2"), report.participants.map { it.transporterName }.toSet(),
            "both carts are in the cycle; the closure is why one of the edges exists"
        )
        // Naming the closure is the point of reporting at all. Without it the report reads as a cart
        // awaiting an empty zone and sends the reader hunting a vehicle that is not there.
        assertEquals(
            listOf("Crew"),
            report.participants.mapNotNull { it.awaitedZoneReservedFor },
            "exactly one edge runs through the reservation, and the report must say whose"
        )
        val rendered = report.toString()
        assertTrue(rendered.contains("free but reserved for Crew"), rendered)
        assertTrue(rendered.contains("Ask for regions that do not abut"), rendered)
        assertEquals(1.0, aisle.system.numDeadlocksDetected.value, 0.0)
    }
}
