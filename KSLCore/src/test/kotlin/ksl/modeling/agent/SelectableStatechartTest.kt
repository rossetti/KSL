/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.agent

import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Phase 7 tests: selectable statecharts (`buildStatechart` /
 *  `useStatechart` / `isStarted` / `stopStatechart`) and final-state
 *  semantics (`final` / `onCompletion`).
 */
class SelectableStatechartTest {

    // ── Construction-time variant selection ────────────────────────────────

    /**
     *  Two design variants built at construction; the model selects
     *  one via a parameter. Variant A times out after 1.0; variant B
     *  after 3.0. We verify the agent followed the selected variant by
     *  checking which final state it reached and when.
     */
    private class VariantModel(parent: ModelElement, val useVariantB: Boolean) :
        AgentModel(parent, "variant") {

        var completedState: String? = null
        var completedAt: Double = Double.NaN

        inner class Worker : Agent("worker") {
            val designA = buildStatechart("A") {
                initial("running")
                state("running") { onTimeout(1.0) { transitionTo("doneA") } }
                final("doneA")
                onCompletion { name ->
                    completedState = name
                    completedAt = currentTime
                }
            }
            val designB = buildStatechart("B") {
                initial("running")
                state("running") { onTimeout(3.0) { transitionTo("doneB") } }
                final("doneB")
                onCompletion { name ->
                    completedState = name
                    completedAt = currentTime
                }
            }

            init {
                useStatechart(if (useVariantB) designB else designA)
            }
        }

        val worker = Worker()
    }

    @Test
    fun constructionTimeSelectionPicksVariantA() {
        val model = Model("variantA")
        val tm = VariantModel(model, useVariantB = false)
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()
        assertEquals("doneA", tm.completedState)
        assertEquals(1.0, tm.completedAt, 1e-9)
    }

    @Test
    fun constructionTimeSelectionPicksVariantB() {
        val model = Model("variantB")
        val tm = VariantModel(model, useVariantB = true)
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()
        assertEquals("doneB", tm.completedState)
        assertEquals(3.0, tm.completedAt, 1e-9)
    }

    // ── Between-run variant switching on ONE instance ───────────────────────

    /**
     *  The Controls-package workflow: construct once, select a variant
     *  via a settable parameter, run, switch, run again — same model
     *  instance, model not running between simulate() calls.
     */
    private class SwitchableModel(parent: ModelElement) : AgentModel(parent, "switch") {
        var lastCompletion: String? = null

        inner class Worker : Agent("worker") {
            val fast = buildStatechart("fast") {
                initial("go"); state("go") { onTimeout(1.0) { transitionTo("end") } }
                final("end"); onCompletion { lastCompletion = "fast" }
            }
            val slow = buildStatechart("slow") {
                initial("go"); state("go") { onTimeout(5.0) { transitionTo("end") } }
                final("end"); onCompletion { lastCompletion = "slow" }
            }
            init { useStatechart(fast) }
        }
        val worker = Worker()

        fun selectFast() = worker.useStatechart(worker.fast)
        fun selectSlow() = worker.useStatechart(worker.slow)
    }

    @Test
    fun sameInstanceRunWithDifferentVariantsAcrossSimulateCalls() {
        val model = Model("switchable")
        val tm = SwitchableModel(model)
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1

        tm.selectFast()
        model.simulate()
        assertEquals("fast", tm.lastCompletion)

        // Model not running now — switch the variant on the same instance.
        tm.selectSlow()
        model.simulate()
        assertEquals("slow", tm.lastCompletion)
    }

    // ── useStatechart guard ─────────────────────────────────────────────────

    @Test
    fun useStatechartThrowsWhileChartIsRunning() {
        val model = Model("guard-model")
        var caught: Throwable? = null
        val tm = object : AgentModel(model, "guard-am") {
            inner class Worker : Agent("worker") {
                val a = buildStatechart("A") {
                    initial("s"); state("s") { onTimeout(100.0) { transitionTo("s") } }
                }
                val b = buildStatechart("B") {
                    initial("t"); state("t") { onTimeout(100.0) { transitionTo("t") } }
                }
                val script: KSLProcess = process(isDefaultProcess = true) {
                    useStatechart(a)        // starts immediately (model running)
                    delay(1.0)
                    try {
                        useStatechart(b)    // a is still running → should throw
                    } catch (e: Throwable) {
                        caught = e
                    }
                }
            }
            val worker = Worker()
            override fun initialize() {
                super.initialize()
                activate(worker.script)
            }
        }
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()
        assertTrue(caught is IllegalStateException, "expected IllegalStateException; got $caught")
    }

    // ── isStarted reflects lifecycle ───────────────────────────────────────

    @Test
    fun isStartedTracksStartAndStop() {
        val model = Model("isStarted")
        val tm = object : AgentModel(model, "is") {
            var startedMidRun: Boolean = false
            inner class Worker : Agent("worker") {
                val chart = buildStatechart("c") {
                    initial("s"); state("s") { onTimeout(100.0) { transitionTo("s") } }
                }
                val script: KSLProcess = process(isDefaultProcess = true) {
                    useStatechart(chart)
                    delay(1.0)
                    startedMidRun = chart.isStarted
                    stopStatechart()
                }
            }
            val worker = Worker()
            override fun initialize() {
                super.initialize()
                activate(worker.script)
            }
        }
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()
        assertTrue(tm.startedMidRun, "chart should be started mid-run")
        assertFalse(tm.worker.chart.isStarted, "chart should be stopped after stopStatechart()")
    }

