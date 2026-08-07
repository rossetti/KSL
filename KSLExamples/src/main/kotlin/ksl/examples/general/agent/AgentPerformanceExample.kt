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

package ksl.examples.general.agent

import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.positive
import ksl.modeling.agent.sendMessage
import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  A worked example of `PermanentAgent.collectPerformance`: **per-agent statistics
 *  for named role-holders**.
 *
 *  A help desk routes tickets to three specialists of differing speed. Model-level
 *  responses would tell you the desk's average queue and throughput; they cannot
 *  tell you that the slow specialist is saturated while the fast one idles. That
 *  is what per-agent statistics are for, and it is the reason `PermanentAgent`
 *  exists as a distinct tier from the transient `Agent`.
 *
 *  ## What `collectPerformance` publishes
 *
 *  Called on a `PermanentAgent` **before** `simulate()`, it attaches an observer
 *  that publishes, per agent:
 *
 *   - `NumMessagesReceived`, `NumMessagesConsumed` — mailbox traffic.
 *   - `NumInMailbox` — time-weighted pending depth, i.e. that specialist's queue.
 *   - `TimeInState_<name>` — time-weighted fraction of the replication spent in each
 *     declared state, which for this model is utilisation.
 *   - `NumTimesEntered_<name>`, `NumTransitions` — how often each state was visited.
 *   - `NumPendingAtEndOfReplication` — only with `allPerformance = true`.
 *
 *  Two ordering rules matter, and both are easy to get wrong:
 *
 *   1. It must be called before `simulate()`, because the observer is a
 *      `ModelElement` and has to register in the model's element map.
 *   2. The **statechart must already be configured**, because the per-state
 *      responses are pre-allocated from `statechart.stateNames`. Attach the chart in
 *      the agent's `init`, as here, then call `collectPerformance` from the model.
 *      Reverse the order and mailbox stats still appear while state stats silently
 *      do not.
 *
 *  ## Reading the output
 *
 *  Service times are deterministic and differ per specialist, so the results can be
 *  checked by hand: with arrival rate `λ` split evenly three ways, specialist *i*
 *  sees `λ/3` and takes `s_i` per ticket, so its utilisation tends to `λ·s_i/3` and
 *  `TimeInState_Working` should approach that. `main` prints the comparison. The
 *  identity holds only while that value is below 1 — see [Defaults.serviceTimes].
 *
 *  ## Queueing falls out of the mailbox
 *
 *  A specialist only accepts a ticket from its `Idle` state. One that arrives while
 *  it is `Working` stays pending, and the statechart re-scans the mailbox on
 *  re-entering `Idle`. So `NumInMailbox` *is* that specialist's queue length, with
 *  no queue object anywhere in the model.
 */
class AgentPerformanceExample(parent: ModelElement, name: String? = null) :
    AgentModel(parent, name) {

    /** A unit of work carrying the time it was raised. */
    data class Ticket(val id: Int, val raisedAt: Double)

    // ── Tunable parameters ──────────────────────────────────────────────────

    var arrivalMean: Double by positive(Defaults.arrivalMean)

    /** Mutable global defaults for [AgentPerformanceExample]. */
    companion object Defaults {
        /** Mean time between ticket arrivals. Must be positive. */
        var arrivalMean: Double by positive(2.0)

        /**
         *  Deterministic handling time for each specialist, fastest first.
         *
         *  Chosen so every specialist is **stable**. With arrivals dealt evenly,
         *  specialist *i* faces rate `λ/n` and utilisation `ρ_i = λ·s_i/n`; at the
         *  defaults those are 0.25, 0.50 and 0.75. Raise a service time past
         *  `n/λ` — 6.0 here — and that specialist saturates: utilisation pins just
         *  below 1.0 and its mailbox grows without bound, which is worth doing once
         *  on purpose to see what an overloaded agent looks like in the statistics.
         */
        var serviceTimes: List<Double> = listOf(1.5, 3.0, 4.5)
    }

    // ── Specialists ─────────────────────────────────────────────────────────

    /**
     *  A named role-holder: permanent, statechart-driven, and individually measured.
     *  Idle until a ticket arrives, Working for a fixed handling time, then Idle
     *  again.
     */
    inner class Specialist(aName: String, val serviceTime: Double) : PermanentAgent(aName) {
        init {
            statechart {
                initial("Idle")
                state("Idle") {
                    onMessage<AgentMessage.Inform<Ticket>> { transitionTo("Working") }
                }
                state("Working") {
                    onTimeout(serviceTime) { transitionTo("Idle") }
                }
            }
        }
    }

    val specialists: List<Specialist> = Defaults.serviceTimes.mapIndexed { i, s ->
        Specialist("specialist-${i + 1}", s)
    }

    init {
        // Opt each specialist in to per-agent statistics. Must happen before
        // simulate(), and after the statechart exists — both are true here, since
        // the chart is built in the agent's own init.
        specialists.forEach { it.collectPerformance(allPerformance = true) }
    }

    // ── Ticket source ───────────────────────────────────────────────────────

    /**
     *  Raises tickets and deals them round-robin, so each specialist sees the same
     *  arrival rate and any difference in the statistics is its own doing.
     */
    inner class HelpDesk : Agent("help-desk") {
        val script: KSLProcess = process(isDefaultProcess = true) {
            val arrivals = ExponentialRV(arrivalMean, streamNum = 1, streamProvider = streamProvider)
            var raised = 0
            while (true) {
                delay(arrivals.value)
                raised += 1
                val target = specialists[(raised - 1) % specialists.size]
                sendMessage(
                    AgentMessage.Inform(this@HelpDesk, Ticket(raised, currentTime)),
                    target.mailbox,
                )
            }
        }
    }

    override fun initialize() {
        super.initialize()
        // A KSLProcess is one-shot, so the desk is built fresh each replication.
        activate(HelpDesk().script)
    }
}

fun main() {
    val model = Model("AgentPerformanceExample")
    val sys = AgentPerformanceExample(model, "helpdesk")
    model.numberOfReplications = 10
    model.lengthOfReplication = 2000.0
    model.simulate()
    model.print()

    println()
    println("Per-specialist results (expected utilisation = arrivalRate/3 * serviceTime):")
    val rate = 1.0 / sys.arrivalMean
    for (s in sys.specialists) {
        val perf = s.performance ?: continue
        val busy = perf.timeInStateResponse["Working"]?.acrossReplicationStatistic?.average
        val queue = perf.numInMailboxResponse.acrossReplicationStatistic.average
        val expected = rate / sys.specialists.size * s.serviceTime
        println(
            "  %-14s service=%.1f  busy=%.3f (expected %.3f)  meanQueue=%.3f"
                .format(s.name, s.serviceTime, busy ?: Double.NaN, expected, queue)
        )
    }
}
