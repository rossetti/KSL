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
import ksl.modeling.entity.ResourcePoolWithQ
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.Model
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A waiting preemptor moved to another resource preempts there as one arriving there would, including one moved in
 *  the instant it was woken, before it resumed.
 */
class MovedPreemptorTest {

    private class Shop(model: Model, val newcomerThroughPool: Boolean) : ProcessModel(model, "MovedPreemptorShop") {
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
                val request = sourceQueue.peekNext()!! as ResourceRequest
                moveRequestToResource(request, sourceQueue, target, RELEASE_PRIORITY)
            }
        }
        private val sourceHolder = object : Entity("SourceHolder") {
            init { priority = 10 }
            val work = process {
                val a = seize(source, amountNeeded = 2)
                delay(1.0)
                release(a)
                // the release completed the urgent preemptor's reservation and woke it; it has not resumed yet
                urgent.reroute()
                freeAfterMove = target.numAvailableUnits
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
                delay(2.0)
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
     *  `FINISH` and reserves them. At 1 the release completes the reservation and wakes Urgent; in that instant the
     *  holder moves Urgent's request to Target, where a holder of priority 10 has 1 of 2 units until 4. Urgent decides
     *  at Target as one arriving there: it reserves the idle unit, so a newcomer (priority 5) seizing at 2 waits. Urgent
     *  starts at 4 and releases at 5, and the newcomer starts at 5.
     */
    @Test
    @DisplayName("a FINISH preemptor moved in the instant it was woken reserves at the new resource (newcomer through a pool)")
    fun movedWokenFinishPreemptorReservesThroughPool() {
        val shop = run(newcomerThroughPool = true)
        assertEquals(4.0, shop.urgentStart)
        assertEquals(5.0, shop.newcomerStart)
    }

    /** As above, with the newcomer seizing Target directly. */
    @Test
    @DisplayName("a FINISH preemptor moved in the instant it was woken reserves at the new resource (newcomer direct)")
    fun movedWokenFinishPreemptorReservesDirect() {
        val shop = run(newcomerThroughPool = false)
        assertEquals(4.0, shop.urgentStart)
        assertEquals(5.0, shop.newcomerStart)
    }
}
