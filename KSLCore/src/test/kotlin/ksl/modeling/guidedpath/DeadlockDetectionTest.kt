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

import ksl.modeling.guidedpath.exceptions.GuidedPathDeadlockException
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Benchmark §16.3(d), first half: a constructed circular wait must be found, and the report must
 *  name exactly who is in it.
 *
 *  The construction is the one the source text warns about -- two vehicles sent toward each other
 *  over a bidirectional link -- and it is worth being precise about *why* it deadlocks, because the
 *  direction lock looks at first as though it should prevent this. It does prevent the head-on
 *  collision on the link itself: the second vehicle is refused entry and waits at the mouth. What
 *  it cannot prevent is the vehicle waiting at the mouth being *on the first vehicle's
 *  destination*. So the first runs the length of the link, finds the junction at the far end
 *  occupied by the one waiting to come the other way, and stops. Now each holds what the other
 *  needs. The cycle is closed by the destination, not by the link.
 *
 *  That distinction matters for the diagnosis, which is the point of this phase. A report that
 *  named the link would send a modeller looking at the wrong thing. The report names the two
 *  vehicles and the two zones each is actually waiting on, which is what says how to fix it.
 *
 *  Domain rules exercised: `R14`, goal `G5`.
 */
class DeadlockDetectionTest {

