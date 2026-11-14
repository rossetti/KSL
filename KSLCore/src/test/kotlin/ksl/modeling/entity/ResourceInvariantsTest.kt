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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/** The invariant checks: switched on, they catch broken accounting after the event that broke it. */
class ResourceInvariantsTest {

    private val wasEnabled = ResourceInvariants.enabled

    @AfterEach
    fun restore() {
        ResourceInvariants.enabled = wasEnabled
    }

    /** A resource used by one customer, whose accounting is broken on purpose at time 5. */
    private class Broken(parent: ModelElement) : ProcessModel(parent, "broken") {
        val r = ResourceWithQ(this, "R")

        inner class Customer : Entity() {
            val run: KSLProcess = process("customer") { val a = seize(r); delay(10.0); release(a) }
        }

        override fun initialize() {
            activate(Customer().run)
            schedule({ _: KSLEvent<Nothing> -> r.myPromisedUnits += 1 }, 5.0)
        }
    }

    private fun runBroken() {
        val model = Model("Broken${System.nanoTime()}", autoCSVReports = false)
        Broken(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()
    }

    @Test
    @DisplayName("switched on, a unit promised to no request is caught after the event that promised it")
    fun onCatchesBrokenAccounting() {
        ResourceInvariants.enabled = true
        val error = assertFailsWith<RuntimeException> { runBroken() }
        val messages = generateSequence<Throwable>(error) { it.cause }.joinToString(" | ") { it.message ?: "" }
        assertContains(messages, "1 units are promised, but the requests in its queues hold 0")
    }

    @Test
    @DisplayName("switched off, nothing is checked")
    fun offChecksNothing() {
        ResourceInvariants.enabled = false
        runBroken()
    }
}
