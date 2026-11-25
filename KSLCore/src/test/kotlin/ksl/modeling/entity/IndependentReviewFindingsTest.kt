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
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A ready trigger behind a queue-dependent seize, a failure due at a release that completes a capacity change, a
 *  request moved after its allocation was passed, and a wait terminated while its registrations are released.
 */
class IndependentReviewFindingsTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val q = RequestQ(this, "Q").apply {
            requestSelectionRule = RequestSelectionRuleIfc { res, queue -> queue.filter { it.resource == res } }
        }
        val r = ResourceWithQ(this, "R", capacity = 1, queue = q)
        val other = ResourceWithQ(this, "Other")
        val target = ResourceWithQ(this, "Target")
        val two = ResourceWithQ(this, "Two", capacity = 2)
        val pairQ = RequestQ(this, "PairQ").apply {
            // serves the requests for a resource only once two or more wait for it, then all of them
            requestSelectionRule = RequestSelectionRuleIfc { res, queue ->
                val mine = queue.filter { it.resource == res }
                if (mine.size >= 2) mine else emptyList()
            }
        }
        val pair = ResourceWithQ(this, "Pair", capacity = 2, queue = pairQ)
        val threeQ = RequestQ(this, "ThreeQ").apply {
            requestSelectionRule = RequestSelectionRuleIfc { res, queue -> queue.filter { it.resource == res } }
        }
        val three = ResourceWithQ(this, "Three", capacity = 3, queue = threeQ)
        val items = BlockingQueue<Item>(this, name = "Items")
        val anyFillable = BlockingQueue<Item>(this, name = "AnyFillable").apply {
            receiverRequestSelector = FirstFillableRequest()
        }
        val signal = Signal(this, "S")
        val log = mutableListOf<String>()
        val setups = mutableListOf<Shop.() -> Unit>()

        inner class Item(val tag: String) : QObject()

        inner class Actor(tag: String, prio: Int, val body: suspend KSLProcessBuilder.(Actor) -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process(isDefaultProcess = true) { body(this@Actor) }

            /** Moves this entity's waiting request for [from] to [to], as a model's own code might; false if it may not. */
            fun reroute(from: ResourceWithQ, to: ResourceWithQ): Boolean {
                val request = from.myWaitingQ.firstOrNull { it.entity === this } as? ResourceRequest ?: return false
                if (!canMoveRequestToResource(request)) return false
                moveRequestToResource(request, from.myWaitingQ, to, RESUME_PRIORITY)
                return true
            }

            /** Whether this entity has a request in [queue]. */
            fun isQueuedIn(queue: RequestQ): Boolean = queue.any { it.entity === this }
        }

        fun actor(tag: String, priority: Int = 5, body: suspend KSLProcessBuilder.(Actor) -> Unit) =
            Actor(tag, priority, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(configure: Shop.() -> Unit = {}, setup: Shop.() -> Unit): Pair<Shop, Throwable?> {
        val model = Model("IndependentFindings${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        shop.configure()
        shop.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        val error = try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
        return shop to error
    }

    @Test
    @DisplayName("a trigger ready at once wins a wait though it follows a seize whose queue has a rule and cannot serve")
    fun readyTriggerAfterAQueueDependentSeize() {
        val (shop, error) = run {
            actor("H") { val a = seize(r); delay(50.0); release(a) }
            actor("W") {
                val a = seize(other)
                val seizeR = r.seizeTrigger(queue = q)
                val restored = a.restoredTrigger()
                val result = waitForFirst(seizeR, restored, timeout = 2.0)
                log.add("W ${if (result.trigger === restored) "restored" else if (result.trigger == null) "timed out" else "seized"}@$time")
                release(a)
            }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("W restored@0.0"), shop.log)
    }

    @Test
    @DisplayName("a failure due at a release is applied before a capacity change the release completes offers any unit")
    fun failureDueAtAReleaseThatCompletesACapacityChange() {
        val (shop, error) = run(configure = {
            two.capacityChangeRule = CapacityChangeRule.WAIT
            two.failures(ConstantRV(1.0), ConstantRV(5.0), ResourceFailureBasis.USES, InterruptRule.FINISH)
        }) {
            actor("H", 1) { val a = seize(two, 2); log.add("H got@$time"); delay(10.0); release(a); log.add("H released@$time failed=${two.isFailed}") }
            at(1.0) { two.changeCapacity(two.CapacityChangeNotice(capacity = 0)) }
            at(2.0) { two.changeCapacity(two.CapacityChangeNotice(capacity = 1)) }
            actor("P", 2) { delay(3.0); val a = preempt(two, rule = InterruptRule.RESUME); log.add("P got@$time failed=${two.isFailed}"); delay(1.0); release(a); log.add("P done@$time") }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("H got@0.0", "H released@10.0 failed=true", "P got@15.0 failed=false", "P done@16.0"), shop.log)
    }

    @Test
    @DisplayName("a preemptor given its allocation in this instant has left its queue: there is nothing to move")
    fun moveOfAPreemptorWithAPassedAllocation() {
        val (shop, error) = run {
            lateinit var p: Shop.Actor
            actor("H", 1) {
                val a = seize(r); delay(5.0); release(a)
                // in the step of the release, which has just given P its allocation of R
                log.add("queued=${p.isQueuedIn(r.myWaitingQ)}")
                log.add("moved=${p.reroute(r, target)}")
            }
            p = actor("P", 2) { delay(1.0); val a = preempt(r, rule = InterruptRule.RESUME); log.add("P got ${a.resource.name}@$time"); release(a) }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("queued=false", "moved=false", "P got R@5.0"), shop.log)
    }

    @Test
    @DisplayName("a wait terminated while its registrations are released records no wait for any of them")
    fun terminationRecordsNoWait() {
        for (signalFirst in listOf(false, true)) {
            val (shop, error) = run {
                val w = actor("W") {
                    val two = items.itemsTrigger(2)
                    val one = items.itemsTrigger(1)
                    val sig = signal.trigger()
                    if (signalFirst) waitForFirst(sig, two, one) else waitForFirst(two, one, sig)
                    log.add("W returned@$time")
                }
                at(1.0) { items.sendToChannel(Item("a")) }
                at(2.0) { w.terminateProcess() }
            }
            assertNull(error, "signalFirst=$signalFirst: the run stopped: $error")
            assertEquals(emptyList(), shop.log, "signalFirst=$signalFirst")
            assertEquals(0.0, shop.signal.waitingQ.timeInQ.withinReplicationStatistic.count, "signalFirst=$signalFirst: signal waits recorded")
        }
    }

    @Test
    @DisplayName("a seize trigger whose arrival lets an earlier trigger of the same wait win is withdrawn as a loser")
    fun laterSeizeTriggerWakesAnEarlierOne() {
        val (shop, error) = run {
            actor("W") {
                val first = pair.seizeTrigger(queue = pairQ)
                val second = pair.seizeTrigger(queue = pairQ)
                val result = waitForFirst(first, second)
                val a = if (result.firedBy(first)) result.valueOf(first) else result.valueOf(second)
                log.add("W ${if (result.trigger === first) "first" else "second"} ${a.amount}@$time waiting=${pairQ.size} busy=${pair.numBusy}")
                delay(1.0)
                release(a)
            }
            // nothing of the wait is left in the queue, to be served later for a wait that has ended
            at(5.0) { log.add("after@$time waiting=${pairQ.size} busy=${pair.numBusy}") }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("W first 1@0.0 waiting=0 busy=1", "after@5.0 waiting=0 busy=0"), shop.log)
    }

    @Test
    @DisplayName("an items trigger the queue would fill at once, ahead of a request waiting, wins over a later ready trigger")
    fun itemsTriggerFilledAheadOfAWaitingRequest() {
        for (withRuleSeize in listOf(false, true)) {
            val (shop, error) = run {
                actor("X") {
                    // waits for more than the queue will hold, ahead of W
                    waitForItems(anyFillable, 5)
                    log.add("X returned@$time")
                }
                actor("W") {
                    delay(1.0)
                    val a = seize(other)
                    val take = anyFillable.itemsTrigger(1)
                    val restored = a.restoredTrigger()
                    val triggers = if (withRuleSeize) listOf(r.seizeTrigger(queue = q), take, restored) else listOf(take, restored)
                    val result = waitForFirst(triggers)
                    log.add("W ${if (result.firedBy(take)) "items" else if (result.firedBy(restored)) "restored" else "other"}@$time")
                    release(a)
                }
                actor("H") { val a = seize(r); delay(50.0); release(a) }
                at(0.5) { anyFillable.sendToChannel(Item("a")) }
            }
            assertNull(error, "withRuleSeize=$withRuleSeize: the run stopped: $error")
            assertEquals(listOf("W items@1.0"), shop.log, "withRuleSeize=$withRuleSeize")
        }
    }

    @Test
    @DisplayName("a release that frees enough for two seize triggers of one wait serves the first and withdraws the other")
    fun releaseServingTwoTriggersOfOneWait() {
        val (shop, error) = run {
            actor("H", 1) { val a = seize(three, 3); delay(5.0); release(a) }
            actor("W") {
                delay(1.0)
                val one = three.seizeTrigger(1, queue = threeQ)
                val two = three.seizeTrigger(2, queue = threeQ)
                val result = waitForFirst(one, two)
                val a = if (result.firedBy(one)) result.valueOf(one) else result.valueOf(two)
                log.add("W ${if (result.firedBy(one)) "one" else "two"} ${a.amount}@$time waiting=${three.myWaitingQ.size} busy=${three.numBusy}")
                delay(1.0)
                release(a)
            }
            actor("V") {
                delay(6.0)
                val a = seize(three, 3, queue = threeQ)
                log.add("V got ${a.amount}@$time")
                release(a)
            }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("W one 1@5.0 waiting=0 busy=1", "V got 3@6.0"), shop.log)
    }
}
