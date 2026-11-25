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

import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.RequestSelectionRuleIfc
import ksl.modeling.entity.ResourcePoolWithQ
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.Model
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A waiting preemptor moved to another resource preempts there as one arriving there would. A preemptor its resource
 *  has given its units has left its queue, and takes them when it resumes.
 */
class MovedPreemptorTest {

    /** Urgent's request is moved from Source to Target at 0.5, while it waits. */
    private class Shop(
        model: Model,
        val newcomerThroughPool: Boolean,
        val newcomerDelay: Double = 2.0
    ) : ProcessModel(model, "MovedPreemptorShop") {
        val sourceQueue = RequestQ(this, "SourceQueue")
        val source = ResourceWithQ(this, "Source", capacity = 2, queue = sourceQueue)
        val target = ResourceWithQ(this, "Target", capacity = 2)
        val targetPool = ResourcePoolWithQ(this, listOf(target), name = "TargetPool")
        var urgentStart = Double.NaN
        var newcomerStart = Double.NaN
        var freeAfterMove = -1

        private val urgent = object : Entity("Urgent") {
            init { priority = 1 }
            val work = process {
                delay(0.1)
                val a = preempt(source, amountNeeded = 2, rule = InterruptRule.FINISH)
                urgentStart = time
                delay(1.0)
                release(a)
            }

            fun reroute() {
                val request = sourceQueue.first { it.entity === this } as ResourceRequest
                moveRequestToResource(request, sourceQueue, target, RELEASE_PRIORITY)
            }
        }
        private val sourceHolder = object : Entity("SourceHolder") {
            init { priority = 10 }
            val work = process {
                val a = seize(source, amountNeeded = 2)
                delay(0.5)
                // Urgent reserved both units at 0.1 and waits for them
                urgent.reroute()
                freeAfterMove = target.numAvailableUnits
                delay(0.5)
                release(a)
            }
        }
        private val targetHolder = object : Entity("TargetHolder") {
            init { priority = 10 }
            val work = process {
                val a = seize(target)
                delay(4.0)
                release(a)
            }
        }
        private val newcomer = object : Entity("Newcomer") {
            init { priority = 5 }
            val work = process {
                delay(newcomerDelay)
                if (newcomerThroughPool) {
                    val a = seize(targetPool)
                    newcomerStart = time
                    delay(10.0)
                    release(a)
                } else {
                    val a = seize(target)
                    newcomerStart = time
                    delay(10.0)
                    release(a)
                }
            }
        }

        override fun initialize() {
            activate(sourceHolder.work)
            activate(targetHolder.work)
            activate(urgent.work)
            activate(newcomer.work)
        }
    }

    private fun run(newcomerThroughPool: Boolean): Shop {
        val model = Model("MovedPreemptor$newcomerThroughPool", autoCSVReports = false)
        val shop = Shop(model, newcomerThroughPool)
        model.lengthOfReplication = 20.0
        model.simulate()
        return shop
    }

    /**
     *  Source has 2 units, held until 1 by a holder of priority 10. Urgent (priority 1) preempts both at 0.1 under
     *  `FINISH` and reserves them. At 0.5, while it waits, the holder moves Urgent's request to Target, where a holder of
     *  priority 10 has 1 of 2 units until 4. Urgent decides at Target as one arriving there: it reserves the idle unit,
     *  so a newcomer (priority 5) seizing at 2 waits. Urgent starts at 4 and releases at 5, and the newcomer starts at 5.
     */
    @Test
    @DisplayName("a waiting FINISH preemptor moved to another resource reserves there (newcomer through a pool)")
    fun movedWaitingFinishPreemptorReservesThroughPool() {
        val shop = run(newcomerThroughPool = true)
        assertEquals(4.0, shop.urgentStart)
        assertEquals(5.0, shop.newcomerStart)
    }

    /** As above, with the newcomer seizing Target directly. */
    @Test
    @DisplayName("a waiting FINISH preemptor moved to another resource reserves there (newcomer direct)")
    fun movedWaitingFinishPreemptorReservesDirect() {
        val shop = run(newcomerThroughPool = false)
        assertEquals(4.0, shop.urgentStart)
        assertEquals(5.0, shop.newcomerStart)
    }

    /**
     *  A FINISH preemptor whose reservation completes is given its allocation then, whatever its queue's selection rule,
     *  and holds it from that instant. R (2 units, `RESUME`) has a rule serving the most urgent first, as many as the
     *  free units allow. L and K (priority 9) hold 1 each; B (priority 4) preempts K's unit at 1 for 4 of work. F
     *  (priority 2, `FINISH`) reserves 1 at 2. At 3 L releases, completing F's reservation: F is given the unit and starts
     *  at 3, and the failure L then causes meets it as a holder.
     */
    @Test
    @DisplayName("a FINISH preemptor whose reservation completes is given its allocation then, whatever the queue's rule")
    fun completedReservationIsAllocatedWhateverTheRule() {
        val model = Model("PassedAllocationTaken", autoCSVReports = false)
        val shop = object : ProcessModel(model, "PassedShop") {
            val queue = RequestQ(this, "RQueue").also { q ->
                q.requestSelectionRule = RequestSelectionRuleIfc { resource, requestQ ->
                    var left = resource.numAvailableUnits
                    requestQ.sortedBy { it.entity.priority }
                        .filter { r -> (r.amountRequested <= left).also { if (it) left -= r.amountRequested } }
                }
            }
            val r = ResourceWithQ(this, "R", capacity = 2, queue = queue).also { it.interruptRule = InterruptRule.RESUME }
            var fStart = Double.NaN
            private val l = object : Entity("L") {
                init { priority = 9 }
                val work = process {
                    val a = seize(r)
                    delay(3.0)
                    release(a)
                    r.fail()
                    delay(1.0)
                    r.repair()
                }
            }
            private val k = object : Entity("K") {
                init { priority = 9 }
                val work = process { delay(0.1); val a = seize(r); delay(100.0); release(a) }
            }
            private val b = object : Entity("B") {
                init { priority = 4 }
                val work = process { delay(1.0); val a = preempt(r, 1); delay(4.0); release(a) }
            }
            private val f = object : Entity("F") {
                init { priority = 2 }
                val work = process {
                    delay(2.0)
                    val a = preempt(r, 1, rule = InterruptRule.FINISH)
                    fStart = time
                    delay(1.0)
                    release(a)
                }
            }
            private val s = object : Entity("S") {
                init { priority = 1 }
                val work = process { delay(3.5); val a = seize(r); delay(100.0); release(a) }
            }

            override fun initialize() {
                for (e in listOf(l.work, k.work, b.work, f.work, s.work)) activate(e)
            }
        }
        model.lengthOfReplication = 30.0
        model.simulate()
        assertEquals(3.0, shop.fStart)
    }
}

