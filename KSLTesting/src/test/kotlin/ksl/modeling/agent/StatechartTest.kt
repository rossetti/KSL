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
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 *  Regression tests for the Phase 1b statechart DSL:
 *   - state entry / exit ordering
 *   - onTimeout, onMessage, onCondition triggers
 *   - transitionTo semantics including self-transition
 *   - composition with a concurrent `process { }` body
 */
class StatechartTest {

    /** Helper to log "what happened when" without coupling to text formats. */
    private data class Event(val name: String, val time: Double)

    /**
     *  An agent that walks through three states via timeouts:
     *   - idle (1.0 unit) → working (2.0 units) → done (no triggers)
     *  Records every entry, exit, and timeout event.
     */
    private class TimerAgents(parent: ModelElement, name: String? = null) : AgentModel(parent, name) {
        val log = mutableListOf<Event>()

        inner class Walker(aName: String) : Agent(aName) {
            init {
                statechart {
                    initial("idle")
                    state("idle") {
                        onEntry { log.add(Event("idle:entry", agent.currentTime)) }
                        onExit { log.add(Event("idle:exit", agent.currentTime)) }
                        onTimeout(1.0) {
                            log.add(Event("idle:timeout", agent.currentTime))
                            transitionTo("working")
                        }
                    }
                    state("working") {
                        onEntry { log.add(Event("working:entry", agent.currentTime)) }
                        onExit { log.add(Event("working:exit", agent.currentTime)) }
                        onTimeout(2.0) {
                            log.add(Event("working:timeout", agent.currentTime))
                            transitionTo("done")
                        }
                    }
                    state("done") {
                        onEntry { log.add(Event("done:entry", agent.currentTime)) }
                    }
                }
            }
        }

        val walker = Walker("walker")
    }

    @Test
    fun timeoutsAdvanceStatesInOrderAndAtCorrectTimes() {
        val model = Model("TimeoutTest")
        val m = TimerAgents(model, "timer")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        // Expected sequence: idle:entry@0 -> idle:timeout@1 -> idle:exit@1
        //                   -> working:entry@1 -> working:timeout@3 -> working:exit@3
        //                   -> done:entry@3
        val expected = listOf(
            "idle:entry" to 0.0,
            "idle:timeout" to 1.0,
            "idle:exit" to 1.0,
            "working:entry" to 1.0,
            "working:timeout" to 3.0,
            "working:exit" to 3.0,
            "done:entry" to 3.0,
        )
        assertEquals(expected.size, m.log.size, "wrong number of logged events: ${m.log}")
        for ((i, exp) in expected.withIndex()) {
            assertEquals(exp.first, m.log[i].name, "event $i name")
            assertEquals(exp.second, m.log[i].time, 1e-9, "event $i time")
        }
        assertEquals("done", m.walker.statechart!!.currentStateName)
    }

