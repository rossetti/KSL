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
 *  3D analog of [Dynamics]. Continuous-time dynamics primitive for
 *  agents on a [ContinuousVolume]; owns the per-agent 3D velocity
 *  state and the list of [Force3D]s contributing to motion. The
 *  [step] method performs the Euler integration step (sum forces →
 *  integrate velocity → compute candidate position) without
 *  applying it; the caller decides what to do with the candidate
 *  (wrap onto a 3D torus, reject if it enters a no-fly zone, clamp
 *  altitude to ground/ceiling, accept as-is).
 *
 *  Typical use (drone flight):
 *
 *  ```kotlin
 *  val dynamics = Dynamics3D<Drone>(airspace).apply {
 *      addForce(desiredVelocity3D(speed = 5.0, tau = 0.5) { d, dyn ->
 *          val p = dyn.space.positionOf(d) ?: return@desiredVelocity3D Point3D.ORIGIN
 *          flow.directionAt(p)
 *      })
 *      addForce(peerRepulsion3D(radius = 3.0) { d -> 50.0 / d })
 *      maxSpeed = 8.0
 *  }
 *
 *  while (true) {
 *      val (vNew, pNew) = dynamics.step(drone, dt = 0.1)
 *      dynamics.setVelocity(drone, vNew)
 *      airspace.moveTo(drone, clampToAirspace(pNew))
 *      delay(0.1)
 *  }
 *  ```
 *
 *  For the common "no boundary handling needed" case use
 *  [runDynamics3D]. For walls / no-fly zones / altitude clamps,
 *  write your own loop around [step].
 *
 *  All `Defaults` and `maxSpeed` / `minSpeed` validation works
 *  exactly as in 2D [Dynamics].
 *
 *  @param space the 3D projection that owns agent positions
 *  @param mass per-agent mass function. Default is unit mass for all.
 */
class Dynamics3D<A : AgentLike> @JvmOverloads constructor(
    val space: ContinuousVolume<A>,
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
     *  stay reproducible) and boosted to [minSpeed] too. Set to 0 to
     *  allow agents to stop. Must be non-negative and `<= maxSpeed`.
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

    private val velocities: MutableMap<A, Point3D> = mutableMapOf()
    private val forces: MutableList<Force3D<A>> = mutableListOf()

    /** Mutable global defaults for [Dynamics3D]. Mirrors [Dynamics.Defaults]. */
    companion object Defaults {
        /** Default per-agent mass when no mass function is supplied. Must be positive. */
        var unitMass: Double by positive(1.0)
        /** Default maximum speed (no clamp by default). Must be positive. */
        var maxSpeed: Double by positive(Double.POSITIVE_INFINITY)
        /** Default minimum speed. Must be non-negative. */
        var minSpeed: Double by nonNegative(0.0)
    }

    /** Get the current velocity tracked for [agent], or null if not set. */
    fun velocityOf(agent: A): Point3D? = velocities[agent]

    /** Set / update the velocity tracked for [agent]. */
    fun setVelocity(agent: A, v: Point3D) {
        velocities[agent] = v
    }

    /** Stop tracking [agent] (called when an agent leaves the population). */
    fun untrack(agent: A) {
        velocities.remove(agent)
    }

    /** Append [force] to the summed forces. Forces are evaluated in order. */
    fun addForce(force: Force3D<A>) {
        forces.add(force)
    }

    /** Number of forces currently registered. */
    val forceCount: Int
        get() = forces.size

    /**
     *  One Euler integration step for [agent]: sum registered forces,
     *  integrate velocity by `(sumForce / mass) * dt`, clamp to
     *  `[minSpeed, maxSpeed]`, compute candidate position
     *  `pos + vNew * dt`.
     *
     *  Returns `(newVelocity, candidatePosition)`. *Does not apply*
     *  the new state — the caller must call [setVelocity] and
     *  [ContinuousVolume.moveTo] explicitly.
     *
     *  @throws IllegalStateException if [agent] has no position in [space]
     *  @throws IllegalArgumentException if [dt] is non-positive
     */
    fun step(agent: A, dt: Double): Pair<Point3D, Point3D> {
        require(dt > 0.0) { "dt must be positive; was $dt" }
        val pos = space.positionOf(agent)
            ?: error("agent '${agent.name}' has no position in space '${space.name}'")
        val v0 = velocities[agent] ?: Point3D.ORIGIN

        var fx = 0.0
        var fy = 0.0
        var fz = 0.0
        for (force in forces) {
            val f = force.compute(agent, this, dt)
            fx += f.x
            fy += f.y
            fz += f.z
        }

        val m = mass(agent)
        require(m > 0.0) { "mass must be positive; was $m for agent '${agent.name}'" }
        val vRaw = Point3D(
            v0.x + (fx / m) * dt,
            v0.y + (fy / m) * dt,
            v0.z + (fz / m) * dt,
        )
        val vNew = clampSpeed(vRaw, agent)
        val pNew = Point3D(
            pos.x + vNew.x * dt,
            pos.y + vNew.y * dt,
            pos.z + vNew.z * dt,
        )
        return vNew to pNew
    }

    /**
     *  Compute one Euler step for every agent in [agents] *without
     *  applying* any of them — a Jacobi (synchronous) update. Every
     *  step reads the current shared state, so the result is
     *  independent of the order of [agents], unlike calling [step] and
     *  applying per agent in a loop (a Gauss-Seidel update that depends
     *  on iteration order). The caller applies the returned triples.
     *
     *  @throws IllegalArgumentException if [dt] is non-positive
     */
    fun stepAll(agents: Collection<A>, dt: Double): List<Triple<A, Point3D, Point3D>> {
        require(dt > 0.0) { "dt must be positive; was $dt" }
        return agents.map { a -> val (v, p) = step(a, dt); Triple(a, v, p) }
    }

    private fun clampSpeed(v: Point3D, agent: A): Point3D {
        val m = v.magnitude
        return when {
            m > maxSpeed -> v * (maxSpeed / m)
            // Exactly at rest but a positive minSpeed is required: invent
            // a deterministic heading so the body never stays frozen.
            m == 0.0 && minSpeed > 0.0 -> jitterDirection3D(agent.name) * minSpeed
            m in 0.0..<minSpeed && m > 0.0 -> v * (minSpeed / m)
            else -> v
        }
    }
}

