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

import ksl.modeling.entity.Allocation
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceInvariants
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.Signal
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 *  The resource checks of the waits tutorial's "Checking your own model" (`ksl-waits-tutorial.md`): with
 *  `-Dksl.checkInvariants=true` every resource checks, after each event, the units busy, the units promised to
 *  waiting requests, and the waits registered on allocations. The first two are checked elsewhere; this class
 *  checks the third, a wait on an allocation's trigger still listed after the wait has ended, and that a model
 *  using an allocation's triggers correctly passes the checks.
 */
class WaitsCheckingYourOwnModelCasesTest {

    private val wasEnabled = ResourceInvariants.enabled

    @AfterEach
    fun restore() {
        ResourceInvariants.enabled = wasEnabled
    }

    /**
     *  H holds R from 0 to 10. From 1, W waits for the first of H's allocation being interrupted and a signal. At 5,
     *  when `breakIt`, W's wait is closed on purpose without withdrawing its registration from the allocation, as a
     *  registration left out of its wait would be.
     */
    private class Watch(parent: ModelElement, val breakIt: Boolean) : ProcessModel(parent, "Watch") {
        val r = ResourceWithQ(this, "R")
        val go = Signal(this, "Go")
        var held: Allocation? = null

        inner class Holder : Entity() {
            val run: KSLProcess = process("holder") { val a = seize(r); held = a; delay(10.0); release(a) }
        }

        inner class Watcher : Entity() {
            val run: KSLProcess = process("watcher") {
                delay(1.0)
                waitForFirst(held!!.interruptedTrigger(), go.trigger(), timeout = 3.0)
            }
        }

        override fun initialize() {
            held = null
            activate(Holder().run)
            activate(Watcher().run)
            if (breakIt) {
                schedule({ _: KSLEvent<Nothing> ->
                    held!!.myTriggerRegistrations.single().group.closeOnTermination()
                }, 2.0)
            }
        }
    }

    private fun simulate(breakIt: Boolean) {
        val model = Model("Watch${System.nanoTime()}", autoCSVReports = false)
        Watch(model, breakIt)
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()
    }

    @Test
    @DisplayName("switched on, an allocation still listing a wait that has ended is caught after the event that ended it")
    fun allocationListingAnEndedWaitIsCaught() {
        ResourceInvariants.enabled = true
        val error = assertFailsWith<RuntimeException> { simulate(breakIt = true) }
        val messages = generateSequence<Throwable>(error) { it.cause }.joinToString(" | ") { it.message ?: "" }
        assertContains(messages, "at time 2.0")
        assertContains(messages, "still lists a wait that has already ended")
    }

    @Test
    @DisplayName("switched on, a wait on an allocation's trigger that times out leaves nothing listed, and nothing is caught")
    fun allocationTriggerWaitPassesTheChecks() {
        ResourceInvariants.enabled = true
        val error = try {
            simulate(breakIt = false); null
        } catch (e: Throwable) {
            e
        }
        assertNull(error, "the checks stopped a correct model: $error")
    }
}
