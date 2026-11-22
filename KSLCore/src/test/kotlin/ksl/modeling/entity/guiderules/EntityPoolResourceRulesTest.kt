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

package ksl.modeling.entity.guiderules

import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.RequestSelectionRuleIfc
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ResourcePool
import ksl.modeling.entity.ResourcePoolWithQ
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.queue.Queue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Rules of the entity guide's section on pool resources: a request selection rule's list is the order of
 *  service, a rule built by filtering the queue keeps the queue's list order (not its discipline), the rule's
 *  choices for a member come before its choices for the member's pools, and a pool seize trigger of
 *  `waitForFirst` is served at once only when its queue would choose it next.
 */
class EntityPoolResourceRulesTest {

    /**
     *  A one-unit resource with a LIFO queue. H holds it from 0 to 10; A asks at 1 and B at 2, each holding for 5.
     *  With no rule the discipline serves B, the last in, at 10. A rule that filters the queue returns the
     *  requests in the queue's list order, A then B, so A is served at 10, against the LIFO discipline.
     */
    private fun lifoShop(rule: RequestSelectionRuleIfc?): List<String> {
        val m = RuleModel("LifoFilter")
        val q = RequestQ(m, "Q", Queue.Discipline.LIFO)
        q.requestSelectionRule = rule
        val r = ResourceWithQ(m, "R", capacity = 1, queue = q)
        m.plan {
            for ((tag, from, hold) in listOf(Triple("H", 0.0, 10.0), Triple("A", 1.0, 5.0), Triple("B", 2.0, 5.0))) {
                actor(tag) {
                    delay(from)
                    val a = seize(r)
                    log.add("$tag got@$time")
                    delay(hold)
                    release(a)
                }
            }
        }
        return m.simulate(100.0).log
    }

    @Test
    @DisplayName("LIFO queue, no rule: the discipline serves the last request in first")
    fun lifoWithoutRuleFollowsTheDiscipline() {
        assertEquals(listOf("H got@0.0", "B got@10.0", "A got@15.0"), lifoShop(null))
    }

    @Test
    @DisplayName("LIFO queue, a rule that filters the queue: served in the queue's list order, not its discipline")
    fun filterRuleServesInListOrder() {
        val rule = RequestSelectionRuleIfc { resource, requestQ ->
            requestQ.filter { it.resource === resource && it.amountRequested <= resource.numAvailableUnits }
        }
        assertEquals(listOf("H got@0.0", "A got@10.0", "B got@15.0"), lifoShop(rule))
    }

    /**
     *  M, one unit, belongs to pool P; both are seized through one FIFO queue. H holds M from 0 to 10. PR asks
     *  for the pool at 1, MR for M itself at 2, each holding for 5. With no rule, the discipline serves PR first.
     *  With a rule that chooses, for a resource or pool, the requests naming it, the rule's choices for M (MR)
     *  come before its choices for P (PR) when M's unit comes free.
     */
    private fun memberAndPoolShop(rule: RequestSelectionRuleIfc?): List<String> {
        val m = RuleModel("MemberBeforePool")
        val q = RequestQ(m, "Q")
        q.requestSelectionRule = rule
        val member = Resource(m, "M", capacity = 1)
        val pool = ResourcePool(m, listOf(member), "P")
        m.plan {
            actor("H") {
                val a = seize(member, queue = q)
                delay(10.0)
                release(a)
            }
            actor("PR") {
                delay(1.0)
                val a = seize(pool, queue = q)
                log.add("PR got@$time")
                delay(5.0)
                release(a)
            }
            actor("MR") {
                delay(2.0)
                val a = seize(member, queue = q)
                log.add("MR got@$time")
                delay(5.0)
                release(a)
            }
        }
        return m.simulate(100.0).log
    }

    @Test
    @DisplayName("no rule: the pool request that waited longest gets the member's freed unit")
    fun memberAndPoolWithoutRule() {
        assertEquals(listOf("PR got@10.0", "MR got@15.0"), memberAndPoolShop(null))
    }

    @Test
    @DisplayName("with a rule: its choices for the member come before its choices for the member's pool")
    fun ruleChoicesForTheMemberComeFirst() {
        val rule = RequestSelectionRuleIfc { resource, requestQ -> requestQ.filter { it.resource === resource } }
        assertEquals(listOf("MR got@10.0", "PR got@15.0"), memberAndPoolShop(rule))
    }

    /**
     *  Pool P of two one-unit members, with its own queue. A1 and A2 each hold one unit, A1 to 5 and A2 to 30. B
     *  asks the pool for 2 units at 1 and waits at the head of the queue. At 6, with one unit free, T waits for
     *  the first of a seize trigger of P for 1 unit and a timeout of 4. With no rule, the queue would choose T
     *  (B cannot be served), so T is served at once. With a rule that serves strictly from the head, the queue
     *  would choose B, so T is not served and times out at 10, as a plain pool seize does; B is served at 30.
     */
    private fun poolTriggerShop(strictHead: Boolean): List<String> {
        val m = RuleModel("PoolTrigger")
        val q = RequestQ(m, "PQ")
        if (strictHead) {
            q.requestSelectionRule = RequestSelectionRuleIfc { resource, requestQ ->
                listOfNotNull(requestQ.firstOrNull { it.resource === resource })
            }
        }
        val pool = ResourcePoolWithQ(m, listOf(Resource(m, "M1"), Resource(m, "M2")), q, "P")
        m.plan {
            for ((tag, hold) in listOf("A1" to 5.0, "A2" to 30.0)) {
                actor(tag) {
                    val a = seize(pool)
                    delay(hold)
                    release(a)
                }
            }
            actor("B") {
                delay(1.0)
                val a = seize(pool, 2)
                log.add("B got@$time")
                delay(5.0)
                release(a)
            }
            actor("T") {
                delay(6.0)
                val trigger = pool.seizeTrigger(1)
                val res = waitForFirst(trigger, timeout = 4.0)
                if (res.timedOut) {
                    log.add("T timeout@$time")
                } else {
                    log.add("T seized@$time")
                    release(res.valueOf(trigger))
                }
            }
            actor("U") {
                delay(6.0)
                val res = seizeOrTimeout(pool, 1, timeout = 4.0)
                if (res.timedOut) log.add("U timeout@$time") else {
                    log.add("U seized@$time")
                    release(res.value)
                }
            }
        }
        return m.simulate(100.0).log
    }

    @Test
    @DisplayName("no rule: a pool seize trigger the queue would choose next is served at once")
    fun poolTriggerServedAtOnceWhenChosen() {
        assertEquals(listOf("T seized@6.0", "U seized@6.0", "B got@30.0"), poolTriggerShop(strictHead = false))
    }

    @Test
    @DisplayName("a rule that would choose the request ahead: the pool seize trigger is not served at once, as a pool seize is not")
    fun poolTriggerWaitsItsTurnUnderARule() {
        assertEquals(listOf("T timeout@10.0", "U timeout@10.0", "B got@30.0"), poolTriggerShop(strictHead = true))
    }
}
