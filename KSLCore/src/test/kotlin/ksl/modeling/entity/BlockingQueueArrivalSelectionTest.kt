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

import ksl.modeling.queue.Queue
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A request arriving at a blocking queue asks the queue's request selector who is served now, as a later change of
 *  the channel would. Whichever request it chooses is served at once if it can be filled: the arriving one, an
 *  earlier untimed receiver, or an earlier trigger of the same wait.
 *
 *  In each case the channel holds one item from the start, B waits from 0 for two items (it cannot be filled), and
 *  the selector chooses the request at position 1 once three requests wait, and nothing before.
 */
class BlockingQueueArrivalSelectionTest {

    private class Shop(parent: ModelElement, val setup: Shop.() -> Unit) : ProcessModel(parent, "Shop") {
        inner class Item : QObject()

        val buffer = BlockingQueue<Item>(this, name = "Buffer")
        val log = mutableListOf<String>()

        init {
            buffer.receiverRequestSelector = object : BlockingQueue.RequestSelectorIfc<Item> {
                override fun selectRequest(queue: Queue<BlockingQueue<Item>.ChannelRequest>): BlockingQueue<Item>.ChannelRequest? =
                    if (queue.size >= 3) queue[1] else null
            }
        }

        inner class Actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true, block = body)
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = activate(Actor(tag, body).p)

        override fun initialize() {
            log.clear()
            buffer.sendToChannel(Item())
            actor("B") { waitForItems(buffer, amount = 2) }
            setup()
        }
    }

    private fun run(setup: Shop.() -> Unit): Shop {
        val model = Model("ArrivalSelection${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model, setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 5.0
        model.simulate()
        return shop
    }

    @Test
    @DisplayName("the second trigger of a wait joining makes the selector choose the first: the wait takes the item at once")
    fun earlierTriggerOfTheSameWait() {
        val shop = run {
            actor("W") {
                delay(1.0)
                val one = buffer.itemsTrigger()
                val another = buffer.itemsTrigger()
                val r = waitForFirst(one, another, timeout = 2.0)
                log.add(if (r.timedOut) "W timed out@$time" else "W ${if (r.firedBy(one)) "one" else "another"}@$time")
            }
        }
        assertEquals(listOf("W one@1.0"), shop.log)
    }

    @Test
    @DisplayName("an arriving receiver makes the selector choose an earlier untimed receiver: that one takes the item at once")
    fun earlierUntimedReceiver() {
        val shop = run {
            actor("R1") { delay(1.0); waitForItems(buffer, amount = 1); log.add("R1 got@$time") }
            actor("R2") { delay(2.0); waitForItems(buffer, amount = 1); log.add("R2 got@$time") }
        }
        assertEquals(listOf("R1 got@2.0"), shop.log)
    }

    @Test
    @DisplayName("an arriving trigger makes the selector choose an earlier untimed receiver: that one takes the item at once, and the trigger waits")
    fun arrivingTriggerServesAnEarlierReceiver() {
        val shop = run {
            actor("R1") { delay(1.0); waitForItems(buffer, amount = 1); log.add("R1 got@$time") }
            actor("W") {
                delay(2.0)
                val r = waitForFirst(buffer.itemsTrigger(), timeout = 1.0)
                log.add(if (r.timedOut) "W timed out@$time" else "W got@$time")
            }
        }
        assertEquals(listOf("R1 got@2.0", "W timed out@3.0"), shop.log)
    }
}
