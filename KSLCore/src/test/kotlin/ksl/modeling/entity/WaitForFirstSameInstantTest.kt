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

package ksl.modeling.entity

import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.AgentSignal
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  `waitForFirst` when several of a wait's triggers can fire in one instant. A wait has one winner; the other
 *  registrations leave their sources at that instant, and what they would have taken stays for others or for the
 *  next wait. When several triggers can already fire as a wait begins, argument order decides. Two seize triggers of
 *  one wait on one resource, in different queues, serve the wait once, and the units left go to the next request.
 */
class WaitForFirstSameInstantTest {

    private class Shop(parent: ModelElement) : AgentModel(parent, "SameInstantShop") {
        val r = ResourceWithQ(this, "R", capacity = 1)
        val r2 = ResourceWithQ(this, "R2", capacity = 2)
        val altQ = RequestQ(this, "AltQ")
        val q = BlockingQueue<Item>(this, name = "Q")
        val signal = Signal(this, "S")
        val agentSignal = AgentSignal("AS")
        val log = mutableListOf<String>()
        val setups = mutableListOf<Shop.() -> Unit>()

        inner class Hub : Agent("hub")

        val hub = Hub()

        inner class Item(val tag: String) : QObject() {
            override fun toString(): String = tag
        }

        inner class Actor(tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) { body() }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        override fun initialize() {
            super.initialize()
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(reps: Int = 1, setup: Shop.() -> Unit): Pair<Shop, Throwable?> {
        val model = Model("WaitForFirstSameInstant${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        shop.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = 100.0
        val error = runCatching { model.simulate() }.exceptionOrNull()
        return shop to error
    }

    /**
     *  W holds a unit of R2 and, from 1, waits three times for the first of six triggers: a seize of R, an item of Q,
     *  the signal S, the agent signal AS, a message to the hub, and the interrupted trigger of its R2 allocation. H
     *  holds R from 0 to 5; at 5, in one event, it releases R (waking the seize trigger), sends an item, signals S,
     *  fires AS and delivers a message.
     *
     *  The seize wins the first wait. The signal and the agent signal end no wait of W's, since their registrations
     *  are withdrawn at the win; the item and the message stay for the next waits. The second wait finds the item
     *  and the message both ready, and argument order gives it the item; the third takes the message. Two
     *  replications run alike.
     */
    @Test
    @DisplayName("every kind of trigger fired in one event: one winner, every source left clean, and the entity waits again")
    fun everyTriggerInOneEventOneWinnerThenWaitAgain() {
        val (shop, error) = run(reps = 2) {
            actor("H") {
                val a = seize(r)
                delay(5.0)
                release(a)
                q.sendToChannel(Item("i1"))
                signal.signalAll()
                agentSignal.fire()
                hub.mailbox.deliver(AgentMessage.Inform(hub, "m1"))
            }
            actor("W") {
                delay(1.0)
                val heldOther = seize(r2)
                val seizeT = r.seizeTrigger()
                val itemsT = q.itemsTrigger(1)
                val signalT = signal.trigger()
                val agentT = agentSignal.trigger()
                val msgT = hub.mailbox.trigger()
                val interruptedT = heldOther.interruptedTrigger()
                val names: Map<WaitTrigger<*>, String> = mapOf(
                    seizeT to "seize", itemsT to "items", signalT to "signal",
                    agentT to "agent", msgT to "message", interruptedT to "interrupted"
                )
                val held = mutableListOf<Allocation>()
                for (i in 1..3) {
                    val res = waitForFirst(seizeT, itemsT, signalT, agentT, msgT, interruptedT, timeout = 20.0)
                    val winner = res.trigger?.let { names[it] } ?: "timeout"
                    log.add(
                        "W$i $winner@$time busy=${r.numBusy} waitingR=${r.waitingQ.size} requests=${q.requestQ.size} " +
                                "channel=${q.channelQ.size} inSignal=${signal.waitingQ.size} " +
                                "subs=${agentSignal.numSubscribers} pending=${hub.mailbox.size}"
                    )
                    if (res.firedBy(seizeT)) held.add(res.valueOf(seizeT))
                }
                for (a in held) release(a)
                release(heldOther)
            }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(
            listOf(
                "W1 seize@5.0 busy=1 waitingR=0 requests=0 channel=1 inSignal=0 subs=0 pending=1",
                "W2 items@5.0 busy=1 waitingR=0 requests=0 channel=0 inSignal=0 subs=0 pending=1",
                "W3 message@5.0 busy=1 waitingR=0 requests=0 channel=0 inSignal=0 subs=0 pending=0",
            ),
            shop.log
        )
    }

    /**
     *  R2 has 2 units, held by H from 0 to 5. At 1, W waits for the first of a seize trigger of R2 in its own queue
     *  (a), one in AltQ (b), and a signal. At 2, V seizes one unit of R2. At 5 H releases both units: W's request a,
     *  which waited longest, wins with one unit, b is withdrawn, and the other unit goes to V.
     */
    @Test
    @DisplayName("two seize triggers of one wait on one resource in different queues, a third seize waiting: a release of two serves the wait once and the third")
    fun twoSeizeTriggersInTwoQueuesServeTheWaitOnce() {
        val (shop, error) = run {
            actor("H") {
                val a = seize(r2, 2)
                delay(5.0)
                release(a)
            }
            actor("W") {
                delay(1.0)
                val a = r2.seizeTrigger()
                val b = r2.seizeTrigger(queue = altQ)
                val res = waitForFirst(a, b, signal.trigger())
                val alloc = if (res.firedBy(a)) res.valueOf(a) else res.valueOf(b)
                log.add("W ${if (res.firedBy(a)) "a" else "b"} ${alloc.amount}@$time")
                delay(10.0)
                release(alloc)
            }
            actor("V") {
                delay(2.0)
                val alloc = seize(r2)
                log.add("V got@$time")
                delay(10.0)
                release(alloc)
            }
            at(7.0) { log.add("t7 busy=${r2.numBusy} waiting=${r2.waitingQ.size} alt=${altQ.size}") }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("W a 1@5.0", "V got@5.0", "t7 busy=2 waiting=0 alt=0"), shop.log)
    }
}
