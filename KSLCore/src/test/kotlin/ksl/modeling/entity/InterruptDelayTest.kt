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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Interrupted delays. Before R1.8 every interrupted delay failed when it resumed (the end-of-delay check
 *  compared against the cancelled event), a restart failed even with no interrupt time, and interrupting a
 *  process that had delayed earlier but was no longer delaying failed with a misleading message.
 *
 *  In each model a worker delays 10 from time 0 in a delay named "work", and other processes act on it.
 */
class InterruptDelayTest {

    /**
     *  A worker, a process that runs [script] against this model, and optionally a process that terminates
     *  the worker or the interrupting process at [killAt].
     */
    private class Interrupted(
        parent: ModelElement,
        private val script: suspend KSLProcessBuilder.(Interrupted) -> Unit,
        private val workerDelayName: String = "work",
        private val second: (suspend KSLProcessBuilder.(Interrupted) -> Unit)? = null,
        private val killAt: Double = Double.NaN,
        private val killWorker: Boolean = true
    ) : ProcessModel(parent, null) {
        var finishedAt = Double.NaN
        var finishes = 0
        lateinit var worker: Worker
        private lateinit var interrupter: Other

        inner class Worker : Entity() {
            val work: KSLProcess = process {
                delay(10.0, suspensionName = workerDelayName)
                finishedAt = time
                finishes++
            }
        }

        inner class Other(private val body: suspend KSLProcessBuilder.(Interrupted) -> Unit) : Entity() {
            val run: KSLProcess = process { body(this@Interrupted) }
        }

        /** A new entity whose process takes 5, to interrupt with. */
        inner class Repairer : Entity() {
            val fix: KSLProcess = process { delay(5.0) }
        }

        inner class Killer : Entity() {
            val run: KSLProcess = process {
                delay(killAt)
                if (killWorker) worker.terminateProcess() else interrupter.terminateProcess()
            }
        }

        override fun initialize() {
            finishedAt = Double.NaN
            finishes = 0
            worker = Worker()
            interrupter = Other(script)
            activate(worker.work)
            activate(interrupter.run)
            second?.let { activate(Other(it).run) }
            if (!killAt.isNaN()) activate(Killer().run)
        }
    }

    private fun run(runLength: Double = 100.0, build: (Model) -> Interrupted): Pair<Interrupted, Throwable?> {
        val m = Model("Interrupt", autoCSVReports = false)
        val model = build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = runLength
        val error = runCatching { m.simulate() }.exceptionOrNull()
        return model to error
    }

    private fun finishTime(script: suspend KSLProcessBuilder.(Interrupted) -> Unit): Double {
        val (model, error) = run { Interrupted(it, script) }
        assertNull(error, "the run must not fail: ${error?.message}")
        assertEquals(1, model.finishes, "the worker finishes once")
        return model.finishedAt
    }

    // ── the time-based variants ───────────────────────────────────────────────

    @Test
    @DisplayName("Continue: interrupted at 4 for 5, the delay ends at 15")
    fun continueEndsAfterTheRemainingTime() {
        assertEquals(15.0, finishTime { delay(4.0); interruptDelayAndContinue(it.worker.work, "work", 5.0) }, 1e-9)
    }

    @Test
    @DisplayName("Restart: interrupted at 4 for 5, the whole delay runs again and ends at 19")
    fun restartRunsTheWholeDelayAgain() {
        assertEquals(19.0, finishTime { delay(4.0); interruptDelayAndRestart(it.worker.work, "work", 5.0) }, 1e-9)
    }

    @Test
    @DisplayName("No interrupt time: Continue ends at 10 and Restart at 14")
    fun zeroInterruptTime() {
        assertEquals(10.0, finishTime { delay(4.0); interruptDelayAndContinue(it.worker.work, "work", 0.0) }, 1e-9)
        assertEquals(14.0, finishTime { delay(4.0); interruptDelayAndRestart(it.worker.work, "work", 0.0) }, 1e-9)
    }

    @Test
    @DisplayName("An explicit time after the interrupt: interrupted at 4 for 5, then 2 more, ends at 11")
    fun explicitPostInterruptTime() {
        assertEquals(11.0, finishTime {
            delay(4.0)
            interruptDelay(it.worker.work, "work", 5.0, postInterruptDelayTime = 2.0)
        }, 1e-9)
    }

    // ── interrupting with a process ───────────────────────────────────────────

    @Test
    @DisplayName("With a process that takes 5: Continue ends at 15, Restart at 19")
    fun withProcessVariants() {
        assertEquals(15.0, finishTime {
            delay(4.0)
            interruptDelayWithProcessAndContinue(it.worker.work, "work", it.Repairer().fix)
        }, 1e-9)
        assertEquals(19.0, finishTime {
            delay(4.0)
            interruptDelayWithProcessAndRestart(it.worker.work, "work", it.Repairer().fix)
        }, 1e-9)
    }

    // ── repeated interrupts ───────────────────────────────────────────────────

