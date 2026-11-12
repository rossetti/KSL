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

package ksl.modeling.agent

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.animation.entityIdOrNull
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.WaitForFirstResult
import ksl.modeling.entity.WaitResult
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Receiving a message with a time limit, and waiting for the first of several message triggers.
 *  A trigger's waiter joins the same ordered list as an untimed `receiveMessage` waiter; when a
 *  message ends the wait, every other waiter of that wait leaves the mailbox at once, so a second
 *  message at the same instant goes elsewhere; when the time runs out, the waiter leaves and later
 *  messages go to the pending list as if nobody had waited.
 */
class TimedReceiveTest {

    /** A hub agent whose mailbox the receivers wait on, and helpers to run receivers and events. */
    private class HubModel(parent: ModelElement, withStatechart: Boolean) : AgentModel(parent, "hub") {

        val log = mutableListOf<String>()
        val setups = mutableListOf<HubModel.() -> Unit>()

        inner class Hub(aName: String, withStatechart: Boolean) : Agent(aName) {
            init {
                if (withStatechart) {
                    statechart {
                        initial("listening")
                        state("listening") {
                            onMessage<AgentMessage.Inform<String>> { msg -> log.add("statechart:${msg.payload}@$time") }
                        }
                    }
                }
            }
        }

        val hub = Hub("hub", withStatechart)
        val mailbox get() = hub.mailbox

        inner class Receiver(aName: String, val body: suspend KSLProcessBuilder.(Receiver) -> Unit) : Agent(aName) {
            val script: KSLProcess = process(isDefaultProcess = true) { body(this@Receiver) }
        }

        fun receiver(name: String, body: suspend KSLProcessBuilder.(Receiver) -> Unit): Receiver =
            Receiver(name, body).also { activate(it.script) }

        fun at(t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t, priority = priority)
        }

        fun msg(p: String): AgentMessage = AgentMessage.Inform(hub, p)

        fun record(name: String, r: WaitResult<AgentMessage>) {
            log.add(if (r.timedOut) "$name:timedOut waited=${r.timeWaited}@$time" else "$name:${payload(r.value)} waited=${r.timeWaited}@$time")
        }