    /** One bidirectional link between two junctions: the smallest network a cycle fits in. */
    private class TwoWay(parent: ModelElement) : ModelElement(parent, "TwoWay") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("TwoWay")
            .link("Both", "A", "B", length = 36.0, zoneLength = 12.0, type = LinkType.BIDIRECTIONAL)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
    }

    /**
     *  Outbound starts on the link heading for B; Inbound stands on B and is told to go to A. Each
     *  ends up holding what the other needs.
     */
    private fun headOnModel(
        detection: Boolean = true,
        replicationLength: Double = 50.0
    ): Pair<Model, TwoWay> {
        val m = Model("HeadOnDeadlock")
        val tw = TwoWay(m)
        GuidedTransporter(
            tw.system, TransporterPlacement.OnZone("Both.Zone1"), ConstantRV(10.0), 1, name = "Outbound"
        )
        GuidedTransporter(
            tw.system, TransporterPlacement.At("B"), ConstantRV(10.0), 1, name = "Inbound"
        )
        object : ModelElement(tw, "Driver") {
            override fun initialize() {
                val outbound = tw.system.transporters.first { it.name.endsWith("Outbound") }
                val inbound = tw.system.transporters.first { it.name.endsWith("Inbound") }
                schedule({ _: KSLEvent<Nothing> -> outbound.sendTo("B") }, 0.0)
                schedule({ _: KSLEvent<Nothing> -> inbound.sendTo("A") }, 0.5)
            }
        }
        tw.system.deadlockDetectionEnabled = detection
        m.numberOfReplications = 1
        m.lengthOfReplication = replicationLength
        return m to tw
    }

    @Test
    @DisplayName("A constructed circular wait raises rather than stalling silently")
    fun aCircularWaitRaises() {
        val (m, _) = headOnModel()
        assertFailsWith<GuidedPathDeadlockException>(
            "two transporters each holding what the other needs must end the replication, not " +
                    "leave the clock running to the end with nobody moving"
        ) { m.simulate() }
    }

    @Test
    @DisplayName("The report names exactly the two transporters and the two zones")
    fun theReportNamesExactlyTheParticipants() {
        val (m, _) = headOnModel()
        val thrown = assertFailsWith<GuidedPathDeadlockException> { m.simulate() }
        val report = thrown.report

        assertEquals(
            2, report.participants.size,
            "exactly two transporters are in this cycle and no others may be swept in: ${report.participants}"
        )
        val names = report.participants.map { it.transporterName.substringAfterLast(":") }.toSet()
        assertEquals(setOf("Outbound", "Inbound"), names)

        // Each awaits what the other holds, which is what makes it a cycle rather than a queue.
        val byName = report.participants.associateBy { it.transporterName.substringAfterLast(":") }
        val outbound = assertNotNull(byName["Outbound"])
        val inbound = assertNotNull(byName["Inbound"])
        assertEquals(
            "B", outbound.awaitedZoneName,
            "Outbound is stopped at the far end of the link wanting the junction Inbound stands on"
        )
        assertEquals(
            "Both.Zone3", inbound.awaitedZoneName,
            "Inbound wants onto the link at the end Outbound has reached"
        )
        assertTrue(
            outbound.awaitedZoneName in inbound.heldZoneNames,
            "the cycle closes only if each participant holds what the next awaits: " +
                    "Inbound holds ${inbound.heldZoneNames}"
        )
        assertTrue(
            inbound.awaitedZoneName in outbound.heldZoneNames,
            "and the other way round: Outbound holds ${outbound.heldZoneNames}"
        )
    }

    @Test
    @DisplayName("The report is raised when the cycle forms, not when the replication ends")
    fun theReportIsRaisedAtTheInstantTheCycleForms() {
        val (m, _) = headOnModel(replicationLength = 5000.0)
        val thrown = assertFailsWith<GuidedPathDeadlockException> { m.simulate() }
        // Outbound starts on Both.Zone1 and must cross Zone2 and Zone3 at ten feet per minute over
        // twelve-foot zones: 1.2 each, so it is stopped at the far end at 2.4. The cycle exists
        // from that instant, and the replication runs to 5000, so detecting at the end would be
        // detecting nearly five thousand time units late.
        assertEquals(2.4, thrown.report.time, 1e-9)
    }

    @Test
    @DisplayName("The message names every participant, so a log alone is enough to diagnose it")
    fun theMessageIsSelfContained() {
        val (m, _) = headOnModel()
        val thrown = assertFailsWith<GuidedPathDeadlockException> { m.simulate() }
        val message = assertNotNull(thrown.message)
        assertTrue(message.contains("Outbound"), message)
        assertTrue(message.contains("Inbound"), message)
        assertTrue(message.contains("Both.Zone3"), message)
        assertTrue(message.contains("holds") && message.contains("awaits"), message)
    }

    @Test
    @DisplayName("With detection off the same model stalls silently instead")
    fun withDetectionOffTheRunStallsInstead() {
        // This is the behaviour the phase exists to replace, and asserting it is what makes the
        // improvement measurable rather than merely claimed: the run completes, reports no error at
        // all, and the only trace of the deadlock is that both transporters are still blocked.
        val (m, tw) = headOnModel(detection = false)
        m.simulate()
        assertEquals(
            2, tw.system.blockedTransporters.size,
            "both transporters are stuck, and nothing in the ordinary output says so"
        )
        assertEquals(
            0.0, tw.system.numObstructionsDetected.value, 0.0,
            "a circular wait is not an obstruction and must not be counted as one"
        )
    }

    @Test
    @DisplayName("Ordinary blocking is not mistaken for a cycle")
    fun ordinaryBlockingIsNotACycle() {
        // Two transporters going the same way on a one-way link: the follower blocks, waits, and
        // proceeds. A detector that reported this would be useless, because blocking is normal
        // operation and happens constantly in any fleet worth simulating.
        val m = Model("Following")
        val oneWay = object : ModelElement(m, "OneWay") {
            val network: GuidedPathNetwork = GuidedPathNetwork.builder("OneWay")
                .link("Down", "A", "B", length = 36.0, zoneLength = 12.0)
                .link("On", "B", "C", length = 36.0, zoneLength = 12.0)
                .build()
            val system = GuidedPathTransportSystem(this, network, name = "Sys")
        }
        val leader = GuidedTransporter(
            oneWay.system, TransporterPlacement.OnZone("Down.Zone2"), ConstantRV(10.0), 1, name = "Leader"
        )
        val follower = GuidedTransporter(
            oneWay.system, TransporterPlacement.OnZone("Down.Zone1"), ConstantRV(10.0), 1, name = "Follower"
        )
        object : ModelElement(oneWay, "Driver") {
            override fun initialize() {
                // Both leave at once and the leader goes further on, so it is never standing in the
                // follower's way. The follower is held up only by a transporter that is itself
                // moving, which is the ordinary case and must produce neither report.
                schedule({ _: KSLEvent<Nothing> -> leader.sendTo("C") }, 0.0)
                schedule({ _: KSLEvent<Nothing> -> follower.sendTo("B") }, 0.0)
            }
        }
        oneWay.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()
        assertTrue(follower.numTimesBlocked.value > 0.0, "the follower must genuinely have blocked")
        assertEquals("B", follower.frontZone?.name, "and must then have got through")
        assertEquals("C", leader.frontZone?.name, "as must the leader")
        assertNull(follower.awaitedZone, "with nothing left to wait for")
        assertEquals(
            0.0, oneWay.system.numObstructionsDetected.value, 0.0,
            "being held up by something that is itself moving is not an obstruction either"
        )
    }
}
