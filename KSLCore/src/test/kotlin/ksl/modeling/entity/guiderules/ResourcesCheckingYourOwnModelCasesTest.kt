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

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceInvariants
import ksl.modeling.entity.ResourcePoolWithQ
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

/**
 *  A check of the resources tutorial's "Checking your own model" (`ksl-resources-tutorial.md`): each resource and
 *  blocking queue checks that every request of a `waitForFirst` belongs to a wait that is still open. A resource's
 *  own queue and a blocking queue are checked elsewhere; this class checks a request waiting in a pool's queue, which
 *  the pool's members check.
 */
class ResourcesCheckingYourOwnModelCasesTest {

    private val wasEnabled = ResourceInvariants.enabled

    @AfterEach
    fun restore() {
        ResourceInvariants.enabled = wasEnabled
    }

    /**
     *  Both rooms of a pool are held from 0 to 10. From 1, W waits for the first of a room from the pool and a signal.
     *  At 5 its wait is closed on purpose without withdrawing what it placed, as a registration left out of its wait
     *  would be: its request stays in the pool's queue for a wait that has ended.
     */
    private class Rooms(parent: ModelElement) : ProcessModel(parent, "Rooms") {
        val a = ResourceWithQ(this, "A")
        val b = ResourceWithQ(this, "B")
        val pool = ResourcePoolWithQ(this, listOf(a, b), name = "P")
        val go = Signal(this, "Go")

        inner class Holder(val r: ResourceWithQ) : Entity() {
            val run: KSLProcess = process("holder") { val x = seize(r); delay(10.0); release(x) }
        }

        inner class Waiter : Entity() {
            val run: KSLProcess = process("waiter") {
                delay(1.0)
                waitForFirst(pool.seizeTrigger(), go.trigger())
            }
        }

        override fun initialize() {
            activate(Holder(a).run)
            activate(Holder(b).run)
            activate(Waiter().run)
            schedule({ _: KSLEvent<Nothing> ->
                pool.myWaitingQ.peekFirst()!!.registration!!.group.closeOnTermination()
            }, 5.0)
        }
    }

    @Test
    @DisplayName("switched on, a pool request left in the pool's queue after its wait ended is caught")
    fun poolRequestOfAnEndedWaitIsCaught() {
        ResourceInvariants.enabled = true
        val model = Model("PoolLeftBehind${System.nanoTime()}", autoCSVReports = false)
        Rooms(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        val error = assertFailsWith<RuntimeException> { model.simulate() }
        val messages = generateSequence<Throwable>(error) { it.cause }.joinToString(" | ") { it.message ?: "" }
        assertContains(messages, "at time 5.0")
        assertContains(messages, "waits in P:Q for a wait that has already ended")
    }
}
