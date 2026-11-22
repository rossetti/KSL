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
import kotlin.test.assertTrue

/**
 *  A waiting preemptor that needs more units than are free gives way only to a request that is more urgent, or
 *  equally urgent and ahead of it in its own queue by the queue's discipline. A request behind it does not hold
 *  it back, even though the preemptor, needing to take from a holder, is not the request the free units would
 *  serve next.
 *
 *  In each case: R has 2 units. H (priority 1) holds one from 0 to 10, L (priority 9) the other from 0 to 50. P
 *  (priority 3) calls preempt for 2 units under RESUME at 1 and waits, since H cannot be preempted. S (priority 3, the same
 *  queue) seizes 1 unit at 2. At 10 H releases: the freed unit and L's unit serve P when P is ahead of S.
 */
class PreemptorQueueOrderTest {

    private class Shop(parent: ModelElement, discipline: Queue.Discipline) : ProcessModel(parent, "Shop") {
        val q = RequestQ(this, "RQ", discipline)
        val r = ResourceWithQ(this, "R", capacity = 2, queue = q)
        val log = mutableListOf<String>()

        /** Which of P and S the queue's discipline reaches first, read just before H releases. */
        var firstByOrder: String? = null

        inner class Actor(tag: String, prio: Int, body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process(isDefaultProcess = true, block = body)
        }

        fun actor(tag: String, prio: Int, body: suspend KSLProcessBuilder.() -> Unit) = activate(Actor(tag, prio, body).p)

        fun job(tag: String, from: Double, hold: Double, prio: Int) = actor(tag, prio) {
            delay(from)
            val a = seize(r)
            log.add("$tag got@$time")
            delay(hold)
            release(a)
        }

        override fun initialize() {
            log.clear()
            firstByOrder = null
            job("H", 0.0, 10.0, 1)
            job("L", 0.0, 50.0, 9)
            actor("P", 3) {
                delay(1.0)
                val a = preempt(r, 2, rule = InterruptRule.RESUME)
                log.add("P got@$time")
                delay(5.0)
                release(a)
            }
            job("S", 2.0, 30.0, 3)
            actor("watch", 5) {
                delay(9.5)
                val first = q.firstMatching { it.entity.name == "P" || it.entity.name == "S" }
                firstByOrder = first?.entity?.name
            }
        }
    }

    private fun run(discipline: Queue.Discipline, configure: Shop.() -> Unit = {}): Shop {
        val model = Model("PreemptorQueueOrder${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model, discipline)
        shop.configure()
        model.numberOfReplications = 1
        model.lengthOfReplication = 200.0
        model.simulate()
        return shop
    }

    @Test
    @DisplayName("FIFO: an equally urgent request that entered after the preemptor does not hold it back")
    fun fifoRequestBehindDoesNotHoldItBack() {
        val shop = run(Queue.Discipline.FIFO)
        assertTrue("P got@10.0" in shop.log, "P is ahead of S: it takes the freed unit and L's at 10; log = ${shop.log}")
        assertTrue("S got@10.0" !in shop.log, "S waits behind P; log = ${shop.log}")
    }

    @Test
    @DisplayName("LIFO: the equally urgent request that entered later is ahead, and the preemptor gives way to it")
    fun lifoRequestAheadIsServedFirst() {
        val shop = run(Queue.Discipline.LIFO)
        assertTrue("S got@10.0" in shop.log, "under LIFO S is ahead of P and takes the freed unit; log = ${shop.log}")
        assertTrue("P got@40.0" in shop.log, "P waits until S releases; log = ${shop.log}")
    }

    @Test
    @DisplayName("ranked, equal priorities: entry order decides, so the preemptor is ahead")
    fun rankedEqualPrioritiesFollowEntryOrder() {
        val shop = run(Queue.Discipline.RANKED)
        assertTrue("P got@10.0" in shop.log, "log = ${shop.log}")
    }

    @Test
    @DisplayName("random: whichever of the two the queue's kept draw reaches first is served first")
    fun randomFollowsTheKeptDraw() {
        repeat(20) {
            val shop = run(Queue.Discipline.RANDOM)
            val first = shop.firstByOrder
            if (first == "P") {
                assertTrue("P got@10.0" in shop.log, "P first in the draw; log = ${shop.log}")
            } else {
                assertEquals("S", first)
                assertTrue("S got@10.0" in shop.log, "S first in the draw; log = ${shop.log}")
            }
        }
    }

    @Test
    @DisplayName("a request selection rule that would choose the request behind: the preemptor, ahead by the discipline, still goes first")
    fun selectionRuleDoesNotChangeWhoIsAhead() {
        val shop = run(Queue.Discipline.FIFO) {
            // serves S ahead of everything else, and otherwise the first request that fits, in entry order
            q.requestSelectionRule = RequestSelectionRuleIfc { resource, requestQ ->
                val fits = requestQ.filter { it.amountRequested <= resource.numAvailableUnits }
                (fits.filter { it.entity.name == "S" } + fits.filter { it.entity.name != "S" }).take(1)
            }
        }
        assertTrue("P got@10.0" in shop.log, "log = ${shop.log}")
    }
}