    // ── Final states ───────────────────────────────────────────────────────

    @Test
    fun finalStateStopsChartAndFiresOnCompletion() {
        val model = Model("final")
        val tm = object : AgentModel(model, "fin") {
            var completionFired: String? = null
            var stoppedAfterFinal: Boolean = false
            inner class Worker : Agent("worker") {
                init {
                    statechart {
                        initial("running")
                        state("running") { onTimeout(2.0) { transitionTo("complete") } }
                        final("complete")
                        onCompletion { name ->
                            completionFired = name
                            stoppedAfterFinal = statechart?.isStarted == false
                        }
                    }
                }
            }
            val worker = Worker()
        }
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()
        assertEquals("complete", tm.completionFired)
        assertTrue(tm.stoppedAfterFinal, "chart should already be stopped when onCompletion fires")
        assertEquals("complete", tm.worker.statechart!!.currentStateName)
        assertFalse(tm.worker.statechart!!.isStarted)
    }

    @Test
    fun multipleFinalStatesReportWhichWasReached() {
        // A chart that branches to one of two final states based on a flag.
        val model = Model("multifinal")
        val tm = object : AgentModel(model, "mf") {
            var reached: String? = null
            var goSuccess = true
            inner class Worker : Agent("worker") {
                init {
                    statechart {
                        initial("deciding")
                        state("deciding") {
                            // Timeout drives the sim forward and fires the branch.
                            onTimeout(1.0) {
                                transitionTo(if (goSuccess) "success" else "aborted")
                            }
                        }
                        final("success")
                        final("aborted")
                        onCompletion { name -> reached = name }
                    }
                }
            }
            val worker = Worker()
        }
        tm.goSuccess = false
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()
        assertEquals("aborted", tm.reached)
    }

    // ── Re-activation: run chart A to completion, then chart B ─────────────

    @Test
    fun reActivationWithDifferentChartAfterCompletion() {
        val model = Model("reactivate")
        val tm = object : AgentModel(model, "re") {
            val completions = mutableListOf<String>()
            inner class Worker : Agent("worker") {
                // chartB is installed from chartA's onCompletion.
                val chartB = buildStatechart("B") {
                    initial("running")
                    state("running") { onTimeout(1.0) { transitionTo("doneB") } }
                    final("doneB")
                    onCompletion { name -> completions.add(name) }
                }
                val chartA = buildStatechart("A") {
                    initial("running")
                    state("running") { onTimeout(1.0) { transitionTo("doneA") } }
                    final("doneA")
                    onCompletion { name ->
                        completions.add(name)
                        // Chart A is already stopped here; install and start B.
                        useStatechart(chartB)
                    }
                }
                init { useStatechart(chartA) }
            }
            val worker = Worker()
        }
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()
        // A completes at t=1 (doneA), then B is installed and completes at t=2 (doneB).
        assertEquals(listOf("doneA", "doneB"), tm.completions)
    }

    // ── Validation ──────────────────────────────────────────────────────────

    @Test
    fun initialStateCannotBeFinal() {
        val model = Model("badinitial")
        val tm = object : AgentModel(model, "bi") {
            inner class Worker : Agent("worker")
            val worker = Worker()
        }
        assertThrows<IllegalArgumentException> {
            tm.worker.buildStatechart("bad") {
                initial("done")
                final("done")
            }
        }
    }

    @Test
    fun duplicateOnCompletionThrows() {
        val model = Model("dupcompletion")
        val tm = object : AgentModel(model, "dc") {
            inner class Worker : Agent("worker")
            val worker = Worker()
        }
        assertThrows<IllegalArgumentException> {
            tm.worker.buildStatechart("bad") {
                initial("s")
                state("s") { onTimeout(1.0) { transitionTo("done") } }
                final("done")
                onCompletion { }
                onCompletion { }   // second declaration
            }
        }
    }

    // ── End-of-replication cleanup of a never-stopped transient chart ──────

    @Test
    fun signalDoesNotReachStoppedChartAfterCompletion() {
        // A chart subscribes to a signal, then reaches a final state
        // (auto-stop). Firing the signal afterward must NOT invoke the
        // handler — confirming the subscription was torn down.
        val model = Model("nosignalleak")
        val tm = object : AgentModel(model, "leak") {
            val signal = AgentSignal("poke")
            var pokesAfterCompletion = 0
            var completed = false
            inner class Worker : Agent("worker") {
                init {
                    statechart {
                        initial("running")
                        state("running") {
                            onSignal(signal) { pokesAfterCompletion += 1 }
                            onTimeout(1.0) { transitionTo("done") }
                        }
                        final("done")
                        onCompletion { completed = true }
                    }
                }
            }
            val worker = Worker()
            inner class Poker : Agent("poker") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    delay(2.0)          // after the worker's chart has completed at t=1
                    signal.fire()
                    signal.fire()
                }
            }
            val poker = Poker()
            override fun initialize() {
                super.initialize()
                activate(poker.script)
            }
        }
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()
        assertTrue(tm.completed)
        assertEquals(0, tm.pokesAfterCompletion, "signal must not reach a chart that reached its final state")
    }
}
