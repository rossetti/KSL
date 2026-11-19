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

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A request queue's selection rule decides who is served next when a request arrives as well as when units are
 *  freed: a request the rule chooses is not left waiting beside free units behind one the rule never chooses.
 *
 *  A resource of one unit whose queue's rule selects only entities named X. H holds the unit from 0 to 10; W waits
 *  from 1, and the rule never chooses it; X arrives at 12.
 */
class RequestSelectionRuleTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val queue = RequestQ(this, "Q").apply {
            requestSelectionRule = RequestSelectionRuleIfc { _, q -> q.filter { it.entity.name.startsWith("X") } }
        }
        val r = ResourceWithQ(this, "R", queue = queue)
        val log = mutableListOf<String>()

        inner class Job(name: String, val from: Double, val hold: Double) : Entity(name) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                delay(from)
                val a = seize(r)
                log.add("${this@Job.name} got@$time")
                delay(hold)
                release(a)
            }
        }

        /** Asks for the unit from [from], giving up after [timeout]. */
        inner class Impatient(name: String, val from: Double, val timeout: Double) : Entity(name) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                delay(from)
                val result = seizeOrTimeout(r, timeout = timeout)
                if (result.timedOut) {
                    log.add("${this@Impatient.name} gave up@$time")
                } else {
                    log.add("${this@Impatient.name} got@$time")
                    release(result.value)
                }
            }
        }

        val setups = mutableListOf<Shop.() -> Unit>()
        var standard = true

        override fun initialize() {
            log.clear()
            if (!standard) {
                for (s in setups) s()
                return
            }
            // H is served before the rule matters: it is the only request when it arrives, and the rule is asked only
            // whom among waiting requests to serve; it holds the unit to 10
            activate(Job("X-H", 0.0, 10.0).p)
            activate(Job("W", 1.0, 1.0).p)
            activate(Job("X", 12.0, 1.0).p)
        }
    }

    @Test
    @DisplayName("an arrival the rule chooses takes a free unit, though a request the rule never chooses waits ahead of it")
    fun arrivalChosenByTheRule() {
        val model = Model("SelectionRule${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        // at 10 the rule chooses nobody, and the unit stays free; X arrives at 12 and takes it; W is never chosen
        assertEquals(listOf("X-H got@0.0", "X got@12.0"), shop.log)
    }

    @Test
    @DisplayName("a timed seize the rule does not choose waits as an untimed one does, though the unit is free, and gives up")
    fun timedSeizeFollowsTheRule() {
        val model = Model("SelectionRuleTimed${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        shop.standard = false
        shop.setups.add { activate(Impatient("W", 0.0, 2.0).p) }
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        assertEquals(listOf("W gave up@2.0"), shop.log)
    }
}
