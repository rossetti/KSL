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

/**
 * A filtering [AnimationSink] that drops an entity's events when its **type** or its **current process** is
 * excluded from capture (Phase 10.1d). A decorator in the same family as [WindowedAnimationSink]: it wraps a
 * downstream sink and removes events without the producers (or that sink) knowing anything about it.
 *
 * Entity/process events are emitted unconditionally by the engine (they bypass the structural registration
 * gate), so capture selection for them is applied here. Filtering is **exclusion-only**: an event is dropped
 * only when its type is in [excludedTypes] or its current process is in [excludedProcesses]. With both sets
 * empty (the default), nothing is dropped — so default capture-all and structural `SELECTED` mode are
 * unaffected (this filter does not impose the structural include-list on entities).
 *
 * Identity is recovered from the trace by the same normalization the viewer uses (decision F7): the entity's
 * **type** is learned from its `EntityCreated`, and its **current process** is tracked from
 * `ProcessActivated`/`ProcessCompleted`. Process exclusion is keyed by the composite `"Type.process"` name so
 * identically named processes on different types are distinguished. Lifecycle markers and events not keyed to
 * an entity always pass.
 *
 * @param downstream the sink that records events this filter keeps
 * @param excludedTypes type names (matching `EntityCreated.entityType`) whose events are dropped entirely
 * @param excludedProcesses composite `"Type.process"` names whose events are dropped while the entity is in
 *        that process
 */
class EntityCaptureFilteringSink(
    private val downstream: AnimationSink,
    private val excludedTypes: Set<String>,
    private val excludedProcesses: Set<String>
) : AnimationSink {

    private val typeOf = HashMap<Long, String>()      // entityId -> type (from EntityCreated)
    private val processOf = HashMap<Long, String>()   // entityId -> current process (from ProcessActivated)

    /** True when there is anything to exclude; lets callers skip wrapping entirely otherwise. */
    val filtersAnything: Boolean get() = excludedTypes.isNotEmpty() || excludedProcesses.isNotEmpty()

    override val isActive: Boolean
        get() = downstream.isActive

    override fun emit(event: AnimationEvent) {
        // Lifecycle markers frame the trace regardless of any capture selection.
        if (event.isLifecycleMarker) {
            downstream.emit(event)
            return
        }
        // Learn the type as early as possible so EntityCreated itself is classifiable.
        if (event is AnimationEvent.EntityCreated) typeOf[event.entityId] = event.entityType

        val id = event.entityIdOrNull
        if (id == null) {
            downstream.emit(event)   // not keyed to an entity (resource/queue/response/agent/...) — always pass
            return
        }

        val type = typeOf[id]
        // The process relevant to *this* event: a process-lifecycle event uses its own; others use the last
        // known current process. (A null process — e.g. EntityCreated before any process — never excludes.)
        val process = when (event) {
            is AnimationEvent.ProcessActivated -> event.processName
            is AnimationEvent.ProcessCompleted -> event.processName
            else -> processOf[id]
        }

        val excluded = (type != null && type in excludedTypes) ||
            (type != null && process != null && "$type.$process" in excludedProcesses)

        // Maintain current-process state regardless of whether this event is dropped, so subsequent events of a
        // kept entity are gated against the right process.
        when (event) {
            is AnimationEvent.ProcessActivated -> processOf[id] = event.processName
            is AnimationEvent.ProcessCompleted, is AnimationEvent.EntityDisposed,
            is AnimationEvent.EntityTerminated -> processOf.remove(id)
            else -> {}
        }

        if (!excluded) downstream.emit(event)
    }

    override fun onReplicationStart(replicationNumber: Int): Unit = downstream.onReplicationStart(replicationNumber)
    override fun onReplicationEnd(replicationNumber: Int): Unit = downstream.onReplicationEnd(replicationNumber)
    override fun onExperimentEnd(): Unit = downstream.onExperimentEnd()
}
