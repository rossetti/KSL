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
import kotlin.test.assertTrue

/**
 *  Phase C6 — the `StatechartObserver` contract.
 *
 *  It was the untested member of the observer trio: `MailboxObserver` and
 *  `AgentRegistryObserver` each had a dedicated test, this had none, despite
 *  statecharts being the package's primary behaviour idiom and so the observer most
 *  likely to be reached for. `StatechartHierarchyTest` uses it as an instrument for
 *  ordering; this covers the contract itself.
 *
 *  **API asymmetry worth recording.** `MailboxObserver` has `addObserver` /
 *  `removeObserver`, `AgentRegistryObserver` has `attachRegistryObserver` /
 *  `detachRegistryObserver`, and `StatechartObserver` has only `addObserver` — there
 *  is no way to detach one, and the naming differs across all three. Nothing here
 *  depends on removal, so the tests pin what exists rather than asserting what does
 *  not.
 */
class StatechartObserverTest {

    private class Recorder(val label: String) : AgentModel.StatechartObserver {
        val entered = mutableListOf<String>()
        val exited = mutableListOf<String>()
        val transitions = mutableListOf<String>()
        val times = mutableListOf<Double>()

        override fun onStateEntered(stateName: String, time: Double) {
            entered.add(stateName); times.add(time)
        }
        override fun onStateExited(stateName: String, time: Double) {
            exited.add(stateName)
        }
        override fun onTransition(fromState: String, toState: String, time: Double) {
            transitions.add("$fromState->$toState")
        }
    }

    /** Two states on a timer, so every callback fires at a known time. */
    private class ObservedModel(parent: ModelElement, val observers: List<Recorder>) :
        AgentModel(parent, "observed") {

        inner class Walker : Agent("walker") {
            init {
                statechart {
                    initial("first")
                    state("first") { onTimeout(2.0) { transitionTo("second") } }
                    state("second") {}
                }
                observers.forEach { statechart?.addObserver(it) }
            }
        }
        val walker = Walker()
    }

    private fun run(observers: List<Recorder>, length: Double = 5.0): ObservedModel {
        val model = Model("observerTest")
        val m = ObservedModel(model, observers)
        model.numberOfReplications = 1
        model.lengthOfReplication = length
        model.simulate()
        return m
    }

    @Test
    @DisplayName("C6: all three callbacks fire, with entry before the transition notification")
    fun allCallbacksFireInOrder() {
        val r = Recorder("only")
        run(listOf(r))
        assertEquals(listOf("first", "second"), r.entered, "entries")
        assertEquals(listOf("first"), r.exited, "exits")
        assertEquals(listOf("first->second"), r.transitions, "transitions")
    }

    @Test
    @DisplayName("C6: callbacks carry the simulated time of the event")
    fun callbacksCarrySimulatedTime() {
        val r = Recorder("timed")
        run(listOf(r))
        assertEquals(listOf(0.0, 2.0), r.times, "initial entry at 0, transition entry at the timeout")
    }

    /**
     *  Several observers may watch one chart, and each must see the full sequence.
     *  A collector that de-duplicated or short-circuited would break any second
     *  consumer — `AgentPerformance` already attaches one internally, so a user
     *  observer is always the second.
     */
    @Test
    @DisplayName("C6: multiple observers each receive the full sequence")
    fun multipleObserversEachSeeEverything() {
        val a = Recorder("a")
        val b = Recorder("b")
        run(listOf(a, b))
        assertEquals(a.entered, b.entered, "entries must reach both")
        assertEquals(a.exited, b.exited, "exits must reach both")
        assertEquals(a.transitions, b.transitions, "transitions must reach both")
        assertTrue(a.entered.isNotEmpty(), "and must be non-empty")
    }

    /**
     *  The documented contract is that events fire "for every level of the active
     *  chain (composite + leaf), in entry / exit order" — so a nested chart reports
     *  its composites, not only its leaf.
     */
    @Test
    @DisplayName("C6: composite states are reported, not just the leaf")
    fun compositeLevelsAreReported() {
        val r = Recorder("nested")
        val model = Model("nestedObserver")
        object : AgentModel(model, "nested") {
            val walker = object : Agent("walker") {
                init {
                    statechart {
                        initial("outer")
                        state("outer") {
                            initial("inner")
                            state("inner") {}
                        }
                    }
                    statechart?.addObserver(r)
                }
            }
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 2.0
        model.simulate()
        assertEquals(
            listOf("outer", "inner"), r.entered,
            "the composite and the leaf should both be reported, outermost first",
        )
    }

    /**
     *  An observer attached to one agent's chart must not receive another's. Charts
     *  are per-agent, and a shared observer list would cross the streams.
     */
    @Test
    @DisplayName("C6: observers are scoped to the chart they were added to")
    fun observersAreScopedToTheirChart()
    {
        val watched = Recorder("watched")
        val model = Model("scopedObservers")
        object : AgentModel(model, "scoped") {
            val a = object : Agent("a") {
                init {
                    statechart {
                        initial("s"); state("s") { onTimeout(1.0) { transitionTo("t") } }
                        state("t") {}
                    }
                    statechart?.addObserver(watched)
                }
            }

            @Suppress("unused")
            val b = object : Agent("b") {
                init {
                    statechart {
                        initial("x"); state("x") { onTimeout(1.0) { transitionTo("y") } }
                        state("y") {}
                    }
                }
            }
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 3.0
        model.simulate()
        assertEquals(listOf("s", "t"), watched.entered, "only the watched chart's states")
        assertTrue(
            watched.entered.none { it == "x" || it == "y" },
            "the unwatched agent's states must not leak in",
        )
    }
}
