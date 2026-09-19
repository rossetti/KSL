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
package ksl.modeling.guidedpath.exceptions

import ksl.modeling.entity.ProcessModel
import ksl.simulation.KSLEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  Phase 0 tests for the guided path exception types.
 *
 *  Two things are pinned here. First, the priority ordering decided by open issue OI-3, which the
 *  contention path in a later phase depends on and which nothing else would catch if it drifted.
 *  Second, the message contract every specification error promises: that a modeler can act on the
 *  message alone, without reading a stack trace. The network validation tests of the next phase
 *  assert against these same factory functions, so the wording is fixed in one place.
 */
class GuidedPathExceptionTest {

    // ---- OI-3: the zone claim priority sits between resume and move ----

    @Test
    fun `zone claim outranks a move so a woken claim settles before traversals at the same time`() {
        // Smaller values are higher priority in the KSL executive.
        assertTrue(
            ProcessModel.ZONE_CLAIM_PRIORITY < ProcessModel.MOVE_PRIORITY,
            "ZONE_CLAIM_PRIORITY (${ProcessModel.ZONE_CLAIM_PRIORITY}) must be a higher priority " +
                    "than MOVE_PRIORITY (${ProcessModel.MOVE_PRIORITY})"
        )
    }

    @Test
    fun `zone claim never preempts a process resumption already in flight`() {
        assertTrue(
            ProcessModel.ZONE_CLAIM_PRIORITY > ProcessModel.RESUME_PRIORITY,
            "ZONE_CLAIM_PRIORITY (${ProcessModel.ZONE_CLAIM_PRIORITY}) must be a lower priority " +
                    "than RESUME_PRIORITY (${ProcessModel.RESUME_PRIORITY})"
        )
    }

    @Test
    fun `zone claim settles space one step before a resource seize at the same time`() {
        assertTrue(
            ProcessModel.ZONE_CLAIM_PRIORITY < ProcessModel.SEIZE_PRIORITY,
            "ZONE_CLAIM_PRIORITY (${ProcessModel.ZONE_CLAIM_PRIORITY}) must be a higher priority " +
                    "than SEIZE_PRIORITY (${ProcessModel.SEIZE_PRIORITY})"
        )
        assertEquals(KSLEvent.HIGH_PRIORITY - 1, ProcessModel.ZONE_CLAIM_PRIORITY)
    }

    // ---- Network specification errors name the offending element and its values ----

    @Test
    fun `a link geometry failure reports the remainder so a unit mistake is distinguishable`() {
        val e = GuidedPathNetworkException.linkGeometry("Link1", 50.0, 12.0, 4, 2.0)
        val m = e.message!!
        assertTrue(m.contains("Link1"), m)
        assertTrue(m.contains("50.0"), m)
        assertTrue(m.contains("12.0"), m)
        assertTrue(m.contains("remainder of 2.0"), m)
    }

    @Test
    fun `a non positive property names the element the property and the value`() {
        val m = GuidedPathNetworkException.nonPositive("Link", "Link1", "velocityFactor", 0.0).message!!
        assertTrue(m.contains("Link1"), m)
        assertTrue(m.contains("velocityFactor"), m)
        assertTrue(m.contains("0.0"), m)
    }

    @Test
    fun `a malformed spur names the terminal its degree and the links that made it one`() {
        val m = GuidedPathNetworkException
            .spurTerminalDegree("Link5", "I4", 3, listOf("Link3", "Link4", "Link5")).message!!
        assertTrue(m.contains("Link5"), m)
        assertTrue(m.contains("I4"), m)
        assertTrue(m.contains("degree 3"), m)
        assertTrue(m.contains("Link3"), m)
    }

    @Test
    fun `a placement conflict names both transporters and the zone they would share`() {
        val m = GuidedPathNetworkException.placementOverlap("AGV1", "AGV2", "Link1.Zone2").message!!
        assertTrue(m.contains("AGV1"), m)
        assertTrue(m.contains("AGV2"), m)
        assertTrue(m.contains("Link1.Zone2"), m)
    }

    @Test
    fun `a transporter too long for its placement reports both zone counts`() {
        val m = GuidedPathNetworkException
            .transporterTooLongForPlacement("AGV1", 3, "Link1", 2).message!!
        assertTrue(m.contains("AGV1"), m)
        assertTrue(m.contains("3 zones"), m)
        assertTrue(m.contains("Link1"), m)
        assertTrue(m.contains("only 2"), m)
    }

    @Test
    fun `attaching a second system to one network names the system already attached`() {
        val m = GuidedPathNetworkException.networkAlreadyAttached("Net1", "System1").message!!
        assertTrue(m.contains("Net1"), m)
        assertTrue(m.contains("System1"), m)
    }

    // ---- Routing errors distinguish an unreachable pair from a misbehaving rule ----

    @Test
    fun `an unreachable pair names both endpoints and points at link direction`() {
        val m = GuidedPathRoutingException.unreachable("I1", "I7").message!!
        assertTrue(m.contains("I1"), m)
        assertTrue(m.contains("I7"), m)
        assertTrue(m.contains("unidirectional"), m)
    }

    @Test
    fun `a bad route names the rule class so the defect is attributed to the extension`() {
        val m = GuidedPathRoutingException
            .nonAdjacentRoute("MyCongestionAwareRule", "Link1.Zone4", "Link3.Zone1").message!!
        assertTrue(m.contains("MyCongestionAwareRule"), m)
        assertTrue(m.contains("Link1.Zone4"), m)
        assertTrue(m.contains("Link3.Zone1"), m)
    }

    // ---- Deadlock and obstruction reports ----

    @Test
    fun `a deadlock report renders every participant with what it holds and awaits`() {
        val report = DeadlockReport(
            time = 42.0,
            participants = listOf(
                DeadlockParticipant("AGV1", listOf("Link1.Zone2"), "Link1.Zone3"),
                DeadlockParticipant("AGV2", listOf("Link1.Zone3"), "Link1.Zone2")
            )
        )
        val m = report.toString()
        assertTrue(m.contains("42.0"), m)
        assertTrue(m.contains("AGV1"), m)
        assertTrue(m.contains("AGV2"), m)
        assertTrue(m.contains("Link1.Zone2"), m)
        assertTrue(m.contains("Link1.Zone3"), m)
        assertEquals(m, GuidedPathDeadlockException(report).message)
    }

    @Test
    fun `a deadlock cycle of fewer than two participants is not a cycle`() {
        assertFailsWith<IllegalArgumentException> {
            DeadlockReport(1.0, listOf(DeadlockParticipant("AGV1", listOf("Z1"), "Z2")))
        }
    }

    @Test
    fun `an obstruction report says it is not a circular wait and names the idle transporter`() {
        val o = IdleTransporterObstruction(7.5, "AGV2", "I4", "AGV1")
        val m = o.toString()
        assertTrue(m.contains("AGV1"), m)
        assertTrue(m.contains("AGV2"), m)
        assertTrue(m.contains("I4"), m)
        assertTrue(m.contains("not a circular wait"), m)
        assertEquals(m, GuidedPathObstructionException(o).message)
    }
}
