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
import kotlin.test.assertTrue

/**
 *  That asking the transporters how much of the guide path is covered gives the same answer as
 *  asking the zones.
 *
 *  `refreshFleetCounts` is the hottest thing in the subsystem -- the movement engine calls it at
 *  seven points, one of them the completion of every zone traversal -- and it needs one number from
 *  the guide path: how many zones are covered. It used to get that by walking every zone, which on
 *  the reference benchmark meant four hundred zone visits per traversal, four million times over,
 *  and cost about three quarters of the whole run. It now gets it by summing what the transporters
 *  say they cover, a loop over the fleet that was running anyway.
 *
 *  The two are the same number, and `checkCoverageIsConserved` exists to assert exactly that --
 *  but only at the end of an instant, and `refreshFleetCounts` is called *within* events. So the
 *  substitution rests on the two agreeing at every moment either is observed, which is a stronger
 *  claim than the audit makes and needs its own test.
 *
 *  This derives the fraction a second way, from the per-zone responses, which are written by a real
 *  walk over the zones. Two time-weighted averages over the same interval: the aggregate, and the
 *  mean of the per-zone series. If the transporter-side count were ever wrong -- momentarily, in
 *  some event, in a way the end-of-instant audit cannot see -- the two integrals would part company.
 *
 *  It is done on a contended model on purpose. A single transporter one zone long covers exactly one
 *  zone at every instant, so the two derivations agree there whatever either of them does.
 */
class ZoneUtilizationDerivationTest {

    /**
     *  Two carts on a short path, one of them two zones long, sent so that they contend.
     *
     *  Contention is the point: a blocked cart holds its zones while going nowhere, one cart is
     *  between zones part of the time, and a two-zone cart covers one zone rather than two while it
     *  is still driving on. Each of those is a moment when a careless count would be wrong.
     */
    private class Contended(parent: ModelElement) : ModelElement(parent, "Contended") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Contended")
            .link("L1", "A", "B", length = 60.0, zoneLength = 12.0)
            .link("L2", "B", "C", length = 36.0, zoneLength = 12.0)
            .link("L3", "C", "A", length = 48.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        val long = GuidedTransporter(
            system, TransporterPlacement.OnZone("L1.Zone2"), ConstantRV(10.0), 2, name = "Long"
        )
        val short = GuidedTransporter(
            system, TransporterPlacement.OnZone("L1.Zone4"), ConstantRV(14.0), 1, name = "Short"
        )

        override fun initialize() {
            // Round and round, so that the pair keep meeting rather than settling down.
            schedule({ _: KSLEvent<Nothing> -> long.sendTo("C") }, 0.0)
            schedule({ _: KSLEvent<Nothing> -> short.sendTo("C") }, 0.0)
            for (t in 1..8) {
                schedule({ _: KSLEvent<Nothing> -> long.sendTo(if (t % 2 == 0) "A" else "C") }, 20.0 * t)
                schedule({ _: KSLEvent<Nothing> -> short.sendTo(if (t % 2 == 0) "C" else "A") }, 20.0 * t)
            }
        }
    }

    @Test
    fun `the aggregate matches a walk over the zones, under contention`() {
        val m = Model("ZoneUtilizationDerivation")
        val c = Contended(m)
        c.system.collectZoneStatistics = true
        c.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 180.0
        m.simulate()

        // The aggregate, derived from what the transporters say they cover.
        val aggregate = c.system.zoneUtilization.withinReplicationStatistic.weightedAverage

        // The same fraction, derived from a genuine walk: each per-zone series is the fraction of
        // time that zone was covered, so their mean is the fraction of the network covered.
        val perZone = c.system.zoneCoverage.values
            .sumOf { it.withinReplicationStatistic.weightedAverage } / c.network.zones.size

        assertEquals(
            perZone, aggregate, 1e-9,
            "the fraction of the guide path covered, asked of the transporters ($aggregate) and " +
                    "asked of the zones ($perZone), must be the same number"
        )

        // Anti-vacuity. Both derivations would agree at zero, and a model in which the carts never
        // moved or never contended would prove nothing about either.
        assertTrue(aggregate > 0.0, "nothing was ever covered, so the comparison is empty")
        assertTrue(
            c.long.numTimesBlocked.value + c.short.numTimesBlocked.value > 0.0,
            "the carts never contended, so the moments this test exists for never arose"
        )
    }
}
