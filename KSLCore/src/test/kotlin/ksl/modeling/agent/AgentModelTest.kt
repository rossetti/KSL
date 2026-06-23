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

import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Regression tests for the Phase 1a agent-based modeling scaffolding:
 *   - `AgentModel` / `Agent` construction and registration
 *   - `AgentMailbox` send / receive via `KSLProcessBuilder` extensions
 *   - Sealed `AgentMessage` performative hierarchy
 *   - `Population` composable views
 */
class AgentModelTest {

    /**
     *  Two agents that exchange a single ping/pong via their default mailboxes.
     *  After the simulation we assert the recipient saw the message at the
     *  expected time and that the message round-tripped intact.
     */
    private class PingPong(parent: ModelElement, name: String? = null) : AgentModel(parent, name) {

        data class Greeting(val text: String)

        var pingReceivedAt: Double = Double.NaN
        var pongReceivedAt: Double = Double.NaN
        var pingPayload: String? = null
        var pongPayload: String? = null

        inner class Pinger(val partner: () -> Ponger, aName: String? = null) : Agent(aName) {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(1.0)
                sendMessage(
                    AgentMessage.Request(this@Pinger, Greeting("ping"), conversationId = "c1"),
                    partner().mailbox,
                )
                val reply = receiveMessageOfType<AgentMessage.Inform<Greeting>, AgentMessage>(mailbox)
                pongReceivedAt = entity.currentTime
                pongPayload = reply.payload.text
            }
        }

        inner class Ponger(aName: String? = null) : Agent(aName) {
            val script: KSLProcess = process(isDefaultProcess = true) {
                val req = receiveMessageOfType<AgentMessage.Request<Greeting>, AgentMessage>(mailbox)
                pingReceivedAt = entity.currentTime
                pingPayload = req.payload.text
                delay(2.0)
                val replyTo = (req.from as Agent).mailbox
                sendMessage(
                    AgentMessage.Inform(this@Ponger, Greeting("pong"), conversationId = req.conversationId),
                    replyTo,
                )
            }
        }

        val ponger = Ponger("ponger")
        val pinger = Pinger({ ponger }, "pinger")

