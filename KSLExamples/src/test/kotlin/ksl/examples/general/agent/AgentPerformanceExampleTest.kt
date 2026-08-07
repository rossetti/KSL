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

package ksl.examples.general.agent

import ksl.simulation.Model
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Phase C8 — `PermanentAgent.collectPerformance`.
 *
 *  `AgentPerformance` had no example. It is the reason `PermanentAgent` exists as a
 *  tier distinct from the transient `Agent`, so leaving it unexampled left the
 *  distinction looking arbitrary.
 *
 *  These tests check the example says something true. Service times are
 *  deterministic and arrivals are dealt round-robin, so each specialist's
 *  utilisation has a closed form — `arrivalRate / specialists * serviceTime` — and
 *  the per-agent statistics can be checked against it rather than merely inspected.
 */
class AgentPerformanceExampleTest {

    private fun run(reps: Int = 5, length: Double = 3000.0): AgentPerformanceExample {
        val model = Model("perfExample")
        val sys = AgentPerformanceExample(model, "helpdesk")
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        model.simulate()
        return sys
    }

    @Test
    @DisplayName("C8: every specialist gets its own performance observer")
    fun everySpecialistIsMeasured() {
        val sys = run(reps = 1, length = 500.0)
        assertEquals(3, sys.specialists.size)
        for (s in sys.specialists) {
            assertNotNull(s.performance, "${s.name} should have a performance observer")
            assertTrue(s.performance!!.tracksStatechart, "${s.name} should track statechart stats")
        }
    }

    /**
     *  The point of per-agent statistics: an aggregate response cannot distinguish
     *  the saturated specialist from the idle one. Utilisation must rise with
     *  service time, and match the closed form.
     */
    @Test
    @DisplayName("C8: utilisation differs per specialist and matches the analytic value")
    fun utilisationMatchesTheAnalyticValue() {
        val sys = run()
        val rate = 1.0 / sys.arrivalMean
        val busy = sys.specialists.map { s ->
            val v = s.performance!!.timeInStateResponse["Working"]!!
                .acrossReplicationStatistic.average
            Triple(s.name, s.serviceTime, v)
        }

        // Strictly increasing in service time: the slow specialist is busier.
        assertTrue(
            busy[0].third < busy[1].third && busy[1].third < busy[2].third,
            "utilisation should rise with service time; got ${busy.map { it.third }}",
        )

        for ((name, serviceTime, observed) in busy) {
            val expected = rate / sys.specialists.size * serviceTime
            assertTrue(
                kotlin.math.abs(observed - expected) < 0.06,
                "$name: expected utilisation near $expected, observed $observed",
            )
        }
    }

    /**
     *  A ticket arriving while a specialist is Working stays pending until it
     *  re-enters Idle, so the mailbox is that specialist's queue. The slowest
     *  specialist must therefore hold the deepest one — with no queue object
     *  anywhere in the model.
     */
    @Test
    @DisplayName("C8: mailbox depth behaves as a per-specialist queue")
    fun mailboxDepthActsAsAQueue() {
        val sys = run()
        val queues = sys.specialists.map {
            it.performance!!.numInMailboxResponse.acrossReplicationStatistic.average
        }
        assertTrue(
            queues[2] > queues[0],
            "the slowest specialist should hold the deepest queue; got $queues",
        )
    }

    /**
     *  Mailbox traffic is published whether or not a statechart exists, and every
     *  specialist is dealt the same share, so received counts should be close to
     *  equal — which also confirms the round-robin actually is one.
     */
    @Test
    @DisplayName("C8: round-robin routing gives each specialist a comparable share")
    fun routingIsEven() {
        val sys = run()
        val received = sys.specialists.map {
            it.performance!!.numMessagesReceivedResponse.acrossReplicationStatistic.average
        }
        val spread = received.max() - received.min()
        assertTrue(spread <= 2.0, "round-robin shares should be within one ticket; got $received")
        assertTrue(received.min() > 100.0, "the run should be long enough to be meaningful")
    }

    /**
     *  `allPerformance = true` is what publishes the end-of-replication pending
     *  count; the example opts in, so it must be present.
     */
    @Test
    @DisplayName("C8: allPerformance publishes the end-of-replication pending count")
    fun allPerformancePublishesFinalPending() {
        val sys = run(reps = 2, length = 500.0)
        for (s in sys.specialists) {
            assertNotNull(
                s.performance!!.finalPendingResponse,
                "${s.name} opted in to allPerformance, so final pending should be published",
            )
        }
    }
}
