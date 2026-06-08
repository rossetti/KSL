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

/**
 *  Continuous-time dynamics primitive for agents on a
 *  [ContinuousProjection]. Owns the per-agent velocity state and the
 *  list of [Force]s contributing to motion; the [step] method
 *  performs the Euler integration step (sum forces → integrate
 *  velocity → compute candidate position) without applying it.
 *  Applying the new state — including boundary handling — is the
 *  caller's responsibility, because boundary policy varies between
 *  models (toroidal wrap for boids, walled rejection for pedestrians).
 *
 *  Typical use:
 *
 *  ```kotlin
 *  val dynamics = Dynamics<Boid>(space).apply {
 *      addForce(separation(radius = 3.0))
 *      addForce(alignment(radius = 8.0))
 *      addForce(cohesion(radius = 10.0))
 *      maxSpeed = 4.0
 *      minSpeed = 1.0
 *  }
 *  // initial velocity
 *  dynamics.setVelocity(boid, Point2D(...))
 *
 *  // in the agent's process { } body:
 *  while (true) {
 *      val (vNew, pNew) = dynamics.step(boid, dt = 0.05)
 *      dynamics.setVelocity(boid, vNew)
 *      space.moveTo(boid, wrap(pNew))
 *      delay(0.05)
 *  }
 *  ```
 *
 *  For the common "no boundary handling needed" case use
 *  [runDynamics] which packages the entire loop into one suspending
 *  call.
 *
 *  Mass and speed clamps validate via the [Defaults] convention
 *  (setters throw `IllegalArgumentException` on invalid values).
 *  Forces and velocities aren't validated — Force factories produce
 *  bounded values by design.
 *
 *  @param space the projection that owns agent positions
 *  @param mass per-agent mass function. Default is unit mass for all.
 */
class Dynamics<A : AgentLike> @JvmOverloads constructor(
    val space: ContinuousProjection<A>,
    var mass: (A) -> Double = { Defaults.unitMass },
    maxSpeed: Double = Defaults.maxSpeed,
    minSpeed: Double = Defaults.minSpeed,
) {
    /**
     *  Hard upper bound on velocity magnitude after each integration
     *  step. Must be positive and `>= minSpeed`.
     */
    var maxSpeed: Double = maxSpeed
        set(value) {
            require(value > 0.0) { "maxSpeed must be positive; was $value" }
            require(value >= minSpeed) { "maxSpeed ($value) must be >= minSpeed ($minSpeed)" }
            field = value
        }

    /**
     *  Lower bound — velocities below this magnitude are rescaled back
     *  up to [minSpeed]. A body at *exactly* zero velocity is given a
     *  deterministic heading (a pure function of its identity, so runs
     *  stay reproducible) and boosted to [minSpeed] too, so a positive
     *  `minSpeed` truly guarantees the agent never comes to rest
     *  (boids). Set to 0 to allow agents to stop. Must be non-negative
     *  and `<= maxSpeed`.
     */
    var minSpeed: Double = minSpeed
        set(value) {
            require(value >= 0.0) { "minSpeed must be non-negative; was $value" }
            require(value <= maxSpeed) { "minSpeed ($value) must be <= maxSpeed ($maxSpeed)" }
            field = value
        }

    init {
        // Property initializers bypass the custom setters above, so
        // validate the constructor-supplied values here, including the
        // cross-constraint that the setters enforce on later mutation.
        require(maxSpeed > 0.0) { "maxSpeed must be positive; was $maxSpeed" }
        require(minSpeed >= 0.0) { "minSpeed must be non-negative; was $minSpeed" }
        require(minSpeed <= maxSpeed) { "minSpeed ($minSpeed) must be <= maxSpeed ($maxSpeed)" }
    }

    private val velocities: MutableMap<A, Point2D> = mutableMapOf()
    private val forces: MutableList<Force<A>> = mutableListOf()

    /** Mutable global defaults for [Dynamics]. */
    companion object Defaults {
        /** Default per-agent mass when no mass function is supplied. Must be positive. */
        var unitMass: Double by positive(1.0)
        /** Default maximum speed (no clamp by default). Must be positive. */
        var maxSpeed: Double by positive(Double.POSITIVE_INFINITY)
        /** Default minimum speed. Must be non-negative. */
        var minSpeed: Double by nonNegative(0.0)
    }

    /** Get the current velocity tracked for [agent], or null if not set. */
    fun velocityOf(agent: A): Point2D? = velocities[agent]

    /** Set / update the velocity tracked for [agent]. */
    fun setVelocity(agent: A, v: Point2D) {
        velocities[agent] = v
    }

    /** Stop tracking [agent] (called when an agent leaves the population). */
    fun untrack(agent: A) {
        velocities.remove(agent)
    }

    /** Append [force] to the summed forces. Forces are evaluated in order. */
    fun addForce(force: Force<A>) {
        forces.add(force)
    }

    /** Number of forces currently registered. */
    val forceCount: Int
        get() = forces.size

    /**
     *  One Euler integration step for [agent]: sum the registered
     *  forces, integrate velocity by `(sumForce / mass) * dt`, clamp
     *  to `[minSpeed, maxSpeed]`, compute the candidate position
     *  `pos + vNew * dt`.
     *
     *  Returns the pair `(newVelocity, candidatePosition)`. *Does not
     *  apply* the new state — the caller must call [setVelocity] and
     *  [ContinuousProjection.moveTo] explicitly (typically after
     *  validating or wrapping the candidate position).
     *
     *  @throws IllegalStateException if [agent] has no position in [space]
     *  @throws IllegalArgumentException if [dt] is non-positive
     */
    fun step(agent: A, dt: Double): Pair<Point2D, Point2D> {
        require(dt > 0.0) { "dt must be positive; was $dt" }
        val pos = space.positionOf(agent)
            ?: error("agent '${agent.name}' has no position in space '${space.name}'")
        val v0 = velocities[agent] ?: Point2D.ORIGIN

        var fx = 0.0
        var fy = 0.0
        for (force in forces) {
            val f = force.compute(agent, this, dt)
            fx += f.x
            fy += f.y
        }

        val m = mass(agent)
        require(m > 0.0) { "mass must be positive; was $m for agent '${agent.name}'" }
        val vRaw = Point2D(v0.x + (fx / m) * dt, v0.y + (fy / m) * dt)
        val vNew = clampSpeed(vRaw, agent)
        val pNew = Point2D(pos.x + vNew.x * dt, pos.y + vNew.y * dt)
        return vNew to pNew
    }

    /**
     *  Compute one Euler step for every agent in [agents] *without
     *  applying* any of them — a Jacobi (synchronous) update. Every
     *  step reads the current shared state, so the result is
     *  independent of the order of [agents], unlike calling [step] and
     *  applying per agent in a loop (which lets earlier agents' moves
     *  bias later ones — a Gauss-Seidel update that depends on
     *  iteration order). Use this when reproducibility / order
     *  independence matters, e.g. flocking.
     *
     *  Returns one `(agent, newVelocity, candidatePosition)` triple per
     *  agent in iteration order; the caller applies them (typically via
     *  [setVelocity] / [ContinuousProjection.moveTo]), optionally
     *  wrapping or rejecting the candidate position first.
     *
     *  @throws IllegalArgumentException if [dt] is non-positive
     */
    fun stepAll(agents: Collection<A>, dt: Double): List<Triple<A, Point2D, Point2D>> {
        require(dt > 0.0) { "dt must be positive; was $dt" }
        return agents.map { a -> val (v, p) = step(a, dt); Triple(a, v, p) }
    }

    private fun clampSpeed(v: Point2D, agent: A): Point2D {
        val m = v.magnitude
        return when {
            m > maxSpeed -> v * (maxSpeed / m)
            // Exactly at rest but a positive minSpeed is required: invent
            // a deterministic heading so the body never stays frozen.
            m == 0.0 && minSpeed > 0.0 -> jitterDirection2D(agent.name) * minSpeed
            m in 0.0..<minSpeed && m > 0.0 -> v * (minSpeed / m)
            else -> v
        }
    }
}

