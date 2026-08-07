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

package ksl.modeling.agent

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Phase A2 — semantics of *hierarchical* statecharts.
 *
 *  `AgentModel.Statechart` is a genuine LCA-based hierarchical chart:
 *  `performTransition` exits from the leaf up to (not including) the least common
 *  ancestor, then enters from below the LCA down to the new leaf. These tests pin
 *  the observable consequences of that against UML expectations, since the
 *  combinatorics of composite-state edge cases have to be *chosen* — no coverage
 *  sweep will surface them.
 *
 *  Ordering is recorded through `AgentModel.StatechartObserver`, whose contract
 *  states that events fire "for every level of the active chain (composite + leaf),
 *  in entry / exit order". That interface previously had no test of its own.
 */
class StatechartHierarchyTest {

    /** Records the observer callbacks verbatim, in order. */
    private class Recorder : AgentModel.StatechartObserver {
        val steps = mutableListOf<String>()
        override fun onStateEntered(stateName: String, time: Double) {
            steps.add("enter:$stateName")
        }
        override fun onStateExited(stateName: String, time: Double) {
            steps.add("exit:$stateName")
        }
        override fun onTransition(fromState: String, toState: String, time: Double) {
            steps.add("transition:$fromState->$toState")
        }
    }

    private fun runModel(build: (Model) -> Unit, length: Double = 12.0) {
        val m = Model("StatechartHierarchy")
        build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = length
        m.simulate()
    }

    // ── A2.1 — entry / exit ordering across nesting levels ───────────────────

    /**
     *  Three levels deep on each side, with a same-parent hop first and a
     *  cross-tree hop second, so one run exercises both LCA cases.
     */
    private class NestingModel(parent: ModelElement) : AgentModel(parent, "nesting") {
        val recorder = Recorder()
        inner class Walker : Agent("walker") {
            init {
                statechart {
                    initial("A")
                    state("A") {
                        initial("A1")
                        state("A1") {
                            initial("A1a")
                            state("A1a") { onTimeout(1.0) { transitionTo("A1b") } }
                            state("A1b") { onTimeout(1.0) { transitionTo("B1a") } }
                        }
                    }
                    state("B") {
                        initial("B1")
                        state("B1") {
                            initial("B1a")
                            state("B1a") {}
                        }
                    }
                }
                statechart?.addObserver(recorder)
            }
        }
        val walker = Walker()
    }

    @Test
    @DisplayName("A2.1: initial entry runs outermost-first down the chain")
    fun initialEntryIsOutermostFirst() {
        var m: NestingModel? = null
        runModel({ m = NestingModel(it) })
        assertEquals(
            listOf("enter:A", "enter:A1", "enter:A1a"),
            m!!.recorder.steps.take(3),
            "entering the initial chain should proceed root -> leaf",
        )
    }

    @Test
    @DisplayName("A2.1: a same-parent transition touches only the leaf")
    fun sameParentTransitionLeavesAncestorsUntouched() {
        var m: NestingModel? = null
        runModel({ m = NestingModel(it) })
        // A1a -> A1b share parent A1, so the LCA is A1 and neither A1 nor A moves.
        val window = m!!.recorder.steps.subList(3, 6)
        assertEquals(listOf("exit:A1a", "enter:A1b", "transition:A1a->A1b"), window)
        assertFalse(
            window.any { it == "exit:A1" || it == "exit:A" },
            "ancestors above the LCA must not be exited",
        )
    }

    @Test
    @DisplayName("A2.1: a cross-tree transition exits innermost-first, enters outermost-first")
    fun crossTreeTransitionOrdersExitsAndEntriesPerUml() {
        var m: NestingModel? = null
        runModel({ m = NestingModel(it) })
        val window = m!!.recorder.steps.subList(6, m!!.recorder.steps.size)
        assertEquals(
            listOf(
                "exit:A1b", "exit:A1", "exit:A",
                "enter:B", "enter:B1", "enter:B1a",
                "transition:A1b->B1a",
            ),
            window,
            "UML requires exits innermost-first and entries outermost-first, " +
                "with the transition notification last",
        )
    }