        override fun initialize() {
            super.initialize()
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(withStatechart: Boolean = false, sink: AnimationSink? = null, setup: HubModel.() -> Unit): HubModel {
        val model = Model("TimedReceive${System.nanoTime()}", autoCSVReports = false)
        if (sink != null) model.animationSink = sink
        val m = HubModel(model, withStatechart)
        m.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        return m
    }

    // ── The shorthand ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a matching message already pending is taken at once")
    fun fastPath() {
        val m = run {
            mailbox.deliver(msg("early"))
            receiver("r") { record("r", receiveMessageOrTimeout(mailbox, 5.0)) }
        }
        assertEquals(listOf("r:early waited=0.0@0.0"), m.log)
        assertEquals(0, m.mailbox.size)
    }

    @Test
    @DisplayName("a message delivered before the deadline ends the wait at its delivery")
    fun deliveredBeforeDeadline() {
        val m = run {
            receiver("r") { record("r", receiveMessageOrTimeout(mailbox, 10.0)) }
            at(3.0) { mailbox.deliver(msg("a")) }
        }
        assertEquals(listOf("r:a waited=3.0@3.0"), m.log)
    }

    @Test
    @DisplayName("timed out: the waiter leaves, and a later message goes to the pending list")
    fun timedOut() {
        var sizeAfter = -1
        val m = run {
            receiver("r") { record("r", receiveMessageOrTimeout(mailbox, 5.0)) }
            at(6.0) { mailbox.deliver(msg("late")); sizeAfter = mailbox.size }
        }
        assertEquals(listOf("r:timedOut waited=5.0@5.0"), m.log)
        assertEquals(1, sizeAfter)
    }

    @Test
    @DisplayName("a message delivered at the deadline instant wins at default priorities; an earlier timeoutPriority loses it")
    fun deadlineInstant() {
        val satisfied = run {
            receiver("r") { record("r", receiveMessageOrTimeout(mailbox, 5.0)) }
            at(5.0) { mailbox.deliver(msg("edge")) }
        }
        assertEquals(listOf("r:edge waited=5.0@5.0"), satisfied.log)

        var sizeAfter = -1
        val timedOut = run {
            receiver("r") {
                record("r", receiveMessageOrTimeout(mailbox, 5.0, timeoutPriority = KSLEvent.DEFAULT_PRIORITY - 1))
            }
            at(5.0) { mailbox.deliver(msg("edge")); sizeAfter = mailbox.size }
        }
        assertEquals(listOf("r:timedOut waited=5.0@5.0"), timedOut.log)
        assertEquals(1, sizeAfter, "the message goes to the pending list, not to the timed-out waiter")
    }

    @Test
    @DisplayName("a message the predicate rejects is left pending and the wait times out")
    fun rejectingPredicate() {
        val m = run {
            receiver("r") { record("r", receiveMessageOrTimeout(mailbox, 5.0) { payload(it) == "wanted" }) }
            at(2.0) { mailbox.deliver(msg("other")) }
        }
        assertEquals(listOf("r:timedOut waited=5.0@5.0"), m.log)
        assertEquals(1, m.mailbox.size)
    }

    @Test
    @DisplayName("a reservation captures a matching message before a timed receiver sees it")
    fun reservationFirst() {
        var reservation: AgentModel.AgentMailbox<AgentMessage>.Reservation? = null
        val m = run {
            reservation = mailbox.reserve { true }
            receiver("r") { record("r", receiveMessageOrTimeout(mailbox, 5.0)) }
            at(2.0) { mailbox.deliver(msg("captured")) }
        }
        assertEquals(listOf("r:timedOut waited=5.0@5.0"), m.log)
        assertEquals(listOf("captured"), reservation!!.collected().map { payload(it) })
    }

    @Test
    @DisplayName("termination during the wait removes the waiter; a later message goes to the pending list")
    fun termination() {
        var sizeAfter = -1
        val m = run {
            val r = receiver("r") { record("r", receiveMessageOrTimeout(mailbox, 10.0)) }
            at(2.0) { r.terminateProcess() }
            at(3.0) { mailbox.deliver(msg("after")); sizeAfter = mailbox.size }
        }
        assertEquals(emptyList(), m.log)
        assertEquals(1, sizeAfter)
    }

    @Test
    @DisplayName("two timed receivers on one mailbox: the earlier-registered is served; the other times out")
    fun twoTimedReceivers() {
        val m = run {
            receiver("first") { record("first", receiveMessageOrTimeout(mailbox, 10.0)) }
            receiver("second") { record("second", receiveMessageOrTimeout(mailbox, 10.0)) }
            at(3.0) { mailbox.deliver(msg("only")) }
        }
        assertEquals(listOf("first:only waited=3.0@3.0", "second:timedOut waited=10.0@10.0"), m.log)
    }

    @Test
    @DisplayName("observers see nothing delivered or consumed when a wait times out")
    fun observersOnTimeout() {
        var delivered = 0
        var consumed = 0
        val m = run {
            mailbox.attachObserver(object : AgentModel.MailboxObserver<AgentMessage> {
                override fun onMessageDelivered(message: AgentMessage, currentSize: Int) { delivered++ }
                override fun onMessageConsumed(message: AgentMessage, currentSize: Int) { consumed++ }
            })
            receiver("r") { record("r", receiveMessageOrTimeout(mailbox, 5.0)) }
        }
        assertEquals(listOf("r:timedOut waited=5.0@5.0"), m.log)
        assertEquals(0, delivered)
        assertEquals(0, consumed)
    }

    @Test
    @DisplayName("receiveMessageOfTypeOrTimeout skips messages of other types and returns the typed message")
    fun ofType() {
        val m = run {
            receiver("r") {
                val r = receiveMessageOfTypeOrTimeout<AgentMessage.Reject, AgentMessage>(mailbox, 10.0)
                log.add(if (r.timedOut) "timedOut" else "reject:${r.value.reason}@$time")
            }
            at(1.0) { mailbox.deliver(msg("not a reject")) }
            at(2.0) { mailbox.deliver(AgentMessage.Reject(hub, "c1", "no")) }
        }
        assertEquals(listOf("reject:no@2.0"), m.log)
        assertEquals(1, m.mailbox.size, "the Inform stays pending")
    }

    // ── waitForFirst with message triggers ──────────────────────────────────

    /** Waits for the first of a trigger for payloads starting with "a" and one for payloads of length two. */
    private fun HubModel.twoTriggerReceiver(timeout: Double = 20.0, swap: Boolean = false) {
        val startsWithA = mailbox.trigger { payload(it).startsWith("a") }
        val lengthTwo = mailbox.trigger { payload(it).length == 2 }
        receiver("w") {
            val r: WaitForFirstResult =
                if (swap) waitForFirst(lengthTwo, startsWithA, timeout = timeout)
                else waitForFirst(startsWithA, lengthTwo, timeout = timeout)
            val which = when (r.trigger) {
                startsWithA -> "startsWithA"
                lengthTwo -> "lengthTwo"
                else -> "timedOut"
            }
            val value = r.trigger?.let { payload(r.valueOf(it) as AgentMessage) }
            log.add("$which:$value@$time")
        }
    }

    @Test
    @DisplayName("a message matching both triggers goes to the first passed")
    fun matchingBoth() {
        val m = run {
            twoTriggerReceiver()
            at(3.0) { mailbox.deliver(msg("ab")) }
        }
        assertEquals(listOf("startsWithA:ab@3.0"), m.log)
        val swapped = run {
            twoTriggerReceiver(swap = true)
            at(3.0) { mailbox.deliver(msg("ab")) }
        }
        assertEquals(listOf("lengthTwo:ab@3.0"), swapped.log)
    }

    @Test
    @DisplayName("a message matching only the second trigger fires it")
    fun matchingSecond() {
        val m = run {
            twoTriggerReceiver()
            at(3.0) { mailbox.deliver(msg("zz")) }
        }
        assertEquals(listOf("lengthTwo:zz@3.0"), m.log)
    }

    @Test
    @DisplayName("the loser's waiter is gone at the claim: a second message at the same instant goes to the pending list")
    fun loserGoneAtClaim() {
        var sizeAfter = -1
        val m = run {
            twoTriggerReceiver()
            at(3.0) {
                mailbox.deliver(msg("zz"))
                mailbox.deliver(msg("ab"))
                sizeAfter = mailbox.size
            }
        }
        assertEquals(listOf("lengthTwo:zz@3.0"), m.log)
        assertEquals(1, sizeAfter)
    }

    @Test
    @DisplayName("fast path with two pending candidates: the first trigger in argument order wins")
    fun fastPathArgumentOrder() {
        val m = run {
            mailbox.deliver(msg("zz"))
            mailbox.deliver(msg("ab"))
            twoTriggerReceiver()
        }
        assertEquals(listOf("startsWithA:ab@0.0"), m.log, "startsWithA is passed first, though zz arrived first")
        assertEquals(1, m.mailbox.size)
    }

    @Test
    @DisplayName("both message triggers time out: both waiters leave the mailbox")
    fun bothTimeOut() {
        var sizeAfter = -1
        val m = run {
            twoTriggerReceiver(timeout = 4.0)
            at(5.0) { mailbox.deliver(msg("ab")); sizeAfter = mailbox.size }
        }
        assertEquals(listOf("timedOut:null@4.0"), m.log)
        assertEquals(1, sizeAfter)
    }

    // ── Interactions ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a statechart on the same mailbox: the timed receiver is served first; after its timeout the statechart sees messages")
    fun statechartOnSameMailbox() {
        val m = run(withStatechart = true) {
            receiver("r") { record("r", receiveMessageOrTimeout(mailbox, 5.0)) }
            at(2.0) { mailbox.deliver(msg("first")) }
            receiver("r2") {
                delay(3.0)
                record("r2", receiveMessageOrTimeout(mailbox, 2.0))
            }
            at(8.0) { mailbox.deliver(msg("second")) }
        }
        assertEquals(
            listOf("r:first waited=2.0@2.0", "r2:timedOut waited=2.0@5.0", "statechart:second@8.0"),
            m.log
        )
    }

    @Test
    @DisplayName("an untimed receiver and a timed one on one mailbox: the earlier-registered is served, whichever kind")
    fun untimedAndTimedOrder() {
        val untimedFirst = run {
            receiver("untimed") { val x = receiveMessage(mailbox); log.add("untimed:${payload(x)}@$time") }
            receiver("timed") { record("timed", receiveMessageOrTimeout(mailbox, 10.0)) }
            at(3.0) { mailbox.deliver(msg("m")) }
        }
        assertEquals(listOf("untimed:m@3.0", "timed:timedOut waited=10.0@10.0"), untimedFirst.log)

        val timedFirst = run {
            receiver("timed") { record("timed", receiveMessageOrTimeout(mailbox, 10.0)) }
            receiver("untimed") { val x = receiveMessage(mailbox); log.add("untimed:${payload(x)}@$time") }
            at(3.0) { mailbox.deliver(msg("m")) }
            at(4.0) { mailbox.deliver(msg("n")) }
        }
        assertEquals(listOf("timed:m waited=3.0@3.0", "untimed:n@4.0"), timedFirst.log)
    }

    @Test
    @DisplayName("a send made inside a delivery's observer is offered to the remaining waiters, never to the claimed wait's released ones")
    fun reentrantDelivery() {
        var sizeAfter = -1
        val m = run {
            mailbox.attachObserver(object : AgentModel.MailboxObserver<AgentMessage> {
                override fun onMessageDelivered(message: AgentMessage, currentSize: Int) {
                    if (payload(message) == "first") mailbox.deliver(msg("second"))
                }
            })
            val first = mailbox.trigger { payload(it) == "first" }
            val second = mailbox.trigger { payload(it) == "second" }
            receiver("w") {
                val r = waitForFirst(first, second, timeout = 20.0)
                log.add("w:${payload(r.valueOf(r.trigger!!) as AgentMessage)}@$time")
            }
            receiver("other") { val x = receiveMessage(mailbox) { payload(it) == "second" }; log.add("other:${payload(x)}@$time") }
            at(3.0) { mailbox.deliver(msg("first")); sizeAfter = mailbox.size }
        }
        assertEquals(setOf("w:first@3.0", "other:second@3.0"), m.log.toSet())
        assertEquals(0, sizeAfter)
    }

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    @Test
    @DisplayName("trace: a timed receive emits no wait event when satisfied, and WaitTimedOut once when it times out")
    fun trace() {
        val sink = CollectingSink()
        lateinit var satisfied: HubModel.Receiver
        lateinit var timedOut: HubModel.Receiver
        run(sink = sink) {
            satisfied = receiver("s") { receiveMessageOrTimeout(mailbox, 10.0) { payload(it) == "s" } }
            timedOut = receiver("t") { receiveMessageOrTimeout(mailbox, 4.0, suspensionName = "decision") { payload(it) == "t" } }
            at(2.0) { mailbox.deliver(msg("s")) }
        }
        val waitTypes = setOf(
            AnimationEvent.WaitingForSignal::class, AnimationEvent.SignalReceived::class,
            AnimationEvent.WaitTimedOut::class
        )
        val forSatisfied = sink.events.filter { it::class in waitTypes && it.entityIdOrNull == satisfied.id }
        val forTimedOut = sink.events.filter { it::class in waitTypes && it.entityIdOrNull == timedOut.id }
        assertEquals(emptyList(), forSatisfied)
        assertEquals(listOf<AnimationEvent>(AnimationEvent.WaitTimedOut(4.0, timedOut.id, "decision")), forTimedOut)
    }

    @Test
    @DisplayName("a trigger built from a mailbox is reusable across waits")
    fun triggerReuse() {
        val m = run {
            val t = mailbox.trigger()
            receiver("r") {
                for (i in 1..3) {
                    val r = waitForFirst(t, timeout = 2.0)
                    log.add(if (r.timedOut) "timedOut@$time" else "${payload(r.valueOf(t))}@$time")
                }
            }
            at(1.0) { mailbox.deliver(msg("one")) }
            at(5.0) { mailbox.deliver(msg("three")) }
        }
        assertEquals(listOf("one@1.0", "timedOut@3.0", "three@5.0"), m.log)
        assertTrue(m.mailbox.isEmpty)
    }

    private companion object {
        fun payload(m: AgentMessage): String = when (m) {
            is AgentMessage.Inform<*> -> m.payload as String
            else -> m.toString()
        }
    }
}
