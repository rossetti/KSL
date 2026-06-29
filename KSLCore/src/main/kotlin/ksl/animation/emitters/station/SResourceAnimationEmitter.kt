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

package ksl.animation.emitters.station

import ksl.animation.AnimationEvent
import ksl.modeling.station.ResourceFailureListenerIfc
import ksl.modeling.station.SResource
import ksl.modeling.station.StationResourceIfc
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/**
 * Emits an [AnimationEvent.ResourceStateChanged] whenever the station-package [SResource]
 * changes state. This is the station counterpart to [ksl.animation.emitters.ResourceAnimationEmitter]
 * (Phase 2.2): `SResource` is a different type that does not fire `AllocationListenerIfc`, so it
 * needs its own emitter.
 *
 * It observes the resource's busy-count response — incremented on `seize`, decremented on
 * `release` — so every busy/idle transition produces an event; and it listens for failures via
 * [ResourceFailureListenerIfc] so the failed state is captured too. Register with both:
 *
 * ```kotlin
 * val emitter = SResourceAnimationEmitter(resource)
 * (resource.numBusyUnits as ModelElement).attachModelElementObserver(emitter)
 * resource.attachResourceFailureListener(emitter)
 * ```
 *
 * The reported state is one of "Failed", "Busy", "Idle", or "Inactive"; busy units are
 * `capacity - numAvailableUnits`. The resource is unchanged.
 *
 * @param resource the station resource to animate
 */
class SResourceAnimationEmitter(private val resource: SResource) : ModelElementObserver(), ResourceFailureListenerIfc {

    /** Fired when the busy-count response changes (seize/release). */
    override fun update(modelElement: ModelElement) {
        emitState()
    }

    /** Fired when the resource fails. */
    override fun resourceFailed(resource: StationResourceIfc) {
        emitState()
    }

    private fun emitState() {
        val sink = resource.model.animationSink
        if (sink.isActive) {
            val busyUnits = (resource.capacity - resource.numAvailableUnits).coerceAtLeast(0)
            sink.emit(AnimationEvent.ResourceStateChanged(resource.time, resource.name, stateName(), busyUnits, resource.capacity))
        }
    }

    private fun stateName(): String = when {
        resource.isFailed -> "Failed"
        resource.isBusy -> "Busy"
        resource.isIdle -> "Idle"
        else -> "Inactive"
    }
}
