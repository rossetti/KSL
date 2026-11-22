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
import ksl.modeling.entity.KSLProcessBuilder
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A message delivered, or an agent signal fired, from `replicationEnded()` while a process waits for it: the
 *  replication has ended, so nothing resumes. The message stays in the mailbox, the signal does nothing for the
 *  waiting process, and the next replication starts clean.
 */
class EndOfReplicationDeliveryTest {

    private class Hub(parent: ModelElement) : AgentModel(parent, "hubModel") {
        val log = mutableListOf<String>()
        val signal = AgentSignal("sig")
        var fireAtEnd = false
        var deliverAtEnd = false
        var body: suspend KSLProcessBuilder.() -> Unit = {}

        inner class HubAgent : Agent("hub")

        val hub = HubAgent()

        inner class Receiver : Agent("receiver") {
            val script: KSLProcess = process(isDefaultProcess = true) { body() }
        }

        override fun initialize() {
            super.initialize()
            log.add("start r=${model.currentReplicationNumber} mailbox=${hub.mailbox.size} subs=${signal.numSubscribers}")
            activate(Receiver().script)
        }

        override fun replicationEnded() {
            super.replicationEnded()
            if (fireAtEnd) signal.fire()
            if (deliverAtEnd) {
                hub.mailbox.deliver(AgentMessage.Inform(hub, "late"))
                log.add("after deliver mailbox=${hub.mailbox.size}")
            }
        }
    }

    private fun run(config: Hub.() -> Unit): Pair<Hub, Throwable?> {
        val model = Model("EndDelivery${System.nanoTime()}", autoCSVReports = false)
        val h = Hub(model)
        h.config()
        model.numberOfReplications = 2
        model.lengthOfReplication = 10.0
        val error = try {
            model.simulate(); null
        } catch (e: Throwable) {
            e
        }
        return h to error
    }

    @Test
    @DisplayName("an agent signal fired in replicationEnded while a process waits for it resumes nothing")
    fun signalFiredAtTheEnd() {
        val (h, error) = run {
            fireAtEnd = true
            body = {
                val res = waitForFirst(signal.trigger(), timeout = 1000.0)
                log.add("resumed timedOut=${res.timedOut}")
            }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("start r=1 mailbox=0 subs=0", "start r=2 mailbox=0 subs=0"), h.log)
    }

    @Test
    @DisplayName("a message delivered in replicationEnded while a timed receiver waits stays in the mailbox")
    fun messageToATimedReceiverAtTheEnd() {
        val (h, error) = run {
            deliverAtEnd = true
            body = {
                val res = receiveMessageOrTimeout(hub.mailbox, 1000.0)
                log.add("resumed timedOut=${res.timedOut}")
            }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(
            listOf("start r=1 mailbox=0 subs=0", "after deliver mailbox=1", "start r=2 mailbox=0 subs=0", "after deliver mailbox=1"),
            h.log
        )
    }

    @Test
    @DisplayName("a message delivered in replicationEnded while a plain receiver waits stays in the mailbox")
    fun messageToAnUntimedReceiverAtTheEnd() {
        val (h, error) = run {
            deliverAtEnd = true
            body = {
                val m = receiveMessage(hub.mailbox)
                log.add("resumed with $m")
            }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(
            listOf("start r=1 mailbox=0 subs=0", "after deliver mailbox=1", "start r=2 mailbox=0 subs=0", "after deliver mailbox=1"),
            h.log
        )
    }

    @Test
    @DisplayName("a message delivered in replicationEnded while a waitForFirst waits for it stays in the mailbox")
    fun messageToAWaitForFirstAtTheEnd() {
        val (h, error) = run {
            deliverAtEnd = true
            body = {
                val res = waitForFirst(hub.mailbox.trigger(), signal.trigger())
                log.add("resumed by ${res.trigger}")
            }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(
            listOf("start r=1 mailbox=0 subs=0", "after deliver mailbox=1", "start r=2 mailbox=0 subs=0", "after deliver mailbox=1"),
            h.log
        )
    }
}
