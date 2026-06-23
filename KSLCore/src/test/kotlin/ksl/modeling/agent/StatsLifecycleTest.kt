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

/**
 *  Batch 5 audit checks for stats/lifecycle:
 *   - M6: time-in-state for a state the agent is still occupying at the
 *     end of a replication must be counted to the end, and must not
 *     carry across replications.
 *   - M9: signal subscriptions held at the end of a replication must be
 *     torn down so they do not accumulate across replications.
 */
class StatsLifecycleTest {

    // ── M6: time-in-state for a held state ────────────────────────────────────

    private class TimeInStateModel(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        inner class Worker : PermanentAgent("worker") {
            init {
                statechart {
                    initial("phaseA")
                    // Spend the first half in phaseA, then move to phaseB and
                    // stay there (held) for the rest of the replication.
                    state("phaseA") { onTimeout(50.0) { transitionTo("phaseB") } }
                    state("phaseB") {}
                }
            }
        }

        val worker = Worker()
        val perf: AgentPerformance = worker.collectPerformance()
    }

    @Test
    fun timeInStateCountsAHeldTerminalStateToTheEnd() {
        val model = Model("TimeInStateTest")
        val m = TimeInStateModel(model, "tis")
        model.lengthOfReplication = 100.0
        model.numberOfReplications = 1
        model.simulate()

        val a = m.perf.timeInStateResponse["phaseA"]!!.acrossReplicationStatistic.average
        val b = m.perf.timeInStateResponse["phaseB"]!!.acrossReplicationStatistic.average
        // 50 of 100 time units in each → time-weighted fraction 0.5 each.
        assertEquals(0.5, a, 1e-6, "phaseA occupancy fraction")
        assertEquals(0.5, b, 1e-6, "phaseB (held to end) occupancy fraction must be counted")
    }

    @Test
    fun timeInStateDoesNotCarryAcrossReplications() {
        val model = Model("TimeInStateMultiRepTest")
        val m = TimeInStateModel(model, "tis2")
        model.lengthOfReplication = 100.0
        model.numberOfReplications = 3
        model.simulate()

        // Each replication is identical: 0.5 occupancy each phase, every
        // replication. A carryover bug would skew the across-rep average.
        val a = m.perf.timeInStateResponse["phaseA"]!!.acrossReplicationStatistic
        val b = m.perf.timeInStateResponse["phaseB"]!!.acrossReplicationStatistic
        assertEquals(3.0, a.count, "three replications observed for phaseA")
        assertEquals(0.5, a.average, 1e-6)
        assertEquals(0.5, b.average, 1e-6)
    }

    // ── M9: signal subscriptions across replications ─────────────────────────

    private class HeldSignalModel(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        val pulse = AgentSignal("pulse")
        var fires = 0

        // The listener subscribes to `pulse` in "watching" and NEVER
        // transitions out — so the subscription is still live at the end
        // of every replication and must be torn down by the registry sweep.
        inner class Listener : Agent("listener") {
            init {
                statechart {
                    initial("watching")
                    state("watching") {
                        onSignal(pulse) { fires += 1 }
                    }
                }
            }
        }

        val listener = Listener()

        override fun initialize() {
            super.initialize()
            // Fire once per replication via a scheduled event (rep-safe;
            // no process to re-activate across replications).
            schedule(ModelElement.EventActionIfc<Nothing> { pulse.fire() }, 1.0)
        }
    }

    @Test
    fun signalSubscriptionsDoNotLeakAcrossReplications() {
        val model = Model("SignalLeakTest")
        val m = HeldSignalModel(model, "sigleak")
        model.lengthOfReplication = 10.0
        model.numberOfReplications = 3
        model.simulate()

        // Fired once per replication; the subscription (held in "watching"
        // to the end of each rep) must be removed at afterReplication so
        // subscribers do not accumulate.
        assertEquals(3, m.fires, "pulse should fire once per replication")
        assertEquals(
            0, m.pulse.numSubscribers,
            "held signal subscriptions must be torn down between replications",
        )
    }
}
