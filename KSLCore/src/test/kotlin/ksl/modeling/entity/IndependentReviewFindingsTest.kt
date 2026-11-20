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
        val items = BlockingQueue<Item>(this, name = "Items")
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
                val request = from.myWaitingQ.firstOrNull { it.entity === this } as ResourceRequest
                if (!canMoveRequestToResource(request)) return false
                moveRequestToResource(request, from.myWaitingQ, to, RESUME_PRIORITY)
                return true
            }

            /** Moves the request without checking first. */
            fun rerouteUnchecked(from: ResourceWithQ, to: ResourceWithQ) {
                val request = from.myWaitingQ.firstOrNull { it.entity === this } as ResourceRequest
                moveRequestToResource(request, from.myWaitingQ, to, RESUME_PRIORITY)
            }
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
    @DisplayName("a preemptor given its allocation in this instant is not moved: the check says so, and the move is refused")
    fun moveOfAPreemptorWithAPassedAllocation() {
        val (shop, error) = run {
            lateinit var p: Shop.Actor
            actor("H", 1) {
                val a = seize(r); delay(5.0); release(a)
                // in the step of the release, which has just passed P its allocation of R
                log.add("moved=${p.reroute(r, target)}")
                val refused = runCatching { p.rerouteUnchecked(r, target) }.exceptionOrNull()
                log.add("refused=${refused is IllegalArgumentException}")
            }
            p = actor("P", 2) { delay(1.0); val a = preempt(r, rule = InterruptRule.RESUME); log.add("P got ${a.resource.name}@$time"); release(a) }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("moved=false", "refused=true", "P got R@5.0"), shop.log)
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
}
