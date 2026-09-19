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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 *  The zone's own state machine: free, claimed, occupied, and back to free.
 *
 *  Two properties are load bearing. There is no way from free straight to occupied, so the interval
 *  during which a transporter is *approaching* a zone is already exclusive -- without that, two
 *  transporters could each start into the same free zone and arrive together. And a release always
 *  passes through free, so the state is consistent at every moment the clock could be observed.
 *
 *  The mutators are not visible outside the package. That is what makes exclusivity something the
 *  subsystem can guarantee rather than merely intend, and it is why this test lives beside the code
 *  rather than outside it.
 */
class ZoneStateTest {

    private class Bed(parent: ModelElement) : ModelElement(parent, "Bed") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Bed")
            .link("L1", "A", "B", length = 24.0, zoneLength = 12.0)
            .link("L2", "B", "C", length = 24.0, zoneLength = 12.0)
            // A branch off A, so a movement can be superseded by one that starts down a different
            // link rather than merely re-using the zone already reserved.
            .link("L3", "A", "D", length = 24.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val first = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(10.0), name = "First")
        val second = GuidedTransporter(system, TransporterPlacement.At("C"), ConstantRV(10.0), name = "Second")
    }

    /** A test bed that has never been run, so every zone is still free and untouched. */
    private fun bed(): Bed = Bed(Model("ZoneState"))

    private fun spareZone(b: Bed): Zone = b.network.link("L1")!!.zones[0]

    @Test
    fun `a zone starts free and holds no one`() {
        val z = spareZone(bed())
        assertEquals(ZoneState.FREE, z.state)
        assertTrue(z.isAvailable)
        assertTrue(!z.hasHolder)
        assertNull(z.holder)
    }

    @Test
    fun `claiming a free zone reserves it without covering it`() {
        val b = bed()
        val z = spareZone(b)
        assertTrue(z.claim(b.first))
        assertEquals(ZoneState.CLAIMED, z.state)
        assertSame(b.first, z.holder)
        assertTrue(z.hasHolder)
        // Reserved is not covered: a claimed zone counts against availability, not against occupancy.
        assertTrue(!z.isCovered)
    }

    @Test
    fun `claiming a zone someone else holds fails and changes nothing`() {
        val b = bed()
        val z = spareZone(b)
        z.claim(b.first)
        assertTrue(!z.claim(b.second))
        assertEquals(ZoneState.CLAIMED, z.state)
        assertSame(b.first, z.holder)
    }

    @Test
    fun `a transporter cannot claim a zone it already holds`() {
        val b = bed()
        val z = spareZone(b)
        z.claim(b.first)
        assertFailsWith<IllegalStateException> { z.claim(b.first) }
    }

    @Test
    fun `a claimed zone becomes occupied by the transporter that claimed it`() {
        val b = bed()
        val z = spareZone(b)
        z.claim(b.first)
        z.cover(b.first)
        assertEquals(ZoneState.COVERED, z.state)
        assertTrue(z.isCovered)
    }

    @Test
    fun `there is no way from free straight to occupied`() {
        val b = bed()
        val z = spareZone(b)
        // Without the intermediate reservation, two transporters could each begin travelling into
        // the same free zone and both arrive in it.
        assertFailsWith<IllegalStateException> { z.cover(b.first) }
    }

    @Test
    fun `a zone cannot be occupied by a transporter that did not claim it`() {
        val b = bed()
        val z = spareZone(b)
        z.claim(b.first)
        assertFailsWith<IllegalStateException> { z.cover(b.second) }
    }

    @Test
    fun `releasing returns the zone to free and to no one`() {
        val b = bed()
        val z = spareZone(b)
        z.claim(b.first)
        z.cover(b.first)
        z.release(b.first)
        assertEquals(ZoneState.FREE, z.state)
        assertNull(z.holder)
    }

    @Test
    fun `only the holder may release a zone`() {
        val b = bed()
        val z = spareZone(b)
        z.claim(b.first)
        z.cover(b.first)
        assertFailsWith<IllegalStateException> { z.release(b.second) }
    }

    @Test
    fun `a claim may be abandoned, which is the only way a zone is freed without being entered`() {
        val b = bed()
        val z = spareZone(b)
        z.claim(b.first)
        z.abandonClaim(b.first)
        assertEquals(ZoneState.FREE, z.state)
        assertNull(z.holder)
    }

    @Test
    fun `an occupied zone has no claim to abandon`() {
        val b = bed()
        val z = spareZone(b)
        z.claim(b.first)
        z.cover(b.first)
        assertFailsWith<IllegalStateException> { z.abandonClaim(b.first) }
    }

    @Test
    fun `resetting returns a zone to its start of replication condition whatever state it was in`() {
        val b = bed()
        val z = spareZone(b)
        z.claim(b.first)
        z.cover(b.first)
        z.resetZone()
        assertEquals(ZoneState.FREE, z.state)
        assertNull(z.holder)
    }

    // ---- the abandon path as the engine actually reaches it ------------------------------------

    @Test
    fun `a redirected transporter finishes entering the zone it reserved before turning`() {
        // A vehicle part way into a zone is physically between two places and cannot stop and turn
        // round. So the reservation it holds is always one it will use, which is what stops a
        // superseded movement from leaving a zone held by a transporter that never arrives.
        val m = Model("Redirect")
        val net = GuidedPathNetwork.builder("Loop")
            .link("L1", "A", "B", length = 24.0, zoneLength = 12.0)
            .link("L2", "B", "C", length = 24.0, zoneLength = 12.0)
            .link("L3", "C", "A", length = 24.0, zoneLength = 12.0)
            .link("Spur", "A", "D", length = 24.0, zoneLength = 12.0, type = LinkType.SPUR)
            .build()
        val holder = object : ModelElement(m, "Holder") {}
        val system = GuidedPathTransportSystem(holder, net, name = "Sys")
        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart")
        system.checkInvariants = true

        val whereAtBoundary = mutableListOf<String>()
        object : ModelElement(holder, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> cart.sendTo("B") }, 0.0)
                // Redirect while still travelling into L1.Zone1, which takes 1.2 to cross.
                schedule({ _: KSLEvent<Nothing> -> cart.sendTo("D") }, 0.5)
                // Just after the boundary, the reserved zone should have been entered, not dropped.
                schedule({ _: KSLEvent<Nothing> ->
                    whereAtBoundary.add(cart.frontZone!!.name)
                }, 1.3)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()

        // The reservation was honoured: the cart entered the zone it had claimed.
        assertEquals(listOf("L1.Zone1"), whereAtBoundary)
        // And then it went where it was redirected, the long way round the loop and down the spur.
        assertEquals("D", cart.frontZone?.name)
        assertEquals(1, net.zones.count { it.hasHolder })
        assertNull(cart.claimedZone)
    }

    @Test
    fun `a zone reset between replications is claimable again`() {
        val m = Model("ReuseAfterReset")
        val b = Bed(m)
        b.system.checkInvariants = true
        object : ModelElement(b, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> b.first.sendTo("B") }, 0.0)
            }
        }
        m.numberOfReplications = 3
        m.lengthOfReplication = 100.0
        m.simulate()
        // Three replications each drove the same transporter over the same zones. A zone left
        // claimed by a previous replication would have stopped the next one dead.
        assertEquals("B", b.first.frontZone?.name)
    }
}
