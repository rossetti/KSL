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

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.RequestQ
import ksl.modeling.spatial.InterpolatedMovement
import ksl.modeling.spatial.LocationIfc
import ksl.modeling.spatial.MovePathIfc
import ksl.modeling.spatial.MovePurpose
import ksl.modeling.spatial.VehicleMovementIfc

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
 *  It also implements [VehicleMovementIfc], the movement seam a
 *  fleet is written against, so tours, dispatching and a manifest
 *  can be driven over a continuous projection by exactly the code
 *  that drives them over a guide path. See "The seam" below.
 *
 *  @param capacity initial resource capacity (default 1).
 *  @param queue optional shared request queue.
 *  @param velocity how fast [beginTravelTo] moves it. A property of
 *    the vehicle rather than of each call, because the seam's caller
 *    is a fleet that knows what a journey is *for* and not how fast
 *    this particular vehicle goes. [travelTo] still takes its own
 *    velocity per call and is unaffected.
 *  @param stepSize the interpolation step, in coordinate units. What
 *    a journey is discretised into, and therefore how quickly a
 *    redirection or a halt is observed: at most `stepSize/velocity`
 *    later.
 */
open class MovableAgentResource @JvmOverloads constructor(
    agentModel: AgentModel,
    val space: ContinuousProjection<AgentLike>,
    initPosition: Point2D,
    name: String? = null,
    capacity: Int = Defaults.capacity,
    queue: RequestQ? = null,
    var velocity: Double = Defaults.velocity,
    var stepSize: Double = Travel.Defaults.stepSize,
) : AgentResource(agentModel, name, capacity, queue), VehicleMovementIfc {

    init {
        require(velocity > 0.0) { "velocity must be positive; was $velocity" }
        require(stepSize > 0.0) { "stepSize must be positive; was $stepSize" }
    }

    /**
     *  Mutable global defaults for [MovableAgentResource] construction.
     */
    companion object Defaults {
        /** Default on-shift capacity for new movable agent resources. Must be positive. */
        var capacity: Int by positive(1)

        /** Default travel velocity for the movement seam. Must be positive. */
        var velocity: Double by positive(1.0)
    }

    /**
     *  Where it starts each replication.
     *
     *  Held rather than consumed, because a replication has to be able to put it back. See
     *  [initialize].
     */
    val initialPosition: Point2D = initPosition

    init {
        space.context.add(this)
        space.placeAt(this, initialPosition)
    }

    /**
     *  Puts the resource back where it was declared, at the start of every replication.
     *
     *  **Without this a replication began wherever the previous one stopped.** The context restores
     *  *membership* between replications -- an agent added during model construction stays a member
     *  -- but nothing restored a *position*, because the position was set once in an `init` block
     *  that runs at construction and never again. So replication 1 started at the declared point and
     *  every replication after it started whereever replication 1 happened to end, silently: the run
     *  completes, the statistics look plausible, and only a model whose answer depends on where
     *  vehicles begin would show it.
     *
     *  This is the same defect a manifest surviving a replication is, and it has the same shape: an
     *  interface cannot enforce it, so the class that owns the state has to be a `ModelElement` and
     *  override this. Membership is re-established defensively as well, so that a model which
     *  removed the resource from its context mid-replication still starts the next one whole.
     */
    override fun initialize() {
        super.initialize()
        if (this !in space.context) space.context.add(this)
        space.placeAt(this, initialPosition)
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

    // ---- the movement seam ---------------------------------------------------------------------
    //
    // `VehicleMovementIfc` is what a fleet needs from whatever moves its vehicles, and the guide
    // path was its first implementer. This is the second, and the point of there being two is that
    // the machinery above -- tours, stops, dispatching, the manifest -- is written once.
    //
    // **The seam could not be satisfied by handing it a TravelHandle, and that is the finding.**
    // `startTravel`/`awaitTravel` put the integration loop inside the *traveller's own process*: it
    // is `awaitTravel` that delays, steps and re-plans. The seam requires the opposite -- a command
    // that does not suspend, handing back the queue its caller must wait in -- because a fleet's
    // control loop has to be able to command a vehicle from somewhere other than the vehicle's
    // process, and because the two ends of a wait must not disagree about where the wake comes
    // from.
    //
    // So the clockwork is `InterpolatedMovement`, which lives in `ksl.modeling.spatial` -- below
    // this package and below `guidedpath`, which is where a thing both of them need belongs. What
    // is here is the *geometry*: how far apart two points on this projection are, where a fraction
    // of the way between them is, and how to put the resource there. `travelTo` and `awaitTravel`
    // are untouched and remain the way an agent moves itself; this is the way a fleet moves it.

    /** The plane this vehicle's positions are expressed in. One per projection. */
    val plane: ProjectionSpatialModel
        get() = space.spatialModel

    /** This projection, as a geometry the shared clockwork can move through. */
    private inner class ProjectionPath : MovePathIfc {

        override val positionNow: LocationIfc
            get() = plane.location(position)

        override fun distanceBetween(from: LocationIfc, to: LocationIfc): Double =
            space.distance(pointOf(from), pointOf(to))

        override fun isReachable(destination: LocationIfc): Boolean {
            if (destination !is ProjectionSpatialModel.ProjectedLocation) return false
            if (destination.spatialModel !== plane) return false
            val p = destination.point
            return space.torus || (p.x in space.xRange && p.y in space.yRange)
        }

        /**
         *  A plane can always say where between two points is. The delta is taken through the
         *  projection so that a torus interpolates the short way round, which is the way the
         *  vehicle would actually go.
         */
        override fun positionAlong(from: LocationIfc, to: LocationIfc, fraction: Double): LocationIfc {
            val f = pointOf(from)
            val d = space.delta(f, pointOf(to))
            return plane.location(Point2D(f.x + d.x * fraction, f.y + d.y * fraction))
        }

        override fun placeAt(location: LocationIfc) {
            space.moveTo(this@MovableAgentResource, pointOf(location))
        }

        private fun pointOf(location: LocationIfc): Point2D =
            (location as? ProjectionSpatialModel.ProjectedLocation)?.point
                ?: error(
                    "location (${location.name}) is not a location on projection " +
                            "(${space.name}); make one with space.spatialModel.location(x, y)"
                )
    }

    private val myMovement: InterpolatedMovement =
        InterpolatedMovement(this, ProjectionPath(), stepSize, { velocity })

    /** Where the vehicle is now, interpolated to this instant by the step that last completed. */
    override val positionNow: LocationIfc
        get() = plane.location(position)

    /**
     *  Straight-line distance across the projection, which on a plane **is** the path the vehicle
     *  would take. Wraps where the projection is a torus, because there the short way round is the
     *  way it would actually go.
     */
    override fun pathDistanceTo(destination: LocationIfc): Double =
        space.distance(position, pointOf(destination))

    /** True for any location on this plane and inside the projection's bounds. */
    override fun isReachable(destination: LocationIfc): Boolean {
        if (destination !is ProjectionSpatialModel.ProjectedLocation) return false
        if (destination.spatialModel !== plane) return false
        val p = destination.point
        return space.torus || (p.x in space.xRange && p.y in space.yRange)
    }

    override fun beginTravelTo(
        destination: LocationIfc,
        purpose: MovePurpose,
        waiter: ProcessModel.Entity
    ): HoldQueue? {
        myMovement.stepSize = stepSize
        return myMovement.beginTravelTo(destination, waiter)
    }

    override val isHalted: Boolean
        get() = myMovement.isHalted

    /**
     *  Stops the vehicle where it stands and wakes whoever was waiting for it.
     *
     *  The substrate's way of stopping a vehicle part way -- the counterpart of a guide path's
     *  movement gate refusing at a boundary. Whoever called this owns starting it again.
     */
    fun halt() = myMovement.halt()

    override fun resumeHalted() = myMovement.resumeHalted()

    override val distanceTravelled: Double
        get() = myMovement.distanceTravelled

    /**
     *  How long it has spent travelling this replication.
     *
     *  Accumulated a step at a time, so it is a step function rather than a continuous one. It does
     *  not include time seized-but-standing: what a vehicle has been *held* for is the resource's
     *  own busy time and is already reported as that.
     */
    override val operatingTime: Double
        get() = myMovement.operatingTime

    private fun pointOf(location: LocationIfc): Point2D =
        (location as? ProjectionSpatialModel.ProjectedLocation)?.point
            ?: error(
                "location (${location.name}) is not a location on projection (${space.name}); " +
                        "make one with space.spatialModel.location(x, y)"
            )
}
