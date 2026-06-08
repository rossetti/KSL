/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.agent

import ksl.examples.general.agent.AutonomousForkliftExample
import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 *  Regression tests for Phase 2: `AgentResource`.
 *   - is a usable Resource (entities seize and release normally)
 *   - exposes an AgentMailbox that can receive messages
 *   - supports a statechart that reacts to incoming messages and to
 *     the resource's own state changes
 *   - statechart can drive goOffShift / goOnShift to refuse new
 *     seizes without affecting in-flight allocations
 */
class AgentResourceTest {

    /**
     *  Three entities each seize a single-capacity AgentResource for
     *  2 time units in turn. Verifies seize/release semantics still
     *  work transparently when the Resource is an AgentResource.
     */
    private class SeizeReleaseScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        val server: AgentResource = AgentResource(this, "server", capacity = 1)
        val completionTimes: MutableList<Double> = mutableListOf()

        inner class Customer(aName: String) : Agent(aName) {
            val script: KSLProcess = process(isDefaultProcess = true) {
                val a = seize(server)
                delay(2.0)
                release(a)
                completionTimes.add(currentTime)
            }
        }

        val customers = listOf(Customer("c1"), Customer("c2"), Customer("c3"))

        override fun initialize() {
            super.initialize()
            for (c in customers) activate(c.script)
        }
    }

    @Test
    fun agentResourceBehavesAsAResourceForSeizeAndRelease() {
        val model = Model("SeizeReleaseTest")
        val s = SeizeReleaseScenario(model, "seizerel")
        model.lengthOfReplication = 100.0
        model.numberOfReplications = 1
        model.simulate()

        // Capacity is 1, each customer holds for 2 time units, so they
        // finish at t = 2, 4, 6.
        assertEquals(listOf(2.0, 4.0, 6.0), s.completionTimes)
    }

    /**
     *  A `Notifier` agent sends an Inform message to an AgentResource's
     *  mailbox. The AgentResource has a statechart that records every
     *  Inform it sees. Verifies an AgentResource is a valid statechart
     *  owner (i.e., implements AgentLike correctly) and that its
     *  mailbox receives messages just like an Agent's does.
     */
    private class MessagingResourceScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        var notificationsSeen: Int = 0
        var lastNotificationAt: Double = Double.NaN

        val resource: AgentResource = AgentResource(this, "watch", capacity = 1).apply {
            statechart {
                initial("running")
                state("running") {
                    onMessage<AgentMessage.Inform<String>> { msg ->
                        notificationsSeen += 1
                        lastNotificationAt = agent.currentTime
                    }
                }
            }
        }

        inner class Notifier : Agent("notifier") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(1.5)
                sendMessage(AgentMessage.Inform(this@Notifier, "tick"), resource.mailbox)
                delay(2.0)
                sendMessage(AgentMessage.Inform(this@Notifier, "tick"), resource.mailbox)
            }
        }

        val notifier = Notifier()

        override fun initialize() {
            super.initialize()
            activate(notifier.script)
        }
    }

    @Test
    fun agentResourceMailboxReceivesMessagesViaStatechart() {
        val model = Model("MessagingResourceTest")
        val s = MessagingResourceScenario(model, "messaging")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        assertEquals(2, s.notificationsSeen)
        assertEquals(3.5, s.lastNotificationAt, 1e-9)
    }

    /**
     *  An AgentResource that goes off-shift on receipt of a "break"
     *  message and back on-shift on receipt of a "return" message.
     *  While off-shift, new seize requests queue up; in-flight
     *  allocations finish normally. When on-shift again, the queued
     *  request is granted.
     */
    private class ShiftScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        val log: MutableList<Pair<String, Double>> = mutableListOf()

        val server: AgentResource = AgentResource(this, "shifter", capacity = 1).apply {
            statechart {
                initial("on")
                state("on") {
                    onMessage<AgentMessage.Inform<String>>({ it.payload == "break" }) {
                        (agent as AgentResource).goOffShift()
                        transitionTo("off")
                    }
                }
                state("off") {
                    onEntry { log.add("offShiftAt" to agent.currentTime) }
                    onMessage<AgentMessage.Inform<String>>({ it.payload == "return" }) {
                        (agent as AgentResource).goOnShift()
                        transitionTo("on")
                    }
                }
            }
        }

        inner class Customer(aName: String, val arrivalTime: Double, val workTime: Double) :
            Agent(aName) {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(arrivalTime)
                log.add("$aName:arrived" to currentTime)
                val a = seize(server)
                log.add("$aName:seized" to currentTime)
                delay(workTime)
                release(a)
                log.add("$aName:released" to currentTime)
            }
        }

        inner class Manager : Agent("manager") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(1.0)
                sendMessage(AgentMessage.Inform(this@Manager, "break"), server.mailbox)
                delay(5.0)
                sendMessage(AgentMessage.Inform(this@Manager, "return"), server.mailbox)
            }
        }

        // c1 arrives at t=0, takes 1.0 of work → done at t=1
        // break at t=1
        // c2 arrives at t=2 — server off-shift, must wait
        // return at t=6 — c2 seizes
        // c2 finishes at t=8
        val c1 = Customer("c1", arrivalTime = 0.0, workTime = 1.0)
        val c2 = Customer("c2", arrivalTime = 2.0, workTime = 2.0)
        val manager = Manager()

        override fun initialize() {
            super.initialize()
            activate(c1.script)
            activate(c2.script)
            activate(manager.script)
        }
    }

    @Test
    fun agentResourceGoesOffAndOnShiftDrivenByStatechart() {
        val model = Model("ShiftTest")
        val s = ShiftScenario(model, "shift")
        model.lengthOfReplication = 20.0
        model.numberOfReplications = 1
        model.simulate()

        val log = s.log.toMap()
        // c1 should seize immediately and release at t=1.
        assertEquals(0.0, log["c1:seized"]!!, 1e-9)
        assertEquals(1.0, log["c1:released"]!!, 1e-9)
        // Server goes off-shift at t=1.
        assertContains(s.log, "offShiftAt" to 1.0)
        // c2 arrives at t=2 and waits until return at t=6.
        assertEquals(2.0, log["c2:arrived"]!!, 1e-9)
        assertEquals(6.0, log["c2:seized"]!!, 1e-9)
        assertEquals(8.0, log["c2:released"]!!, 1e-9)
        // Final statechart state is "on" again after the return.
        assertEquals("on", s.server.statechart!!.currentStateName)
    }

    /**
     *  Verifies that `isOffShift` reflects the resource's current
     *  capacity and does not require the statechart to be configured.
     *  Also verifies idempotence: a second goOffShift / goOnShift is
     *  a no-op.
     */
    @Test
    fun goOffShiftAndOnShiftAreIdempotentAndReflectInIsOffShift() {
        val model = Model("IdempotenceTest")
        val agentModel = object : AgentModel(model, "idemp") {}
        val server = AgentResource(agentModel, "s", capacity = 2)
        // Construction is fine without a statechart.
        assertTrue(!server.isOffShift)

        // We need a running model to manipulate capacity via the
        // change-notice machinery — schedule a probe that toggles
        // shifts and checks state.
        val probeLog = mutableListOf<Pair<String, Boolean>>()
        object : AgentModel(model, "probe") {
            inner class Probe : Agent("probe") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    probeLog.add("initial" to server.isOffShift)
                    server.goOffShift()
                    probeLog.add("afterOff1" to server.isOffShift)
                    server.goOffShift()  // idempotent
                    probeLog.add("afterOff2" to server.isOffShift)
                    server.goOnShift()
                    probeLog.add("afterOn1" to server.isOffShift)
                    server.goOnShift()  // idempotent
                    probeLog.add("afterOn2" to server.isOffShift)
                }
            }
            val p = Probe()
            override fun initialize() {
                super.initialize()
                activate(p.script)
            }
        }

        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        assertEquals(
            listOf(
                "initial" to false,
                "afterOff1" to true,
                "afterOff2" to true,
                "afterOn1" to false,
                "afterOn2" to false,
            ),
            probeLog,
        )
    }

    // ── AgentResource performance / statechart-state stats ──────────────────

    /**
     *  Deterministic verification that an AgentResource gets the same
     *  AgentPerformance treatment as a PermanentAgent: mailbox stats
     *  plus per-state TimeInState_X / NumTimesEntered_X plus
     *  NumTransitions when a statechart is configured.
     *
     *  Scenario: a worker resource cycles through three states by
     *  timeout — onShift (1.0 unit), onBreak (2.0 units), retired
     *  (rest of replication). Over a 10-unit run with no warm-up:
     *    - TimeInState_onShift  = 1.0 / 10.0 = 0.1
     *    - TimeInState_onBreak  = 2.0 / 10.0 = 0.2
     *    - TimeInState_retired  = 7.0 / 10.0 = 0.7
     *    - NumTimesEntered: each = 1
     *    - NumTransitions: 2
     *
     *  This is the same shape as the AgentModelTest scenario for
     *  PermanentAgent — the point is to confirm the same code paths
     *  fire when the agent is an AgentResource.
     */
    private class ResourcePerformanceScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        val worker: AgentResource = AgentResource(this, "worker", capacity = 1).apply {
            statechart {
                initial("onShift")
                state("onShift") {
                    onTimeout(1.0) { transitionTo("onBreak") }
                }
                state("onBreak") {
                    onTimeout(2.0) { transitionTo("retired") }
                }
                state("retired") {
                    // terminal
                }
            }
            collectPerformance()
        }
    }

    @Test
    fun agentResourcePerformanceTracksStatechartStats() {
        val model = Model("ResourcePerfTest")
        val s = ResourcePerformanceScenario(model, "rperf")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        val perf = s.worker.performance!!
        assertTrue(perf.tracksStatechart, "worker has a statechart, perf should track it")

        assertEquals(
            0.1,
            perf.timeInStateResponse["onShift"]!!.acrossReplicationStatistic.average,
            1e-9,
        )
        assertEquals(
            0.2,
            perf.timeInStateResponse["onBreak"]!!.acrossReplicationStatistic.average,
            1e-9,
        )
        assertEquals(
            0.7,
            perf.timeInStateResponse["retired"]!!.acrossReplicationStatistic.average,
            1e-9,
        )

        for (stateName in listOf("onShift", "onBreak", "retired")) {
            assertEquals(
                1.0,
                perf.numTimesEnteredResponse[stateName]!!.acrossReplicationStatistic.average,
                1e-9,
            )
        }
        assertEquals(
            2.0,
            perf.numTransitionsResponse!!.acrossReplicationStatistic.average,
            1e-9,
        )
    }

    /**
     *  Verifies AgentResource mailbox stats: a sender sends 5 messages
     *  to the resource's mailbox (separately from any seize/release
     *  traffic, which uses the resource's request queue not the
     *  mailbox). All 5 should be counted as received.
     */
    private class ResourceMailboxPerfScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        val server: AgentResource = AgentResource(this, "server", capacity = 1).apply {
            statechart {
                initial("listening")
                state("listening") {
                    onMessage<AgentMessage.Inform<Int>> { /* consume */ }
                }
            }
            collectPerformance(allPerformance = true)
        }

        inner class Pinger : Agent("pinger") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                repeat(5) { i ->
                    delay(1.0)
                    sendMessage(AgentMessage.Inform(this@Pinger, i), server.mailbox)
                }
            }
        }

        val pinger = Pinger()

        override fun initialize() {
            super.initialize()
            activate(pinger.script)
        }
    }

    @Test
    fun agentResourcePerformanceTracksMailboxStats() {
        val model = Model("ResourceMailboxPerfTest")
        val s = ResourceMailboxPerfScenario(model, "rmail")
        model.lengthOfReplication = 20.0
        model.numberOfReplications = 1
        model.simulate()

        val perf = s.server.performance!!
        assertEquals(
            5.0,
            perf.numMessagesReceivedResponse.acrossReplicationStatistic.average,
            1e-9,
            "5 messages were sent and all received",
        )
        assertEquals(
            5.0,
            perf.numMessagesConsumedResponse.acrossReplicationStatistic.average,
            1e-9,
            "all 5 should have been consumed by the statechart",
        )
        assertEquals(
            0.0,
            perf.finalPendingResponse!!.acrossReplicationStatistic.average,
            1e-9,
        )
    }

    // ── AutonomousForkliftExample smoke test ─────────────────────────────────

    @Test
    fun autonomousForkliftExampleRunsAndChargesItself() {
        val model = Model("ForkliftSmokeTest")
        val sys = AutonomousForkliftExample(model, "fl")
        model.lengthOfReplication = 500.0
        model.numberOfReplications = 1
        model.simulate()

        // Battery should always be in [0, 1] at the end of the run.
        assertTrue(sys.forklift.battery in 0.0..1.0, "battery out of range: ${sys.forklift.battery}")
        // At least one move completed.
        assertTrue(
            sys.tisResponse.acrossReplicationStatistic.count > 0,
            "expected at least one completed move",
        )
    }
}
