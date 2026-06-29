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
import ksl.modeling.agent.AgentModel
import ksl.simulation.Model

/**
 * An [AgentModel.StatechartObserver] that emits agent statechart events:
 * [AnimationEvent.AgentStateEntered], [AnimationEvent.AgentStateExited], and
 * [AnimationEvent.AgentTransition]. Attach one per agent to that agent's statechart
 * (`agent.statechart?.addObserver(StatechartAnimationEmitter(agent.name, model))`).
 *
 * Because the statechart callbacks carry only the state name and time (not the agent),
 * each instance is bound to a single [agentName].
 *
 * @param agentName the name of the agent whose statechart this observes
 * @param model the run-wide model, for its animation sink
 */
class StatechartAnimationEmitter(
    private val agentName: String,
    private val model: Model
) : AgentModel.StatechartObserver {

    override fun onStateEntered(stateName: String, time: Double) {
        emit(AnimationEvent.AgentStateEntered(time, agentName, stateName))
    }

    override fun onStateExited(stateName: String, time: Double) {
        emit(AnimationEvent.AgentStateExited(time, agentName, stateName))
    }

    override fun onTransition(fromState: String, toState: String, time: Double) {
        emit(AnimationEvent.AgentTransition(time, agentName, fromState, toState))
    }

    private fun emit(event: AnimationEvent) {
        val sink = model.animationSink
        if (sink.isActive) sink.emit(event)
    }
}
