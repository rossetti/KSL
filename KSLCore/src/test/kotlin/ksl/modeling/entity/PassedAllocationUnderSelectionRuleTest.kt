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
 *  A waiting preemptor given its allocation leaves its queue when it resumes; under a request selection rule, the
 *  units still free are then offered again, as when a woken seize leaves, so the request the rule chooses next is
 *  served at once rather than at the next release.
 */
class PassedAllocationUnderSelectionRuleTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        // serves, in queue order, every request the free units cover
        val q = RequestQ(this, "Q").also { q ->
            q.requestSelectionRule = RequestSelectionRuleIfc { resource, requestQ ->
                var left = resource.numAvailableUnits
                requestQ.filter { r -> (r.amountRequested <= left).also { if (it) left -= r.amountRequested } }
            }
        }
        val r = ResourceWithQ(this, "R", capacity = 1, queue = q)
        val got = mutableListOf<String>()

        init {
            r.capacityChangeRule = CapacityChangeRule.WAIT
        }

        inner class Job(n: String, p: Int, val start: Double, val work: Double, val preempts: Boolean) : Entity(n) {
            init {
                priority = p
            }

            val s = process {
                delay(start)
                val a = if (preempts) preempt(r, 1, rule = InterruptRule.RESUME) else seize(r)
                got.add("$name@$time")
                delay(work)
                release(a)
            }
        }

        override fun initialize() {
            // the capacity goes to 0 once the holder's unit is back (at 1), and to 2 when that change ends (at 2); the
            // preemptors find nothing to take meanwhile and wait, and the increase gives each a unit
            activate(Job("H", 5, 0.0, 1.0, false).s)
            schedule(::changes, 0.0, priority = KSLEvent.DEFAULT_PRIORITY + 1)
            activate(Job("P1", 1, 1.0, 1.0, true).s)
            activate(Job("P2", 2, 1.5, 1.0, true).s)
        }

        private fun changes(e: KSLEvent<Nothing>) {
            r.changeCapacity(r.CapacityChangeNotice(0, 1.0))
            r.changeCapacity(r.CapacityChangeNotice(2, 1.0))
        }
    }

    private fun run(): List<String> {
        val model = Model("PassedUnderRule${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        model.lengthOfReplication = 10.0
        val failure = runCatching { model.simulate() }.exceptionOrNull()
        assertNull(failure, "the run stopped: $failure")
        return shop.got
    }

    @Test
    @DisplayName("two waiting preemptors given the units of a capacity increase both start then")
    fun capacityIncrease() {
        assertEquals(listOf("H@0.0", "P1@2.0", "P2@2.0"), run())
    }
}
