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

import ksl.modeling.guidedpath.exceptions.GuidedPathRoutingException
import ksl.modeling.guidedpath.rules.RouteSelectionRuleIfc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  A route selection rule is user code, so a rule that returns something a transporter could not
 *  follow is a defect in the extension rather than in the subsystem. Catching it before the
 *  transporter starts moving is what turns it into a message naming the rule, rather than a
 *  transporter stranded in the middle of the network with nothing to explain how it got there.
 *
 *  Every rule below is deliberately wrong in one specific way. The assertions check that the
 *  offending rule is named, because with several rules in a model the class name is the only thing
 *  that tells a modeler which one to look at.
 *
 *  These rules are declared here, in the test source set and outside the subsystem's package, which
 *  also demonstrates that writing one requires no access the subsystem keeps to itself.
 */
class RouteRuleContractTest {

    /** A rule that names itself as broken, so the message can be checked for the class name. */
    private class SkipsAZoneRule : RouteSelectionRuleIfc {
        override fun selectRoute(
            network: GuidedPathNetwork,
            fromZone: Zone,
            travellingForward: Boolean,
            toIntersection: GuidedPathNetwork.Intersection
        ): List<Zone> {
            val proper = network.shortestPathZones(fromZone, travellingForward, toIntersection)
            // Drop an interior zone, leaving a gap a transporter would have to jump.
            return proper.filterIndexed { i, _ -> i != 1 }
        }
    }

    private class StartsSomewhereElseRule : RouteSelectionRuleIfc {
        override fun selectRoute(
            network: GuidedPathNetwork,
            fromZone: Zone,
            travellingForward: Boolean,
            toIntersection: GuidedPathNetwork.Intersection
        ): List<Zone> {
            val proper = network.shortestPathZones(fromZone, travellingForward, toIntersection)
            return proper.drop(1)
        }
    }

    private class StopsShortRule : RouteSelectionRuleIfc {
        override fun selectRoute(
            network: GuidedPathNetwork,
            fromZone: Zone,
            travellingForward: Boolean,
            toIntersection: GuidedPathNetwork.Intersection
        ): List<Zone> {
            val proper = network.shortestPathZones(fromZone, travellingForward, toIntersection)
            return proper.dropLast(1)
        }
    }

    private class ReturnsNothingRule : RouteSelectionRuleIfc {
        override fun selectRoute(
            network: GuidedPathNetwork,
            fromZone: Zone,
            travellingForward: Boolean,
            toIntersection: GuidedPathNetwork.Intersection
        ): List<Zone> = emptyList()
    }

    /** A rule that is merely unusual, not wrong: it takes a longer way round on purpose. */
    private class PrefersTheLongWayRule : RouteSelectionRuleIfc {
        override fun selectRoute(
            network: GuidedPathNetwork,
            fromZone: Zone,
            travellingForward: Boolean,
            toIntersection: GuidedPathNetwork.Intersection
        ): List<Zone> {
            val viaC = network.shortestPathZones(fromZone, travellingForward, network.requireLocation("C"))
            val onward = network.shortestPathZones(viaC.last(), true, toIntersection)
            return viaC + onward
        }
    }

    private fun networkWith(rule: RouteSelectionRuleIfc): GuidedPathNetwork =
        GuidedPathNetwork.builder("N")
            .routeSelectionRule(rule)
            .link("L1", "A", "B", length = 20.0, zoneLength = 5.0)
            .link("L2", "B", "C", length = 10.0, zoneLength = 5.0)
            .link("L3", "C", "A", length = 10.0, zoneLength = 5.0)
            .build()

    private fun routeAtoB(net: GuidedPathNetwork) =
        net.route(net.requireLocation("A"), net.requireLocation("B"))

    @Test
    fun `a rule that skips a zone is rejected naming the rule and the offending pair`() {
        val e = assertFailsWith<GuidedPathRoutingException> { routeAtoB(networkWith(SkipsAZoneRule())) }
        val m = e.message ?: ""
        assertTrue(m.contains("SkipsAZoneRule"), m)
        assertTrue(m.contains("not adjacent"), m)
        assertTrue(m.contains("L1.Zone1"), m)
        assertTrue(m.contains("L1.Zone3"), m)
    }

    @Test
    fun `a rule whose route does not start where the transporter is, is rejected`() {
        val e = assertFailsWith<GuidedPathRoutingException> {
            routeAtoB(networkWith(StartsSomewhereElseRule()))
        }
        val m = e.message ?: ""
        assertTrue(m.contains("StartsSomewhereElseRule"), m)
        assertTrue(m.contains("must start at"), m)
    }

    @Test
    fun `a rule whose route stops short of the destination is rejected`() {
        val e = assertFailsWith<GuidedPathRoutingException> { routeAtoB(networkWith(StopsShortRule())) }
        val m = e.message ?: ""
        assertTrue(m.contains("StopsShortRule"), m)
        assertTrue(m.contains("destination"), m)
    }

    @Test
    fun `a rule that returns nothing for a transporter not yet there is rejected`() {
        val e = assertFailsWith<GuidedPathRoutingException> { routeAtoB(networkWith(ReturnsNothingRule())) }
        val m = e.message ?: ""
        assertTrue(m.contains("ReturnsNothingRule"), m)
        assertTrue(m.contains("empty"), m)
    }

    @Test
    fun `the default rule is validated on the same path as any other and passes`() {
        val net = networkWith(ksl.modeling.guidedpath.rules.ShortestPathRouteRule())
        assertEquals(20.0, routeAtoB(net).totalLength)
    }

    @Test
    fun `a rule may deliberately choose a longer path, which is not an error`() {
        val net = networkWith(PrefersTheLongWayRule())
        val r = routeAtoB(net)
        // A to C the short way is 10, then C to A is 10 and A to B is 20: the long way round.
        assertTrue(r.totalLength > net.distance(net.requireLocation("A"), net.requireLocation("B")))
        assertTrue(r.zones.any { it.name.startsWith("L3") }, r.zones.joinToString { it.name })
    }

    @Test
    fun `a rule is consulted for every route, so replacing it changes where transporters go`() {
        val shortest = networkWith(ksl.modeling.guidedpath.rules.ShortestPathRouteRule())
        val longer = networkWith(PrefersTheLongWayRule())
        assertTrue(routeAtoB(longer).zones.size > routeAtoB(shortest).zones.size)
    }

    @Test
    fun `the rule in force is visible on the network`() {
        val rule = PrefersTheLongWayRule()
        assertNotNull(networkWith(rule).routeSelectionRule)
        assertTrue(networkWith(rule).routeSelectionRule is PrefersTheLongWayRule)
    }
}
