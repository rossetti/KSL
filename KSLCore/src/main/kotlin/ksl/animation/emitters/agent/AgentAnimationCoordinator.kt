/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.animation.emitters.agent

import ksl.animation.AnimationEvent
import ksl.modeling.agent.AgentLike
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.ContinuousProjection
import ksl.modeling.agent.ContinuousVolume
import ksl.modeling.agent.GridProjection
import ksl.modeling.agent.NetworkProjection
import ksl.modeling.agent.Projection
import ksl.modeling.agent.VoxelProjection
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/**
 * Coordinates all per-agent animation for one [AgentModel]: it announces each agent
 * ([ksl.animation.AnimationEvent.AgentRegistered]) and wires that agent's statechart
 * ([StatechartAnimationEmitter]) and mailbox ([MailboxAnimationEmitter]) emitters. This is
 * what the animation controller installs per agent model so a config-driven trace captures
 * agent behavior, not just registrations.
 *
 * Lifecycle:
 *  - [attach] registers this as the model's registry observer, so **transient** agents
 *    (created during the run) are wired as they register.
 *  - [snapshotExistingAgents] wires every already-registered agent; it must be called at
 *    replication start (when the sink is capturing), because **permanent** agents register at
 *    construction — before the controller attached — and are not re-announced by the observer.
 *  - [detach] unwires everything.
 *
 * Each agent is wired at most once (tracked by identity), so calling [snapshotExistingAgents]
 * every replication is safe.
 *
 * Cleanup note: a mailbox observer is removed on [detach]; a statechart has no observer-removal
 * API, so its observer remains attached but becomes a no-op once the controller restores the
 * null sink. Multi-replication caveat: transient agents from earlier replications are not
 * unwired between replications (their observers fire into the inactive sink and cost nothing
 * once that replication is no longer captured).
 *
 * @param agentModel the agent model to coordinate
 */
