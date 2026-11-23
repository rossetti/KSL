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

/**
 *  Waiting preemptors, request selection rules and the fast path of a wait, where two paths that should decide
 *  alike are checked against each other.
 */
class ResourceReviewFindingsTest {

    private class Shop(parent: ModelElement, capacity: Int, rule: RequestSelectionRuleIfc?) : ProcessModel(parent, "Shop") {
        val q = RequestQ(this, "Q").apply { requestSelectionRule = rule }
        val r = ResourceWithQ(this, "R", capacity = capacity, queue = q)
        val otherQ = RequestQ(this, "OtherQ")
        val q2 = RequestQ(this, "Q2")
        val r2 = ResourceWithQ(this, "R2", queue = q2)
        val log = mutableListOf<String>()
        val setups = mutableListOf<Shop.() -> Unit>()

        init {
            r.registerCapacityChangeQueue(otherQ)
        }

        inner class Actor(tag: String, prio: Int, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process(isDefaultProcess = true) { body() }
        }

        fun actor(tag: String, priority: Int = 5, body: suspend KSLProcessBuilder.() -> Unit) =
            Actor(tag, priority, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        fun job(tag: String, from: Double, hold: Double, priority: Int = 5, n: Int = 1, queue: RequestQ? = null) =
            actor(tag, priority) {
                delay(from)
                val a = if (queue == null) seize(r, n) else seize(r, n, queue = queue)
                log.add("$tag got@$time")
                delay(hold)
                release(a)
            }

        fun urgent(tag: String, from: Double, hold: Double, priority: Int, n: Int = 1) =
            actor(tag, priority) {
                delay(from)
                val a = preempt(r, n, rule = InterruptRule.RESUME)
                log.add("$tag got@$time")
                delay(hold)
                release(a)
            }

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(capacity: Int, rule: RequestSelectionRuleIfc? = null, setup: Shop.() -> Unit): Shop {
        val model = Model("ReviewFindings${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model, capacity, rule)
        shop.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        return shop
    }

    @Test
    @DisplayName("a waiting preemptor needing fewer units than are freed is compared with the request next in line, not one further back")
    fun preemptorNeedingFewerThanFreed() {
        val shop = run(2) {
            job("H", 0.0, 4.0, priority = 1, n = 2)
            job("L", 1.0, 5.0, priority = 10)
            job("B", 2.0, 5.0, priority = 2, queue = otherQ)
            urgent("P", 3.0, 1.0, priority = 5)
        }
        // at 4 the default offer would serve L first (entered first): P outranks L and takes a unit, then L takes the
        // other. B, more urgent than P but behind L, is served at P's release, at 5
        assertEquals(listOf("H got@0.0", "P got@4.0", "L got@4.0", "B got@5.0"), shop.log)
    }

    @Test
    @DisplayName("a request selection rule that never chooses a preemptor keeps it waiting at a later offer, as on arrival")
    fun ruleBindsAWaitingPreemptor() {
        val shop = run(2, rule = RequestSelectionRuleIfc { res, q -> q.filter { it.resource == res && it.entity.name.startsWith("X") } }) {
            urgent("P", 0.0, 1.0, priority = 1)
            job("X1", 1.0, 1.0, queue = otherQ)
        }
        assertEquals(listOf("X1 got@1.0"), shop.log)
    }

    @Test
    @DisplayName("a rule that chooses requests once two are waiting serves them both when the second arrives, beside free units")
    fun ruleDependingOnWhatIsQueued() {
        val shop = run(2, rule = RequestSelectionRuleIfc { res, q ->
            val mine = q.filter { it.resource == res }
            if (mine.size >= 2) mine else emptyList()
        }) {
            job("E1", 0.0, 1.0)
            job("E2", 1.0, 1.0)
        }
        // both are served at 1, E1 first by the rule; E2, arriving, takes its units in its own event, so its line may
        // come before E1's resume
        assertEquals(setOf("E1 got@1.0", "E2 got@1.0"), shop.log.toSet())
    }

    @Test
    @DisplayName("waitForFirst ends with the first trigger in argument order that can fire at once, whatever its queue's rule")
    fun fastPathOrderWithARule() {
        val shop = run(1, rule = RequestSelectionRuleIfc { res, q -> q.filter { it.resource == res } }) {
            actor("W") {
                val first = r.seizeTrigger(queue = q)
                val second = r2.seizeTrigger(queue = q2)
                val result = waitForFirst(first, second)
                val won = when (result.trigger) {
                    first -> "R"
                    second -> "R2"
                    else -> "nothing"
                }
                log.add("W got $won@$time")
                when (result.trigger) {
                    first -> release(result.valueOf(first))
                    second -> release(result.valueOf(second))
                    else -> Unit
                }
            }
        }
        assertEquals(listOf("W got R@0.0"), shop.log)
    }

    @Test
    @DisplayName("off shift with the capacity put back directly, a preemption takes from a less urgent holder as seizes are served")
    fun preemptionAfterCapacityPutBackOffShift() {
        val shop = run(1) {
            job("L", 0.0, 10.0, priority = 9)
            at(1.0) { r.goOffShift() }
            at(2.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 1)) }
            urgent("P", 3.0, 1.0, priority = 1)
        }
        assertEquals(listOf("L got@0.0", "P got@3.0"), shop.log)
    }
}
