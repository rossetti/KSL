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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 *  How network construction scales with the number of intersections.
 *
 *  Shortest paths are found once, at construction, by an algorithm that is cubic in time and
 *  quadratic in memory. That is a deliberate trade -- it buys a table lookup on the hot path -- but
 *  it also bounds how large a guide path can usefully get, and the bound was an estimate rather
 *  than a measurement. This test measures it.
 *
 *  The assertions are loose on purpose. Timing on a shared build machine is not reproducible, so a
 *  tight bound would be a flaky test rather than a useful one. What is asserted is that
 *  construction completes, that the routing is correct at every size, and that the time stays
 *  inside a bound generous enough that only a change of algorithmic order could breach it. The
 *  measured figures are printed so a reader can see the actual shape.
 */
class NetworkScalingTest {

    /**
     * A ring of intersections joined by one-way links, so that every intersection can reach every
     * other and the shortest path between the far side is genuinely long.
     */
    private fun ring(size: Int): GuidedPathNetwork {
        val b = GuidedPathNetwork.builder("Ring$size")
        for (i in 0 until size) {
            b.link("L$i", "I$i", "I${(i + 1) % size}", length = 10.0, zoneLength = 5.0)
        }
        return b.build()
    }

    private fun report(size: Int): Double {
        var net: GuidedPathNetwork? = null
        val elapsed = measureTime { net = ring(size) }
        val n = net!!
        assertEquals(size, n.intersections.size)
        assertEquals(size, n.links.size)
        // Correctness at scale, not merely completion: the far side of the ring is size/2 hops
        // away, and going the other way round is not possible on one-way links.
        val half = size / 2
        assertEquals(
            10.0 * half,
            n.distance(n.requireLocation("I0"), n.requireLocation("I$half")),
            1e-9
        )
        val route = n.route(n.requireLocation("I0"), n.requireLocation("I$half"))
        assertEquals(10.0 * half, route.totalLength, 1e-9)
        println("  intersections=$size  build=${elapsed.inWholeMilliseconds} ms")
        return elapsed.inWholeMilliseconds.toDouble()
    }

    @Test
    fun `construction scales to the network sizes this subsystem claims to support`() {
        println("Guided path network construction, one-way ring:")
        val fifty = report(50)
        val hundred = report(100)
        val twoFifty = report(250)
        val fiveHundred = report(500)
        // Five hundred intersections is the stated ceiling. A cubic algorithm at that size is on
        // the order of a hundred million operations, which is a second or two at worst. Sixty
        // seconds leaves room for a loaded machine while still failing outright if the cost ever
        // became quartic or the tables were rebuilt per query.
        assertTrue(fiveHundred < 60_000, "500 intersections took $fiveHundred ms")
        assertTrue(fifty >= 0 && hundred >= 0 && twoFifty >= 0)
    }

    @Test
    fun `routing stays correct on a large network with a long way round`() {
        val n = ring(250)
        // Every hop is ten, so the distance to the intersection k steps ahead is ten k.
        for (k in listOf(1, 7, 60, 124, 249)) {
            assertEquals(
                10.0 * k,
                n.distance(n.requireLocation("I0"), n.requireLocation("I$k")),
                1e-9,
                "distance to I$k"
            )
            assertEquals(
                10.0 * k,
                n.route(n.requireLocation("I0"), n.requireLocation("I$k")).totalLength,
                1e-9,
                "route length to I$k"
            )
        }
    }

    @Test
    fun `a large route materializes every zone it crosses in order`() {
        val n = ring(100)
        val r = n.route(n.requireLocation("I0"), n.requireLocation("I50"))
        // Fifty links of two zones each, plus the fifty junctions entered.
        assertEquals(50 * 2 + 50, r.zones.size)
        assertEquals(500.0, r.totalLength, 1e-9)
        // Adjacency holds all the way along, which the network checked when it built the route.
        for (i in 0 until r.zones.size - 1) {
            assertTrue(
                r.zones[i + 1] in n.successorsOf(r.zones[i], true) ||
                        r.zones[i + 1] in n.successorsOf(r.zones[i], false),
                "zones ${r.zones[i].name} and ${r.zones[i + 1].name} are not adjacent"
            )
        }
    }
}
