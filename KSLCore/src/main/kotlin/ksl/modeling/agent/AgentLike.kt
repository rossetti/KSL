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

/**
 *  The minimal contract a [Statechart] needs from its owner. Both
 *  [AgentModel.Agent] and [AgentResource] implement this interface, so a
 *  statechart can govern either kind of actor without the statechart
 *  runtime having to know which it is.
 *
 *  Statechart action handlers receive an `AgentLike` reference rather
 *  than a concrete type. When a handler needs to read or mutate
 *  owner-specific state (a worker agent's task queue, a forklift's
 *  battery level), cast to the concrete type at the call site.
 */
interface AgentLike {

    /**
     *  Default mailbox for receiving [AgentMessage] traffic. Routes
     *  through the enclosing [AgentModel]'s shared message bus.
     */
    val mailbox: AgentModel.AgentMailbox<AgentMessage>

    /**
     *  Display name, used for diagnostic logging by the statechart
     *  runtime and example code.
     */
    val name: String

    /**
     *  The current simulation time. Provided here so action handlers
     *  can log or compute durations without having to reach for the
     *  owner's outer model element.
     */
    val currentTime: Double

    /**
     *  Optional statechart governing this agent's reactive behavior.
     *  Default `null` for `AgentLike` implementations that don't
     *  provide a statechart abstraction. The concrete agent types
     *  ([AgentModel.Agent], [AgentModel.PermanentAgent], [AgentResource])
     *  each override this with their own backing field.
     */
    val statechart: AgentModel.Statechart?
        get() = null
}
