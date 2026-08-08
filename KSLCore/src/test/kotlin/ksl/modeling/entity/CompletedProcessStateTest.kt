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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Gate E1. Termination now leaves the entity in `ProcessEnded`; normal completion did not, and left
 *  it `Active` — a state the textbook defines as "executing non-suspending code within its process",
 *  which a finished entity is not. Re-activation worked from there only because `Active` happens to
 *  implement `schedule()`.
 *
 *  With completion routed through the same state, both ways a process can end agree, and
 *  `Entity.isProcessEnded` becomes a property that can actually be true — it never could be before
 *  this effort, because nothing anywhere called `processEnded()`.
 *
 *  Nothing outside `ProcessModel` reads `isActive` or `isProcessEnded`, which is what made this
 *  cheap. What it must not disturb is the two ways an entity carries on after finishing a process:
 *  the process sequence, and a fresh process activated from the `afterRunningProcess` hook.
 */
class CompletedProcessStateTest {

    private fun <T : ProcessModel> simulate(name: String, factory: (Model) -> T): T {
        val m = Model(name)
        val p = factory(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        return p
    }

    // ── The state after a normal completion ─────────────────────────────────

    private class SingleProcessModel(parent: ModelElement) : ProcessModel(parent, null) {
        lateinit var worker: Worker
        var disposed = false

        inner class Worker : Entity() {
            val work = process("work") { delay(10.0) }
        }

        override fun dispose(entity: Entity) {
            disposed = true
        }

        override fun initialize() {
            disposed = false
            worker = Worker()
            activate(worker.work)
        }
    }

    @Test
    @DisplayName("E1: an entity whose process completed is left in ProcessEnded, not Active")
    fun completedEntityIsInProcessEnded() {
        val m = simulate("completedState") { SingleProcessModel(it) }
        assertTrue(m.worker.isProcessEnded, "isProcessEnded must be true after a normal completion")
        assertFalse(m.worker.isActive, "the entity is no longer executing anything")
        assertFalse(m.worker.hasCurrentProcess, "completion clears the binding, as it always has")
        assertFalse(m.worker.isSuspended, "and a finished entity is not suspended")
        assertTrue(m.disposed, "the disposal path must still run")
    }

    // ── The process sequence still runs ─────────────────────────────────────

    private class SequenceModel(parent: ModelElement) : ProcessModel(parent, null) {
        lateinit var worker: Worker
        val order = mutableListOf<String>()

        inner class Worker : Entity() {
            val first = process("first") {
                delay(5.0)
                order.add("first")
            }
            val second = process("second") {
                delay(5.0)
                order.add("second")
            }
            val third = process("third") {
                delay(5.0)
                order.add("third")
            }
        }

        override fun initialize() {
            order.clear()
            worker = Worker()
            worker.useProcessSequence = true
            worker.processSequence = mutableListOf(worker.first, worker.second, worker.third)
            startProcessSequence(worker)
        }
    }

    @Test
    @DisplayName("E1: the process-sequence path still runs every process in order")
    fun processSequenceStillRuns() {
        val m = simulate("sequence") { SequenceModel(it) }
        assertEquals(
            listOf("first", "second", "third"), m.order,
            "each completion transitions through ProcessEnded and must still accept the next schedule()",
        )
        assertTrue(m.worker.isProcessEnded, "the entity ends the sequence in ProcessEnded")
    }

    // ── A fresh process from the afterRunningProcess hook still works ───────

    /**
     *  The supported pattern from `ksl.examples.general.misc.TestProcessActivation`: the hook runs
     *  *after* the state transition, so it must find an entity that accepts `schedule()`.
     */
    private class HookActivationModel(parent: ModelElement) : ProcessModel(parent, null) {
        lateinit var worker: Worker
        var secondRan = false

        inner class Worker : Entity() {
            val first = process("first") { delay(5.0) }
            fun second(): KSLProcess = process("second") {
                delay(5.0)
                secondRan = true
            }

            override fun afterRunningProcess(completedProcess: KSLProcess) {
                if (completedProcess.name == "first") activate(second())
            }
        }

        override fun initialize() {
            secondRan = false
            worker = Worker()
            activate(worker.first)
        }
    }

    @Test
    @DisplayName("E1: a fresh process activated from afterRunningProcess still works")
    fun freshProcessFromHookStillActivates() {
        val m = simulate("hookActivation") { HookActivationModel(it) }
        assertTrue(m.secondRan, "the hook runs after the transition and must still be able to schedule")
    }
}
