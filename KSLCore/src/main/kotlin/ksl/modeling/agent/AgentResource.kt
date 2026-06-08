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

import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.ResourceWithQ

/**
 *  A resource that is also an agent. Behaves like an ordinary
 *  [ResourceWithQ] from the seizing entity's perspective — entities
 *  pass it to `seize` / `release` from inside a process — and on top
 *  of that exposes the agent capabilities the user might want for a
 *  forklift, repair worker, or nurse:
 *
 *   - a [mailbox] for receiving [AgentMessage] traffic, routed via
 *     the enclosing [AgentModel]'s shared bus,
 *   - an optional [AgentModel.Statechart] for reactive autonomous
 *     behavior,
 *   - convenience helpers for taking the resource off-shift
 *     ([goOffShift]) and back on-shift ([goOnShift]).
 *
 *  `AgentResource` sits in the *permanent* tier of agents — it is a
 *  [ResourceWithQ], which is a [ksl.simulation.ModelElement], so
 *  instances must be constructed before `simulate()` runs. Its
 *  statechart lifecycle is driven by its own `initialize()` /
 *  `afterReplication()` hooks rather than by [AgentModel]'s registry.
 *
 *  Note that `AgentResource` is intentionally *not* an
 *  [AgentModel.Agent] subclass — it's-a `Resource` first. The
 *  [AgentLike] interface gives it the same mailbox/statechart
 *  capabilities without forcing the inheritance.
 *
 *  Per-request decisions (`shouldGrant` / refuse) are not in this
 *  version: the cleanest hook for that intercepts inside
 *  `ProcessModel`'s seize implementation, which is out of scope.
 *  The on/off-shift pattern (capacity manipulation) covers most of
 *  the practical refusal use cases — an off-shift resource cannot be
 *  newly seized, and in-flight allocations finish naturally.
 *
 *  @param agentModel the enclosing AgentModel (needed for mailbox /
 *    statechart machinery; passed explicitly so `AgentResource` can
 *    be parented to it)
 *  @param name optional name for the resource
 *  @param capacity initial capacity (also the on-shift capacity to
 *    restore after returning from a break)
 *  @param queue optional shared request queue; if null a private one
 *    is created
 */
