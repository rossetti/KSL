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
 *  Namespace holding mutable, globally-overridable defaults for the
 *  agent-layer travel primitives ([travelTo], [travelThrough]).
 *
 *  Set values on [Travel.Defaults] once at model setup if you want
 *  every subsequent call without an explicit `stepSize =` argument to
 *  pick up a different value:
 *
 *  ```kotlin
 *  Travel.Defaults.stepSize = 0.25     // global override
 *  travelTo(agent, space, dest, velocity = 2.0)    // uses 0.25
 *  travelTo(agent, space, dest, velocity = 2.0, stepSize = 0.5)  // per-call override
 *  ```
 *
 *  Per-call overrides always win; the companion holds the fallback.
 */
object Travel {
    /** Mutable global defaults for travel primitives. */
    object Defaults {
        /**
         *  Default interpolation step size in *coordinate units of
         *  travel* for [travelTo] and [travelThrough] when the caller
         *  doesn't pass an explicit `stepSize`. Must be positive.
         *  See §11.5 of the agent-based-modeling design doc for the
         *  distance-vs-time rationale.
         */
        var stepSize: Double by positive(1.0)
    }
}

/**
 *  Outcome of a [travelTo] call. Exposed so callers can inspect
 *  what just happened — duration, distance, and the start/arrival
 *  times — without computing them externally.
 */
data class TravelResult(
    val startedAt: Double,
    val arrivedAt: Double,
    val distance: Double,
) {
    val duration: Double get() = arrivedAt - startedAt
    val averageVelocity: Double get() = if (duration > 0.0) distance / duration else 0.0
}

/**
 *  Move [agent] from its current position in [space] to
 *  [destination] at constant [velocity]. The traversal takes
 *  `distance / velocity` simulated time units; the projection is
 *  updated to interpolated intermediate positions every [stepSize]
 *  coordinate-units of travel so concurrent spatial queries
 *  (`space.within(...)`, `space.neighborsOf(...)`) reflect the
 *  moving agent's current position at step granularity.
 *
 *  Pattern (discrete-step continuous motion):
 *  ```
 *  inner class Vehicle : Agent("v") {
 *      val script: KSLProcess = process(isDefaultProcess = true) {
 *          travelTo(this@Vehicle, mySpace, destination = Point2D(50.0, 50.0), velocity = 2.0)
 *      }
 *  }
 *  ```
 *
 *  Granularity tradeoff: smaller [stepSize] gives finer-grained
 *  position updates at the cost of more events on the calendar. The
 *  parameter is in *distance* (coordinate units), not time, so the
 *  spatial fidelity is the same regardless of [velocity] — a fast
 *  vehicle and a slow vehicle using `stepSize = 0.5` both update
 *  every half-unit of travel. Pick a step that matches whatever
 *  spatial resolution matters: cell size for grid-aligned movement,
 *  collision radius for obstacle checks, pedestrian shoulder width
 *  for crowd queries.
 *
 *  Edge cases:
 *   - If `current == destination` (already at the target) the
 *     function returns immediately with `distance == 0.0`.
 *   - If `totalDistance <= stepSize`, a single delay-and-move is
 *     used (no interpolation needed).
 *   - The final position is set exactly to [destination] (not the
 *     accumulated interpolation result, which could drift due to
 *     floating-point error over many steps).
 *
 *  Limitations (deliberate v1 scope):
 *   - **Uninterruptible.** Once started, the travel runs to
 *     completion. To support "change direction mid-travel," wrap
 *     the call site in your own logic (e.g., short travels chained
 *     together) or use the existing `delay` + manual `moveTo`
 *     pattern.
 *   - **No collision detection.** The agent passes through other
 *     agents and obstacles. For obstacle-aware navigation use
 *     [GridGraph] to plan a path of waypoints, then `travelTo`
 *     each.
 *   - **Constant velocity.** Accelerate / decelerate isn't a
 *     primitive. Compose with multiple `travelTo` calls at
 *     different velocities if needed.
 *
 *  @param agent the moving agent; must be a member of [space]'s
 *    context and already placed somewhere.
 *  @param space the projection in which the motion occurs.
 *  @param destination target coordinates.
 *  @param velocity speed in coordinate-units-per-simulated-time;
 *    must be positive.
 *  @param stepSize interpolation interval in *coordinate units of
 *    travel*; must be positive.
 *  @return a [TravelResult] describing the completed motion.
 */