    @Test
    @DisplayName("A second interrupt after the first has ended: Continue ends at 17, Restart at 24")
    fun secondInterruptAfterResuming() {
        assertEquals(17.0, finishTime {
            delay(4.0)
            interruptDelayAndContinue(it.worker.work, "work", 5.0)   // 4 to 9, resumes with 6 left
            delay(3.0)                                                // now 12
            interruptDelayAndContinue(it.worker.work, "work", 2.0)   // 12 to 14, 3 left
        }, 1e-9)
        assertEquals(24.0, finishTime {
            delay(4.0)
            interruptDelayAndContinue(it.worker.work, "work", 5.0)
            delay(3.0)
            interruptDelayAndRestart(it.worker.work, "work", 2.0)    // 12 to 14, then the whole 10
        }, 1e-9)
    }

    @Test
    @DisplayName("A second interrupt while the delay is frozen is ignored, and the worker resumes once")
    fun secondInterruptWhileFrozenIsIgnored() {
        val (model, error) = run {
            Interrupted(
                it,
                script = { delay(4.0); interruptDelayAndContinue(it.worker.work, "work", 5.0) },
                second = { delay(6.0); interruptDelayAndContinue(it.worker.work, "work", 5.0) }
            )
        }
        assertNull(error, "the run must not fail: ${error?.message}")
        assertEquals(15.0, model.finishedAt, 1e-9)
        assertEquals(1, model.finishes)
    }

    // ── misuse and isInDelay ─────────────────────────────────────────────────

    private fun assertMisuse(error: Throwable?, state: String) {
        assertNotNull(error, "the misuse must stop the run")
        val cause = generateSequence(error) { it.cause }.last()
        assertIs<IllegalStateException>(cause, "got ${cause::class.simpleName}: ${cause.message}")
        assertTrue(cause.message!!.contains("isInDelay"), "the message points to the check: ${cause.message}")
        assertTrue(cause.message!!.contains(state), "the message says '$state': ${cause.message}")
    }

    @Test
    @DisplayName("Interrupting a target whose delay has ended is an error that says so")
    fun interruptAfterTheDelayEndedIsAnError() {
        val (_, error) = run { Interrupted(it, { delay(12.0); interruptDelayAndContinue(it.worker.work, "work", 1.0) }) }
        assertMisuse(error, "not executing this process")
    }

    @Test
    @DisplayName("Interrupting a target that is in a delay with another name is an error that names it")
    fun interruptOfAnotherDelayIsAnError() {
        val (_, error) = run {
            Interrupted(it, { delay(4.0); interruptDelayAndRestart(it.worker.work, "work", 1.0) }, workerDelayName = "setup")
        }
        assertMisuse(error, "in delay 'setup'")
    }

    @Test
    @DisplayName("isInDelay is true only inside the named delay, and guards an interrupt")
    fun isInDelayGuardsAnInterrupt() {
        val seen = mutableListOf<Boolean>()
        val (model, error) = run {
            Interrupted(it, {
                val w = it.worker.work
                delay(4.0)
                seen += w.isInDelay("work")
                seen += w.isInDelay("setup")
                if (w.isInDelay("work")) interruptDelayAndContinue(w, "work", 5.0) else delay(5.0)
                delay(10.0)                                   // now 19; the worker finished at 15
                seen += w.isInDelay("work")
                if (w.isInDelay("work")) interruptDelayAndContinue(w, "work", 5.0) else delay(5.0)
            })
        }
        assertNull(error, "the guarded calls never misuse: ${error?.message}")
        assertEquals(listOf(true, false, false), seen)
        assertEquals(15.0, model.finishedAt, 1e-9)
    }

    // ── edge cases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A delay that ends after the run's end can be interrupted")
    fun delayEndingAfterTheRunCanBeInterrupted() {
        val (model, error) = run(runLength = 8.0) {
            Interrupted(it, { delay(4.0); interruptDelayAndContinue(it.worker.work, "work", 2.0) })
        }
        assertNull(error, "the run must not fail: ${error?.message}")
        assertTrue(model.finishedAt.isNaN(), "the worker had not finished when the run ended")
    }

    @Test
    @DisplayName("A target terminated while frozen is not rescheduled")
    fun targetTerminatedWhileFrozen() {
        val (model, error) = run {
            Interrupted(it, { delay(4.0); interruptDelayAndContinue(it.worker.work, "work", 5.0) }, killAt = 6.0)
        }
        assertNull(error, "the run must not fail: ${error?.message}")
        assertTrue(model.finishedAt.isNaN(), "the terminated worker never finishes")
    }

    @Test
    @DisplayName("An interrupter terminated mid-interrupt lets the target resume from that moment")
    fun interrupterTerminatedMidInterrupt() {
        val (model, error) = run {
            Interrupted(
                it, { delay(4.0); interruptDelayAndContinue(it.worker.work, "work", 5.0) },
                killAt = 6.0, killWorker = false
            )
        }
        assertNull(error, "the run must not fail: ${error?.message}")
        assertEquals(12.0, model.finishedAt, 1e-9, "frozen at 4 with 6 left, released at 6")
    }
}
