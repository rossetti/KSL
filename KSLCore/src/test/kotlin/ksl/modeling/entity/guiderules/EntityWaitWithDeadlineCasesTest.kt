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

import ksl.modeling.entity.BlockingQueue
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.WaitResult
import ksl.modeling.queue.Queue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Cases of the entity guide's section on waiting with a deadline: each timed form of a blocking queue receive takes
 *  the untimed form's `blockingPriority`, which ranks its request in a `RANKED` request queue as it would rank the
 *  untimed one; and the any-items form, timing out beside items its predicate rejects, takes nothing, and its wait is
 *  recorded for its whole length.
 */
class EntityWaitWithDeadlineCasesTest {

    /**
     *  Q's request queue is `RANKED`. At 0, U (priority 5) waits, untimed, for one item. At 1, T (priority 5) waits for
     *  one item with a timed [form], passing `blockingPriority` 1, so its request ranks ahead of U's. Items come at 2
     *  and 3: T takes the first, U the second.
     */
    private fun blockingPriorityRanks(
        form: suspend KSLProcessBuilder.(BlockingQueue<RuleModel.Item>) -> WaitResult<List<RuleModel.Item>>
    ): List<String> {
        val m = RuleModel("TimedBlockingPriority")
        val q = BlockingQueue<RuleModel.Item>(m, name = "Q")
        q.requestQ.initialDiscipline = Queue.Discipline.RANKED
        m.plan {
            actor("U") {
                val got = waitForItems(q, 1)
                log.add("U $got@$time")
            }
            actor("T") {
                delay(1.0)
                val res = form(q)
                log.add("T ${res.valueOrNull()}@$time")
            }
            at(2.0) { q.sendToChannel(Item("i1")) }
            at(3.0) { q.sendToChannel(Item("i2")) }
        }
        return m.simulate(50.0).log
    }

    @Test
    @DisplayName("waitForItemsOrTimeout takes blockingPriority, which ranks its request as for waitForItems")
    fun waitForItemsOrTimeoutBlockingPriority() {
        assertEquals(
            listOf("T [i1]@2.0", "U [i2]@3.0"),
            blockingPriorityRanks { q -> waitForItemsOrTimeout(q, 20.0, 1, blockingPriority = 1) }
        )
    }

    @Test
    @DisplayName("waitForAnyItemsOrTimeout takes blockingPriority, which ranks its request as for waitForAnyItems")
    fun waitForAnyItemsOrTimeoutBlockingPriority() {
        assertEquals(
            listOf("T [i1]@2.0", "U [i2]@3.0"),
            blockingPriorityRanks { q -> waitForAnyItemsOrTimeout(q, 20.0, { true }, blockingPriority = 1) }
        )
    }

    @Test
    @DisplayName("q.waitForOrTimeout takes blockingPriority, which ranks its request as for q.waitFor")
    fun queueWaitForOrTimeoutBlockingPriority() {
        assertEquals(
            listOf("T [i1]@2.0", "U [i2]@3.0"),
            blockingPriorityRanks { q -> q.waitForOrTimeout(20.0, 1, blockingPriority = 1) }
        )
    }

    @Test
    @DisplayName("q.waitForAnyOrTimeout takes blockingPriority, which ranks its request as for q.waitForAny")
    fun queueWaitForAnyOrTimeoutBlockingPriority() {
        assertEquals(
            listOf("T [i1]@2.0", "U [i2]@3.0"),
            blockingPriorityRanks { q -> q.waitForAnyOrTimeout(20.0, { true }, blockingPriority = 1) }
        )
    }

    /**
     *  Q holds a red item from 0. At 1, W waits up to 5 for any blue items. Nothing blue comes, so W times out at 6:
     *  the red item stays in the channel, W's request leaves the request queue, and its wait of 5 is recorded once.
     */
    @Test
    @DisplayName("the any-items form timing out beside items it rejects takes nothing, and its wait is recorded for its length")
    fun anyItemsTimeoutTakesNothing() {
        val m = RuleModel("AnyItemsTimeout")
        val q = BlockingQueue<RuleModel.Item>(m, name = "Q")
        m.plan {
            q.sendToChannel(Item("red"))
            actor("W") {
                delay(1.0)
                val res = waitForAnyItemsOrTimeout(q, 5.0, { it.tag == "blue" })
                log.add("W ${if (res.timedOut) "timed out" else "got ${res.value}"}@$time waited=${res.timeWaited}")
            }
            at(7.0) {
                val st = q.requestQ.timeInQ.withinReplicationStatistic
                log.add("t7 channel=${q.channelQ.size} requests=${q.requestQ.size} waits=${st.count} total=${st.weightedSum}")
            }
        }
        m.simulate(50.0)
        assertEquals(listOf("W timed out@6.0 waited=5.0", "t7 channel=1 requests=0 waits=1.0 total=5.0"), m.log)
    }
}
