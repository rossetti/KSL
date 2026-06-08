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
 *  A relational or positional structure layered over an
 *  [AgentModel.Context]. Borrowed from Repast Simphony's terminology:
 *  a context owns *membership* (who is in this collection), while
 *  projections add *structure* (where each member is, who's related
 *  to whom). A single context can have multiple projections layered
 *  on top — an agent can simultaneously have a 2D position and
 *  membership in a network without those abstractions interfering.
 *
 *  Concrete projections in Phase 3 v1:
 *   - [ContinuousProjection] — 2D Euclidean positions with neighbor
 *     queries.
 *
 *  Grid and network projections may be added in a later phase. The
 *  interface stays minimal so adding new projection types is purely
 *  additive.
 *
 *  Lifecycle: a projection is attached to its context via
 *  [AgentModel.Context.addProjection]. When agents join or leave the
 *  context, the context calls [onAgentJoined] / [onAgentLeft] on each
 *  attached projection so the projection can update its bookkeeping
 *  (drop the agent's position, edges, etc.).
 */
interface Projection<A : AgentLike> {

    /** Display name for diagnostics. */
    val name: String

    /**
     *  Called by the [AgentModel.Context] when [agent] joins. Default
     *  no-op — projections that need to do something (e.g., place the
     *  agent at a default position) override.
     */
    fun onAgentJoined(agent: A) {}

    /**
     *  Called by the [AgentModel.Context] when [agent] leaves. Default
     *  no-op — projections that track per-agent state (positions,
     *  edges) typically override to drop their bookkeeping for the
     *  departing agent.
     */
    fun onAgentLeft(agent: A) {}
}
