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
 *  A request woken with a promise can find, when it resumes, that the units promised to it are gone: the
 *  capacity was cut in the same instant. It withdraws its promise and waits again. The units it withdrew
 *  are then free, and must be offered to the requests behind it, as when a waiting request leaves.
 */
class BrokenPromiseTest {

    private class Cut(parent: ModelElement, rule: CapacityChangeRule) : ProcessModel(parent, "cut") {
        val r = ResourceWithQ(this, "R", capacity = 2)
        val served = mutableMapOf<String, Double>()

        init {
            r.capacityChangeRule = rule
        }

        inner class Holder : Entity() {
            val p: KSLProcess = process("holder") {
                val a = seize(r, 2)
                delay(5.0)
                release(a)
                r.changeCapacity(r.CapacityChangeNotice(capacity = 1, duration = Double.POSITIVE_INFINITY))
            }
        }

        inner class Customer(val tag: String, val amount: Int, val arrive: Double) : Entity() {
            val p: KSLProcess = process("customer") {
                delay(arrive)
                val a = seize(r, amount)
                served[tag] = time
                delay(1.0)
                release(a)
            }
        }

        override fun initialize() {
            served.clear()
            activate(Holder().p)
            activate(Customer("R1", 2, 1.0).p)
            activate(Customer("R2", 1, 2.0).p)
        }
    }

    @Test
    @DisplayName("a promise broken by a capacity cut in the same instant: the withdrawn unit goes to the request behind")
    fun withdrawnUnitsAreOfferedOn() {
        val model = Model("Broken${System.nanoTime()}", autoCSVReports = false)
        val m = Cut(model, CapacityChangeRule.IGNORE)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        // R1 needs two units and the capacity is now one: it is never served
        assertEquals(mapOf("R2" to 5.0), m.served)
    }
}
