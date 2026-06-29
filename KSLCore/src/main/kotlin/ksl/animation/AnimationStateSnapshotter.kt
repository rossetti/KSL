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

package ksl.animation

import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.ContinuousProjection
import ksl.modeling.agent.ContinuousVolume
import ksl.modeling.agent.GridProjection
import ksl.modeling.agent.NetworkProjection
import ksl.modeling.agent.Projection
import ksl.modeling.agent.VoxelProjection
import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.Resource
import ksl.modeling.queue.Queue
import ksl.modeling.station.SResource
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

/**
 * Emits a single **opening-frame keyframe** at [keyframeTime] (a capture window's `startTime`, 9A.2/9B):
 * a comprehensive re-statement of the model's current animation state, so a windowed trace whose window
 * begins mid-run is replayable from its first frame. Without it, a renderer joining at `startTime` would
 * see only the deltas that happen *after* `startTime` and would not know what already exists.
 *
 * **How it runs.** In [initialize] (which fires once per replication, giving automatic re-arming) it
 * schedules **one** [KSLEvent.VERY_HIGH_PRIORITY] event at `keyframeTime` — no `EventGenerator`, because a
 * keyframe is a single instant, not a recurring sample. The very-high priority makes the keyframe fire
 * *before* any real events scheduled at `keyframeTime`, so the renderer sees the full snapshot first and
 * then the in-window deltas refine it. If `keyframeTime <= time` at initialization (the window already
 * includes the run start) nothing is scheduled — the live stream already covers the opening frame.
 *
 * **What it emits** (each guarded by `sink.isActive`, gated by [captureSpec] just as the live emitters
 * are), in dependency order so every reference resolves:
 *  1. [AnimationEvent.EntityCreated] for every live in-flight entity (the union of every [ProcessModel]'s
 *     `suspendedEntitiesSnapshot`, 9B.1) — at a scheduled instant that set is exactly the in-flight
 *     entities. Entities are not a [CaptureSpec] kind, so all live ones register (matching the live
 *     stream, where entity events are not element-gated).
 *  2. Per captured [Resource]: [AnimationEvent.ResourceStateChanged] plus an
 *     [AnimationEvent.SeizeAllocated] per current allocation (placing each *served* entity at its resource).
 *  3. Per captured [SResource]: [AnimationEvent.ResourceStateChanged] (station state).
 *  4. Per captured [Queue]: [AnimationEvent.QueueLengthChanged] plus an [AnimationEvent.QObjectEnqueued]
 *     per member (placing each *queued* entity in its queue).
 *  5. Per captured [Conveyor]: [AnimationEvent.ConveyorDefined] (its anchors, rebuilt from the entry/exit
 *     cells since the original fires at t=0) plus an [AnimationEvent.ConveyorItemMoved] per item on the
 *     belt at its front cell (placing each conveyed entity).
 *  6. Per captured [AgentModel]: [AnimationEvent.SpaceDefined] per projection, then per agent
 *     [AnimationEvent.AgentRegistered] and [AnimationEvent.AgentStateEntered] (current statechart state),
 *     then [AnimationEvent.AgentPositionChanged] for each placed agent.
 *  7. Per captured [Response]/[Counter]: [AnimationEvent.ResponseObserved] (current value/statistics).
 *  8. Residual entity placement (the **position-resolution order**): any live entity *not* already placed
 *     by a queue (step 4), an allocation (step 2), or a conveyor (step 5) is given a *rest* placement via
 *     [AnimationEvent.MoveCompleted] at its `currentLocation`, but only when that location is Cartesian
 *     (finite `x`/`y`); coordinate-free models (e.g. `DistancesModel`, whose coordinates are `NaN`) leave
 *     the entity registered-but-unplaced rather than emit a meaningless `NaN` position.
 *
 * **Semantics / limitation.** The opening frame is a **static rest snapshot**. An entity that was
 * mid-*move* when the window opened is suspended and falls to step 8, so it is shown *at rest at its
 * current location*; its in-flight interpolation is not reconstructed — it snaps to correctness at its
 * next in-window event. This is the right trade for a keyframe and avoids fabricating interpolation state.
 *
 * @param parent the model element this snapshotter is parented to (typically the model)
 * @param captureSpec the same spec that gates the live emitters, so the keyframe captures the same elements
 * @param keyframeTime the simulated time at which to emit the opening frame (a capture window's `startTime`)
 * @param name an optional model-element name
 */
