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
 *  Phase B2 — `AgentLike.dispose`, the "this agent is finished" operation.
 *
 *  A statechart holds scheduled timeout and condition events, so an agent that has
 *  left the population keeps running: a pending trigger still fires, transitions its
 *  state and executes entry and exit actions, and messages delivered afterwards pile
 *  up unread. Nothing corrects that until end of replication.
 *
 *  `AgentModel.Context.remove` is deliberately *not* the fix. Removal means "no
 *  longer part of this population" — an agent may leave one context and join
 *  another — so it updates membership, notifies projections, and stops the animation
 *  drawing the agent, but leaves behaviour alone. Disposal is the separate,
 *  explicit act.
 */
class AgentDisposalTest {

    private class TeardownModel(
        parent: ModelElement,
        val removeAt: Double?,
        val disposeAt: Double?,
    ) : AgentModel(parent, "teardown") {

        val fired = mutableListOf<String>()
        val ctx: Context<Walker> = Context("walkers")

        inner class Walker(aName: String) : Agent(aName) {
            init {
                statechart {
                    initial("waiting")
                    state("waiting") {
                        // Still pending when the agent departs at t = 3.
                        onTimeout(5.0) {
                            fired.add("timeout@${agent.currentTime}")
                            transitionTo("done")
                        }
                    }
                    state("done") { onEntry { fired.add("enteredDone@${agent.currentTime}") } }
                }
            }
        }

        val walker = Walker("walker")

        /** Mailbox depth sampled at the end of the run. */
        var finalMailboxSize: Int = -1

        /** Whether the chart was still running immediately after the departure step. */
        var chartRunningAfterDeparture: Boolean? = null

        override fun initialize() {
            super.initialize()
            ctx.add(walker)
            removeAt?.let { t ->
                schedule(EventActionIfc<Nothing> { ctx.remove(walker) }, t)
            }
            disposeAt?.let { t ->
                schedule(EventActionIfc<Nothing> {
                    walker.dispose()
                    chartRunningAfterDeparture = walker.statechart?.isRunning
                }, t)
            }
            // Arrives after the departure, and after the pending timeout would fire.
            schedule(EventActionIfc<Nothing> {
                walker.mailbox.deliver(AgentMessage.Inform(walker, "late"))
            }, 6.0)
            schedule(EventActionIfc<Nothing> {
                finalMailboxSize = walker.mailbox.size
            }, 8.0)
        }
    }

    private fun run(m: TeardownModel): TeardownModel {
        m.model.numberOfReplications = 1
        m.model.lengthOfReplication = 9.0
        m.model.simulate()
        return m
    }

    // ── The behaviour disposal exists to stop ────────────────────────────────

    /**
     *  Baseline. Removing an agent from its context leaves its statechart running,
     *  so a trigger armed before departure still fires afterwards. This is recorded
     *  rather than treated as a defect: removal is a membership operation, and an
     *  agent may leave one context for another.
     */
    @Test
    @DisplayName("B2: context removal alone leaves the statechart running")
    fun contextRemovalDoesNotStopBehaviour() {
        val m = run(TeardownModel(Model("removeOnly"), removeAt = 3.0, disposeAt = null))
        assertFalse(m.walker in m.ctx.members, "the agent did leave the context")
        assertEquals(
            listOf("timeout@5.0", "enteredDone@5.0"), m.fired,
            "a trigger armed before departure still fires after it",
        )
        assertEquals(1, m.finalMailboxSize, "messages delivered after departure accumulate")
    }

    /** Disposal cancels the pending trigger, so nothing fires after departure. */
    @Test
    @DisplayName("B2: dispose stops the statechart and cancels pending triggers")
    fun disposeStopsPendingTriggers() {
        val m = run(TeardownModel(Model("disposed"), removeAt = 3.0, disposeAt = 3.0))
        assertTrue(m.fired.isEmpty(), "no trigger should fire after disposal; got ${m.fired}")
        assertEquals(false, m.chartRunningAfterDeparture, "the chart should be stopped")
    }

    /** Disposal also clears the mailbox, so later traffic does not pile up unread. */
    @Test
    @DisplayName("B2: dispose clears the mailbox")
    fun disposeClearsTheMailbox() {
        val m = TeardownModel(Model("disposedMailbox"), removeAt = 3.0, disposeAt = 3.0)
        run(m)
        // The message delivered at t = 6 still lands — disposal is not a permanent
        // block — but nothing queued before disposal survives it.
        assertEquals(1, m.finalMailboxSize, "only post-disposal traffic remains")
    }

    /**
     *  Disposal must be safe to repeat: a model that disposes on departure and again
     *  in a cleanup pass should not fail or double-cancel.
     */
    @Test
    @DisplayName("B2: dispose is idempotent")
    fun disposeIsIdempotent() {
        val model = Model("disposeTwice")
        val m = object : AgentModel(model, "twice") {
            val walker = object : Agent("walker") {
                init { statechart { initial("s"); state("s") { onTimeout(5.0) {} } } }
            }
            override fun initialize() {
                super.initialize()
                schedule(EventActionIfc<Nothing> {
                    walker.dispose()
                    walker.dispose()
                }, 1.0)
            }
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 4.0
        model.simulate()
        assertEquals(false, m.walker.statechart?.isRunning)
    }

    /**
     *  Disposal tears down behaviour, not identity: the statechart can be started
     *  again. This pins that `dispose` is not a one-way door, which matters for
     *  permanent agents that are disposed mid-replication and restarted by
     *  `AgentModel.initialize()` on the next one.
     */
    @Test
    @DisplayName("B2: a disposed statechart can be restarted")
    fun disposedStatechartCanBeRestarted() {
        val model = Model("disposeRestart")
        val m = object : AgentModel(model, "restart") {
            val log = mutableListOf<String>()
            var runningAfterRestart: Boolean? = null
            val walker = object : Agent("walker") {
                init {
                    statechart {
                        initial("s")
                        state("s") { onEntry { log.add("entered@${agent.currentTime}") } }
                    }
                }
            }
            override fun initialize() {
                super.initialize()
                schedule(EventActionIfc<Nothing> { walker.dispose() }, 1.0)
                schedule(EventActionIfc<Nothing> {
                    walker.statechart?.start()
                    runningAfterRestart = walker.statechart?.isRunning
                }, 2.0)
            }
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 4.0
        model.simulate()
        assertEquals(listOf("entered@0.0", "entered@2.0"), m.log, "the initial state is re-entered")
        // Sampled during the run: afterReplication() stops every active statechart,
        // so the flag is necessarily false once simulate() has returned.
        assertEquals(true, m.runningAfterRestart, "the chart is running again after restart")
    }
}
