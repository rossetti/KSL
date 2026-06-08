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

import ksl.modeling.entity.KSLProcessBuilder
import kotlin.math.ceil

/**
 *  Move [agent] from its current 3D position in [space] to
 *  [destination] at constant [velocity]. The 3D analog of [travelTo].
 *
 *  The traversal takes `distance / velocity` simulated time units;
 *  the projection is updated to interpolated intermediate positions
 *  every [stepSize] coordinate-units of travel so concurrent spatial
 *  queries (`space.within(...)`, `space.neighborsOf(...)`) reflect
 *  the moving agent's current 3D position at step granularity.
 *
 *  Pattern (discrete-step continuous motion in 3D):
 *  ```
 *  inner class Drone : Agent("d") {
 *      val script: KSLProcess = process(isDefaultProcess = true) {
 *          travelTo3D(this@Drone, airspace,
 *              destination = Point3D(50.0, 50.0, 30.0),
 *              velocity = 5.0)
 *      }
 *  }
 *  ```
 *
 *  Reuses [Travel.Defaults.stepSize] for the default — the
 *  parameter is in *coordinate units of travel* regardless of
 *  dimensionality, so 2D and 3D models share the same notion of
 *  spatial step granularity.
 *
 *  Reuses [TravelResult] — durations, distances, and start/arrival
 *  times are scalar regardless of how many dimensions the motion
 *  spans.
 *
 *  Edge cases (same as the 2D version):
 *   - If `current == destination` the function returns immediately
 *     with `distance == 0.0`.
 *   - If `totalDistance <= stepSize`, a single delay-and-snap is
 *     used.
 *   - The final position is set exactly to [destination] (no
 *     floating-point drift).
 *
 *  Limitations (same as the 2D version): uninterruptible; no
 *  collision detection; constant velocity per call. See §12.6 of
 *  the agent-based-modeling design doc for the deferred
 *  interruptible-travel work.
 *
 *  @param agent the moving agent; must be a member of [space]'s
 *    context and already placed somewhere.
 *  @param space the 3D projection in which the motion occurs.
 *  @param destination target 3D coordinates.
 *  @param velocity speed in coordinate-units-per-simulated-time;
 *    must be positive.
 *  @param stepSize interpolation interval in *coordinate units of
 *    travel*; must be positive.
 *  @return a [TravelResult] describing the completed motion.
 */
suspend fun <A : AgentLike> KSLProcessBuilder.travelTo3D(
    agent: A,
    space: ContinuousVolume<in A>,
    destination: Point3D,
    velocity: Double,
    stepSize: Double = Travel.Defaults.stepSize,
): TravelResult {
    require(velocity > 0.0) { "velocity must be positive; was $velocity" }
    require(stepSize > 0.0) { "stepSize must be positive; was $stepSize" }

    val current = space.positionOf(agent)
        ?: error(
            "agent '${agent.name}' has no position in volume '${space.name}'; " +
                "place it via space.placeAt(...) before calling travelTo3D",
        )

    val startedAt = agent.currentTime
    val totalDistance = current.distanceTo(destination)

    // Already there — return immediately.
    if (totalDistance == 0.0) {
        return TravelResult(startedAt = startedAt, arrivedAt = startedAt, distance = 0.0)
    }

    // Short hop: just delay and snap to destination, no interpolation needed.
    if (totalDistance <= stepSize) {
        delay(totalDistance / velocity)
        space.moveTo(agent, destination)
        return TravelResult(
            startedAt = startedAt,
            arrivedAt = agent.currentTime,
            distance = totalDistance,
        )
    }

    // Multi-step travel. Compute number of steps from distance,
    // then derive per-step duration from velocity.
    val numSteps = ceil(totalDistance / stepSize).toInt()
    val stepDist = totalDistance / numSteps
    val stepDt = stepDist / velocity
    val direction = (destination - current).normalized()

    var pos = current
    // Take numSteps - 1 interpolated steps; the final step jumps to
    // destination exactly so floating-point drift never matters.
    for (i in 1 until numSteps) {
        delay(stepDt)
        pos = Point3D(
            pos.x + direction.x * stepDist,
            pos.y + direction.y * stepDist,
            pos.z + direction.z * stepDist,
        )
        space.moveTo(agent, pos)
    }
    delay(stepDt)
    space.moveTo(agent, destination)

    return TravelResult(
        startedAt = startedAt,
        arrivedAt = agent.currentTime,
        distance = totalDistance,
    )
}

/**
 *  Travel a sequence of [waypoints] in 3D order. Equivalent to a
 *  chain of [travelTo3D] calls, returning the cumulative result.
 *  The agent starts from its current position and visits each
 *  waypoint in order; the last waypoint is the final destination.
 *
 *  Useful in combination with [VoxelGraph.shortestPath]: compute a
 *  path of voxels, convert each to its center coordinate via
 *  [FlowField3D.centerOf] (or your own helper), and travel through
 *  the waypoints.
 *
 *  See [travelTo3D] for the meaning of [stepSize].
 */
suspend fun <A : AgentLike> KSLProcessBuilder.travelThrough3D(
    agent: A,
    space: ContinuousVolume<in A>,
    waypoints: List<Point3D>,
    velocity: Double,
    stepSize: Double = Travel.Defaults.stepSize,
): TravelResult {
    require(waypoints.isNotEmpty()) { "waypoints must not be empty" }
    val startedAt = agent.currentTime
    var totalDistance = 0.0
    for (waypoint in waypoints) {
        val leg = travelTo3D(agent, space, waypoint, velocity, stepSize)
        totalDistance += leg.distance
    }
    return TravelResult(
        startedAt = startedAt,
        arrivedAt = agent.currentTime,
        distance = totalDistance,
    )
}