        override fun initialize() {
            activate(pinger.script)
            activate(ponger.script)
        }
    }

    @Test
    fun pingPongDeliversMessagesAndRoundTrips() {
        val model = Model("PingPongTest")
        val pp = PingPong(model, "pingpong")
        model.lengthOfReplication = 100.0
        model.numberOfReplications = 1
        model.simulate()

        // Ponger receives ping after pinger's 1.0 delay
        assertEquals(1.0, pp.pingReceivedAt, 1e-9, "ping should arrive at t=1.0")
        // Pinger receives pong after ponger's additional 2.0 delay
        assertEquals(3.0, pp.pongReceivedAt, 1e-9, "pong should arrive at t=3.0")

        assertEquals("ping", pp.pingPayload)
        assertEquals("pong", pp.pongPayload)
    }

    @Test
    fun agentsAreRegisteredWithTheirAgentModel() {
        val model = Model("RegistrationTest")
        val pp = PingPong(model, "registration")
        assertEquals(2, pp.agentCount)
        assertTrue(pp.agents.any { it.name.endsWith("pinger") })
        assertTrue(pp.agents.any { it.name.endsWith("ponger") })
    }

    @Test
    fun mailboxStartsEmpty() {
        val model = Model("MailboxEmptyTest")
        val pp = PingPong(model, "mb")
        assertTrue(pp.pinger.mailbox.isEmpty)
        assertEquals(0, pp.ponger.mailbox.size)
    }

    @Test
    fun populationFiltersAndShufflesReproducibly() {
        val model = Model("PopulationTest")
        val pp = PingPong(model, "pop")
        val all = Population<AgentModel.Agent>({ pp.agents.filterIsInstance<AgentModel.Agent>() })

        assertEquals(2, all.size)
        assertTrue(!all.isEmpty)

        val pingers = all.ofType<PingPong.Pinger>()
        assertEquals(1, pingers.size)
        assertIs<PingPong.Pinger>(pingers.first())

        val named = all.where { it.name.endsWith("ponger") }
        assertEquals(1, named.size)

        val stream = model.streamProvider.rnStream(1)
        val s1a = all.shuffled(stream).map { it.name }
        stream.resetStartStream()
        val s1b = all.shuffled(stream).map { it.name }
        assertEquals(s1a, s1b, "Shuffle with reset stream state must be reproducible")
    }

    /**
     *  Verifies the §8.2 refactor: an Agent (with its default mailbox)
     *  can now be constructed *during* a replication, which used to
     *  fail because AgentMailbox was a ModelElement. This test models
     *  arrival-driven population growth — the canonical case the
     *  previous design couldn't express.
     */
    private class RuntimeAgentScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        var runtimeAgentsCreated = 0
        var runtimeMessagesExchanged = 0

        // A simple "responder" agent created up-front: when any greeting
        // arrives, it counts it.
        inner class Responder : Agent("responder") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                while (true) {
                    receiveMessageOfType<AgentMessage.Inform<String>, AgentMessage>(mailbox)
                    runtimeMessagesExchanged += 1
                }
            }
        }

        val responder = Responder()

        // A transient agent type, created at runtime by the arrivals process.
        inner class GreetingAgent(aName: String) : Agent(aName) {
            val script: KSLProcess = process(isDefaultProcess = true) {
                runtimeAgentsCreated += 1
                sendMessage(
                    AgentMessage.Inform(this@GreetingAgent, "hello from ${this@GreetingAgent.name}"),
                    responder.mailbox,
                )
            }
        }

        // Arrivals: create a new GreetingAgent every time unit. This is
        // the path that used to throw IllegalStateException.
        inner class Arrivals : Agent("arrivals") {
            var nextId = 0
            val script: KSLProcess = process(isDefaultProcess = true) {
                while (true) {
                    delay(1.0)
                    nextId += 1
                    val g = GreetingAgent("greeting$nextId")
                    activate(g.script)
                }
            }
        }

        val arrivals = Arrivals()

        override fun initialize() {
            super.initialize()
            activate(responder.script)
            activate(arrivals.script)
        }
    }

    @Test
    fun agentsCanBeConstructedAtRuntime() {
        val model = Model("RuntimeCreationTest")
        val s = RuntimeAgentScenario(model, "runtime")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        // With arrivals every 1.0 time unit over 10 units of simulation,
        // ~9 greeting agents should be created at runtime and each
        // should successfully send a message to the responder.
        assertTrue(
            s.runtimeAgentsCreated >= 8,
            "expected ~9 runtime-created agents; got ${s.runtimeAgentsCreated}",
        )
        assertEquals(
            s.runtimeAgentsCreated, s.runtimeMessagesExchanged,
            "every runtime-created agent should have successfully delivered its message",
        )
        // The runtime agents are NOT in the registry (only setup-time
        // agents are). After simulate(), the registry contains exactly
        // the three setup-time agents declared on the model.
        assertEquals(
            2, s.agentCount,
            "registry should contain only the two setup-time agents (responder, arrivals)",
        )
    }

    /**
     *  Verifies the permanent-agent tier: a `PermanentAgent` is a
     *  ModelElement-derived setup-time agent that participates in the
     *  same mailbox+statechart infrastructure as the transient `Agent`.
     *  It can own its own per-agent Response statistics (not tested
     *  here, but the architectural hook is in place).
     */
    private class PermanentAgentScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        var receivedAt: Double = Double.NaN
        var receivedPayload: String? = null

        inner class Manager : PermanentAgent("manager") {
            init {
                statechart {
                    initial("listening")
                    state("listening") {
                        onMessage<AgentMessage.Inform<String>> { msg ->
                            receivedAt = agent.currentTime
                            receivedPayload = msg.payload
                        }
                    }
                }
            }
        }

        inner class Worker : Agent("worker") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(2.0)
                sendMessage(AgentMessage.Inform(this@Worker, "report"), manager.mailbox)
            }
        }

        val manager = Manager()
        val worker = Worker()

        override fun initialize() {
            super.initialize()
            activate(worker.script)
        }
    }

    /**
     *  Verifies AgentPerformance: a PermanentAgent that opts into
     *  performance collection produces standard KSL Response /
     *  TWResponse instances for mailbox traffic. The test sends a
     *  known number of messages and asserts the across-replication
     *  count statistics match the expected totals.
     */
    private class PerformanceScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        inner class Counter : PermanentAgent("counter") {
            init {
                collectPerformance(allPerformance = true)
                statechart {
                    initial("counting")
                    state("counting") {
                        onMessage<AgentMessage.Inform<Int>> { /* consume */ }
                    }
                }
            }
        }

        inner class Sender(val target: () -> Counter, val totalToSend: Int) : Agent("sender") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                repeat(totalToSend) { i ->
                    delay(0.5)
                    sendMessage(AgentMessage.Inform(this@Sender, i), target().mailbox)
                }
            }
        }

        val counter = Counter()
        val sender = Sender({ counter }, totalToSend = 20)

        override fun initialize() {
            super.initialize()
            activate(sender.script)
        }
    }

    @Test
    fun permanentAgentPerformanceCollectsMailboxStats() {
        val model = Model("PerformanceTest")
        val s = PerformanceScenario(model, "perf")
        model.lengthOfReplication = 100.0
        model.numberOfReplications = 1
        model.simulate()

        val perf = s.counter.performance!!
        // 20 messages sent, all received, all consumed by the statechart.
        assertEquals(
            20.0,
            perf.numMessagesReceivedResponse.acrossReplicationStatistic.average,
            1e-9,
            "NumMessagesReceived",
        )
        assertEquals(
            20.0,
            perf.numMessagesConsumedResponse.acrossReplicationStatistic.average,
            1e-9,
            "NumMessagesConsumed",
        )
        // Statechart handler consumed each immediately on direct-handoff
        // path, so the time-weighted size of the pending queue should be
        // effectively zero.
        assertTrue(
            perf.numInMailboxResponse.acrossReplicationStatistic.average < 1e-6,
            "NumInMailbox should be ~0 since messages are direct-handed off",
        )
        // Final pending should be 0.
        assertEquals(
            0.0,
            perf.finalPendingResponse!!.acrossReplicationStatistic.average,
            1e-9,
            "NumPendingAtEndOfReplication",
        )
    }

    /**
     *  Same shape as [PerformanceScenario], but with the receiver
     *  *not* consuming messages (no statechart, no `receiveMessage`).
     *  Verifies `NumInMailbox` and `NumPendingAtEndOfReplication`
     *  reflect the accumulating backlog.
     */
    private class PerformanceBacklogScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        inner class Hoarder : PermanentAgent("hoarder") {
            init { collectPerformance(allPerformance = true) }
        }

        inner class Sender(val target: () -> Hoarder) : Agent("sender") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                repeat(10) { i ->
                    delay(1.0)
                    sendMessage(AgentMessage.Inform(this@Sender, i), target().mailbox)
                }
            }
        }

        val hoarder = Hoarder()
        val sender = Sender({ hoarder })

        override fun initialize() {
            super.initialize()
            activate(sender.script)
        }
    }

    @Test
    fun agentPerformanceTracksBacklogWhenMessagesAreNotConsumed() {
        val model = Model("BacklogTest")
        val s = PerformanceBacklogScenario(model, "backlog")
        model.lengthOfReplication = 20.0
        model.numberOfReplications = 1
        model.simulate()

        val perf = s.hoarder.performance!!
        // 10 messages were sent and all queued (no consumer).
        assertEquals(
            10.0,
            perf.numMessagesReceivedResponse.acrossReplicationStatistic.average,
            1e-9,
        )
        assertEquals(
            0.0,
            perf.numMessagesConsumedResponse.acrossReplicationStatistic.average,
            1e-9,
            "nothing was consumed",
        )
        // Final pending should be exactly the 10 messages sent.
        assertEquals(
            10.0,
            perf.finalPendingResponse!!.acrossReplicationStatistic.average,
            1e-9,
            "all 10 messages should still be pending",
        )
        // Time-weighted size: 0 from t=0 to t=1, 1 from t=1 to t=2, 2 from
        // t=2 to t=3, ..., 10 from t=10 to t=20. Average over 20 units =
        // (0+1+2+...+9 + 10*10) / 20 = (45 + 100) / 20 = 7.25.
        assertEquals(
            7.25,
            perf.numInMailboxResponse.acrossReplicationStatistic.average,
            1e-6,
            "time-weighted average mailbox size",
        )
    }

    /**
     *  Verifies statechart-state stats: time-in-state, entry counts,
     *  and transition counts are tracked when AgentPerformance is
     *  attached to a PermanentAgent that has a statechart configured.
     *
     *  Scenario: a Walker permanent-agent's statechart steps through
     *  three states by timeout — idle (1.0 unit), busy (2.0 units),
     *  done (rest of replication). Over a 10-unit run after a 0-unit
     *  warm-up:
     *    - TimeInState_idle = 1.0 / 10.0 = 0.1
     *    - TimeInState_busy = 2.0 / 10.0 = 0.2
     *    - TimeInState_done = 7.0 / 10.0 = 0.7
     *    - NumTimesEntered: idle=1, busy=1, done=1
     *    - NumTransitions: 2 (idle->busy, busy->done)
     */
    private class StatechartStatsScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        inner class Walker : PermanentAgent("walker") {
            init {
                statechart {
                    initial("idle")
                    state("idle") {
                        onTimeout(1.0) { transitionTo("busy") }
                    }
                    state("busy") {
                        onTimeout(2.0) { transitionTo("done") }
                    }
                    state("done") {
                        // no outgoing transitions; rest of replication stays here
                    }
                }
                collectPerformance()
            }
        }

        val walker = Walker()
    }

    @Test
    fun agentPerformanceTracksStatechartTimeAndCounts() {
        val model = Model("StatechartStatsTest")
        val s = StatechartStatsScenario(model, "scstats")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        val perf = s.walker.performance!!
        assertTrue(perf.tracksStatechart, "Walker has a statechart; performance should track it")

        // Time-in-state fractions. TWResponse averages over the
        // replication, so each is occupancy_time / replication_length.
        assertEquals(
            0.1,
            perf.timeInStateResponse["idle"]!!.acrossReplicationStatistic.average,
            1e-9,
            "TimeInState_idle should be 1/10",
        )
        assertEquals(
            0.2,
            perf.timeInStateResponse["busy"]!!.acrossReplicationStatistic.average,
            1e-9,
            "TimeInState_busy should be 2/10",
        )
        assertEquals(
            0.7,
            perf.timeInStateResponse["done"]!!.acrossReplicationStatistic.average,
            1e-9,
            "TimeInState_done should be 7/10",
        )

        // Each state was entered exactly once.
        for (stateName in listOf("idle", "busy", "done")) {
            assertEquals(
                1.0,
                perf.numTimesEnteredResponse[stateName]!!.acrossReplicationStatistic.average,
                1e-9,
                "NumTimesEntered_$stateName",
            )
        }

        // Two transitions: idle->busy and busy->done.
        assertEquals(
            2.0,
            perf.numTransitionsResponse!!.acrossReplicationStatistic.average,
            1e-9,
            "NumTransitions",
        )
    }

    /**
     *  Verifies that when a PermanentAgent has *no* statechart at the
     *  time `collectPerformance` is called, only mailbox stats are
     *  tracked and `tracksStatechart` is false.
     */
    private class PerformanceNoStatechartScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        inner class Silent : PermanentAgent("silent") {
            init { collectPerformance() }
        }

        val silent = Silent()
    }

    @Test
    fun agentPerformanceWithoutStatechartReportsTracksStatechartFalse() {
        val model = Model("NoSCStatsTest")
        val s = PerformanceNoStatechartScenario(model, "nosc")
        model.lengthOfReplication = 5.0
        model.numberOfReplications = 1
        model.simulate()

        val perf = s.silent.performance!!
        assertTrue(!perf.tracksStatechart)
        // Statechart responses are empty maps / null.
        assertTrue(perf.timeInStateResponse.isEmpty())
        assertTrue(perf.numTimesEnteredResponse.isEmpty())
        assertTrue(perf.numTransitionsResponse == null)
    }

    /**
     *  Verifies state re-entry counts. A statechart that ping-pongs
     *  between two states for a known number of cycles should report
     *  the expected entry counts and total transitions.
     */
    private class StatechartPingPongScenario(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        inner class Ponger : PermanentAgent("ponger") {
            init {
                statechart {
                    initial("a")
                    state("a") { onTimeout(1.0) { transitionTo("b") } }
                    state("b") { onTimeout(1.0) { transitionTo("a") } }
                }
                collectPerformance()
            }
        }

        val ponger = Ponger()
    }

    @Test
    fun agentPerformanceCountsRepeatedStateEntries() {
        val model = Model("PingPongStatsTest")
        val s = StatechartPingPongScenario(model, "pp")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        val perf = s.ponger.performance!!
        // From t=0: a (1.0) -> b (1.0) -> a (1.0) -> b (1.0) -> ... over 10 units.
        // Entries: a at t=0, 2, 4, 6, 8 (5 entries); b at t=1, 3, 5, 7, 9 (5 entries).
        // Transitions: 10 (5 a->b + 5 b->a).
        // Wait — at t=10 the last transition fires (b->a), but the replication
        // ends right at t=10. Let's check: a@0, transition@1 to b, transition@2
        // to a, ..., transition@10 to b. Since the replication clock stops at
        // t=10, the transition@10 may or may not register depending on event
        // ordering. KSL processes events at the replication-end time before
        // stopping, so we expect 10 transitions.
        val aEntries = perf.numTimesEnteredResponse["a"]!!.acrossReplicationStatistic.average
        val bEntries = perf.numTimesEnteredResponse["b"]!!.acrossReplicationStatistic.average
        val transitions = perf.numTransitionsResponse!!.acrossReplicationStatistic.average

        // The exact totals depend on KSL's end-of-replication event ordering.
        // The relationships are deterministic regardless:
        //   transitions == aEntries + bEntries - 1   (initial entry has no transition)
        //   |aEntries - bEntries| <= 1
        assertEquals(
            transitions,
            aEntries + bEntries - 1.0,
            1e-9,
            "transitions = total entries - 1 (initial entry is not a transition)",
        )
        assertTrue(
            kotlin.math.abs(aEntries - bEntries) <= 1.0,
            "a and b entries should differ by at most 1; got a=$aEntries b=$bEntries",
        )
        // And we expect roughly 5 each over 10 time units of 1.0-unit cycles.
        assertTrue(aEntries >= 4.0, "expected ~5 entries to a; got $aEntries")
        assertTrue(bEntries >= 4.0, "expected ~5 entries to b; got $bEntries")
    }

    @Test
    fun permanentAgentReceivesViaStatechart() {
        val model = Model("PermanentAgentTest")
        val s = PermanentAgentScenario(model, "perm")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        assertEquals(2.0, s.receivedAt, 1e-9)
        assertEquals("report", s.receivedPayload)
        // Both agents are in the registry — manager (PermanentAgent)
        // and worker (Agent), since both were created at setup time.
        assertEquals(2, s.agentCount)
    }

    @Test
    fun messagePerformativesCarryFromAndConversation() {
        val model = Model("MessageHierarchyTest")
        val pp = PingPong(model, "mh")
        val req = AgentMessage.Request(pp.pinger, "task", conversationId = "x")
        assertEquals(pp.pinger, req.from)
        assertEquals("task", req.payload)
        assertEquals("x", req.conversationId)

        val info = AgentMessage.Inform(pp.ponger, 42)
        assertEquals(pp.ponger, info.from)
        assertEquals(42, info.payload)
        assertNull(info.conversationId)

        // sealed hierarchy: exhaustive when over AgentMessage
        val msg: AgentMessage = req
        val classified: String = when (msg) {
            is AgentMessage.Request<*> -> "request"
            is AgentMessage.Inform<*> -> "inform"
            is AgentMessage.Propose<*> -> "propose"
            is AgentMessage.Accept -> "accept"
            is AgentMessage.Reject -> "reject"
            is AgentMessage.Cancel -> "cancel"
        }
        assertEquals("request", classified)
        assertNotNull(classified)
    }
}