open class AgentResource @JvmOverloads constructor(
    private val agentModel: AgentModel,
    name: String? = null,
    capacity: Int = Defaults.capacity,
    queue: RequestQ? = null,
) : ResourceWithQ(agentModel, name, capacity, queue), AgentLike {

    /**
     *  Mutable global defaults for [AgentResource] construction.
     */
    companion object Defaults {
        /** Default on-shift capacity for new agent resources. Must be positive. */
        var capacity: Int by positive(1)
    }

    /**
     *  The capacity this resource has when on-shift. Captured at
     *  construction so [goOnShift] can restore it after a break.
     */
    val onShiftCapacity: Int = capacity

    /**
     *  Default mailbox for receiving [AgentMessage] traffic. Routes
     *  via the enclosing [AgentModel]'s shared bus.
     */
    override val mailbox: AgentModel.AgentMailbox<AgentMessage> =
        agentModel.AgentMailbox(this)

    /**
     *  The current simulation time, exposed via [AgentLike] so
     *  statechart actions can read it without reaching for `time` on
     *  the outer model element.
     */
    override val currentTime: Double
        get() = time

    /**
     *  Optional statechart governing this resource's reactive
     *  behavior. Configured via [statechart] and started automatically
     *  at replication initialization by this resource's own
     *  `initialize()` hook.
     */
    override var statechart: AgentModel.Statechart? = null
        internal set

    /**
     *  Declare and install this resource's statechart in one step. May
     *  be called at most once; a second call throws. Use
     *  [buildStatechart] + [useStatechart] for the multi-variant /
     *  design-comparison case. See [AgentModel.Statechart] for the DSL
     *  and semantics.
     */
    fun statechart(block: StatechartBuilder.() -> Unit): AgentModel.Statechart {
        check(statechart == null) {
            "AgentResource ${this.name} already has a statechart configured."
        }
        val sc = buildStatechart("statechart", block)
        useStatechart(sc)
        return sc
    }

    /**
     *  Build a statechart *without* installing it. See
     *  [AgentModel.Agent.buildStatechart].
     */
    fun buildStatechart(name: String, block: StatechartBuilder.() -> Unit): AgentModel.Statechart {
        val builder = StatechartBuilder(this).apply(block)
        return builder.build(agentModel, "${this.name}:$name")
    }

    /**
     *  Select the active statechart. Allowed only when the current
     *  chart (if any) is not started — between replications for a
     *  setup-time resource. The selected chart is started by this
     *  resource's [initialize] at the next replication. See
     *  [AgentModel.Agent.useStatechart].
     */
    fun useStatechart(chart: AgentModel.Statechart?) {
        val current = statechart
        check(current == null || !current.isStarted) {
            "cannot replace ${this.name}'s statechart while it is running."
        }
        // Defensive, unconditional teardown of the outgoing chart (the
        // guard already ensures it is not running, so this is a no-op
        // today; it future-proofs the swap).
        current?.stop()
        statechart = chart
        if (chart != null && agentModel.model.isRunning) chart.start()
    }

    /** Gracefully stop this resource's statechart, if any. Idempotent. */
    fun stopStatechart() {
        statechart?.stop()
    }

    /**
     *  Optional performance observer attached to this resource's
     *  mailbox (and statechart, if one is configured). Created by
     *  [collectPerformance]; null otherwise.
     */
    var performance: AgentModel.AgentPerformance? = null
        private set

    /**
     *  Opt this resource in to mailbox-traffic and (if a statechart
     *  was configured before this call) statechart-state statistics.
     *  Must be called before `simulate()` runs since the returned
     *  [AgentModel.AgentPerformance] is a [ksl.simulation.ModelElement].
     *  Idempotent — a second call returns the existing observer.
     *
     *  See [AgentModel.AgentPerformance] for the metrics published.
     *
     *  @param allPerformance if true, additionally publishes the
     *    `NumPendingAtEndOfReplication` response
     */
    fun collectPerformance(allPerformance: Boolean = false): AgentModel.AgentPerformance {
        performance?.let {
            require(it.allPerformance == allPerformance) {
                "${this.name} already has performance collection configured with " +
                    "allPerformance=${it.allPerformance}; cannot re-request with " +
                    "allPerformance=$allPerformance."
            }
            return it
        }
        val perf = agentModel.AgentPerformance(parent = this, agent = this, allPerformance = allPerformance)
        performance = perf
        return perf
    }

    override fun initialize() {
        super.initialize()
        // Reset before start(): clears stale traffic/waiters from the
        // previous replication, then start() re-registers the chart's
        // arrival listener on the now-clean mailbox.
        mailbox.reset()
        offShift = false
        statechart?.start()
    }

    override fun afterReplication() {
        super.afterReplication()
        statechart?.stop()
    }

    /**
     *  Tracks whether [goOffShift] is in effect. Kept explicitly rather
     *  than inferred from `capacity == 0`, so a resource legitimately
     *  constructed or driven to zero capacity is not mistaken for
     *  off-shift (and [goOffShift] / [goOnShift] behave correctly).
     *  Reset to on-shift at the start of each replication.
     */
    private var offShift: Boolean = false

    /**
     *  Whether the resource is currently off-shift (taken off via
     *  [goOffShift]).
     */
    val isOffShift: Boolean
        get() = offShift

    /**
     *  Take the resource off-shift indefinitely. New seize requests
     *  wait in the request queue until [goOnShift]; in-flight
     *  allocations finish normally under the current
     *  [ksl.modeling.entity.Resource.capacityChangeRule] (IGNORE by
     *  default).
     */
    fun goOffShift() {
        if (offShift) return
        offShift = true
        changeCapacity(CapacityChangeNotice(capacity = 0, duration = Double.POSITIVE_INFINITY))
    }

    /**
     *  Bring the resource back on-shift, restoring its
     *  [onShiftCapacity]. Any requests waiting in the resource's
     *  request queue are notified per the capacity-change rule.
     */
    fun goOnShift() {
        if (!offShift) return
        offShift = false
        changeCapacity(
            CapacityChangeNotice(capacity = onShiftCapacity, duration = Double.POSITIVE_INFINITY)
        )
    }

    init {
        // Notify registry observers (the mailbox above is already
        // constructed; a subclass-configured statechart is not yet).
        agentModel.notifyAgentRegistered(this)
    }
}
