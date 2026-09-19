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

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 *  The two link types whose rules are subtlest, and the ones that decide whether a layout can
 *  deadlock at all.
 *
 *  A spur is a dead end, so it has to be entered and left the same way. A transporter sent to the
 *  far end therefore takes the whole spur: a second one sent to the same place would face it with
 *  neither able to pass. What makes a spur useful rather than merely restrictive is that traffic
 *  passing *through* its mouth is untouched -- which is what lets a spur park a stopped transporter
 *  out of everyone's way, the device the text recommends for designing deadlock out.
 *
 *  A two-way link admits only one direction of travel at a time, for the same reason and with the
 *  same consequence if it did not.
 */
class SpurAndBidirectionalSemanticsTest {

    /** A one-way triangle with a two-zone spur hanging off `I2`. */
    private class Yard(parent: ModelElement) : ModelElement(parent, "Yard") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Yard")
            .link("L1", "I1", "I2", length = 48.0, zoneLength = 12.0)
            .link("L2", "I2", "I3", length = 24.0, zoneLength = 12.0)
            .link("L3", "I3", "I1", length = 24.0, zoneLength = 12.0)
            .link("Spur", "I2", "S", length = 24.0, zoneLength = 12.0, type = LinkType.SPUR)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
    }

    private fun cart(y: Yard, at: String, name: String, length: Int = 1) =
        GuidedTransporter(y.system, TransporterPlacement.At(at), ConstantRV(10.0), length, name = name)

    private fun drive(y: Yard, vararg orders: Triple<Double, GuidedTransporter, String>) {
        object : ModelElement(y, "Driver") {
            override fun initialize() {
                for ((t, transporter, destination) in orders) {
                    schedule({ _: KSLEvent<Nothing> -> transporter.sendTo(destination) }, t)
                }
            }
        }
    }

    private fun run(y: Yard, length: Double = 200.0) {
        y.system.checkInvariants = true
        val m = y.model
        m.numberOfReplications = 1
        m.lengthOfReplication = length
        m.simulate()
    }

    // ---- a spur is taken over by whoever is sent to its far end --------------------------------

    @Test
    fun `a transporter sent down a spur reserves it for as long as it is down there`() {
        val y = Yard(Model("SpurHeld"))
        val first = cart(y, "I1", "First")
        val probes = mutableListOf<String?>()
        drive(y, Triple(0.0, first, "S"))
        object : ModelElement(y, "Probe") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> ->
                    probes.add(y.network.link("Spur")!!.spurReservation?.name)
                }, 10.0)
            }
        }
        run(y)
        assertEquals(listOf<String?>("First"), probes)
        assertEquals("S", first.frontZone?.name)
    }

    @Test
    fun `a second transporter sent to the same dead end waits for the first to come out`() {
        val y = Yard(Model("SpurQueue"))
        val first = cart(y, "I1", "First")
        val second = cart(y, "I3", "Second")
        // The first goes down and stays; the second is sent to the same place.
        drive(y, Triple(0.0, first, "S"), Triple(5.0, second, "S"))
        val held = mutableListOf<Boolean>()
        object : ModelElement(y, "Probe") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> ->
                    held.add(second.transporterState == TransporterState.BLOCKED)
                }, 30.0)
            }
        }
        run(y)
        // With the first still parked at the far end, the second cannot follow it in.
        assertEquals(listOf(true), held)
        assertSame(first, y.network.link("Spur")!!.spurReservation)
        assertNotNull(second.awaitedLink)
        assertEquals("Spur", second.awaitedLink?.name)
    }

    @Test
    fun `the second transporter goes in once the first has left the spur`() {
        val y = Yard(Model("SpurRelease"))
        val first = cart(y, "I1", "First")
        val second = cart(y, "I3", "Second")
        drive(y, Triple(0.0, first, "S"), Triple(5.0, second, "S"), Triple(40.0, first, "I3"))
        run(y)
        // The first left, so the second was woken and took the spur in its turn.
        assertEquals("S", second.frontZone?.name)
        assertSame(second, y.network.link("Spur")!!.spurReservation)
        assertTrue(second.numTimesBlocked.value >= 1, "the second should have waited at some point")
    }

    @Test
    fun `traffic passing through the mouth of a spur is not held up by it`() {
        // This is what makes a spur worth using: it takes a transporter out of the way without also
        // taking the junction out of service.
        val y = Yard(Model("SpurPassThrough"))
        val parked = cart(y, "I1", "Parked")
        val passing = cart(y, "I3", "Passing")
        drive(y, Triple(0.0, parked, "S"), Triple(20.0, passing, "I3"))
        run(y)
        // The passing transporter went right round the triangle, through I2, while the other sat
        // at the end of the spur.
        assertEquals("I3", passing.frontZone?.name)
        assertEquals(0, passing.numTimesBlocked.value.toInt())
        assertEquals("S", parked.frontZone?.name)
    }

    @Test
    fun `a transporter too long for a spur keeps hold of the junction at its mouth`() {
        // Four zones of transporter will not fit in the dead end plus two zones of spur, so its
        // rear is still in the junction when its front reaches the far end. It therefore holds the
        // mouth, exactly as the text describes, without anything special being written to make that
        // happen -- it simply follows from a transporter covering a run of zones.
        val y = Yard(Model("LongOnSpur"))
        val long = GuidedTransporter(
            y.system, TransporterPlacement.OnZone("L1.Zone4"), ConstantRV(10.0), 4, name = "Long"
        )
        drive(y, Triple(0.0, long, "S"))
        run(y)
        assertEquals("S", long.frontZone?.name)
        assertEquals(
            listOf("I2", "Spur.Zone1", "Spur.Zone2", "S"),
            long.coveredZones.map { it.name }
        )
        // Holding the mouth means nothing can pass through the junction at all.
        assertSame(long, y.network.intersection("I2")!!.zone.holder)
    }

    // ---- a two-way link runs one way at a time -------------------------------------------------

    /** Two junctions joined by a single two-way link of three zones. */
    private class TwoWay(parent: ModelElement) : ModelElement(parent, "TwoWay") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("TwoWay")
            .link("Both", "A", "B", length = 36.0, zoneLength = 12.0, type = LinkType.BIDIRECTIONAL)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
    }

    @Test
    fun `a transporter standing on a two-way link holds its direction from the outset`() {
        // Standing on a two-way link obstructs oncoming traffic just as travelling along it does,
        // so the link is running that way from the moment the replication begins. Without this the
        // first transporter to move would find the link unclaimed and another could enter against it.
        val m = Model("HoldsFromStart")
        val tw = TwoWay(m)
        GuidedTransporter(tw.system, TransporterPlacement.OnZone("Both.Zone1"), ConstantRV(10.0), 1, name = "Standing")
        val locks = mutableListOf<DirectionLock?>()
        object : ModelElement(tw, "Probe") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> locks.add(tw.network.link("Both")!!.directionLock) }, 1.0)
            }
        }
        tw.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()
        assertEquals(listOf<DirectionLock?>(DirectionLock(forward = true, count = 1)), locks)
    }

    @Test
    fun `two transporters may share a two-way link when they travel the same way`() {
        val m = Model("SameWay")
        val tw = TwoWay(m)
        val first = GuidedTransporter(tw.system, TransporterPlacement.OnZone("Both.Zone2"), ConstantRV(10.0), 1, name = "First")
        val second = GuidedTransporter(tw.system, TransporterPlacement.OnZone("Both.Zone1"), ConstantRV(10.0), 1, name = "Second")
        val locks = mutableListOf<DirectionLock?>()
        object : ModelElement(tw, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> first.sendTo("B") }, 0.0)
                schedule({ _: KSLEvent<Nothing> -> locks.add(tw.network.link("Both")!!.directionLock) }, 0.5)
            }
        }
        tw.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()
        // Both are on the link, running the same way: the link admits them together.
        assertEquals(listOf<DirectionLock?>(DirectionLock(forward = true, count = 2)), locks)
        assertEquals(0, second.numTimesBlocked.value.toInt())
        assertEquals("B", first.frontZone?.name)
    }

    @Test
    fun `a transporter may not enter a two-way link that is running the other way`() {
        val m = Model("HeadOn")
        val tw = TwoWay(m)
        // One is on the link heading for B, the other waits at B wanting to come the other way.
        val outbound = GuidedTransporter(tw.system, TransporterPlacement.OnZone("Both.Zone1"), ConstantRV(10.0), 1, name = "Outbound")
        val inbound = GuidedTransporter(tw.system, TransporterPlacement.At("B"), ConstantRV(10.0), 1, name = "Inbound")
        val states = mutableListOf<TransporterState>()
        object : ModelElement(tw, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> outbound.sendTo("B") }, 0.0)
                schedule({ _: KSLEvent<Nothing> -> inbound.sendTo("A") }, 0.5)
                schedule({ _: KSLEvent<Nothing> -> states.add(inbound.transporterState) }, 1.0)
            }
        }
        tw.system.checkInvariants = true
        // This configuration deadlocks a moment after the point being tested, and it is meant to:
        // Inbound is standing on B, which is where Outbound is going, so once Outbound reaches the
        // far end of the link the two are waiting on each other. That is a real circular wait and
        // Phase 6's detector raises on it at time 2.4. What this test is about is the direction
        // rule at time 1.0, before any of that, so detection is switched off to leave the two
        // mechanisms independently testable. The deadlock itself is asserted in
        // DeadlockDetectionTest, which uses this same shape deliberately.
        tw.system.deadlockDetectionEnabled = false
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()
        // Held at the mouth rather than sent in to meet the other head on.
        assertEquals(listOf(TransporterState.BLOCKED), states)
        assertEquals("Both", inbound.awaitedLink?.name)
    }

    @Test
    fun `the direction is given up when the last transporter leaves the link`() {
        val m = Model("Releases")
        val tw = TwoWay(m)
        val outbound = GuidedTransporter(tw.system, TransporterPlacement.OnZone("Both.Zone1"), ConstantRV(10.0), 1, name = "Outbound")
        val locks = mutableListOf<DirectionLock?>()
        object : ModelElement(tw, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> outbound.sendTo("B") }, 0.0)
                schedule({ _: KSLEvent<Nothing> -> locks.add(tw.network.link("Both")!!.directionLock) }, 1.0)
                schedule({ _: KSLEvent<Nothing> -> locks.add(tw.network.link("Both")!!.directionLock) }, 20.0)
            }
        }
        tw.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()
        assertEquals(DirectionLock(forward = true, count = 1), locks[0])
        // Off the link and standing at B: the link is free for either direction again.
        assertNull(locks[1])
        assertEquals("B", outbound.frontZone?.name)
    }

    @Test
    fun `with the link free the other direction runs`() {
        val m = Model("OtherWay")
        val tw = TwoWay(m)
        val inbound = GuidedTransporter(tw.system, TransporterPlacement.At("B"), ConstantRV(10.0), 1, name = "Inbound")
        object : ModelElement(tw, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> inbound.sendTo("A") }, 0.0)
            }
        }
        tw.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()
        assertEquals("A", inbound.frontZone?.name)
        assertEquals(0, inbound.numTimesBlocked.value.toInt())
    }
}
