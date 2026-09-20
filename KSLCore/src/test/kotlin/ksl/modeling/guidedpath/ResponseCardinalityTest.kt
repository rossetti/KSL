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

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  `G10`: the size of a report must not depend on the size of the guide path.
 *
 *  A thousand-zone network with occupancy collected automatically would register a thousand
 *  time-weighted responses, and every standard report, every CSV, and every output-database table
 *  would carry them whether or not anyone had asked. The result is not merely verbose: it makes a
 *  realistic network unusable for the ordinary case, which is somebody who wants to know how long
 *  parts took.
 *
 *  So the assertion here is exact rather than approximate. A ten-zone network and a network a
 *  hundred times larger must register **the same number** of responses with the detail switched
 *  off, and the difference when it is switched on must be exactly the responses asked for and
 *  nothing else.
 */
class ResponseCardinalityTest {

    /** Set by the model element that tries to change a flag mid-run. */
    private var caught: Throwable? = null

    /**
     *  A ring of `n` intersections joined by one-way links, so that zone count scales with `n` and
     *  every network built this way is legal.
     *
     *  @param zonesPerLink how finely each link is divided, which is what makes the zone count grow
     *   independently of the intersection count
     */
    private class Ring(
        parent: ModelElement,
        intersections: Int,
        zonesPerLink: Int,
        collectLinks: Boolean = false,
        collectZones: Boolean = false
    ) : ModelElement(parent, "Ring") {

        val network: GuidedPathNetwork = run {
            var b = GuidedPathNetwork.builder("Ring$intersections")
            for (i in 0 until intersections) {
                b = b.intersection("I$i", x = i.toDouble() * 10.0, y = 0.0)
            }
            for (i in 0 until intersections) {
                val next = (i + 1) % intersections
                b = b.link(
                    "L$i", "I$i", "I$next",
                    length = 12.0 * zonesPerLink, zoneLength = 12.0, beginDirection = 0.0
                )
            }
            b.build()
        }

        val system = GuidedPathTransportSystem(
            this, network,
            collectLinkStatistics = collectLinks,
            collectZoneStatistics = collectZones,
            name = "Sys"
        )
    }

    /** Every response and counter registered anywhere in the model. */
    private fun statisticNames(build: (Model) -> Unit): Set<String> {
        val m = Model("Cardinality")
        build(m)
        return (m.responses.map { it.name } + m.counters.map { it.name }).toSet()
    }

    @Test
    @DisplayName("A network a hundred times larger registers exactly the same statistics")
    fun cardinalityDoesNotGrowWithTheNetwork() {
        val small = statisticNames { Ring(it, intersections = 5, zonesPerLink = 2) }
        val large = statisticNames { Ring(it, intersections = 50, zonesPerLink = 20) }

        // Named rather than merely counted: two sets of the same size could still differ, and the
        // property being defended is that a bigger guide path changes nothing about the report.
        assertEquals(
            small, large,
            "a ten-zone and a thousand-zone network must register the same statistics with the " +
                    "detail off. Extra in the large one: ${large - small}"
        )
        assertTrue(
            small.none { it.contains(":L0:") },
            "no per-link statistic may be registered when link statistics were not asked for: $small"
        )
    }

    @Test
    @DisplayName("A thousand-zone network really is a thousand zones")
    fun theLargeNetworkIsActuallyLarge() {
        // Guards the test above from passing because both networks were small.
        val m = Model("SizeCheck")
        val ring = Ring(m, intersections = 50, zonesPerLink = 20)
        assertEquals(1050, ring.network.zones.size, "1000 link zones plus 50 intersections")
    }

