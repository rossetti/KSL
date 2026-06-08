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

import ksl.modeling.entity.RequestQ

/**
 *  An [AgentResource] whose position is tracked in a
 *  [ContinuousProjection]. Composes the agent-resource semantics
 *  (seizable, queueable, on-shift / off-shift, statechart, mailbox,
 *  optional `AgentPerformance` stats) with continuous-space
 *  position tracking. Spatial queries on the projection
 *  (`space.within(p, r)`, `space.neighborsOf(...)`) automatically
 *  see this resource at its current position.
 *
 *  The agent-layer analog of `ksl.modeling.spatial.MovableResource`,
 *  but built on agent-layer primitives. Differences from the
 *  spatial-layer version:
 *
 *   - Position lives in a `ContinuousProjection`, not a `SpatialModel`.
 *     Spatial queries on the projection see this resource alongside
 *     any other agents.
 *   - Movement uses the agent-layer [travelTo] primitive (or any
 *     other code that updates the projection's positions). No
 *     built-in `KSLProcess.transportWith(this)` integration — for
 *     that path, use the spatial-layer `MovableResource` directly
 *     (possibly with the Phase 4.1 bridge for shared coordinates).
 *   - Velocity isn't a fixed property of the resource. Each travel
 *     specifies its own velocity, which is more flexible (allows
 *     loaded vs. empty velocities, fast vs. slow modes) at the
 *     cost of one extra parameter per call site.
 *
 *  Typical usage:
 *
 *  ```kotlin
 *  class Warehouse(parent: ModelElement) : AgentModel(parent, "warehouse") {
 *      val world: Context<AgentLike> = Context("world")
 *      val floor: ContinuousProjection<AgentLike> =
 *          ContinuousProjection(world, 0.0..100.0, 0.0..100.0)
 *
 *      val forklift: MovableAgentResource = MovableAgentResource(
 *          this, floor, initPosition = Point2D(50.0, 50.0), name = "forklift-1",
 *      )
 *
 *      inner class TaskRunner : Agent("runner") {
 *          val script: KSLProcess = process(isDefaultProcess = true) {
 *              val allocation = seize(forklift)
 *              travelTo(forklift, floor, Point2D(80.0, 20.0), velocity = 2.5)
 *              delay(2.0)  // load
 *              travelTo(forklift, floor, Point2D(10.0, 90.0), velocity = 2.5)
 *              delay(2.0)  // unload
 *              release(allocation)
 *          }
 *      }
 *  }
 *  ```
 *
 *  Lifecycle: a `MovableAgentResource` is a `ResourceWithQ` (via
 *  `AgentResource`), so it's a `ModelElement` and must be
 *  constructed before `simulate()`. It joins its projection's
 *  context automatically at construction and is placed at
 *  [initPosition].
 *
 *  Constraints on type variance: the projection is typed as
 *  `ContinuousProjection<AgentLike>` so the same projection can
 *  hold both `Agent`s and `MovableAgentResource`s (and any other
 *  `AgentLike` types). Models that need a more specific projection
 *  type can either keep separate projections per type or upcast
 *  this resource to `AgentLike` at the call site.
 *
 *  @param agentModel the enclosing `AgentModel`; required as
 *    [AgentResource] needs an `AgentModel` parent for its mailbox.
 *  @param space the projection that tracks this resource's
 *    position. Must hold `AgentLike` (or compatible) members.
 *  @param initPosition starting position in the projection.
 *  @param name optional name; defaults to `MovableAgentResource_<id>`.
 *  @param capacity initial resource capacity (default 1).
 *  @param queue optional shared request queue.
 */
open class MovableAgentResource @JvmOverloads constructor(
    agentModel: AgentModel,
    val space: ContinuousProjection<AgentLike>,
    initPosition: Point2D,
    name: String? = null,
    capacity: Int = Defaults.capacity,
    queue: RequestQ? = null,
) : AgentResource(agentModel, name, capacity, queue) {

    /**
     *  Mutable global defaults for [MovableAgentResource] construction.
     */
    companion object Defaults {
        /** Default on-shift capacity for new movable agent resources. Must be positive. */
        var capacity: Int by positive(1)
    }

    init {
        space.context.add(this)
        space.placeAt(this, initPosition)
    }

    /**
     *  Current position in [space]. Throws if the resource has
     *  somehow lost its position (should never happen during normal
     *  use, since the resource joins the context at construction
     *  and only leaves on explicit `context.remove`).
     */
    val position: Point2D
        get() = space.positionOf(this)
            ?: error("MovableAgentResource '${this.name}' has no position in projection '${space.name}'")

    /**
     *  Instantly place at the given point, bypassing any motion-time
     *  semantics. For continuous-time movement use [travelTo] from
     *  inside a `process { }` body.
     */
    fun placeAt(point: Point2D) {
        space.placeAt(this, point)
    }
}
