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

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.guidedpath.exceptions.GuidedPathRoutingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 *  Route materialization on networks whose answers can be worked out by hand.
 *
 *  The property that matters most is domain rule R12: a route's total length equals the distance
 *  the network reports between its endpoints. Distance and path are computed from the same tables,
 *  so if they ever disagreed the movement engine would spend a different amount of time travelling
 *  than the dispatcher assumed when it chose the transporter. Every topology below asserts it.
 */
class RoutePlannerTest {

    private fun chain(): GuidedPathNetwork = GuidedPathNetwork.builder("Chain")
        .link("L1", "A", "B", length = 10.0, zoneLength = 5.0)
        .link("L2", "B", "C", length = 20.0, zoneLength = 5.0)
        .link("L3", "C", "D", length = 30.0, zoneLength = 5.0)
        .build()

    private fun route(net: GuidedPathNetwork, from: String, to: String) =
        net.route(net.requireLocation(from), net.requireLocation(to))

    /** R12 on every ordered pair that is reachable at all. */
    private fun assertRouteLengthMatchesDistance(net: GuidedPathNetwork) {
        for (from in net.intersections) {
            for (to in net.intersections) {
                if (from === to || !net.isReachable(from, to)) continue
                val r = net.route(from, to)
                assertEquals(
                    net.distance(from, to), r.totalLength, 1e-9,
                    "route length disagreed with distance for ${from.name} -> ${to.name}"
                )
            }
        }
    }

    @Test
    fun `a route along a chain crosses every zone of every link plus each junction entered`() {
        val net = chain()
        val r = route(net, "A", "C")
        // Two zones of L1, junction B, four zones of L2, junction C.
        assertEquals(listOf("L1.Zone1", "L1.Zone2", "B", "L2.Zone1", "L2.Zone2", "L2.Zone3", "L2.Zone4", "C"),
            r.zones.map { it.name })
        assertEquals(30.0, r.totalLength)
    }

    @Test
    fun `a route does not include the zone the transporter is already in`() {
        val net = chain()
        val r = route(net, "A", "B")
        assertSame(assertNotNull(net.intersection("A")).zone, r.origin)
        assertTrue(r.zones.none { it === r.origin })
        assertEquals("L1.Zone1", r.zones.first().name)
    }

    @Test
    fun `a route ends at the destination's own zone`() {
        val net = chain()
        val r = route(net, "A", "D")
        assertSame(assertNotNull(net.intersection("D")).zone, r.zones.last())
        assertSame(assertNotNull(net.intersection("D")), r.destination)
    }

    @Test
    fun `route length equals reported distance on a chain`() {
        assertRouteLengthMatchesDistance(chain())
    }

    @Test
    fun `route length equals reported distance on the one way loop of the book layout`() {
        assertRouteLengthMatchesDistance(SimpleAgvNetwork.create())
    }

    @Test
    fun `route length equals reported distance on a network with a bidirectional link`() {
        val net = GuidedPathNetwork.builder("N")
            .link("L1", "A", "B", length = 10.0, zoneLength = 5.0, type = LinkType.BIDIRECTIONAL)
            .link("L2", "B", "C", length = 10.0, zoneLength = 5.0, type = LinkType.BIDIRECTIONAL)
            .build()
        assertRouteLengthMatchesDistance(net)
    }

    @Test
    fun `route length equals reported distance when junctions have a length`() {
        val net = GuidedPathNetwork.builder("N")
            .intersection("B", length = 4.0)
            .intersection("C", length = 3.0)
            .link("L1", "A", "B", length = 10.0, zoneLength = 10.0)
            .link("L2", "B", "C", length = 10.0, zoneLength = 10.0)
            .build()
        assertRouteLengthMatchesDistance(net)
        assertEquals(27.0, route(net, "A", "C").totalLength)
    }