/**
 *  Run [agent] under [dynamics] until [until] returns true. Each
 *  iteration: compute the Euler step, store the new velocity, move
 *  the agent to the candidate position *as-is*, delay [dt].
 *
 *  This is the convenience entry point for the common case where no
 *  boundary handling is needed (e.g., flocking in an unbounded or
 *  toroidal space *if you set up `space` with `torus = true`* — note
 *  that for torus models you also want to wrap positions yourself or
 *  use [Dynamics] with a custom loop, since `ContinuousProjection`
 *  stores positions verbatim).
 *
 *  For models with walls, gates, or other position-rejection rules,
 *  write your own loop around [Dynamics.step] so you can validate
 *  the candidate position before applying.
 *
 *  ```kotlin
 *  val script = process(isDefaultProcess = true) {
 *      runDynamics(this@Boid, dynamics, dt = 0.05) { false }
 *  }
 *  ```
 *
 *  @throws IllegalArgumentException if [dt] is non-positive
 */
suspend fun <A : AgentLike> KSLProcessBuilder.runDynamics(
    agent: A,
    dynamics: Dynamics<A>,
    dt: Double = 0.05,
    until: () -> Boolean = { false },
) {
    require(dt > 0.0) { "dt must be positive; was $dt" }
    while (!until()) {
        val (vNew, pNew) = dynamics.step(agent, dt)
        dynamics.setVelocity(agent, vNew)
        dynamics.space.moveTo(agent, pNew)
        delay(dt)
    }
}

/**
 *  Drive a whole population under [dynamics] from a single controller
 *  process using a Jacobi (synchronous, order-independent) update. Each
 *  tick computes every agent's step from the shared current state via
 *  [Dynamics.stepAll], then applies them all, then delays [dt]. This is
 *  the batched analog of [runDynamics] and avoids the order-of-update
 *  bias you get when each agent runs its own [runDynamics] loop.
 *
 *  [agents] is re-evaluated each tick so the population may change
 *  (births / deaths). Override [apply] to inject boundary handling
 *  (e.g., toroidal wrap) before the candidate position is committed; by
 *  default the candidate is applied as-is.
 *
 *  ```kotlin
 *  val controller = process(isDefaultProcess = true) {
 *      runDynamicsAll(dynamics, agents = { flock.members }, dt = 0.05)
 *  }
 *  ```
 *
 *  @throws IllegalArgumentException if [dt] is non-positive
 */
suspend fun <A : AgentLike> KSLProcessBuilder.runDynamicsAll(
    dynamics: Dynamics<A>,
    agents: () -> Collection<A>,
    dt: Double = 0.05,
    until: () -> Boolean = { false },
    apply: (agent: A, vNew: Point2D, pNew: Point2D) -> Unit = { a, v, p ->
        dynamics.setVelocity(a, v)
        dynamics.space.moveTo(a, p)
    },
) {
    require(dt > 0.0) { "dt must be positive; was $dt" }
    while (!until()) {
        val results = dynamics.stepAll(agents(), dt)
        for ((a, v, p) in results) apply(a, v, p)
        delay(dt)
    }
}