    @Test
    @DisplayName("Asking for link statistics adds exactly two per link and one per intersection")
    fun linkStatisticsAddExactlyWhatWasAskedFor() {
        val off = statisticNames { Ring(it, intersections = 5, zonesPerLink = 4) }
        val on = statisticNames { Ring(it, intersections = 5, zonesPerLink = 4, collectLinks = true) }
        val added = on - off
        // Occupancy and utilization for each of the five links, and occupancy for each of the five
        // intersections. Nothing else: an opt-in that quietly brought other things with it would be
        // as surprising as no opt-in at all.
        assertEquals(15, added.size, "unexpected additions: $added")
        assertEquals(5, added.count { it.endsWith(":NumZonesCovered") })
        assertEquals(5, added.count { it.endsWith(":Utilization") })
        assertEquals(5, added.count { it.endsWith(":IntersectionCovered") })
    }

    @Test
    @DisplayName("Asking for zone statistics adds exactly one per zone")
    fun zoneStatisticsAddExactlyOnePerZone() {
        val off = statisticNames { Ring(it, intersections = 5, zonesPerLink = 4) }
        val on = statisticNames { Ring(it, intersections = 5, zonesPerLink = 4, collectZones = true) }
        val added = on - off
        // Twenty link zones and five intersection zones.
        assertEquals(25, added.size, "unexpected additions: $added")
        assertTrue(added.all { it.endsWith(":ZoneCovered") }, "$added")
    }

    @Test
    @DisplayName("A tier can be switched on after construction and takes effect at once")
    fun aTierCanBeSwitchedOnAfterConstruction() {
        // The model refuses to add a model element only while it is *running*, so everything the
        // setter does is legal right up to the first replication. A flag that had to be decided in
        // the constructor would force a modeller to know they wanted the detail before they had
        // built the thing they wanted it about.
        val m = Model("SwitchOn")
        val ring = Ring(m, intersections = 5, zonesPerLink = 4)
        val before = (m.responses.map { it.name } + m.counters.map { it.name }).toSet()
        ring.system.collectLinkStatistics = true
        val after = (m.responses.map { it.name } + m.counters.map { it.name }).toSet()
        assertEquals(15, (after - before).size, "unexpected additions: ${after - before}")
        assertEquals(5, ring.system.linkCoverage.size)
    }

    @Test
    @DisplayName("Switching a tier off removes its responses rather than leaving them empty")
    fun switchingOffRemovesTheResponses() {
        // Leaving them registered but never written would be the worst of both: every report and
        // every database table would carry the columns with nothing in them, and a reader could not
        // tell "collected and always zero" from "not collected at all".
        val m = Model("SwitchOff")
        val ring = Ring(m, intersections = 5, zonesPerLink = 4, collectLinks = true, collectZones = true)
        val withDetail = (m.responses.map { it.name } + m.counters.map { it.name }).toSet()
        ring.system.collectLinkStatistics = false
        ring.system.collectZoneStatistics = false
        val without = (m.responses.map { it.name } + m.counters.map { it.name }).toSet()
        assertTrue(
            withDetail.size > without.size,
            "the report must actually shrink: ${withDetail.size} then ${without.size}"
        )
        assertTrue(ring.system.linkCoverage.isEmpty())
        assertTrue(ring.system.zoneCoverage.isEmpty())
        assertTrue(
            without.none { it.endsWith(":NumZonesCovered") || it.endsWith(":ZoneCovered") },
            "no trace of the removed tiers may remain: $without"
        )
        // And what is left is exactly what a system built without the detail would have had.
        val neverAsked = statisticNames { Ring(it, intersections = 5, zonesPerLink = 4) }
        assertEquals(neverAsked, without)
    }

    @Test
    @DisplayName("A tier switched off and on again registers cleanly")
    fun aTierCanBeSwitchedOffAndOnAgain() {
        // Removal frees the names, so this only works if the responses really left the model. A
        // half-removal would surface here as a duplicate-name refusal.
        val m = Model("SwitchCycle")
        val ring = Ring(m, intersections = 5, zonesPerLink = 4, collectLinks = true)
        val first = (m.responses.map { it.name }).toSet()
        ring.system.collectLinkStatistics = false
        ring.system.collectLinkStatistics = true
        val second = (m.responses.map { it.name }).toSet()
        assertEquals(first, second)
    }

