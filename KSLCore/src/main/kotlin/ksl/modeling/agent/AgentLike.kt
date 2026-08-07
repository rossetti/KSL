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
 *  The minimal contract a `Statechart` needs from its owner. Both
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

    /**
     *  Tear down this agent's *behaviour*: stop its statechart and clear its
     *  mailbox. Call it when an agent is finished — a pedestrian that has
     *  evacuated, a customer that has departed, a vehicle taken out of service.
     *
     *  Without it a departed agent keeps running. A statechart holds scheduled
     *  timeout and condition events, so a pending trigger will still fire after the
     *  agent has left the population, transitioning its state and running its entry
     *  and exit actions; and messages delivered to its mailbox afterwards accumulate
     *  unread. Neither is corrected until end of replication.
     *
     *  **This is deliberately separate from context membership.**
     *  `AgentModel.Context.remove` means "no longer part of this population" — it
     *  updates membership, notifies projections, and tells the animation layer to
     *  stop drawing the agent. It does *not* mean the agent is finished, because an
     *  agent may legitimately leave one context and join another. So removal does
     *  not dispose, and the usual departure is both:
     *
     *  ```
     *  context.remove(agent)
     *  agent.dispose()
     *  ```
     *
     *  Disposal is idempotent and does not remove the agent from any context, end a
     *  running `process { }` body, or prevent the agent being used again — a
     *  statechart can be restarted with `statechart?.start()`.
     */
    fun dispose() {
        statechart?.stop()
        mailbox.reset()
    }
}
