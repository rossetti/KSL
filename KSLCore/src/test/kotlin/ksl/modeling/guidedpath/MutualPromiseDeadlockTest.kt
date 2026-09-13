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
import kotlin.test.assertTrue

/**
 *  A circular wait that runs *through* two pending closures, which the design argued was impossible.
 *
 *  §18.2 of the design record concluded that all-or-nothing acquisition keeps a non-vehicle holder
 *  a sink in the wait-for graph, so no cycle could run through one. That conflated two different
 *  things: **having no `awaitedZone`** with **having no outgoing edge**. A closure that is still
 *  *pending* waits on every vehicle occupying the zones it has reserved. All-or-nothing removes a
 *  different edge -- the holder holding what a vehicle wants -- and leaves that one untouched. And
 *  the reservation creates the edge back the other way: a vehicle refused entry by a promise is
 *  waiting on whoever the promise is for.
 *
 *  §18.3's termination argument fails on the same point. It reads "the zones beyond the region are
 *  not reserved, so a vehicle inside can always continue", which is true of one pending closure and
 *  false of two adjacent ones.
 *
 *  ## The arrangement
 *
 *  A one-way loop of two links, two twelve-foot zones each, so the cycle of zones is
 *
 *      L1.Zone1 -> L1.Zone2 -> I2 -> L2.Zone1 -> L2.Zone2 -> I1 -> back to L1.Zone1
 *
 *  Closure A reserves `L1.Zone1`, `L1.Zone2` and the junction `I2`; closure B reserves
 *  `L2.Zone1`, `L2.Zone2` and the junction `I1`. Between them they reserve the whole loop, and
 *  neither can be granted while a cart stands in its region.
 *
 *  Cart1 starts inside A and Cart2 inside B, and each is sent the long way round, so each must pass
 *  through the other's region. The exit rule lets each cart advance *within* the closure it is
 *  already in -- Cart1 reaches `I2`, Cart2 reaches `I1` -- and then refuses it entry to the other,
 *  because a closure admits only a vehicle holding one of *its own* zones. Neither cart can move,
 *  so neither region ever drains, so neither closure is ever granted.
 *
 *  Including the junctions in the regions is what removes the escape: a cart stepping onto an
 *  unreserved junction would leave the region behind it and let the closure through.
 *
 *  The zones it waits on are **free**, merely promised, so before this was fixed the detector saw
 *  no edge at all -- `obstructorsOf` followed `awaitedZone?.holder`, and a free zone has no holder.
 *  Four things were permanently stuck and the only sign of it was one end-of-replication line
 *  saying the guide path "may have stopped moving".
 */
class MutualPromiseDeadlockTest {

    private class Crew(id: String) : ZoneHolderIfc {
        override val name: String = id
        override val awaitedZone: Zone? get() = null
    }

    private class Silent : ZoneHoldActionIfc {
        override fun holdBegan(allocation: ZoneAllocation) {}
        override fun holdEnded(allocation: ZoneAllocation) {}
    }

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
        private val silent = Silent()

        private fun zones(vararg names: String) = names.map { network.zone(it)!! }

        override fun initialize() {
            schedule({ _: KSLEvent<Nothing> ->
                system.requestZones(crewA, zones("L1.Zone1", "L1.Zone2", "I2"), silent)
                system.requestZones(crewB, zones("L2.Zone1", "L2.Zone2", "I1"), silent)
            }, 0.5)
            schedule({ _: KSLEvent<Nothing> ->
                cart1.sendTo("I1")
                cart2.sendTo("I2")
            }, 1.0)
        }
    }

    @Test
    fun `a cycle through two pending closures is detected and names them`() {
        val m = Model("MutualPromise")
        val loop = Loop(m)
        loop.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0

        val e = assertFailsWith<GuidedPathDeadlockException> { m.simulate() }
        val report = e.report

        assertEquals(
            2, report.participants.size,
            "both carts are in the cycle; the closures are why each edge exists, not extra nodes"
        )
        assertEquals(
            setOf("Cart1", "Cart2"), report.participants.map { it.transporterName }.toSet()
        )
        // Naming the closure is the point of reporting at all. Without it a modeller reads two
        // carts awaiting two zones and goes looking for a head-on vehicle conflict that is not
        // there -- the zones are free, and what refuses them is a reservation.
        assertEquals(
            setOf("CrewA", "CrewB"),
            report.participants.mapNotNull { it.awaitedZoneReservedFor }.toSet(),
            "each awaited zone is free but reserved, and the report must say for whom"
        )
        val rendered = report.toString()
        assertTrue(rendered.contains("reserved for CrewA"), rendered)
        assertTrue(rendered.contains("reserved for CrewB"), rendered)
        assertEquals(1.0, loop.system.numDeadlocksDetected.value, 0.0)
    }
}
