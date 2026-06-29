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
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement

/**
 * A [ModelElementObserver] that emits an [AnimationEvent.ResponseObserved] each time
 * a [Response] (including its time-weighted subtype `TWResponse`) or a [Counter] is
 * observed. Setting a response's or counter's value fires a `Status.UPDATE`
 * notification, which routes here as [update].
 *
 * Attach one instance to each statistical variable you want to animate
 * (`response.attachModelElementObserver(emitter)`); a single instance can observe
 * many variables because [update] receives the element that fired.
 *
 * @param name optional observer name
 */
class ResponseAnimationEmitter(name: String? = null) : ModelElementObserver(name) {

    override fun update(modelElement: ModelElement) {
        val sink = modelElement.model.animationSink
        if (!sink.isActive) return
        when (modelElement) {
            is Response -> {
                // Carry the current within-replication statistics so a renderer can show a live
                // summary without recomputing (D11).
                val s = modelElement.withinReplicationStatistic
                sink.emit(
                    AnimationEvent.ResponseObserved(
                        modelElement.time, modelElement.name, modelElement.value,
                        count = s.count, average = s.weightedAverage, min = s.min, max = s.max
                    )
                )
            }
            is Counter -> sink.emit(AnimationEvent.ResponseObserved(modelElement.time, modelElement.name, modelElement.value))
            else -> return // attached only to responses/counters; ignore anything else
        }
    }
}
