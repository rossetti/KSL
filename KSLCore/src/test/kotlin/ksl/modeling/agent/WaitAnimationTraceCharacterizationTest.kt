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
import ksl.modeling.entity.BlockingQueue
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.Signal
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Pins the animation trace of the untimed waits: a signal wait emits `WaitingForSignal` and
 *  `SignalReceived`; an items wait and a `receiveMessage` emit no wait events of their own. The
 *  trace is a public format, and the timed waits keep these events.
 */
class WaitAnimationTraceCharacterizationTest {

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    /** The event types that describe a wait, and the entity each names. */
    private fun waitEventsOf(events: List<AnimationEvent>, entityId: Long): List<String> =
        events.mapNotNull { e ->
            when (e) {
                is AnimationEvent.WaitingForSignal -> if (e.entityId == entityId) "WaitingForSignal(${e.signalName})@${e.simTime}" else null
                is AnimationEvent.SignalReceived -> if (e.entityId == entityId) "SignalReceived(${e.signalName})@${e.simTime}" else null
                is AnimationEvent.HoldEntered -> if (e.entityId == entityId) "HoldEntered@${e.simTime}" else null
                is AnimationEvent.HoldReleased -> if (e.entityId == entityId) "HoldReleased@${e.simTime}" else null
                is AnimationEvent.WaitingForProcess -> if (e.entityId == entityId) "WaitingForProcess@${e.simTime}" else null
                is AnimationEvent.WaitForProcessCompleted -> if (e.entityId == entityId) "WaitForProcessCompleted@${e.simTime}" else null
                else -> null
            }
        }

    private class SignalAndItems(parent: ModelElement) : ProcessModel(parent, "trace") {
        val signal = Signal(this, "S")
        val q = BlockingQueue<Item>(this, name = "Q")
        lateinit var signalWaiter: Waiter
        lateinit var itemsWaiter: Receiver

        inner class Item : QObject()

        inner class Waiter : Entity("waiter") {
            val p: KSLProcess = process { waitFor(signal) }
        }

        inner class Receiver : Entity("receiver") {
            val p: KSLProcess = process { waitForItems(q, 1) }
        }

        override fun initialize() {
            signalWaiter = Waiter()
            itemsWaiter = Receiver()
            activate(signalWaiter.p)
            activate(itemsWaiter.p)
            schedule(::fire, 5.0)
        }

        private fun fire(event: KSLEvent<Nothing>) {
            signal.signalAll()
            q.sendToChannel(Item())
        }
    }

    @Test
    @DisplayName("a signal wait emits WaitingForSignal then SignalReceived; an items wait emits no wait event")
    fun signalAndItemsTrace() {
        val m = Model("trace${System.nanoTime()}", autoCSVReports = false)
        val sink = CollectingSink()
        m.animationSink = sink
        val sm = SignalAndItems(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 10.0
        m.simulate()
        assertEquals(
            listOf("WaitingForSignal(S)@0.0", "SignalReceived(S)@5.0"),
            waitEventsOf(sink.events, sm.signalWaiter.id),
        )
        assertEquals(emptyList(), waitEventsOf(sink.events, sm.itemsWaiter.id))
    }

    private class MessageModel(parent: ModelElement) : AgentModel(parent, "messageTrace") {
        inner class Hub(aName: String) : Agent(aName)

        val hub = Hub("hub")
        lateinit var receiver: Receiver

        inner class Receiver(aName: String) : Agent(aName) {
            val script: KSLProcess = process(isDefaultProcess = true) { receiveMessage(hub.mailbox) }
        }

        override fun initialize() {
            super.initialize()
            receiver = Receiver("receiver")
            activate(receiver.script)
            schedule(EventActionIfc<Nothing> { hub.mailbox.deliver(AgentMessage.Inform(hub, "m")) }, 5.0)
        }
    }

    @Test
    @DisplayName("receiveMessage emits no wait event")
    fun receiveMessageTrace() {
        val m = Model("messageTrace${System.nanoTime()}", autoCSVReports = false)
        val sink = CollectingSink()
        m.animationSink = sink
        val mm = MessageModel(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 10.0
        m.simulate()
        assertEquals(emptyList(), waitEventsOf(sink.events, mm.receiver.id))
    }
}
