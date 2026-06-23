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

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Batch 3 audit fixes for the statechart subsystem:
 *   - H3: an `onCondition` trigger is evaluated even when nothing else
 *     in the model schedules an event (a zero-delay bootstrap event
 *     forces the executive's condition sweep).
 *   - M4: a transition requested from a final state's entry action is
 *     honored, not silently discarded by finalization.
 *   - M5: swapping the active chart via `useStatechart` tears the
 *     outgoing chart down cleanly and selects the new one.
 */
class StatechartHardeningTest {

    // ── H3: onCondition fires without any other event traffic ─────────────────

    private class ConditionOnlyModel(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        var fired = false

        // A lone agent whose only behavior is a condition trigger — no
        // process, no timeouts, no other agents to generate events.
        inner class Sentinel : Agent("sentinel") {
            init {
                statechart {
                    initial("waiting")
                    state("waiting") {
                        onCondition({ true }) {
                            fired = true
                            transitionTo("fired")
                        }
                    }
                    state("fired") {}
                }
            }
        }

        val sentinel = Sentinel()
    }

    @Test
    fun onConditionFiresWithoutOtherEventTraffic() {
        val model = Model("CondBootstrapTest")
        val m = ConditionOnlyModel(model, "condonly")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        assertTrue(m.fired, "onCondition must be evaluated even with no other event traffic")
        assertEquals("fired", m.sentinel.statechart!!.currentStateName)
    }

    // ── M4: transition from a final state's entry action is honored ───────────

    private class FinalTransitionModel(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        var completionFired = false
        var reachedRescued = false

        inner class Worker : Agent("worker") {
            init {
                statechart {
                    initial("start")
                    state("start") { onTimeout(1.0) { transitionTo("finish") } }
                    final("finish") {
                        // Contradictory but explicit: transition out of the
                        // final state on entry. Must be honored, not dropped.
                        onEntry { transitionTo("rescued") }
                    }
                    state("rescued") {
                        onEntry { reachedRescued = true }
                    }
                    onCompletion { completionFired = true }
                }
            }
        }

        val worker = Worker()
    }

    @Test
    fun transitionFromFinalStateEntryIsHonored() {
        val model = Model("FinalTransitionTest")
        val m = FinalTransitionModel(model, "finaltrans")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        assertTrue(m.reachedRescued, "the entry-action transition out of the final state must run")
        assertEquals("rescued", m.worker.statechart!!.currentStateName)
        assertFalse(
            m.completionFired,
            "completion must NOT fire when the final state's entry transitioned away",
        )
    }

    // ── M5: useStatechart swaps cleanly ───────────────────────────────────────

    private class SwapModel(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        var ranA = false
        var ranB = false

        inner class Chooser : Agent("chooser") {
            init {
                val a = buildStatechart("A") {
                    initial("go"); state("go") { onEntry { ranA = true } }
                }
                val b = buildStatechart("B") {
                    initial("go"); state("go") { onEntry { ranB = true } }
                }
                useStatechart(a)  // select A (not started yet — model isn't running)
                useStatechart(b)  // swap to B: A's defensive teardown is a no-op
            }
        }

        val chooser = Chooser()
    }

    @Test
    fun useStatechartSwapSelectsNewChartCleanly() {
        val model = Model("SwapTest")
        val m = SwapModel(model, "swap")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 1
        model.simulate()

        assertTrue(m.ranB, "the finally-selected chart B should run")
        assertFalse(m.ranA, "the swapped-out chart A must not have started")
        assertEquals("go", m.chooser.statechart!!.currentStateName)
    }
}