    @Test
    @DisplayName("A model whose tiers were toggled still runs and still collects")
    fun aToggledModelStillRuns() {
        // The point of the whole exercise: the responses must be live model elements afterwards,
        // not orphans that merely have the right names.
        val m = Model("ToggledRun")
        val ring = Ring(m, intersections = 4, zonesPerLink = 3, collectLinks = true)
        ring.system.collectLinkStatistics = false
        ring.system.collectLinkStatistics = true
        GuidedTransporter(
            ring.system, TransporterPlacement.At("I0"),
            ksl.utilities.random.rvariable.ConstantRV(10.0), 1, name = "Cart"
        )
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()
        val link = ring.network.link("L0")!!
        assertEquals(
            1.0,
            ring.system.intersectionCoverage.getValue(ring.network.requireLocation("I0"))
                .withinReplicationStatistic.weightedAverage,
            1e-9,
            "the parked cart covers I0 for the whole run, and the re-registered response must " +
                    "have been collecting it"
        )
        assertTrue(ring.system.linkUtilization.containsKey(link))
    }

    @Test
    @DisplayName("Neither tier may be switched while the model is running")
    fun theTiersAreFrozenDuringARun() {
        val m = Model("SwitchDuringRun")
        val ring = Ring(m, intersections = 4, zonesPerLink = 3)
        object : ModelElement(ring, "Meddler") {
            override fun initialize() {
                caught = runCatching { system.collectLinkStatistics = true }.exceptionOrNull()
            }

            val system get() = ring.system
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 10.0
        m.simulate()
        val thrown = caught
        assertTrue(
            thrown is IllegalArgumentException,
            "adding responses mid-run would corrupt the model, so it must be refused: $thrown"
        )
    }

    @Test
    @DisplayName("The system-level congestion statistics are always there")
    fun theAggregatesAreAlwaysRegistered() {
        // The other half of the trade: the detail is off by default, so the aggregates that answer
        // the first question anyone asks must not be, or the default is useless.
        val names = statisticNames { Ring(it, intersections = 5, zonesPerLink = 4) }
        for (suffix in listOf(
            ":NumTransportersMoving", ":NumTransportersBlocked", ":NumTransportersIdle",
            ":ZoneUtilization", ":NumDeadlocksDetected", ":NumObstructionsDetected",
            ":TransportTime", ":ApproachTime", ":RideTime", ":TransportBlockedTime",
            ":ZonesTraversedPerTransport", ":RouteLengthPerTransport",
            ":NumZoneTraversals", ":NumEventsScheduled", ":EventsPerZoneTraversal",
            // The decomposition of blocked time, which is what makes general occupancy
            // validatable: a model with no closures has to hide that time in inflated task times,
            // and these three are what turn one fitted fudge into separately observable
            // quantities. Aggregates, so they are on by default with the rest.
            ":NumZonesClosed", ":NumBlockedByVehicle", ":NumBlockedByOccupier",
            ":NumBlockedByPopulation",
            // The split of the vehicle cause by whether what is in the way has anything to do.
            // Always registered with the three above, and for the same reason: it is the reading
            // that separates a queue from a fleet that has stopped, and a default that leaves it
            // out leaves the obstruction count with nothing to be read against.
            ":NumBlockedByIdleVehicle",
            // General occupancy's own three, and the reason they are here rather than on the
            // holders: a model may make as many holders as the run turns out to need, so a
            // response per holder would be a response count nobody can state before the run.
            ":NumHoldersAwaitingSpace", ":TimeToCloseZones", ":NumZoneEngagements",
            // Space asked for and never granted. On by default with the rest because a sweep is
            // exactly where this reading is needed and exactly where nobody reads the log: a run
            // whose closure never happened otherwise reports plausible numbers for a model that
            // did not take place.
            ":NumRequestsUnfilled"
        )) {
            assertTrue(
                names.any { it.endsWith(suffix) },
                "$suffix must be registered whatever the network size, but was not in $names"
            )
        }
    }
}