    // ── A2.2 — trigger conflict between a parent and its substate ────────────

    private class ConflictModel(parent: ModelElement, val childHandles: Boolean) :
        AgentModel(parent, "conflict") {
        val signal = AgentSignal("pulse")
        val fired = mutableListOf<String>()
        inner class Walker : Agent("walker") {
            init {
                statechart {
                    initial("P")
                    state("P") {
                        onSignal(signal) { fired.add("parentHandler"); transitionTo("ParentWon") }
                        initial("C")
                        state("C") {
                            if (childHandles) {
                                onSignal(signal) {
                                    fired.add("childHandler"); transitionTo("ChildWon")
                                }
                            }
                        }
                    }
                    state("ParentWon") {}
                    state("ChildWon") {}
                }
            }
        }
        val walker = Walker()
        override fun initialize() {
            super.initialize()
            schedule(EventActionIfc<Nothing> { signal.fire() }, 1.0)
        }
    }

    /**
     *  UML conflict resolution: when a substate and one of its ancestors both have a
     *  transition enabled by the same event, the innermost wins **and the outer
     *  transition is not taken**. The stronger half of that is that the parent's
     *  handler body must not run at all — a chart where both bodies execute would
     *  still reach the right state while producing wrong side effects.
     */
    @Test
    @DisplayName("A2.2: innermost trigger wins and the parent's handler does not run")
    fun substateTriggerWinsOverAncestorTrigger() {
        var m: ConflictModel? = null
        runModel({ m = ConflictModel(it, childHandles = true) }, length = 4.0)
        assertEquals("ChildWon", m!!.walker.statechart?.currentStateName)
        assertEquals(listOf("childHandler"), m!!.fired, "the parent handler must not execute")
    }

    /**
     *  The companion case: with no competing substate trigger, an ancestor's trigger
     *  must still fire while a substate is active. Without this, the test above
     *  would also pass if parent triggers were simply never armed.
     */
    @Test
    @DisplayName("A2.2: an ancestor trigger still fires when the substate does not handle it")
    fun ancestorTriggerFiresWhenSubstateDoesNotHandleIt() {
        var m: ConflictModel? = null
        runModel({ m = ConflictModel(it, childHandles = false) }, length = 4.0)
        assertEquals("ParentWon", m!!.walker.statechart?.currentStateName)
        assertEquals(listOf("parentHandler"), m!!.fired)
    }

    // ── A2.3 / A2.4 — re-entry, ancestor timeouts, re-entrancy ───────────────

    private class ReEntryModel(parent: ModelElement) : AgentModel(parent, "reentry") {
        val recorder = Recorder()
        inner class Walker : Agent("walker") {
            init {
                statechart {
                    initial("P")
                    state("P") {
                        initial("C1")
                        state("C1") { onTimeout(1.0) { transitionTo("C2") } }
                        state("C2") { onTimeout(1.0) { transitionTo("Away") } }
                    }
                    state("Away") { onTimeout(1.0) { transitionTo("P") } }
                }
                statechart?.addObserver(recorder)
            }
        }
        val walker = Walker()
    }

    /**
     *  Re-entering a composite state restarts it at its initial substate; there is
     *  no history semantics. Leaving P from C2 and returning must land in C1.
     */
    @Test
    @DisplayName("A2.4: re-entering a composite state resets to its initial substate")
    fun compositeReEntryResetsToInitialSubstate() {
        var m: ReEntryModel? = null
        runModel({ m = ReEntryModel(it) }, length = 3.5)
        val steps = m!!.recorder.steps
        val reentry = steps.indexOf("enter:P").let { first ->
            steps.subList(first + 1, steps.size).indexOf("enter:P") + first + 1
        }
        assertTrue(reentry > 0, "expected P to be re-entered within the run")
        assertEquals(
            "enter:C1", steps[reentry + 1],
            "re-entry must restart the composite at its initial substate, not resume C2",
        )
    }

