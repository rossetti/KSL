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

import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  `UC-6`: what the opt-in congestion statistics actually say.
 *
 *  Registering the responses is one thing and was settled in `ResponseCardinalityTest`. What
 *  matters here is that the numbers are right, and the way to know that is to build a model whose
 *  answer can be worked out on paper. A transporter crossing one zone of a four-zone link, once,
 *  over a known replication length, produces a link utilization that is a fraction of two integers
 *  -- so the test compares against arithmetic rather than against whatever the code happened to
 *  produce.
 *
 *  Link utilization is derived at the end of the replication in the same way a conveyor derives
 *  cell utilization: the time-weighted average number of zones covered, divided by how many zones
 *  the link has. Matching that derivation is deliberate, because a modeller comparing a belt with a
 *  guide path should not have to ask whether "utilization" means the same thing in both.
 */
class LinkStatisticsTest {

    /** One transporter, one long one-way link, and nothing to get in the way. */
    private class Straight(
        parent: ModelElement,
        collectLinks: Boolean = true,
        collectZones: Boolean = false
    ) : ModelElement(parent, "Straight") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Straight")
            .intersection("A", x = 0.0, y = 0.0)
            .intersection("B", x = 48.0, y = 0.0)
            .link("Path", "A", "B", length = 48.0, zoneLength = 12.0, beginDirection = 0.0)
            .build()