/**
 *  Run [agent] under [dynamics] until [until] returns true. Each
 *  iteration: compute the 3D Euler step, store the new velocity,
 *  move the agent to the candidate position *as-is*, delay [dt].
 *
 *  Convenience for the "no boundary handling" case. For models with
 *  no-fly zones, altitude clamps, or torus wrap on positions, write
 *  your own loop around [Dynamics3D.step] so you can validate /
 *  transform the candidate before applying.
 *
 *  ```kotlin
 *  val script = process(isDefaultProcess = true) {
 *      runDynamics3D(this@Drone, dynamics, dt = 0.05) {
 *          field.arrivedAt(space.positionOf(this@Drone)!!)
 *      }
 *  }
 *  ```
 *
 *  @throws IllegalArgumentException if [dt] is non-positive
 */
suspend fun <A : AgentLike> KSLProcessBuilder.runDynamics3D(
    agent: A,
    dynamics: Dynamics3D<A>,
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
 *  process using a Jacobi (synchronous, order-independent) update — the
 *  batched, 3D analog of [runDynamics3D]. Each tick computes every
 *  agent's step from the shared current state via [Dynamics3D.stepAll],
 *  applies them all, then delays [dt].
 *
 *  [agents] is re-evaluated each tick so the population may change.
 *  Override [apply] to inject boundary handling (torus wrap, altitude
 *  clamp, no-fly-zone rejection) before the candidate is committed; by
 *  default the candidate is applied as-is.
 *
 *  @throws IllegalArgumentException if [dt] is non-positive
 */
suspend fun <A : AgentLike> KSLProcessBuilder.runDynamics3DAll(
    dynamics: Dynamics3D<A>,
    agents: () -> Collection<A>,
    dt: Double = 0.05,
    until: () -> Boolean = { false },
    apply: (agent: A, vNew: Point3D, pNew: Point3D) -> Unit = { a, v, p ->
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
