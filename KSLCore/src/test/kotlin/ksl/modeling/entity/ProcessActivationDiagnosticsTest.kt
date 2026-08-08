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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Gate D11 of the agent-package hardening effort: a spent `KSLProcess` re-activated should say so
 *  where it happens, and say what to do instead.
 *
 *  A `KSLProcess` is one-shot — once completed or terminated it cannot run again, and repeating
 *  behavior means a new process instance. The rule is clear and consistently applied across the
 *  textbook, but the *diagnostic* was not: the two ways to break it failed differently, and neither
 *  message named the cause.
 *
 *   - A **completed** process passed both of `activate`'s checks, scheduled its activation event, and
 *     failed later from inside that event with "Tried to start process ... from an illegal state:
 *     Completed" — detached in simulated time from the call that caused it.
 *   - A **terminated** process failed at `activate` with "the entity is already running a process",
 *     which is not true of a terminated process. `myCurrentProcess` is cleared only on successful
 *     completion (`afterSuccessfulProcessCompletion`), so a terminated entity is still bound to the
 *     process that unwound, and the pre-existing check reported that binding as "running".
 *
 *  Both now fail at the `activate` call with a message naming the process, its end state, and the
 *  remedy. Finding H-12 is why this matters: five of eleven shipped agent examples broke this rule
 *  by holding a driver's process in a model field and re-activating it every replication.
 */
class ProcessActivationDiagnosticsTest {

    // ── Fixtures ────────────────────────────────────────────────────────────

    /**
     *  Runs one process to its end, then tries to activate something at [actionTime].
     *  [endByTermination] chooses how the first process ends; [reuseSame] chooses whether the
     *  second activation reuses the spent instance or supplies a fresh one.
     */
    private class Reactivator(
        parent: ModelElement,
        val endByTermination: Boolean,
        val reuseSame: Boolean,
    ) : ProcessModel(parent, null) {

        var failure: String? = null
        var succeeded: Boolean = false
        var boundProcessWasSpent: Boolean = false

        inner class Worker : Entity() {
            // Held in a property so the same instance can be handed to activate() twice — the
            // shape that H-12 found in five examples.
            val first: KSLProcess = process("first") { delay(if (endByTermination) 100.0 else 1.0) }
            fun second(): KSLProcess = process("second") { delay(1.0) }
        }

        lateinit var worker: Worker

        override fun initialize() {
            worker = Worker()
            activate(worker.first)
            schedule(::act, 5.0)
        }

        private fun act(event: KSLEvent<Nothing>) {
            if (endByTermination) worker.terminateProcess()
            val cp = worker.currentProcess
            boundProcessWasSpent = cp != null && (cp.isTerminated || cp.isCompleted)
            try {
                activate(if (reuseSame) worker.first else worker.second())
                succeeded = true
            } catch (e: IllegalStateException) {
                // Caught here, at the call site — which is the point of the change. Before it, the
                // completed case threw from inside a later activation event instead.
                failure = e.message
            }
        }
    }

    /** The H-12 shape: a driver entity built once, its one-shot process re-activated every replication. */
    private class FieldHeldDriver(parent: ModelElement) : ProcessModel(parent, null) {
        inner class Driver : Entity() {
            val loop: KSLProcess = process("loop") {
                while (true) {
                    delay(1.0)
                }
            }
        }

        // Built at model-construction time, so there is exactly one process object for the model's
        // whole lifetime — no matter how many replications run.
        private val driver = Driver()

        override fun initialize() {
            activate(driver.loop)
        }
    }

    private fun run(endByTermination: Boolean, reuseSame: Boolean): Reactivator {
        val model = Model("activationDiagnostics")
        val m = Reactivator(model, endByTermination, reuseSame)
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()
        return m
    }

    // ── The two ways to re-activate a spent process ─────────────────────────

