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
import kotlin.test.assertTrue

/**
 *  Shortest-path distance through the guide path, on networks whose answers can be worked out by
 *  hand. Distance on a fixed path is path length, never straight-line separation, and it is not
 *  symmetric when the links are one way -- both properties are what make the ordinary spatial
 *  models unusable for a guide path and are checked here directly.
 */
class NetworkDistanceTest {

    private fun straightChain(): GuidedPathNetwork = GuidedPathNetwork.builder("Chain")
        .link("L1", "A", "B", length = 10.0, zoneLength = 5.0)
        .link("L2", "B", "C", length = 20.0, zoneLength = 5.0)
        .link("L3", "C", "D", length = 30.0, zoneLength = 5.0)
        .build()

    private fun distance(net: GuidedPathNetwork, from: String, to: String): Double =
        net.distance(net.requireLocation(from), net.requireLocation(to))

    @Test
    fun `distance along a chain accumulates the links between the endpoints`() {
        val net = straightChain()
        assertEquals(10.0, distance(net, "A", "B"))
        assertEquals(30.0, distance(net, "A", "C"))
        assertEquals(60.0, distance(net, "A", "D"))
        assertEquals(50.0, distance(net, "B", "D"))
    }

    @Test
    fun `an intersection is zero distance from itself`() {
        val net = straightChain()
        assertEquals(0.0, distance(net, "A", "A"))
    }

    @Test
    fun `one way links make distance asymmetric`() {
        val net = straightChain()
        assertEquals(60.0, distance(net, "A", "D"))
        assertTrue(!net.isReachable(net.requireLocation("D"), net.requireLocation("A")))
    }

    @Test
    fun `a bidirectional link is traversable and costed in both directions`() {
        val net = GuidedPathNetwork.builder("N")
            .link("L1", "A", "B", length = 10.0, zoneLength = 5.0, type = LinkType.BIDIRECTIONAL)
            .build()
        assertEquals(10.0, distance(net, "A", "B"))
        assertEquals(10.0, distance(net, "B", "A"))
    }

    @Test
    fun `the shorter of two paths is the distance`() {
        val net = GuidedPathNetwork.builder("N")
            .link("Long", "A", "B", length = 100.0, zoneLength = 10.0)
            .link("ShortA", "A", "C", length = 10.0, zoneLength = 10.0)
            .link("ShortB", "C", "B", length = 20.0, zoneLength = 10.0)
            .build()
        assertEquals(30.0, distance(net, "A", "B"))
    }

    @Test
    fun `two paths of equal cost give the same distance whichever is chosen`() {
        val net = GuidedPathNetwork.builder("N")
            .link("Upper1", "A", "U", length = 10.0, zoneLength = 10.0)
            .link("Upper2", "U", "B", length = 10.0, zoneLength = 10.0)
            .link("Lower1", "A", "D", length = 10.0, zoneLength = 10.0)
            .link("Lower2", "D", "B", length = 10.0, zoneLength = 10.0)
            .build()
        assertEquals(20.0, distance(net, "A", "B"))
        // The tie is resolved deterministically: the same network gives the same answer every time.
        repeat(5) { assertEquals(20.0, distance(net, "A", "B")) }
    }

    @Test
    fun `crossing an intersection with a length adds that length to the distance`() {
        val net = GuidedPathNetwork.builder("N")
            .intersection("B", length = 4.0)
            .link("L1", "A", "B", length = 10.0, zoneLength = 10.0)
            .link("L2", "B", "C", length = 10.0, zoneLength = 10.0)
            .build()
        // Ten feet of link, four feet of junction, ten feet of link. The origin junction is not
        // entered, so its own length does not count.
        assertEquals(24.0, distance(net, "A", "C"))
        assertEquals(14.0, distance(net, "A", "B"))
    }

    @Test
    fun `an unreachable pair raises rather than returning a sentinel`() {
        val net = GuidedPathNetwork.builder("N")
            .link("L1", "A", "B", length = 10.0, zoneLength = 10.0)
            .link("L2", "C", "D", length = 10.0, zoneLength = 10.0)
            .build()
        val e = assertFailsWith<GuidedPathRoutingException> {
            distance(net, "A", "D")
        }
        val m = e.message ?: ""
        assertTrue(m.contains("A"), m)
        assertTrue(m.contains("D"), m)
    }

    @Test
    fun `a location from another network is rejected as a programmer error`() {
        val first = straightChain()
        val second = straightChain()
        assertFailsWith<IllegalArgumentException> {
            first.distance(first.requireLocation("A"), second.requireLocation("B"))
        }
    }

    @Test
    fun `on the book layout entry to exit runs the long way round the one way loop`() {
        val net = SimpleAgvNetwork.create()
        // I1 -> I2 (48) -> I3 (72) -> I4 (48) -> I5 down the spur (36).
        assertEquals(204.0, distance(net, SimpleAgvNetwork.ENTRY_STATION, SimpleAgvNetwork.EXIT_STATION))
    }

    @Test
    fun `on the book layout the return from the exit is far shorter than the trip out`() {
        val net = SimpleAgvNetwork.create()
        // Back up the bidirectional spur (36) and straight up Link4 (72).
        assertEquals(108.0, distance(net, SimpleAgvNetwork.EXIT_STATION, SimpleAgvNetwork.ENTRY_STATION))
    }

    @Test
    fun `on the book layout a cart reaches the entry station from either home spur`() {
        val net = SimpleAgvNetwork.create()
        // I6 back onto the loop at I2, then round to I1: 6 + 72 + 48 + 72.
        assertEquals(198.0, distance(net, SimpleAgvNetwork.AGV1_HOME, SimpleAgvNetwork.ENTRY_STATION))
        // I7 back onto the loop at I3, then round to I1: 6 + 48 + 72.
        assertEquals(126.0, distance(net, SimpleAgvNetwork.AGV2_HOME, SimpleAgvNetwork.ENTRY_STATION))
    }

    @Test
    fun `every intersection of the book layout can reach every other`() {
        val net = SimpleAgvNetwork.create()
        for (from in net.intersections) {
            for (to in net.intersections) {
                assertTrue(net.isReachable(from, to), "${from.name} -> ${to.name}")
            }
        }
    }

    @Test
    fun `comparing locations is identity, not name or coordinate`() {
        val first = straightChain()
        val second = straightChain()
        assertTrue(first.compareLocations(first.requireLocation("A"), first.requireLocation("A")))
        assertTrue(!first.compareLocations(first.requireLocation("A"), first.requireLocation("B")))
        assertFailsWith<IllegalArgumentException> {
            first.compareLocations(first.requireLocation("A"), second.requireLocation("A"))
        }
    }

    @Test
    fun `addressing a name that is neither an intersection nor an alias lists what is available`() {
        val net = straightChain()
        val e = assertFailsWith<IllegalArgumentException> { net.requireLocation("Z") }
        val m = e.message ?: ""
        assertTrue(m.contains("Z"), m)
        assertTrue(m.contains("Known intersections"), m)
    }
}