// ── Interruptible 3D travel (§12.6) ─────────────────────────────────────────

/**
 *  Mutable handle representing an in-progress interruptible 3D
 *  travel. The 3D analog of [TravelHandle]. Same semantics: returned
 *  by [startTravel3D]; consumed by [awaitTravel3D]; cancellable /
 *  redirectable from any coroutine with a reference to the handle.
 *
 *  Latency for interruption is `stepSize / velocity` at most (one
 *  step boundary). See §12.6 of the agent-based-modeling design doc
 *  for use cases (drone recall, mid-flight reroute on weather, etc.).
 */
class TravelHandle3D<A : AgentLike> internal constructor(
    val agent: A,
    val space: ContinuousVolume<in A>,
    val startedAt: Double,
    initialDestination: Point3D,
    val velocity: Double,
    val stepSize: Double,
) {
    /** Current target. May differ from the original after [redirect]. */
    var destination: Point3D = initialDestination
        internal set

    /** True after a successful arrival at the (possibly redirected) destination. */
    var isComplete: Boolean = false
        internal set

    /** True after [cancel] is called. */
    var isCanceled: Boolean = false
        internal set

    /** True while the travel is in progress (not yet complete or canceled). */
    val isActive: Boolean
        get() = !isComplete && !isCanceled

    /** Cumulative 3D distance the agent has moved through the volume since [startedAt]. */
    var distanceTraveled: Double = 0.0
        internal set

    /** End time of the travel — `Double.NaN` while still active. */
    var endedAt: Double = Double.NaN
        internal set

    internal var redirected: Boolean = false

    /** Stop motion at the agent's current interpolated 3D position. */
    fun cancel() {
        if (isActive) isCanceled = true
    }

    /** Change the destination mid-travel. */
    fun redirect(newDestination: Point3D) {
        if (!isActive) return
        destination = newDestination
        redirected = true
    }

    /** TravelResult snapshot. */
    val result: TravelResult
        get() = TravelResult(
            startedAt = startedAt,
            arrivedAt = if (endedAt.isFinite()) endedAt else agent.currentTime,
            distance = distanceTraveled,
        )
}

/**
 *  Start an interruptible 3D travel. Returns the [TravelHandle3D]
 *  immediately without suspending. Caller drives the integration via
 *  [awaitTravel3D].
 *
 *  See [startTravel] for the 2D version and motivating examples.
 */
fun <A : AgentLike> KSLProcessBuilder.startTravel3D(
    agent: A,
    space: ContinuousVolume<in A>,
    destination: Point3D,
    velocity: Double,
    stepSize: Double = Travel.Defaults.stepSize,
): TravelHandle3D<A> {
    require(velocity > 0.0) { "velocity must be positive; was $velocity" }
    require(stepSize > 0.0) { "stepSize must be positive; was $stepSize" }
    space.positionOf(agent)
        ?: error(
            "agent '${agent.name}' has no position in volume '${space.name}'; " +
                "place it via space.placeAt(...) before calling startTravel3D",
        )
    return TravelHandle3D(
        agent = agent,
        space = space,
        startedAt = agent.currentTime,
        initialDestination = destination,
        velocity = velocity,
        stepSize = stepSize,
    )
}

/**
 *  Drive the 3D integration loop for [handle] to completion or
 *  cancellation. Suspends. Returns the final [TravelResult].
 *
 *  Same semantics as [awaitTravel] in 2D, extended to a third axis.
 */
suspend fun <A : AgentLike> KSLProcessBuilder.awaitTravel3D(
    handle: TravelHandle3D<A>,
): TravelResult {
    if (!handle.isActive) return handle.result
    var pos = handle.space.positionOf(handle.agent)
        ?: error("agent '${handle.agent.name}' has no position; was it removed from the context?")

    while (handle.isActive) {
        handle.redirected = false
        val remaining = pos.distanceTo(handle.destination)
        if (remaining <= 0.0) {
            handle.endedAt = handle.agent.currentTime
            handle.isComplete = true
            break
        }
        val stepDist = kotlin.math.min(handle.stepSize, remaining)
        val direction = (handle.destination - pos).normalized()
        val stepDt = stepDist / handle.velocity

        delay(stepDt)

        if (handle.isCanceled) {
            pos = Point3D(
                pos.x + direction.x * stepDist,
                pos.y + direction.y * stepDist,
                pos.z + direction.z * stepDist,
            )
            handle.space.moveTo(handle.agent, pos)
            handle.distanceTraveled += stepDist
            handle.endedAt = handle.agent.currentTime
            break
        }

        pos = if (stepDist >= remaining - 1e-12) {
            handle.destination
        } else {
            Point3D(
                pos.x + direction.x * stepDist,
                pos.y + direction.y * stepDist,
                pos.z + direction.z * stepDist,
            )
        }
        handle.space.moveTo(handle.agent, pos)
        handle.distanceTraveled += stepDist

        if (pos.distanceTo(handle.destination) <= 1e-9 && !handle.redirected) {
            handle.endedAt = handle.agent.currentTime
            handle.isComplete = true
            break
        }
    }
    return handle.result
}
