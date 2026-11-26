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

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  Removing and terminating every waiter of a queue terminates exactly the waiters present when it is called, even
 *  when terminating one of them frees what another waits for.
 */
class BulkTerminationTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val plans = mutableListOf<Shop.() -> Unit>()
        val log = mutableListOf<String>()
        val terminated = mutableListOf<String>()

        inner class Actor(tag: String, prio: Int, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process("p-$tag") { body() }

            override fun handleTerminatedProcess(terminatedProcess: KSLProcess) {
                terminated.add(name)
            }
        }

        fun actor(tag: String, priority: Int = 5, body: suspend KSLProcessBuilder.() -> Unit) =
            Actor(tag, priority, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t, priority = KSLEvent.DEFAULT_PRIORITY)
        }

        override fun initialize() {
            log.clear()
            terminated.clear()
            for (p in plans) p()
        }
    }

    /** [build] makes the model's elements before the run, and returns what each replication starts. */
    private fun run(build: Shop.() -> (Shop.() -> Unit)): Shop {
        val model = Model("Bulk${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        shop.plans.add(shop.build())
        model.lengthOfReplication = 20.0
        val failure = runCatching { model.simulate() }.exceptionOrNull()
        assertNull(failure, "the run stopped: $failure")
        return shop
    }

    /**
     *  A and B share one queue. Blocker holds A; First holds B and waits for A; Second waits for B. Terminating First
     *  releases B, which would serve Second; both are terminated.
     */
    @Test
    @DisplayName("a request queue shared by two resources: terminating one waiter frees what the next waits for, and both are terminated")
    fun sharedQueue() {
        val shop = run {
            val q = RequestQ(this, "Shared")
            val a = ResourceWithQ(this, "A", queue = q)
            val b = ResourceWithQ(this, "B", queue = q)
            return@run {
            actor("Blocker") { val x = seize(a); delay(10.0); release(x) }
            actor("First") { delay(0.1); val y = seize(b); val x = seize(a); release(x); release(y) }
            actor("Second") { delay(1.0); val y = seize(b); log.add("Second got B@$time"); release(y) }
            at(2.0) { q.removeAllAndTerminate() }
            }
        }
        assertEquals(emptyList(), shop.log)
        assertEquals(listOf("First", "Second"), shop.terminated)
    }

    /**
     *  R has 1 unit, held by H to 1.5, and a rule that serves only the head of the queue. Large asks for 2 and waits;
     *  Small asks for 1 behind it. At 2 both are terminated: withdrawing Large lets the rule choose Small for the free
     *  unit, and it is terminated even so.
     */
    @Test
    @DisplayName("under a head-only selection rule: withdrawing the head lets the rule choose the next waiter, and both are terminated")
    fun headOnlyRule() {
        val shop = run {
            val r = ResourceWithQ(this, "R", capacity = 1)
            r.myWaitingQ.requestSelectionRule = RequestSelectionRuleIfc { resource, requests ->
                val head = requests.firstOrNull()
                if (head != null && head.amountRequested <= resource.numAvailableUnits) listOf(head) else emptyList()
            }
            return@run {
            actor("H") { val x = seize(r); delay(1.5); release(x) }
            actor("Large") { delay(0.1); val x = seize(r, 2); release(x) }
            actor("Small") { delay(1.0); val x = seize(r); log.add("Small got R@$time"); release(x) }
            at(2.0) { r.myWaitingQ.removeAllAndTerminate() }
            }
        }
        assertEquals(emptyList(), shop.log)
        assertEquals(listOf("Large", "Small"), shop.terminated)
    }

    /**
     *  R has 2 units: Holder uses one to 10, one is free. Rush preempts both under FINISH at 1, reserving the free unit
     *  and waiting for Holder's; Ordinary asks for one at 1.5. At 2 both are terminated: ending the reservation frees
     *  the unit it held, which would serve Ordinary.
     */
    @Test
    @DisplayName("ending a FINISH reservation frees the unit it held, and the waiter it would serve is terminated too")
    fun reservationAndWaiter() {
        val shop = run {
            val r = ResourceWithQ(this, "R", capacity = 2)
            return@run {
            actor("Holder", 10) { val x = seize(r); delay(10.0); release(x) }
            actor("Rush", 1) { delay(1.0); val x = preempt(r, 2, rule = InterruptRule.FINISH); release(x) }
            actor("Ordinary", 10) { delay(1.5); val x = seize(r); log.add("Ordinary got R@$time"); release(x) }
            at(2.0) { r.myWaitingQ.removeAllAndTerminate() }
            }
        }
        assertEquals(emptyList(), shop.log)
        assertEquals(listOf("Rush", "Ordinary"), shop.terminated)
    }

    /** W waits for the first of two seize triggers through one queue: it has two requests there, and is terminated once. */
    @Test
    @DisplayName("an entity with two requests in the queue, from a wait for the first of several things, is terminated once")
    fun entityWithTwoRequests() {
        val shop = run {
            val q = RequestQ(this, "Q")
            val a = ResourceWithQ(this, "A", queue = q)
            val b = ResourceWithQ(this, "B", queue = q)
            return@run {
            actor("HA") { val x = seize(a); delay(10.0); release(x) }
            actor("HB") { val x = seize(b); delay(10.0); release(x) }
            actor("W") { delay(1.0); waitForFirst(a.seizeTrigger(queue = q), b.seizeTrigger(queue = q)); log.add("W won@$time") }
            at(2.0) { q.removeAllAndTerminate() }
            }
        }
        assertEquals(emptyList(), shop.log)
        assertEquals(listOf("W"), shop.terminated)
    }

    /**
     *  A waits for 2 items and B for 1 behind it; one item is in the channel, which cannot fill A. At 3 every receiver
     *  is terminated: withdrawing A lets the item fill B, and B is terminated even so, the item left in the channel.
     */
    @Test
    @DisplayName("a blocking queue's receivers: withdrawing the head lets the next be filled, and both are terminated")
    fun blockingQueueReceivers() {
        var itemsLeft = -1
        val shop = run {
            val bq = BlockingQueue<ModelElement.QObject>(this, name = "Parts")
            return@run {
            actor("A") { delay(1.0); waitForItems(bq, 2); log.add("A filled@$time") }
            actor("B") { delay(1.5); waitForItems(bq, 1); log.add("B filled@$time") }
            actor("S") { delay(2.0); send(QObject(), bq) }
            at(3.0) { bq.removeAllAndTerminateChannelRequest() }
            at(4.0) { itemsLeft = bq.channelQ.size }
            }
        }
        assertEquals(emptyList(), shop.log)
        assertEquals(listOf("A", "B"), shop.terminated)
        assertEquals(1, itemsLeft, "the item no one took")
    }
}