class AgentAnimationCoordinator(
    private val agentModel: AgentModel,
    private val overlays: ksl.animation.OverlaySpec = ksl.animation.OverlaySpec.OFF
) : AgentModel.AgentRegistryObserver {

    private val model = agentModel.model
    private val registryEmitter = AgentRegistryAnimationEmitter(agentModel)
    private val wiredAgents = mutableSetOf<AgentLike>()
    private val undoActions = mutableListOf<() -> Unit>()
    private var spacesEmitted = false

    // A NetworkProjection's graph is wired in the model's initialize() (after beforeReplication), and it has no
    // per-move emission to capture later — so its backdrop must be snapshotted right after initialize() (G7).
    private val networkSnapshotObserver = object : ModelElementObserver() {
        override fun initialize(modelElement: ModelElement) {
            snapshotNetworks()
            if (overlays.flowField) snapshotFlowFields() // G11: one-time gradient snapshot per replication
        }
    }

    /** Emit a one-time flow-field gradient snapshot per linked field (G11 overlay, gated by OverlaySpec). */
    private fun snapshotFlowFields() {
        val sink = model.animationSink
        if (!sink.isActive) return
        for (ctx in agentModel.animationContexts()) {
            for ((spaceName, ff) in ctx.flowFields) {
                val cells = ff.distances.entries.filter { it.value.isFinite() }
                    .map { (c, d) -> ksl.animation.FlowCell(c.col, c.row, d) }
                val maxD = cells.maxOfOrNull { it.distance } ?: 0.0
                sink.emit(AnimationEvent.FlowFieldDefined(
                    model.time, spaceName, ff.graph.columns, ff.graph.rows, ff.cellSize, ff.origin.x, ff.origin.y, cells, maxD
                ))
            }
        }
    }

    /** Begin observing registrations so transient agents are wired as they appear. */
    fun attach() {
        agentModel.attachRegistryObserver(this)
        agentModel.attachModelElementObserver(networkSnapshotObserver)
        agentModel.setCapturePlannedPaths(overlays.plannedPaths) // G12: gate reportPlannedPath emissions
        agentModel.setCaptureMarkerPulses(overlays.markerPulses) // G-animated: gate reportMarkerPulse emissions
        // G10: one rate-limited vector sampler for the model when the velocity/force overlay is on (it looks up
        // the linked dynamics at fire time, since models link them in initialize() — after this runs).
        if (overlays.velocities || overlays.forces) DynamicsVectorSampler(agentModel, overlays)
    }

    /** Snapshot each network projection's auto-laid-out graph + agents at node slots (re-done each replication). */
    private fun snapshotNetworks() {
        val sink = model.animationSink
        if (!sink.isActive) return
        for (ctx in agentModel.animationContexts())
            for (p in ctx.projections) if (p is NetworkProjection<*>) p.snapshotNetwork(sink, model.time)
    }

    /** Announce and wire every already-registered agent. Call at replication start. */
    fun snapshotExistingAgents() {
        // snapshotAgents() covers the public registry (Agent/PermanentAgent) plus setup-time
        // AgentResources, which are kept out of `agents` by design but still need wiring (8F.3).
        for (agent in agentModel.snapshotAgents()) wire(agent)
        emitSpacesOnce()
    }

    /** Emit each projection's spatial dimensions once, so the renderer can draw the space from the trace (8K.6a). */
    private fun emitSpacesOnce() {
        if (spacesEmitted) return
        val sink = model.animationSink
        if (!sink.isActive) return
        for (ctx in agentModel.animationContexts()) {
            for (p in ctx.projections) emitSpace(p)
        }
        spacesEmitted = true
    }

    private fun emitSpace(p: Projection<*>) {
        val event = when (p) {
            is GridProjection<*> -> AnimationEvent.SpaceDefined(
                model.time, p.name, "Grid", cols = p.columns, rows = p.rows, cellSize = 1.0, torus = p.torus
            )
            is ContinuousProjection<*> -> AnimationEvent.SpaceDefined(
                model.time, p.name, "Continuous",
                xMin = p.xRange.start, xMax = p.xRange.endInclusive,
                yMin = p.yRange.start, yMax = p.yRange.endInclusive, torus = p.torus
            )
            // 3D spaces flattened to their x–y (col/row) footprint so the 2D renderer has a backdrop — G8.
            is ContinuousVolume<*> -> AnimationEvent.SpaceDefined(
                model.time, p.name, "Continuous",
                xMin = p.xRange.start, xMax = p.xRange.endInclusive,
                yMin = p.yRange.start, yMax = p.yRange.endInclusive, torus = p.torus
            )
            is VoxelProjection<*> -> AnimationEvent.SpaceDefined(
                model.time, p.name, "Grid", cols = p.columns, rows = p.rows, cellSize = 1.0, torus = p.torus
            )
            else -> return // Network backdrops not derived yet
        }
        model.animationSink.emit(event)
    }

    override fun onAgentRegistered(agent: AgentLike) {
        wire(agent)
    }

    private fun wire(agent: AgentLike) {
        if (!wiredAgents.add(agent)) return // already wired
        registryEmitter.onAgentRegistered(agent) // emits AgentRegistered
        agent.statechart?.let { chart ->
            val chartEmitter = StatechartAnimationEmitter(agent.name, model)
            chart.attachObserver(chartEmitter)
            undoActions.add { chart.detachObserver(chartEmitter) }
        }
        val mailboxEmitter = MailboxAnimationEmitter(agent.name, model)
        agent.mailbox.attachObserver(mailboxEmitter)
        undoActions.add { agent.mailbox.detachObserver(mailboxEmitter) }
    }

    /** Stop observing registrations and remove every per-agent observer this wired up. */
    fun detach() {
        agentModel.detachRegistryObserver(this)
        agentModel.detachModelElementObserver(networkSnapshotObserver)
        undoActions.forEach { runCatching { it() } }
        undoActions.clear()
        wiredAgents.clear()
    }
}