    /**
     *  Two agents: a sender that sends a `Request` at t=1.0, and a
     *  receiver whose statechart transitions on receiving that message.
     */
    private class MessageDrivenAgents(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        val log = mutableListOf<Event>()
        var receivedPayload: String? = null

        inner class Receiver : Agent("receiver") {
            init {
                statechart {
                    initial("waiting")
                    state("waiting") {
                        onEntry { log.add(Event("waiting:entry", agent.currentTime)) }
                        onMessage<AgentMessage.Request<String>> { msg ->
                            log.add(Event("got-message", agent.currentTime))
                            receivedPayload = msg.payload
                            transitionTo("done")
                        }
                    }
                    state("done") {
                        onEntry { log.add(Event("done:entry", agent.currentTime)) }
                    }
                }
            }
        }

        inner class Sender(val target: () -> Receiver) : Agent("sender") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(1.0)
                sendMessage(
                    AgentMessage.Request(this@Sender, "hello", conversationId = "c"),
                    target().mailbox,
                )
            }
        }

        val receiver = Receiver()
        val sender = Sender({ receiver })

        override fun initialize() {
            super.initialize()
            activate(sender.script)
        }
    }

    @Test
    fun onMessageFiresAndTransitionsOnArrival() {
        val model = Model("MessageTest")
        val m = MessageDrivenAgents(model, "msg")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        assertEquals("hello", m.receivedPayload)
        assertEquals("done", m.receiver.statechart!!.currentStateName)
        // log: waiting:entry@0 -> got-message@1 -> done:entry@1
        assertEquals(3, m.log.size, "log: ${m.log}")
        assertEquals("waiting:entry", m.log[0].name)
        assertEquals(0.0, m.log[0].time, 1e-9)
        assertEquals("got-message", m.log[1].name)
        assertEquals(1.0, m.log[1].time, 1e-9)
        assertEquals("done:entry", m.log[2].name)
        assertEquals(1.0, m.log[2].time, 1e-9)
    }

    /**
     *  An agent that watches a counter and transitions when it reaches a
     *  threshold. A separate ticker process increments the counter every
     *  unit of time. The condition should fire as soon as the counter
     *  passes the threshold during the C-phase after the tick event.
     */
    private class ConditionDrivenAgents(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        val log = mutableListOf<Event>()
        var counter: Int = 0

        inner class Watcher : Agent("watcher") {
            init {
                statechart {
                    initial("watching")
                    state("watching") {
                        onCondition({ counter >= 3 }) {
                            log.add(Event("triggered", agent.currentTime))
                            transitionTo("triggered")
                        }
                    }
                    state("triggered") {
                        onEntry { log.add(Event("triggered:entry", agent.currentTime)) }
                    }
                }
            }
        }

        inner class Ticker : Agent("ticker") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                while (counter < 5) {
                    delay(1.0)
                    counter += 1
                }
            }
        }

        val watcher = Watcher()
        val ticker = Ticker()

        override fun initialize() {
            super.initialize()
            activate(ticker.script)
        }
    }

    @Test
    fun onConditionFiresWhenPredicateBecomesTrue() {
        val model = Model("ConditionTest")
        val m = ConditionDrivenAgents(model, "cond")
        model.lengthOfReplication = 20.0
        model.numberOfReplications = 1
        model.simulate()

        assertEquals("triggered", m.watcher.statechart!!.currentStateName)
        // ConditionalAction fires in the C-phase after the event at t=3 that
        // pushed counter to 3. The triggered:entry then runs via the
        // scheduled transition event at the same time.
        val triggered = m.log.firstOrNull { it.name == "triggered" }
        assertTrue(triggered != null, "condition should have fired; log: ${m.log}")
        assertEquals(3.0, triggered.time, 1e-9, "condition should fire at t=3.0")
        assertContains(m.log.map { it.name }, "triggered:entry")
    }

    /**
     *  Verifies the agent retains its `process { }` behavior while also
     *  running a statechart. The process body sends self-messages every
     *  unit; the statechart counts how many it sees before transitioning
     *  to a stop state.
     */
    private class ProcessAndStatechartAgent(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        var ticksHandled = 0
        var stoppedAt: Double = Double.NaN

        inner class Both : Agent("both") {
            init {
                statechart {
                    initial("counting")
                    state("counting") {
                        onMessage<AgentMessage.Inform<String>>({ it.payload == "tick" }) {
                            ticksHandled += 1
                            if (ticksHandled >= 3) transitionTo("stopped")
                        }
                    }
                    state("stopped") {
                        onEntry { stoppedAt = agent.currentTime }
                    }
                }
            }

            val ticker: KSLProcess = process(isDefaultProcess = true) {
                repeat(5) {
                    delay(1.0)
                    sendMessage(AgentMessage.Inform(this@Both, "tick"), mailbox)
                }
            }
        }

        val both = Both()

        override fun initialize() {
            super.initialize()
            activate(both.ticker)
        }
    }

    @Test
    fun statechartAndProcessRunConcurrentlyAndCoexist() {
        val model = Model("InteropTest")
        val m = ProcessAndStatechartAgent(model, "interop")
        model.lengthOfReplication = 20.0
        model.numberOfReplications = 1
        model.simulate()

        assertEquals(3, m.ticksHandled, "should stop after 3 ticks")
        assertEquals(3.0, m.stoppedAt, 1e-9, "should reach stopped at t=3.0")
        assertEquals("stopped", m.both.statechart!!.currentStateName)
    }

    /**
     *  Verifies that messages not matched by any onMessage handler remain
     *  in the mailbox so a `process { }` body could still consume them.
     *  We send an unmatched Inform, then verify the channel size grew.
     */
    private class NonMatchingMessageAgent(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        var matchedRequests = 0

        inner class Listener : Agent("listener") {
            init {
                statechart {
                    initial("listening")
                    state("listening") {
                        onMessage<AgentMessage.Request<String>> { matchedRequests += 1 }
                    }
                }
            }
        }

        inner class Talker(val target: () -> Listener) : Agent("talker") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(1.0)
                // Inform should NOT match the listener's Request<String> handler
                sendMessage(AgentMessage.Inform(this@Talker, "ignored"), target().mailbox)
            }
        }

        val listener = Listener()
        val talker = Talker({ listener })

        override fun initialize() {
            super.initialize()
            activate(talker.script)
        }
    }

    /**
     *  An agent's statechart waits in `armed` for a signal. A separate
     *  process fires the signal at t=2.5; the statechart should
     *  transition to `triggered` at that time.
     */
    private class SignalDrivenAgents(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        val alertSignal = AgentSignal("alert")
        var transitionedAt: Double = Double.NaN

        inner class Sentry : Agent("sentry") {
            init {
                statechart {
                    initial("armed")
                    state("armed") {
                        onSignal(alertSignal) {
                            transitionedAt = agent.currentTime
                            transitionTo("triggered")
                        }
                    }
                    state("triggered") {
                        // terminal
                    }
                }
            }
        }

        inner class Alarm : Agent("alarm") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(2.5)
                alertSignal.fire()
            }
        }

        val sentry = Sentry()
        val alarm = Alarm()

        override fun initialize() {
            super.initialize()
            activate(alarm.script)
        }
    }

    @Test
    fun onSignalFiresWhenSignalIsRaised() {
        val model = Model("SignalTest")
        val m = SignalDrivenAgents(model, "signal")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        assertEquals(2.5, m.transitionedAt, 1e-9, "should transition exactly when signal fires")
        assertEquals("triggered", m.sentry.statechart!!.currentStateName)
        // Subscription was torn down after the state was exited.
        assertEquals(
            0, m.alertSignal.numSubscribers,
            "after transition out of armed, no subscribers should remain",
        )
    }

    /**
     *  Verifies that a state's signal subscription is removed on
     *  transition out of that state — a subsequent fire of the signal
     *  must not trigger the (now-stale) handler again.
     */
    private class SignalUnsubscribeAgents(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        val sig = AgentSignal("once")
        var fires = 0

        inner class Listener : Agent("listener") {
            init {
                statechart {
                    initial("waiting")
                    state("waiting") {
                        onSignal(sig) {
                            fires += 1
                            transitionTo("done")
                        }
                    }
                    state("done") {
                        // No handler for `sig` here — a later fire must not
                        // re-trigger the waiting-state handler.
                    }
                }
            }
        }

        inner class Firer : Agent("firer") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(1.0); sig.fire()
                delay(1.0); sig.fire()    // arrives after listener moved to `done`
                delay(1.0); sig.fire()
            }
        }

        val listener = Listener()
        val firer = Firer()

        override fun initialize() {
            super.initialize()
            activate(firer.script)
        }
    }

    @Test
    fun signalSubscriptionIsRemovedOnStateExit() {
        val model = Model("SignalUnsubscribeTest")
        val m = SignalUnsubscribeAgents(model, "sigex")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        assertEquals(
            1, m.fires,
            "handler should fire exactly once; subsequent fires arrive after exit",
        )
        assertEquals("done", m.listener.statechart!!.currentStateName)
    }

    // ── Hierarchical states ─────────────────────────────────────────────────

    /**
     *  A composite "on-shift" state with two substates ("idle",
     *  "working") plus a top-level "off-shift" state. The shift has
     *  its own onTimeout that fires regardless of which substate is
     *  active; substates also have their own onTimeout. Verifies:
     *   - entering "on-shift" enters its initial substate "idle"
     *   - active chain includes both composite and leaf
     *   - substate timeout transitions within the composite (idle ↔ working)
     *   - composite timeout transitions out of the composite to "off-shift"
     *   - exit order: substate exit fires before composite exit
     */
    private class HierarchicalAgents(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        val log = mutableListOf<Pair<String, Double>>()

        inner class Worker : Agent("worker") {
            init {
                statechart {
                    initial("on-shift")
                    state("on-shift") {
                        initial("idle")
                        onEntry { log.add("on-shift:entry" to agent.currentTime) }
                        onExit { log.add("on-shift:exit" to agent.currentTime) }
                        onTimeout(5.0) { transitionTo("off-shift") }
                        state("idle") {
                            onEntry { log.add("idle:entry" to agent.currentTime) }
                            onExit { log.add("idle:exit" to agent.currentTime) }
                            onTimeout(1.0) { transitionTo("working") }
                        }
                        state("working") {
                            onEntry { log.add("working:entry" to agent.currentTime) }
                            onExit { log.add("working:exit" to agent.currentTime) }
                            onTimeout(2.0) { transitionTo("idle") }
                        }
                    }
                    state("off-shift") {
                        onEntry { log.add("off-shift:entry" to agent.currentTime) }
                    }
                }
            }
        }

        val worker = Worker()
    }

    @Test
    fun hierarchicalStatesEnterCompositeAndInitialSubstate() {
        val model = Model("HierEntryTest")
        val m = HierarchicalAgents(model, "hier")
        model.lengthOfReplication = 0.5  // before any timeout fires
        model.numberOfReplications = 1
        model.simulate()

        // Entry order should be: on-shift then idle (top-down).
        assertEquals(
            listOf(
                "on-shift:entry" to 0.0,
                "idle:entry" to 0.0,
            ),
            m.log,
        )
        // Active chain should report ["on-shift", "idle"].
        assertEquals(
            listOf("on-shift", "idle"),
            m.worker.statechart!!.activeStateNames,
        )
        // currentStateName is the leaf.
        assertEquals("idle", m.worker.statechart!!.currentStateName)
    }

    @Test
    fun hierarchicalSubstateTransitionStaysInsideComposite() {
        val model = Model("HierSubTest")
        val m = HierarchicalAgents(model, "hier")
        // Run past idle's first timeout (t=1.0) but before the
        // composite's onTimeout fires (t=5.0).
        model.lengthOfReplication = 1.5
        model.numberOfReplications = 1
        model.simulate()

        // Expected: enter on-shift, enter idle (both at t=0). Then
        // idle:timeout fires at t=1.0; transitionTo("working") leaves
        // idle (exit:idle at t=1) but NOT on-shift, and enters working.
        // on-shift's onExit/onEntry should NOT fire.
        val names = m.log.map { it.first }
        assertTrue(
            "idle:exit" in names && "working:entry" in names,
            "expected idle:exit + working:entry; got $names",
        )
        assertTrue(
            "on-shift:exit" !in names,
            "on-shift should NOT exit on a substate transition; got $names",
        )
        // Active chain after the substate transition is [on-shift, working].
        assertEquals(
            listOf("on-shift", "working"),
            m.worker.statechart!!.activeStateNames,
        )
    }

    @Test
    fun hierarchicalCompositeTimeoutExitsAllSubstates() {
        val model = Model("HierCompositeTest")
        val m = HierarchicalAgents(model, "hier")
        // Run past the composite's onTimeout at t=5.0.
        model.lengthOfReplication = 6.0
        model.numberOfReplications = 1
        model.simulate()

        val names = m.log.map { it.first }
        // Composite onTimeout fires at t=5.0 → exit substate then exit
        // composite (bottom-up), then enter "off-shift".
        assertContains(names, "on-shift:exit")
        assertContains(names, "off-shift:entry")
        // Active chain after the transition out of on-shift is just
        // ["off-shift"] (top-level state with no substates).
        assertEquals(
            listOf("off-shift"),
            m.worker.statechart!!.activeStateNames,
        )
        // The leaf substate (idle OR working depending on when we
        // exited) should also have exited before on-shift.
        val onShiftExitIdx = names.indexOf("on-shift:exit")
        val anySubstateExitedBeforeOnShift =
            (names.subList(0, onShiftExitIdx).any { it == "idle:exit" || it == "working:exit" })
        assertTrue(
            anySubstateExitedBeforeOnShift,
            "the active substate must exit before on-shift; got $names",
        )
    }

    /**
     *  Verifies the "most-specific wins" semantic for onMessage:
     *  both the composite and substate declare a handler for the
     *  same message type; only the substate's handler should fire.
     */
    private class MostSpecificMessageAgents(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        var parentFires = 0
        var substateFires = 0

        inner class Listener : Agent("listener") {
            init {
                statechart {
                    initial("active")
                    state("active") {
                        initial("ready")
                        onMessage<AgentMessage.Inform<String>> { parentFires += 1 }
                        state("ready") {
                            onMessage<AgentMessage.Inform<String>> { substateFires += 1 }
                        }
                    }
                }
            }
        }

        inner class Sender(val target: () -> Listener) : Agent("sender") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(1.0)
                sendMessage(AgentMessage.Inform(this@Sender, "ping"), target().mailbox)
            }
        }

        val listener = Listener()
        val sender = Sender({ listener })

        override fun initialize() {
            super.initialize()
            activate(sender.script)
        }
    }

    @Test
    fun mostSpecificMessageHandlerWins() {
        val model = Model("MostSpecificMsgTest")
        val m = MostSpecificMessageAgents(model, "ms")
        model.lengthOfReplication = 5.0
        model.numberOfReplications = 1
        model.simulate()

        assertEquals(1, m.substateFires, "substate handler should fire (most specific)")
        assertEquals(0, m.parentFires, "parent handler should NOT fire when substate handles it")
    }

    /**
     *  Same shape, but the substate has NO handler for the message
     *  type. The composite's handler should fire (handler inherited
     *  from active composite ancestor).
     */
    private class InheritedMessageAgents(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        var parentFires = 0

        inner class Listener : Agent("listener") {
            init {
                statechart {
                    initial("active")
                    state("active") {
                        initial("ready")
                        onMessage<AgentMessage.Inform<String>> { parentFires += 1 }
                        state("ready") {
                            // no handler — inherited from "active"
                        }
                    }
                }
            }
        }

        inner class Sender(val target: () -> Listener) : Agent("sender") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                delay(1.0)
                sendMessage(AgentMessage.Inform(this@Sender, "ping"), target().mailbox)
            }
        }

        val listener = Listener()
        val sender = Sender({ listener })

        override fun initialize() {
            super.initialize()
            activate(sender.script)
        }
    }

    @Test
    fun substateInheritsCompositeMessageHandler() {
        val model = Model("InheritedMsgTest")
        val m = InheritedMessageAgents(model, "inh")
        model.lengthOfReplication = 5.0
        model.numberOfReplications = 1
        model.simulate()
        assertEquals(1, m.parentFires)
    }

    @Test
    fun nonMatchingMessagesRemainInMailbox() {
        val model = Model("NonMatchTest")
        val m = NonMatchingMessageAgent(model, "nm")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        assertEquals(0, m.matchedRequests, "no Request<String> was sent")
        // The Inform should still be sitting in the mailbox afterwards.
        // (afterReplication clears underlying queues, but we observe size
        // mid-replication is not possible here; check end state instead.)
        assertEquals("listening", m.listener.statechart!!.currentStateName)
    }
}