class AnimationStateSnapshotter(
    parent: ModelElement,
    private val captureSpec: CaptureSpec,
    private val keyframeTime: Double,
    name: String? = null
) : ModelElement(parent, name) {

    private val keyframeAction = EventActionIfc<Nothing> { emitKeyframe() }

    override fun initialize() {
        val dt = keyframeTime - time
        if (dt > 0.0) {
            schedule(keyframeAction, dt, priority = KSLEvent.VERY_HIGH_PRIORITY)
        }
    }

    private fun emitKeyframe() {
        val sink = model.animationSink
        if (!sink.isActive) return
        val now = time

        // (1) Register every live in-flight entity first, so later references resolve.
        val liveEntities = LinkedHashSet<ProcessModel.Entity>()
        for (element in model.getModelElements()) {
            if (element is ProcessModel) liveEntities += element.suspendedEntitiesSnapshot
        }
        for (entity in liveEntities) {
            sink.emit(AnimationEvent.EntityCreated(now, entity.id, entity.javaClass.simpleName.ifEmpty { "Entity" }))
        }

        // Ids placed by an association, so they are excluded from residual coordinate placement (step 6).
        val placedIds = HashSet<Long>()

        // (2)-(4) Element aggregates.
        for (element in model.getModelElements()) {
            when (element) {
                is Resource -> {
                    if (!captureSpec.captures(ElementKind.RESOURCE, element.name)) continue
                    sink.emit(
                        AnimationEvent.ResourceStateChanged(
                            now, element.name, element.state.name, element.numBusy, element.capacity
                        )
                    )
                    for (allocation in element.allocations()) {
                        val id = allocation.myEntity.id
                        sink.emit(AnimationEvent.SeizeAllocated(now, id, element.name, allocation.amount))
                        placedIds += id
                    }
                }
                is SResource -> {
                    if (!captureSpec.captures(ElementKind.RESOURCE, element.name)) continue
                    val busyUnits = (element.capacity - element.numAvailableUnits).coerceAtLeast(0)
                    val state = when {
                        element.isFailed -> "Failed"
                        element.isBusy -> "Busy"
                        element.isIdle -> "Idle"
                        else -> "Inactive"
                    }
                    sink.emit(AnimationEvent.ResourceStateChanged(now, element.name, state, busyUnits, element.capacity))
                }
                is Queue<*> -> {
                    if (!captureSpec.captures(ElementKind.QUEUE, element.name)) continue
                    sink.emit(AnimationEvent.QueueLengthChanged(now, element.name, element.size))
                    for (member in element.orderedList()) {
                        sink.emit(AnimationEvent.QObjectEnqueued(now, member.id, element.name))
                        placedIds += member.id
                    }
                }
                is Conveyor -> {
                    if (!captureSpec.captures(ElementKind.CONVEYOR, element.name)) continue
                    // Structure: the conveyor's own ConveyorDefined fires at its initialize (t=0), which a
                    // window drops; rebuild it from the entry/exit anchors.
                    val names = ArrayList<String>()
                    val cells = ArrayList<Int>()
                    for ((loc, cell) in element.entryCells) { names.add(loc); cells.add(cell.index) }
                    for ((loc, cell) in element.exitCells) { names.add(loc); cells.add(cell.index) }
                    sink.emit(AnimationEvent.ConveyorDefined(now, element.name, names, cells))
                    // Items currently on the belt, at their front cell (mirrors the live emitConveyorMove).
                    for (req in element.conveyorRequests()) {
                        val fc = req.frontCell ?: continue
                        sink.emit(AnimationEvent.ConveyorItemMoved(now, req.entity.id, element.name, fc.index))
                        placedIds += req.entity.id
                    }
                }
                is AgentModel -> {
                    if (!captureSpec.captures(ElementKind.AGENT, element.name)) continue
                    for (ctx in element.animationContexts()) {
                        // (a) Space backdrops, so the renderer can draw the grid/continuous space.
                        for (p in ctx.projections) emitSpace(sink, p, now)
                        // (b) Register each currently-live agent (members includes runtime-created agents,
                        // which snapshotAgents() omits) and re-assert its current statechart state.
                        for (agent in ctx.members) {
                            sink.emit(AnimationEvent.AgentRegistered(now, agent.name, agent.javaClass.simpleName.ifEmpty { "Agent" }))
                            agent.statechart?.currentStateName?.let {
                                sink.emit(AnimationEvent.AgentStateEntered(now, agent.name, it))
                            }
                        }
                        // (c) Re-emit each placed agent's current position.
                        for (p in ctx.projections) when (p) {
                            is GridProjection<*> -> p.snapshotPositions(sink, now)
                            is ContinuousProjection<*> -> p.snapshotPositions(sink, now)
                            is ContinuousVolume<*> -> p.snapshotPositions(sink, now)   // 3D flattened to x–y (G8)
                            is VoxelProjection<*> -> p.snapshotPositions(sink, now)     // 3D flattened to col/row (G8)
                            is NetworkProjection<*> -> p.snapshotNetwork(sink, now)     // graph + agents at node slots (G7)
                            else -> {}
                        }
                    }
                }
            }
        }

        // (5) Responses and counters from the model's curated lists.
        for (response in model.responses) {
            if (!captureSpec.captures(ElementKind.RESPONSE, response.name)) continue
            val s = response.withinReplicationStatistic
            sink.emit(
                AnimationEvent.ResponseObserved(
                    now, response.name, response.value,
                    count = s.count, average = s.weightedAverage, min = s.min, max = s.max
                )
            )
        }
        for (counter in model.counters) {
            if (!captureSpec.captures(ElementKind.COUNTER, counter.name)) continue
            sink.emit(AnimationEvent.ResponseObserved(now, counter.name, counter.value))
        }

        // (6) Residual rest placement for entities not placed by a queue or allocation.
        for (entity in liveEntities) {
            if (entity.id in placedIds) continue
            val loc = entity.currentLocation
            if (loc.x.isFinite() && loc.y.isFinite()) {
                sink.emit(AnimationEvent.MoveCompleted(now, entity.id, loc.x, loc.y, loc.z))
            }
        }
    }

    /** Emits a projection's spatial backdrop as a [AnimationEvent.SpaceDefined] (mirrors the coordinator's). */
    private fun emitSpace(sink: AnimationSink, p: Projection<*>, now: Double) {
        val event = when (p) {
            is GridProjection<*> -> AnimationEvent.SpaceDefined(
                now, p.name, "Grid", cols = p.columns, rows = p.rows, cellSize = 1.0, torus = p.torus
            )
            is ContinuousProjection<*> -> AnimationEvent.SpaceDefined(
                now, p.name, "Continuous",
                xMin = p.xRange.start, xMax = p.xRange.endInclusive,
                yMin = p.yRange.start, yMax = p.yRange.endInclusive, torus = p.torus
            )
            // 3D spaces flattened to their x–y (col/row) footprint (G8).
            is ContinuousVolume<*> -> AnimationEvent.SpaceDefined(
                now, p.name, "Continuous",
                xMin = p.xRange.start, xMax = p.xRange.endInclusive,
                yMin = p.yRange.start, yMax = p.yRange.endInclusive, torus = p.torus
            )
            is VoxelProjection<*> -> AnimationEvent.SpaceDefined(
                now, p.name, "Grid", cols = p.columns, rows = p.rows, cellSize = 1.0, torus = p.torus
            )
            else -> return // Network backdrops not derived yet
        }
        sink.emit(event)
    }
}