    private class AncestorTimeoutModel(parent: ModelElement) : AgentModel(parent, "ancestorTimeout") {
        val log = mutableListOf<String>()
        inner class Walker : Agent("walker") {
            init {
                statechart {
                    initial("P")
                    state("P") {
                        onTimeout(3.0) { log.add("parentTimeout@${agent.currentTime}") ; transitionTo("Done") }
                        initial("C1")
                        state("C1") { onTimeout(1.0) { transitionTo("C2") } }
                        state("C2") {}
                    }
                    state("Done") {}
                }
            }
        }
        val walker = Walker()
    }

    /**
     *  Changing substate does not exit the ancestor, so the ancestor's pending
     *  timeout must survive and still fire on its original schedule. The guide
     *  states that leaving a state cancels its timeout and re-entering schedules a
     *  fresh one; this pins the hierarchical corollary.
     */
    @Test
    @DisplayName("A2.4: an ancestor timeout survives a change of substate")
    fun ancestorTimeoutSurvivesSubstateChange() {
        var m: AncestorTimeoutModel? = null
        runModel({ m = AncestorTimeoutModel(it) }, length = 6.0)
        assertEquals(listOf("parentTimeout@3.0"), m!!.log)
        assertEquals("Done", m!!.walker.statechart?.currentStateName)
    }

    private class ExitActionModel(parent: ModelElement) : AgentModel(parent, "exitAction") {
        val recorder = Recorder()
        inner class Walker : Agent("walker") {
            init {
                statechart {
                    initial("S1")
                    state("S1") {
                        onExit { transitionTo("S3") }
                        onTimeout(1.0) { transitionTo("S2") }
                    }
                    state("S2") {}
                    state("S3") {}
                }
                statechart?.addObserver(recorder)
            }
        }
        val walker = Walker()
    }

    /**
     *  **Characterization (hypothesis A2-b).** `transitionTo` does not transition
     *  immediately — it records a pending target and schedules a zero-delay event.
     *  `enterChain` checks for a pending target and bails out mid-chain, so a
     *  transition requested from an *entry* action is absorbed into the one in
     *  flight. There is no equivalent check after exit actions run, so a transition
     *  requested from an *exit* action becomes a second, separate transition that
     *  lands after the first completes.
     *
     *  The consequence is that the intermediate state is genuinely entered — S2's
     *  entry actions run — before the chart moves on to S3, all at the same
     *  simulated time. That is deterministic and defensible, but surprising enough
     *  to be worth pinning: a modeler writing `transitionTo` in an exit action is
     *  likely to expect the intermediate state to be skipped.
     */
    @Test
    @DisplayName("A2.3: a transition from an exit action runs after the in-flight one completes")
    fun transitionFromExitActionIsDeferredNotSubstituted() {
        var m: ExitActionModel? = null
        runModel({ m = ExitActionModel(it) }, length = 4.0)
        assertEquals(
            listOf(
                "enter:S1",
                "exit:S1", "enter:S2", "transition:S1->S2",
                "exit:S2", "enter:S3", "transition:S2->S3",
            ),
            m!!.recorder.steps,
            "the intermediate state is entered rather than skipped",
        )
        assertEquals("S3", m!!.walker.statechart?.currentStateName)
    }

    private class DoubleTransitionModel(parent: ModelElement) : AgentModel(parent, "doubleTransition") {
        inner class Walker : Agent("walker") {
            init {
                statechart {
                    initial("S1")
                    state("S1") {
                        onTimeout(1.0) { transitionTo("S2"); transitionTo("S3") }
                    }
                    state("S2") {}
                    state("S3") {}
                }
            }
        }
        val walker = Walker()
    }

    /**
     *  **Characterization.** Two `transitionTo` calls in one action body resolve to
     *  the *first* target; the second is silently ignored rather than overwriting.
     *  Pinned so the rule cannot flip to last-wins unnoticed, since both are
     *  plausible and neither is currently documented.
     */
    @Test
    @DisplayName("A2.3: with two transitionTo calls in one action, the first target wins")
    fun firstTransitionTargetWinsWithinOneAction() {
        var m: DoubleTransitionModel? = null
        runModel({ m = DoubleTransitionModel(it) }, length = 4.0)
        assertEquals("S2", m!!.walker.statechart?.currentStateName)
    }
}
