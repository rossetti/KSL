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
import ksl.modeling.guidedpath.rules.StartOfZoneControl
import ksl.modeling.guidedpath.rules.ZoneControlRuleIfc
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Two transporters, one path, and blocking time worked out by hand.
 *
 *  This is the sharpest test available that the zone control rules are implemented rather than
 *  approximated. A follower's delay depends entirely on *when* the leader gives up the zone behind
 *  it, so the same journey under the two rules produces two different, separately derivable
 *  answers. A test that only checked where the transporters ended up would pass for either rule,
 *  and for an implementation that ignored the distinction altogether.
 *
 *  The arithmetic. Zones of twelve feet at ten feet a minute, so a zone takes 1.2 minutes to cross.
 *  Two transporters one zone long start nose to tail on `L1`, the leader on `Zone2` and the
 *  follower on `Zone1`, and both are set going in the same instant, leader first.
 *
 *  Under **end** control the leader gives up `Zone2` only on arriving in `Zone3`, at 1.2. The
 *  follower wants `Zone2` at once, cannot have it, and waits exactly that long: **1.2 minutes
 *  blocked, once**. Thereafter the leader is always a zone ahead and releasing as the follower
 *  arrives, so the follower never waits again.
 *
 *  Under **start** control the leader gives up `Zone2` the moment it begins moving into `Zone3`,
 *  which is the same instant the follower asks for it. The follower is **never blocked at all**.
 *
 *  The two destinations are deliberately different. A transporter that stops on the zone another
 *  one is heading for holds it for the rest of the run, and the follower would then wait forever --
 *  which is a real situation, and one a later phase diagnoses, but not the one being measured here.
 */
class TwoVehicleBlockingTest {

    private companion object {
        /** Crossing one twelve foot zone at ten feet a minute. */
        const val ZONE_CROSSING = 1.2

        /** Chosen so that blocked time reads directly off the time-weighted fraction. */
        const val RUN_LENGTH = 20.0
    }

    /** A straight file: A to B is four zones, B to C is two. */
    private class File(
        parent: ModelElement,
        rule: () -> ZoneControlRuleIfc
    ) : ModelElement(parent, "File") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("File")
            .link("L1", "A", "B", length = 48.0, zoneLength = 12.0)
            .link("L2", "B", "C", length = 24.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        // Nose to tail: the leader on the second zone, the follower on the first.
        val leader = GuidedTransporter(
            system, TransporterPlacement.OnZone("L1.Zone2"), ConstantRV(10.0), 1, rule(), "Leader"
        )
        val follower = GuidedTransporter(
            system, TransporterPlacement.OnZone("L1.Zone1"), ConstantRV(10.0), 1, rule(), "Follower"
        )

        val arrivals = linkedMapOf<String, Double>()

        init {
            leader.attachArrivalListener { arrivals["Leader"] = time }
            follower.attachArrivalListener { arrivals["Follower"] = time }
        }

        override fun initialize() {
            arrivals.clear()
            schedule({ _: KSLEvent<Nothing> ->
                // The leader goes on past where the follower stops, so neither ends up standing on
                // the other's destination.
                leader.sendTo("C")
                follower.sendTo("B")
            }, 0.0)
        }

        /** Time spent unable to claim the space ahead, in minutes. */
        fun blockedTime(t: GuidedTransporter): Double = t.fracTimeBlocked.withinReplicationAverage * RUN_LENGTH
    }

    private fun run(rule: () -> ZoneControlRuleIfc): File {
        val m = Model("Blocking")
        val f = File(m, rule)
        f.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = RUN_LENGTH
        m.simulate()
        return f
    }

    @Test
    fun `the leader is never delayed by the transporter behind it`() {
        for (rule in listOf<() -> ZoneControlRuleIfc>({ EndOfZoneControl() }, { StartOfZoneControl() })) {
            val f = run(rule)
            assertEquals(0.0, f.blockedTime(f.leader), 1e-9)
            assertEquals(0, f.leader.numTimesBlocked.value.toInt())
        }
    }

    @Test
    fun `under end control the follower waits exactly one zone crossing, once`() {
        val f = run { EndOfZoneControl() }
        assertEquals(1, f.follower.numTimesBlocked.value.toInt())
        assertEquals(ZONE_CROSSING, f.blockedTime(f.follower), 1e-9)
    }

    @Test
    fun `under start control the follower is never blocked at all`() {
        val f = run { StartOfZoneControl() }
        assertEquals(0, f.follower.numTimesBlocked.value.toInt())
        assertEquals(0.0, f.blockedTime(f.follower), 1e-9)
    }

    @Test
    fun `the difference between the two rules is exactly one zone crossing of delay`() {
        // The one comparison that cannot pass for an implementation treating the rules alike.
        val ends = run { EndOfZoneControl() }
        val starts = run { StartOfZoneControl() }
        assertEquals(
            ZONE_CROSSING,
            ends.blockedTime(ends.follower) - starts.blockedTime(starts.follower),
            1e-9
        )
    }

    @Test
    fun `blocking delays the follower's arrival by exactly what it spent waiting`() {
        val ends = run { EndOfZoneControl() }
        val starts = run { StartOfZoneControl() }
        assertEquals(
            ZONE_CROSSING,
            ends.arrivals["Follower"]!! - starts.arrivals["Follower"]!!,
            1e-9
        )
    }

    @Test
    fun `both transporters get there, and neither is left waiting`() {
        for (rule in listOf<() -> ZoneControlRuleIfc>({ EndOfZoneControl() }, { StartOfZoneControl() })) {
            val f = run(rule)
            assertEquals("C", f.leader.frontZone?.name)
            assertEquals("B", f.follower.frontZone?.name)
            assertEquals(2, f.arrivals.size)
            assertTrue(f.system.blockedTransporters.isEmpty())
        }
    }

    @Test
    fun `the fleet counts add up at every moment the clock advances`() {
        val f = run { EndOfZoneControl() }
        // Moving, blocked and idle partition the fleet, so the three time-weighted averages must
        // sum to the fleet size however the run went.
        val moving = f.system.numTransportersMoving.withinReplicationAverage
        val blocked = f.system.numTransportersBlocked.withinReplicationAverage
        val idle = f.system.numTransportersIdle.withinReplicationAverage
        assertEquals(2.0, moving + blocked + idle, 1e-9)
    }
}
