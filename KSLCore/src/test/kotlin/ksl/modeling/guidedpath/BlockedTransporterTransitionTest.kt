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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Two ways a *waiting* transporter can be made to do something else, both of which were wrong and
 *  neither of which any test caught.
 *
 *  Both were found by running the worked examples rather than by the suite, and both share a
 *  shape worth naming: the engine handled a transporter that was moving and a transporter that was
 *  idle, and treated blocked as though it were one of those. A blocked transporter is neither. It
 *  holds no claim, so it looks idle enough to redirect; but it is on a waiter list and carries a
 *  running clock, so it is not idle at all.
 *
 *  The suite missed them because every earlier test either let a blocked transporter resolve
 *  normally or ended the replication with the fleet at rest. It took a model where work kept
 *  arriving while carts were still waiting -- which is simply what a busy shop looks like -- to
 *  reach either one.
 */
class BlockedTransporterTransitionTest {

    /**
     *  A one-way path that forks at `B`, so a transporter stopped at the fork has somewhere else it
     *  could be sent. Without the fork the question does not arise: a transporter blocked on a line
     *  has no second option, and redirecting it would be meaningless rather than merely untested.
     */
    private class Corridor(parent: ModelElement) : ModelElement(parent, "Corridor") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Corridor")
            .intersection("A", x = 0.0, y = 0.0)
            .intersection("B", x = 36.0, y = 0.0)
            .intersection("C", x = 72.0, y = 0.0)
            .intersection("D", x = 108.0, y = 0.0)
            .intersection("E", x = 72.0, y = -36.0)
            .link("First", "A", "B", length = 36.0, zoneLength = 12.0, beginDirection = 0.0)
            .link("Second", "B", "C", length = 36.0, zoneLength = 12.0, beginDirection = 0.0)
            .link("Bypass", "B", "E", length = 36.0, zoneLength = 12.0, beginDirection = 270.0)
            .link("ToD", "C", "D", length = 36.0, zoneLength = 12.0, beginDirection = 0.0)
            .link("Around", "E", "D", length = 36.0, zoneLength = 12.0, beginDirection = 0.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
    }

    @Test
    @DisplayName("A blocked transporter can be sent somewhere else, and gives up its wait first")
    fun aBlockedTransporterCanBeRedirected() {
        // A cart stopped on its way home is doing work nobody needs while an entity waits for one,
        // so redirecting it is worth being able to do. But it is on a waiter list, and leaving it
        // there put it on the list twice the next time it blocked -- which is what the zone's own
        // check caught, thousands of simulated minutes into a busy shop.
        val m = Model("RedirectBlocked")
        val corridor = Corridor(m)
        GuidedTransporter(
            corridor.system, TransporterPlacement.OnZone("Second.Zone1"), ConstantRV(10.0), 1,
            EndOfZoneControl(), "Parked"
        )
        val traveller = GuidedTransporter(
            corridor.system, TransporterPlacement.At("A"), ConstantRV(10.0), 1, EndOfZoneControl(), "Traveller"
        )
        var blockedAt = -1.0
        var awaited: String? = null
        object : ModelElement(corridor, "Driver") {
            override fun initialize() {
                // Traveller heads for C, whose only approach is the link Parked is standing on.
                schedule({ _: KSLEvent<Nothing> -> traveller.sendTo("C") }, 0.0)
                schedule({ _: KSLEvent<Nothing> ->
                    blockedAt = if (traveller.transporterState == TransporterState.BLOCKED) time else -1.0
                    awaited = traveller.awaitedZone?.name
                }, 5.0)
                // Now send the blocked traveller down the fork, which is clear.
                schedule({ _: KSLEvent<Nothing> -> traveller.sendTo("E") }, 6.0)
            }
        }
        corridor.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()

        assertTrue(blockedAt > 0.0, "the traveller must genuinely have been blocked behind Parked")
        assertEquals("Second.Zone1", awaited, "stopped at the fork, wanting the zone Parked holds")
        // Redirected and arrived: it is at B, holds nothing it should not, and is on no list.
        assertEquals("E", traveller.frontZone?.name)
        assertEquals(TransporterState.IDLE, traveller.transporterState)
        assertNull(traveller.awaitedZone)
        assertNull(traveller.awaitedLink)
        assertFalse(
            corridor.network.zone("Second.Zone1")!!.waiters.contains(traveller),
            "the abandoned wait must be given up, or the transporter is woken later for a journey " +
                    "it is no longer making, and is on the list twice the next time it blocks"
        )
    }

    @Test
    @DisplayName("A transporter still blocked when a replication ends starts the next one clean")
    fun blockedAtTheEndOfAReplicationResetsCleanly() {
        // The reset clears the running blocked-time clock while the state is still BLOCKED, so the
        // transition out of it accumulated `time - NaN`. The NaN then travelled into the first
        // transport result of the *next* replication and failed the run, thousands of simulated
        // minutes from the reset that caused it.
        val m = Model("BlockedAtEnd")
        val corridor = Corridor(m)
        GuidedTransporter(
            corridor.system, TransporterPlacement.OnZone("Second.Zone1"), ConstantRV(10.0), 1,
            EndOfZoneControl(), "Parked"
        )
        val traveller = GuidedTransporter(
            corridor.system, TransporterPlacement.At("A"), ConstantRV(10.0), 1, EndOfZoneControl(), "Traveller"
        )
        object : ModelElement(corridor, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> traveller.sendTo("C") }, 0.0)
            }
        }
        m.numberOfReplications = 3
        // Short enough that the traveller is still stuck behind Parked when time runs out.
        m.lengthOfReplication = 20.0
        m.simulate()

        assertEquals(
            TransporterState.BLOCKED, traveller.transporterState,
            "the replication must indeed have ended with the traveller still waiting"
        )
        val blocked = traveller.fracTimeBlocked.acrossReplicationStatistic
        assertEquals(3.0, blocked.count, 0.0, "one observation per replication")
        assertFalse(blocked.average.isNaN(), "a replication boundary must not poison the statistics")
        assertTrue(
            blocked.average > 0.0,
            "and the transporter really was blocked, so the figure must be positive: ${blocked.average}"
        )
        assertEquals(
            0.0, blocked.variance, 1e-12,
            "a deterministic model must block for the same time every replication; any spread " +
                    "means the reset left something behind"
        )
    }
}
