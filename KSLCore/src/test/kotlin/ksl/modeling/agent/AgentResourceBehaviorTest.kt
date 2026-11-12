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

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  A behavior on an agent resource: it can wait for messages, which a statechart cannot, so a
 *  resource has one or the other, never both. Its agent model is public, so a behavior can reach
 *  other agents through it.
 */
class AgentResourceBehaviorTest {

    private class DeskModel(parent: ModelElement) : AgentModel(parent, "desks") {
        val desk = AgentResource(this, "Desk")
        val room = ResourceWithQ(this, "Room")

        inner class Peer : PermanentAgent("peer")

        /** Holds the room from 0 to 4, then asks for it again at 8 and holds it to 30. */
        inner class Holder : Entity("holder") {
            val p: KSLProcess = process {
                val a = seize(room)
                delay(4.0)
                release(a)
                delay(4.0)
                val b = seize(room)
                delay(21.0)
                release(b)
            }
        }

        val peer = Peer()
        val log = mutableListOf<String>()
        val setups = mutableListOf<DeskModel.() -> Unit>()

        fun at(t: Double, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t)
        }

        fun send(p: String) = desk.mailbox.deliver(AgentMessage.Inform(peer, p))

        override fun initialize() {
            super.initialize()
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(reps: Int = 1, declare: DeskModel.() -> Unit, setup: DeskModel.() -> Unit = {}): DeskModel {
        val model = Model("Desks${System.nanoTime()}", autoCSVReports = false)
        val m = DeskModel(model)
        m.declare()
        m.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = 50.0
        model.simulate()
        return m
    }

    private fun payload(m: AgentMessage) = (m as AgentMessage.Inform<*>).payload as String

    @Test
    @DisplayName("a behavior and a statechart are exclusive, in either order; clearing the chart is allowed")
    fun exclusiveWithStatechart() {
        val chartFirst = DeskModel(Model("ChartFirst${System.nanoTime()}", autoCSVReports = false))
        chartFirst.desk.statechart { initial("idle"); state("idle") {} }
        val e1 = assertFailsWith<IllegalStateException> { chartFirst.desk.cyclicBehavior { delay(1.0) } }
        assertTrue(e1.message!!.contains("one source of truth"), e1.message)

        val behaviorFirst = DeskModel(Model("BehaviorFirst${System.nanoTime()}", autoCSVReports = false))
        behaviorFirst.desk.behavior { delay(1.0) }
        val e2 = assertFailsWith<IllegalStateException> { behaviorFirst.desk.statechart { initial("idle"); state("idle") {} } }
        assertTrue(e2.message!!.contains("one source of truth"), e2.message)
        // building a chart does not install it; installing it is refused; clearing is allowed
        val built = behaviorFirst.desk.buildStatechart("spare") { initial("idle"); state("idle") {} }
        assertFailsWith<IllegalStateException> { behaviorFirst.desk.useStatechart(built) }
        behaviorFirst.desk.useStatechart(null)
    }

    @Test
    @DisplayName("messages sent before the behavior starts wait in the mailbox, and its first receive takes them")
    fun messagesBeforeStart() {
        val m = run(declare = {
            desk.cyclicBehavior { val msg = receiveMessage(desk.mailbox); log.add("${payload(msg)}@$time") }
        }, setup = { send("early1"); send("early2"); at(5.0) { send("later") } })
        assertEquals(listOf("early1@0.0", "early2@0.0", "later@5.0"), m.log)
    }

    @Test
    @DisplayName("a cycle taking each of 50 messages waiting at one instant passes the zero-time guard")
    fun burstOfMessages() {
        val m = run(declare = {
            desk.maxCyclesPerInstant = 100
            desk.cyclicBehavior { val msg = receiveMessage(desk.mailbox); log.add(payload(msg)) }
        }, setup = { at(3.0) { repeat(50) { send("m$it") } } })
        assertEquals(50, m.log.size)
    }

    @Test
    @DisplayName("a behavior reaches other agents through the public agentModel")
    fun sendsThroughAgentModel() {
        var received = -1
        val m = run(declare = {
            desk.behavior {
                delay(2.0)
                val model = desk.agentModel as DeskModel
                sendMessage(AgentMessage.Inform(desk, "hello"), model.peer.mailbox)
            }
        }, setup = { at(3.0) { received = peer.mailbox.size } })
        assertTrue(m.desk.agentModel === m)
        assertEquals(1, received, "the peer received the message")
    }

    @Test
    @DisplayName("a behavior waits for whichever comes first of a message and a room, with a time limit")
    fun messageOrRoom() {
        val m = run(declare = {
            desk.behavior {
                delay(1.0)
                val call = desk.mailbox.trigger()
                val roomTrigger = room.seizeTrigger()
                repeat(3) {
                    val r = waitForFirst(call, roomTrigger, timeout = 10.0)
                    when (r.trigger) {
                        call -> log.add("call ${payload(r.valueOf(call))}@$time")
                        roomTrigger -> { log.add("room@$time"); delay(5.0); release(r.valueOf(roomTrigger)) }
                        else -> log.add("timeout@$time")
                    }
                }
            }
        }, setup = {
            activate(Holder().p)
            at(3.0) { send("ring") }
        })
        // the call at 3; the room freed at 4; at 9 the holder, waiting since 8, is given the room, so a timeout at 19
        assertEquals(listOf("call ring@3.0", "room@4.0", "timeout@19.0"), m.log)
    }

    @Test
    @DisplayName("collectPerformance on an agent resource with a behavior and no statechart collects its mailbox statistics")
    fun performanceWithBehavior() {
        val model = Model("Perf${System.nanoTime()}", autoCSVReports = false)
        val m = DeskModel(model)
        m.desk.cyclicBehavior { receiveMessage(m.desk.mailbox) }
        val perf = m.desk.collectPerformance()
        m.setups.add { at(1.0) { send("a") }; at(2.0) { send("b") } }
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        model.simulate()
        assertEquals(2.0, perf.numMessagesReceivedResponse.acrossReplicationStatistic.average)
    }
}
