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
import ksl.modeling.agent.ContinuousProjection
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

/**
 * The non-invasive, opt-in agent-position capture (decision D6 option b): a model element
 * that, every [samplingInterval] simulated time units, reads each agent's position in
 * [projection] and emits an [AnimationEvent.AgentPositionSampled]. It does not touch the
 * agent package; it only reads positions.
 *
 * This is coarser than the projection `placeAt`/`moveTo` hook (the default, decision D6
 * option a / Phase 3A.1): it samples on a fixed clock, so sub-interval motion is aliased.
 * Use it when an engine touch is undesirable; use the hook for full fidelity.
 *
 * @param parent the model element to attach to (e.g. the agent model)
 * @param projection the continuous projection whose agents' positions to sample
 * @param samplingInterval the time between samples (> 0)
 * @param name optional element name
 */
class AgentPositionSampler<A : AgentLike>(
    parent: ModelElement,
    private val projection: ContinuousProjection<A>,
    private val samplingInterval: Double,
    name: String? = null
) : ModelElement(parent, name) {

    init {
        require(samplingInterval > 0.0) { "samplingInterval ($samplingInterval) must be > 0.0" }
    }

    private val sampleAction = SampleAction()

    override fun initialize() {
        sampleAction.schedule(samplingInterval)
    }

    private inner class SampleAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            val sink = model.animationSink
            if (sink.isActive) {
                for (agent in projection.context.members) {
                    val pos = projection.positionOf(agent) ?: continue
                    sink.emit(AnimationEvent.AgentPositionSampled(time, agent.name, projection.name, pos.x, pos.y))
                }
            }
            sampleAction.schedule(samplingInterval)
        }
    }
}