    @Test
    fun `a bidirectional link traversed backwards yields its zones in reverse order`() {
        val net = GuidedPathNetwork.builder("N")
            .link("L1", "A", "B", length = 15.0, zoneLength = 5.0, type = LinkType.BIDIRECTIONAL)
            .build()
        assertEquals(listOf("L1.Zone1", "L1.Zone2", "L1.Zone3", "B"), route(net, "A", "B").zones.map { it.name })
        assertEquals(listOf("L1.Zone3", "L1.Zone2", "L1.Zone1", "A"), route(net, "B", "A").zones.map { it.name })
    }

    @Test
    fun `the shorter of two paths is the one materialized`() {
        val net = GuidedPathNetwork.builder("N")
            .link("Long", "A", "B", length = 100.0, zoneLength = 10.0)
            .link("ShortA", "A", "C", length = 10.0, zoneLength = 10.0)
            .link("ShortB", "C", "B", length = 20.0, zoneLength = 10.0)
            .build()
        val r = route(net, "A", "B")
        assertEquals(30.0, r.totalLength)
        assertTrue(r.zones.none { it.name.startsWith("Long") })
    }

    @Test
    fun `an equal cost tie is broken the same way on every call`() {
        val net = GuidedPathNetwork.builder("N")
            .link("Upper1", "A", "U", length = 10.0, zoneLength = 10.0)
            .link("Upper2", "U", "B", length = 10.0, zoneLength = 10.0)
            .link("Lower1", "A", "D", length = 10.0, zoneLength = 10.0)
            .link("Lower2", "D", "B", length = 10.0, zoneLength = 10.0)
            .build()
        val first = route(net, "A", "B").zones.map { it.name }
        repeat(10) { assertEquals(first, route(net, "A", "B").zones.map { it.name }) }
        // Declaration order settles the tie, so the upper pair wins.
        assertTrue(first.any { it.startsWith("Upper") }, "expected the earlier-declared path: $first")
    }

    @Test
    fun `two networks built the same way route the same way`() {
        val a = SimpleAgvNetwork.create()
        val b = SimpleAgvNetwork.create()
        for (from in a.intersections) {
            for (to in a.intersections) {
                if (from === to) continue
                assertEquals(
                    a.route(from, to).zones.map { it.name },
                    b.route(b.requireLocation(from.name), b.requireLocation(to.name)).zones.map { it.name },
                    "${from.name} -> ${to.name}"
                )
            }
        }
    }

    @Test
    fun `a route to an unreachable destination raises rather than returning something partial`() {
        val net = GuidedPathNetwork.builder("N")
            .link("L1", "A", "B", length = 10.0, zoneLength = 10.0)
            .link("L2", "C", "D", length = 10.0, zoneLength = 10.0)
            .build()
        assertFailsWith<GuidedPathRoutingException> { route(net, "A", "D") }
    }

    // ---- routes planned from part way along a link --------------------------------------------

    @Test
    fun `a transporter part way along a link finishes that link first`() {
        val net = chain()
        val l1 = assertNotNull(net.link("L1"))
        // Standing on the first of L1's two zones, heading for C.
        val r = net.routeFrom(l1.zones[0], net.requireLocation("C"), travellingForward = true)
        assertEquals(listOf("L1.Zone2", "B", "L2.Zone1", "L2.Zone2", "L2.Zone3", "L2.Zone4", "C"),
            r.zones.map { it.name })
    }

    @Test
    fun `a transporter on the last zone of a link steps straight onto the junction`() {
        val net = chain()
        val l1 = assertNotNull(net.link("L1"))
        val r = net.routeFrom(l1.zones[1], net.requireLocation("B"), travellingForward = true)
        assertEquals(listOf("B"), r.zones.map { it.name })
    }

    @Test
    fun `a transporter backing down a bidirectional link takes its zones in reverse`() {
        val net = GuidedPathNetwork.builder("N")
            .link("L1", "A", "B", length = 15.0, zoneLength = 5.0, type = LinkType.BIDIRECTIONAL)
            .build()
        val l1 = assertNotNull(net.link("L1"))
        val r = net.routeFrom(l1.zones[2], net.requireLocation("A"), travellingForward = false)
        assertEquals(listOf("L1.Zone2", "L1.Zone1", "A"), r.zones.map { it.name })
    }

