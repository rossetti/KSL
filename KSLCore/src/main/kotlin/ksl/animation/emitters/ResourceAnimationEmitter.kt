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

package ksl.animation.emitters

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.modeling.entity.Allocation
import ksl.modeling.entity.AllocationListenerIfc
import ksl.modeling.entity.Resource

/**
 * An [AllocationListenerIfc] that animates a [Resource]: on each allocation it emits
 * an [AnimationEvent.SeizeAllocated] (which entity got how many units), on each
 * deallocation an [AnimationEvent.Released], and after either it emits an
 * [AnimationEvent.ResourceStateChanged] reporting the resource's new state name,
 * busy-unit count, and capacity. Register one with
 * `resource.addAllocationListener(ResourceAnimationEmitter(resource))`; the resource
 * is unchanged.
 *
 * Per the design's division of responsibility (decision D4), this listener owns the
 * resource's state and the canonical allocate/release events; the entity-experience
 * events (`SeizeQueued`/`SeizeWaiting`) are emitted separately from the process
 * coroutine, so there is no duplication. Because `SResource` (the station-package
 * resource) is a different type that does not fire `AllocationListenerIfc`, it has
 * its own emitter (Phase 2B) — this one is for the process-view `Resource`.
 *
 * The resource notifies its listeners after applying the allocate/deallocate, so the
 * state read here is already up to date.
 *
 * @param resource the resource this emitter is attached to
 */
class ResourceAnimationEmitter(private val resource: Resource) : AllocationListenerIfc {

    override fun allocate(allocation: Allocation) {
        val sink = resource.model.animationSink
        if (!sink.isActive) return
        sink.emit(
            AnimationEvent.SeizeAllocated(resource.time, allocation.myEntity.id, resource.name, allocation.amount)
        )
        emitState(sink)
    }

    override fun deallocate(allocation: Allocation) {
        val sink = resource.model.animationSink
        if (!sink.isActive) return
        sink.emit(
            AnimationEvent.Released(resource.time, allocation.myEntity.id, resource.name, allocation.amountReleased)
        )
        emitState(sink)
    }

    private fun emitState(sink: AnimationSink) {
        sink.emit(
            AnimationEvent.ResourceStateChanged(
                resource.time, resource.name, resource.state.name, resource.numBusy, resource.capacity
            )
        )
    }
}