suspend fun <A : AgentLike> KSLProcessBuilder.travelTo(
    agent: A,
    space: ContinuousProjection<in A>,
    destination: Point2D,
    velocity: Double,
    stepSize: Double = Travel.Defaults.stepSize,
): TravelResult {
    require(velocity > 0.0) { "velocity must be positive; was $velocity" }
    require(stepSize > 0.0) { "stepSize must be positive; was $stepSize" }

    val current = space.positionOf(agent)
        ?: error(
            "agent '${agent.name}' has no position in projection '${space.name}'; " +
                "place it via space.placeAt(...) before calling travelTo",
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
        pos = Point2D(pos.x + direction.x * stepDist, pos.y + direction.y * stepDist)
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
 *  Travel a sequence of [waypoints] in order. Equivalent to a chain
 *  of [travelTo] calls, returning the cumulative result. The agent
 *  starts from its current position and visits each waypoint in
 *  order; the last waypoint is the final destination.
 *
 *  Useful in combination with [GridGraph.shortestPath]: compute a
 *  path of cells, convert each cell to its center coordinate, and
 *  travel through the waypoints.
 *
 *  See [travelTo] for the meaning of [stepSize].
 */
suspend fun <A : AgentLike> KSLProcessBuilder.travelThrough(
    agent: A,
    space: ContinuousProjection<in A>,
    waypoints: List<Point2D>,
    velocity: Double,
    stepSize: Double = Travel.Defaults.stepSize,
): TravelResult {
    require(waypoints.isNotEmpty()) { "waypoints must not be empty" }
    val startedAt = agent.currentTime
    var totalDistance = 0.0
    for (waypoint in waypoints) {
        val leg = travelTo(agent, space, waypoint, velocity, stepSize)
        totalDistance += leg.distance
    }
    return TravelResult(
        startedAt = startedAt,
        arrivedAt = agent.currentTime,
        distance = totalDistance,
    )
}

// ── Interruptible travel (§12.6) ────────────────────────────────────────────

/**
 *  Mutable handle representing an in-progress interruptible travel
 *  in 2D. Returned by [startTravel]; consumed by [awaitTravel].
 *  The integration loop inside `awaitTravel` reads this handle on
 *  every step, so external entities (dispatchers, statecharts,
 *  other agents) can interrupt or redirect by calling [cancel] /
 *  [redirect] from their own coroutine context.
 *
 *  Latency note: interruptions are observed at the next step
 *  boundary, with maximum latency `stepSize / velocity`. Tight
 *  control loops should use a smaller [stepSize] at the cost of
 *  more events.
 *
 *  See §12.6 of the agent-based-modeling design doc for the
 *  motivating use cases (AGV recall, mid-trip rerouting, low-battery
 *  divert, danger-signal pedestrian reroute, etc.).
 */
class TravelHandle<A : AgentLike> internal constructor(
    val agent: A,
    val space: ContinuousProjection<in A>,
    val startedAt: Double,
    initialDestination: Point2D,
    val velocity: Double,
    val stepSize: Double,
) {
    /** Current target. May differ from the original after [redirect]. */
    var destination: Point2D = initialDestination
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

    /**
     *  Cumulative distance the agent has actually moved through the
     *  projection since [startedAt]. Includes the prefixes of any
     *  legs that were truncated by [redirect] or [cancel].
     */
    var distanceTraveled: Double = 0.0
        internal set

    /**
     *  End time of the travel — set when the integration loop exits
     *  (complete or canceled). `Double.NaN` while still active.
     */
    var endedAt: Double = Double.NaN
        internal set

    /** Flag the integration loop checks to re-plan the direction. */
    internal var redirected: Boolean = false

    /**
     *  Stop motion at the agent's current interpolated position.
     *  The integration loop sees [isCanceled] on its next step check
     *  and exits with `isComplete = false`, `isCanceled = true`.
     *  [awaitTravel] then returns a `TravelResult` whose `arrivedAt`
     *  is the cancellation time and `distance` is the actual distance
     *  traveled so far.
     */
    fun cancel() {
        if (isActive) isCanceled = true
    }

    /**
     *  Change the destination mid-travel. The integration loop sees
     *  the new target on its next step boundary and re-plans the
     *  direction from the agent's current position. Cumulative
     *  [distanceTraveled] keeps growing across the redirect.
     */
    fun redirect(newDestination: Point2D) {
        if (!isActive) return
        destination = newDestination
        redirected = true
    }

    /**
     *  TravelResult snapshot. While active, `arrivedAt` reflects the
     *  current simulated time. After complete / canceled, it
     *  reflects the end time.
     */
    val result: TravelResult
        get() = TravelResult(
            startedAt = startedAt,
            arrivedAt = if (endedAt.isFinite()) endedAt else agent.currentTime,
            distance = distanceTraveled,
        )
}

/**
 *  Start an interruptible 2D travel. Returns the [TravelHandle]
 *  immediately *without* suspending — the caller must then either
 *  call [awaitTravel] to drive the integration to completion, or
 *  hand the handle to another entity (statechart, dispatcher,
 *  controller) that will manage the integration.
 *
 *  Typical usage (single-agent, self-driven):
 *
 *  ```kotlin
 *  val handle = startTravel(this@Drone, airspace, dest, velocity = 5.0)
 *  controller.currentTravel = handle    // expose for external cancel
 *  val result = awaitTravel(handle)
 *  if (result.isLessThanExpected) returnToBase()
 *  ```
 *
 *  See [TravelHandle] for cancel / redirect semantics.
 */
fun <A : AgentLike> KSLProcessBuilder.startTravel(
    agent: A,
    space: ContinuousProjection<in A>,
    destination: Point2D,
    velocity: Double,
    stepSize: Double = Travel.Defaults.stepSize,
): TravelHandle<A> {
    require(velocity > 0.0) { "velocity must be positive; was $velocity" }
    require(stepSize > 0.0) { "stepSize must be positive; was $stepSize" }
    space.positionOf(agent)
        ?: error(
            "agent '${agent.name}' has no position in projection '${space.name}'; " +
                "place it via space.placeAt(...) before calling startTravel",
        )
    return TravelHandle(
        agent = agent,
        space = space,
        startedAt = agent.currentTime,
        initialDestination = destination,
        velocity = velocity,
        stepSize = stepSize,
    )
}

/**
 *  Drive the integration loop for [handle] to completion (or
 *  cancellation). Suspends. Returns the final [TravelResult].
 *
 *  Each step:
 *   - If [handle].`isCanceled` is true, break.
 *   - If [handle].`destination` differs from the current direction
 *     target (set by [TravelHandle.redirect]), re-plan from the
 *     current position.
 *   - Compute step distance `min(stepSize, remaining)`, delay by
 *     `stepDist / velocity`, advance position by that step.
 *   - If within ε of the destination, snap exactly and mark complete.
 *
 *  The agent's position is updated via [ContinuousProjection.moveTo]
 *  at each step, so concurrent spatial queries see the agent moving.
 */
suspend fun <A : AgentLike> KSLProcessBuilder.awaitTravel(handle: TravelHandle<A>): TravelResult {
    if (!handle.isActive) return handle.result
    var pos = handle.space.positionOf(handle.agent)
        ?: error("agent '${handle.agent.name}' has no position; was it removed from the context?")

    while (handle.isActive) {
        // Plan / re-plan direction from the current position to the
        // current destination. The redirect flag is reset here so
        // subsequent steps along the same leg don't replan again.
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

        // Cancel observed during the delay → stop where we'd be at
        // the end of the (just-completed) step.
        if (handle.isCanceled) {
            // Advance position by the step we just delayed for so
            // continuous motion is consistent with the elapsed time.
            pos = Point2D(pos.x + direction.x * stepDist, pos.y + direction.y * stepDist)
            handle.space.moveTo(handle.agent, pos)
            handle.distanceTraveled += stepDist
            handle.endedAt = handle.agent.currentTime
            break
        }

        // Step completed normally. Advance position and check arrival.
        pos = if (stepDist >= remaining - 1e-12) {
            // Final step: snap exactly to destination to avoid drift.
            handle.destination
        } else {
            Point2D(pos.x + direction.x * stepDist, pos.y + direction.y * stepDist)
        }
        handle.space.moveTo(handle.agent, pos)
        handle.distanceTraveled += stepDist

        // Arrived?
        if (pos.distanceTo(handle.destination) <= 1e-9 && !handle.redirected) {
            handle.endedAt = handle.agent.currentTime
            handle.isComplete = true
            break
        }
        // Otherwise loop. If redirected was set during the delay,
        // the next iteration's plan-from-current-position picks up
        // the new destination naturally.
    }
    return handle.result
}
