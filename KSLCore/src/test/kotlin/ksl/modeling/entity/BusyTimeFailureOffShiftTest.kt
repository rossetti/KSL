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

/**
 *  Failures on the `BUSY_TIME` basis count time with a unit working, whatever the shift: a unit finishing its job
 *  under a capacity schedule's zero-capacity item and one finishing it after `goOffShift` are both working, so the
 *  two forms of going off shift give the failure at the same instant.
 *
 *  In each form R has one unit and fails after 5 units of busy time, for a repair of 1. J holds it from 0 to 10.
 *  At 2 the capacity drops to 0 under IGNORE, by a schedule's zero-capacity item or by `goOffShift`, and J goes on
 *  working. The busy time reaches 5 at 5, so R is not failed at 4.9 and is failed at 5.1.
 */
class BusyTimeFailureOffShiftTest {

    private class Shop(parent: ModelElement, bySchedule: Boolean) : ProcessModel(parent, "BusyTimeShop") {
        val r = ResourceWithQ(this, "R", capacity = 1)
        val log = mutableListOf<String>()

        init {
            r.failures(ConstantRV(5.0), ConstantRV(1.0), ResourceFailureBasis.BUSY_TIME)
            if (bySchedule) {
                val schedule = CapacitySchedule(this, startTime = 0.0, autoStartOption = true, repeatable = false)
                schedule.addItem(capacity = 1, duration = 2.0)
                schedule.addItem(capacity = 0, duration = 1000.0)
                r.useSchedule(schedule, CapacityChangeRule.IGNORE)
            }
        }

        private val goesOffByHand = !bySchedule

        inner class Job : Entity("J") {
            val p: KSLProcess = process(isDefaultProcess = true) {
                val a = seize(r)
                delay(10.0)
                release(a)
            }
        }

        private fun at(t: Double, action: () -> Unit) = schedule({ _: KSLEvent<Nothing> -> action() }, t)

        override fun initialize() {
            log.clear()
            activate(Job().p)
            if (goesOffByHand) at(2.0) { r.goOffShift() }
            for (t in listOf(4.9, 5.1)) at(t) { log.add("t$t failed=${r.isFailed}") }
        }
    }

    private fun run(bySchedule: Boolean): List<String> {
        val model = Model("BusyTimeOffShift${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model, bySchedule)
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()
        return shop.log
    }

    @Test
    @DisplayName("BUSY_TIME: a unit finishing its job under a schedule's zero-capacity item is working, and the failure comes at 5")
    fun scheduleZeroCapacityItemCountsTheFinishingJob() {
        assertEquals(listOf("t4.9 failed=false", "t5.1 failed=true"), run(bySchedule = true))
    }

    @Test
    @DisplayName("BUSY_TIME: a unit finishing its job after goOffShift is working, and the failure comes at 5")
    fun goOffShiftCountsTheFinishingJob() {
        assertEquals(listOf("t4.9 failed=false", "t5.1 failed=true"), run(bySchedule = false))
    }
}