    @Test
    fun `a transporter cannot back down a one way link`() {
        val net = chain()
        val l1 = assertNotNull(net.link("L1"))
        val e = assertFailsWith<GuidedPathRoutingException> {
            net.routeFrom(l1.zones[1], net.requireLocation("A"), travellingForward = false)
        }
        assertTrue((e.message ?: "").contains("UNIDIRECTIONAL"), e.message ?: "")
    }

    // ---- the cursor ---------------------------------------------------------------------------

    @Test
    fun `a fresh route offers its first zone and counts nothing traversed`() {
        val r = route(chain(), "A", "C")
        assertEquals(0, r.zonesTraversed)
        assertEquals(8, r.zonesRemaining)
        assertEquals("L1.Zone1", assertNotNull(r.nextZone).name)
        assertTrue(!r.isComplete)
    }

    @Test
    fun `advancing consumes the route one zone at a time and never goes backwards`() {
        val r = route(chain(), "A", "B")
        val expected = r.zones.map { it.name }
        val visited = mutableListOf<String>()
        while (!r.isComplete) {
            visited.add(assertNotNull(r.nextZone).name)
            r.advance()
        }
        assertEquals(expected, visited)
        assertEquals(expected.size, r.zonesTraversed)
        assertEquals(0, r.zonesRemaining)
        assertNull(r.nextZone)
        assertEquals(0.0, r.remainingLength)
    }

    @Test
    fun `advancing past the end is a defect rather than a silent no-op`() {
        val r = route(chain(), "A", "B")
        repeat(r.zones.size) { r.advance() }
        assertFailsWith<IllegalStateException> { r.advance() }
    }

    @Test
    fun `remaining length shrinks by the zone just entered`() {
        val r = route(chain(), "A", "B")
        assertEquals(10.0, r.remainingLength)
        r.advance()
        assertEquals(5.0, r.remainingLength)
    }

    @Test
    fun `routes are values, so one transporter's progress does not touch another's`() {
        val net = chain()
        val first = route(net, "A", "C")
        val second = route(net, "A", "C")
        first.advance()
        assertEquals(1, first.zonesTraversed)
        assertEquals(0, second.zonesTraversed)
    }

    // ---- the book layout ----------------------------------------------------------------------

    @Test
    fun `on the book layout a cart runs the loop clockwise to reach the exit spur`() {
        val net = SimpleAgvNetwork.create()
        val r = route(net, SimpleAgvNetwork.ENTRY_STATION, SimpleAgvNetwork.EXIT_STATION)
        val junctions = r.zones.filterIsInstance<IntersectionZone>().map { it.name }
        assertEquals(listOf("I2", "I3", "I4", "I5"), junctions)
        assertEquals(204.0, r.totalLength)
    }

    @Test
    fun `on the book layout the return from the exit comes straight back up link four`() {
        val net = SimpleAgvNetwork.create()
        val r = route(net, SimpleAgvNetwork.EXIT_STATION, SimpleAgvNetwork.ENTRY_STATION)
        val junctions = r.zones.filterIsInstance<IntersectionZone>().map { it.name }
        assertEquals(listOf("I4", "I1"), junctions)
        assertEquals(108.0, r.totalLength)
    }

    @Test
    fun `successors follow the direction of travel and the link types`() {
        val net = SimpleAgvNetwork.create()
        val link1 = assertNotNull(net.link("Link1"))
        // Mid link, forward: exactly the next zone along.
        assertEquals(listOf("Link1.Zone2"), net.successorsOf(link1.zones[0], true).map { it.name })
        // Last zone, forward: the junction.
        assertEquals(listOf("I2"), net.successorsOf(link1.zones[3], true).map { it.name })
        // A one way link offers nothing backwards.
        assertEquals(emptyList(), net.successorsOf(link1.zones[1], false))
        // From a junction: every link that may be left by, including the bidirectional home spur.
        assertEquals(setOf("Link2.Zone1", "Link5.Zone1"),
            net.successorsOf(assertNotNull(net.intersection("I2")).zone).map { it.name }.toSet())
    }
}