    @Test
    @DisplayName("D11: re-activating a completed process fails at the activate call, not later")
    fun reactivatingCompletedProcessFailsAtTheCall() {
        val m = run(endByTermination = false, reuseSame = true)
        assertTrue(!m.succeeded, "activating a completed process must not succeed")
        val msg = assertNotNull(m.failure, "the failure must surface at the activate() call")
        assertContains(msg, "one-shot")
        assertContains(msg, "already completed")
        assertContains(msg, "first", message = "the message should name the process")
    }

    @Test
    @DisplayName("D11: re-activating a terminated process reports termination, not 'already running'")
    fun reactivatingTerminatedProcessReportsTermination() {
        val m = run(endByTermination = true, reuseSame = true)
        assertTrue(!m.succeeded, "activating a terminated process must not succeed")
        val msg = assertNotNull(m.failure)
        assertContains(msg, "one-shot")
        assertContains(msg, "been terminated")
        assertTrue(
            !msg.contains("already running a process"),
            "the old message described a terminated process as running; that is what this gate fixes",
        )
    }

    // ── The guard against over-tightening ───────────────────────────────────

    /**
     *  A *fresh* process instance after a completed one is legal and used — see
     *  `ksl.examples.general.misc.TestProcessActivation`, which activates a new process from
     *  `afterRunningProcess`. The new check is on the process being activated, not on the entity,
     *  so this must keep working. Without this test the change could quietly forbid a supported
     *  pattern and every other test would still pass.
     */
    @Test
    @DisplayName("D11: a fresh process instance after completion still activates")
    fun freshProcessAfterCompletionStillActivates() {
        val m = run(endByTermination = false, reuseSame = false)
        assertTrue(m.succeeded, "a new process instance on an entity whose process completed is legal")
        assertNull(m.failure)
    }

    // ── H-15: what a terminated entity can and cannot do ────────────────────

    /**
     *  Documents current behavior rather than endorsing it (finding H-15). After a termination the
     *  entity remains bound to the terminated process, so even a *fresh* process cannot be activated
     *  on it. The message now says that, instead of claiming the entity is running something.
     *
     *  If H-15 is later decided in favor of releasing the entity, this test is the one that changes,
     *  and it is deliberately worded so that the change is obvious rather than silent.
     */
    @Test
    @DisplayName("H-15: after termination the entity stays bound, so even a fresh process is refused")
    fun terminatedEntityRefusesEvenAFreshProcess() {
        val m = run(endByTermination = true, reuseSame = false)
        assertTrue(m.boundProcessWasSpent, "the entity is still bound to the terminated process")
        assertTrue(!m.succeeded, "current behavior: no process can be activated on a terminated entity")
        val msg = assertNotNull(m.failure)
        assertContains(msg, "still bound to it")
        assertContains(msg, "new entity", message = "the message should state the available remedy")
    }

    // ── The H-12 shape, end to end ──────────────────────────────────────────

    /**
     *  One replication of the field-held driver is fine — which is exactly why nothing caught H-12:
     *  every shipped example's `main()` sets `numberOfReplications = 1`.
     */
    @Test
    @DisplayName("D11/H-12: a field-held driver process runs fine for one replication")
    fun fieldHeldDriverRunsForOneReplication() {
        val model = Model("fieldDriverOne")
        FieldHeldDriver(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        model.simulate()
        assertEquals(1, model.currentReplicationNumber)
    }

    @Test
    @DisplayName("D11/H-12: the same driver across replications now names the process and the remedy")
    fun fieldHeldDriverAcrossReplicationsNamesTheRemedy() {
        val model = Model("fieldDriverTwo")
        FieldHeldDriver(model)
        model.numberOfReplications = 2
        model.lengthOfReplication = 10.0
        val e = runCatching { model.simulate() }.exceptionOrNull()
        val ex = assertNotNull(e, "replication 2 re-activates a spent process and must fail")
        val msg = assertNotNull(ex.message)
        assertContains(msg, "one-shot")
        assertContains(msg, "loop", message = "the message should name the offending process")
        assertContains(msg, "later replication", message = "and point at the replication-boundary cause")
    }
}