        val system = GuidedPathTransportSystem(
            this, network,
            collectLinkStatistics = collectLinks,
            collectZoneStatistics = collectZones,
            name = "Sys"
        )

        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(10.0), 1, EndOfZoneControl(), "Cart"
        )

        override fun initialize() {
            schedule({ _: KSLEvent<Nothing> -> cart.sendTo("B") }, 0.0)
        }
    }

    private fun run(
        collectLinks: Boolean = true,
        collectZones: Boolean = false,
        replicationLength: Double = 100.0
    ): Straight {
        val m = Model("LinkStats")
        val s = Straight(m, collectLinks, collectZones)
        s.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = replicationLength
        m.simulate()
        return s
    }

    @Test
    @DisplayName("Link utilization is the time-weighted zones covered over the link's zone count")
    fun linkUtilizationMatchesTheArithmetic() {
        val s = run(replicationLength = 100.0)
        val link = s.network.link("Path")!!

        // The cart leaves A at time 0 and covers exactly one zone of the link at a time -- it is
        // one zone long and releases the zone behind on arriving in the next. It enters Path.Zone1
        // at 1.2, and leaves the link when it reaches B at 4.8. So one of the four zones is covered
        // from 1.2 to 4.8, which is 3.6 time units of a 100-unit replication.
        val expectedZonesCovered = 3.6 / 100.0
        assertEquals(
            expectedZonesCovered,
            s.system.linkCoverage.getValue(link).withinReplicationStatistic.weightedAverage,
            1e-9
        )
        assertEquals(
            expectedZonesCovered / 4.0,
            s.system.linkUtilization.getValue(link).value,
            1e-9,
            "utilization is the average zones covered over the four zones the link has"
        )
    }

    @Test
    @DisplayName("Intersection occupancy records a transporter standing at a junction")
    fun intersectionOccupancyIsRecorded() {
        val s = run(replicationLength = 100.0)
        val a = s.network.requireLocation("A")
        val b = s.network.requireLocation("B")

        // The cart holds A from 0 until it enters the first link zone at 1.2, and holds B from 4.8
        // to the end of the run, having parked there.
        assertEquals(
            1.2 / 100.0,
            s.system.intersectionCoverage.getValue(a).withinReplicationStatistic.weightedAverage,
            1e-9
        )
        assertEquals(
            (100.0 - 4.8) / 100.0,
            s.system.intersectionCoverage.getValue(b).withinReplicationStatistic.weightedAverage,
            1e-9,
            "an idle transporter goes on occupying the junction it stopped on, which is exactly " +
                    "why this statistic is worth having"
        )
    }

    @Test
    @DisplayName("Per-zone occupancy sums to the link's zone occupancy")
    fun zoneOccupancySumsToLinkOccupancy() {
        val s = run(collectZones = true, replicationLength = 100.0)
        val link = s.network.link("Path")!!
        val perZoneTotal = link.zones.sumOf {
            s.system.zoneCoverage.getValue(it).withinReplicationStatistic.weightedAverage
        }
        assertEquals(
            s.system.linkCoverage.getValue(link).withinReplicationStatistic.weightedAverage,
            perZoneTotal,
            1e-9,
            "the two tiers must agree, or a modeller drilling from one to the other finds them " +
                    "telling different stories"
        )
    }

    @Test
    @DisplayName("Both tiers can be switched on together")
    fun bothTiersCoexist() {
        // An intersection is a zone, so both tiers want to measure it. The first version of this
        // registered them under one name and the model refused to build -- a defect nobody would
        // have met until they switched on both flags, at which point nothing would run at all.
        val s = run(collectLinks = true, collectZones = true)
        assertEquals(s.network.intersections.size, s.system.intersectionCoverage.size)
        assertEquals(s.network.zones.size, s.system.zoneCoverage.size)
        val a = s.network.requireLocation("A")
        assertEquals(
            s.system.intersectionCoverage.getValue(a).withinReplicationStatistic.weightedAverage,
            s.system.zoneCoverage.getValue(a.zone).withinReplicationStatistic.weightedAverage,
            1e-9,
            "the two tiers measure the same junction and must agree about it"
        )
    }

    @Test
    @DisplayName("Nothing is collected, and nothing is registered, when the flags are off")
    fun withTheFlagsOffThereIsNothingToRead() {
        val s = run(collectLinks = false, collectZones = false)
        assertTrue(s.system.linkCoverage.isEmpty())
        assertTrue(s.system.linkUtilization.isEmpty())
        assertTrue(s.system.intersectionCoverage.isEmpty())
        assertTrue(s.system.zoneCoverage.isEmpty())
    }

    @Test
    @DisplayName("The system-wide zone utilization is reported whether or not the detail is")
    fun theAggregateIsAlwaysCollected() {
        val s = run(collectLinks = false)
        // One of the five zones -- four on the link and the junction at each end, less the shared
        // reckoning -- is covered at every instant, since the single cart is always somewhere.
        val average = s.system.zoneUtilization.withinReplicationStatistic.weightedAverage
        assertEquals(
            1.0 / s.network.zones.size, average, 1e-9,
            "one transporter one zone long covers exactly one of the network's zones at all times"
        )
    }

    @Test
    @DisplayName("Events per zone traversal is one when nothing waits for anything")
    fun eventCostIsTrackedAndIsOnePerTraversalWhenClear() {
        // The floor, and the number the guide's performance section rests on: one transporter with
        // an empty path ahead schedules exactly one event for each zone it enters. Anything above
        // this is waiting, and a climb over time would be transporters being woken and refused --
        // a performance defect that leaves every answer correct, which is why it needs a number
        // rather than a reader's attention.
        val s = run()
        // From A the cart enters the link's four zones and then B: five zones entered, five
        // traversal events, and nothing else scheduled because nothing ever made it wait.
        assertEquals(5.0, s.system.numZoneTraversals.value, 0.0)
        assertEquals(5.0, s.system.numEventsScheduled.value, 0.0)
        assertEquals(1.0, s.system.eventsPerZoneTraversal.value, 1e-9)
    }

    @Test
    @DisplayName("The statistics reset between replications rather than accumulating")
    fun statisticsResetBetweenReplications() {
        val m = Model("LinkStatsReplicated")
        val s = Straight(m, collectLinks = true)
        s.system.checkInvariants = true
        m.numberOfReplications = 3
        m.lengthOfReplication = 100.0
        m.simulate()
        val link = s.network.link("Path")!!
        val across = s.system.linkUtilization.getValue(link).acrossReplicationStatistic
        assertEquals(3.0, across.count, 0.0, "one observation per replication")
        assertEquals(
            0.0, across.variance, 1e-12,
            "a deterministic model must give the same utilization every replication; any spread " +
                    "means state leaked across the boundary"
        )
    }
}
