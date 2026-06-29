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

/**
 * An [AgentModel.AgentRegistryObserver] that emits an [AnimationEvent.AgentRegistered]
 * whenever an agent is registered with the model. Attach it with
 * `agentModel.attachRegistryObserver(AgentRegistryAnimationEmitter(agentModel))`; the
 * agent model is unchanged.
 *
 * The agent's name is its identity in agent events, and the agent's runtime class name
 * is used as its type (so the renderer can style by agent kind).
 *
 * Note: the registry observer only fires for agents registered *after* it is attached
 * (typically transient agents created during a run). Agents that already exist when the
 * observer is attached (setup-time/permanent agents) are not re-announced here; the
 * animation controller emits a one-time snapshot of those at attach (the wiring step).
 * There is no removal hook on the registry, so `AgentRemoved` is not emitted yet.
 *
 * @param agentModel the model whose agent registrations to observe (and whose
 *        animation sink to emit to)
 */
class AgentRegistryAnimationEmitter(private val agentModel: AgentModel) : AgentModel.AgentRegistryObserver {

    override fun onAgentRegistered(agent: AgentLike) {
        val sink = agentModel.model.animationSink
        if (sink.isActive) {
            sink.emit(
                AnimationEvent.AgentRegistered(
                    agent.currentTime, agent.name, agent.javaClass.simpleName.ifEmpty { "Agent" }
                )
            )
        }
    }
}
