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

package ksl.examples.general.agent

import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.ContinuousProjection
import ksl.modeling.agent.Dynamics
import ksl.modeling.agent.Point2D
import ksl.modeling.agent.alignment
import ksl.modeling.agent.cohesion
import ksl.modeling.agent.nonNegative
import ksl.modeling.agent.positive
import ksl.modeling.agent.separation
import ksl.modeling.agent.weighted
import ksl.modeling.entity.KSLProcess
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.math.cos
import kotlin.math.sin

/**
 *  Reynolds (1987) **boids flocking** as the second continuous-
 *  dynamics example after [PedestrianCrowdExample]. Same per-step
 *  integration loop, completely different problem: no walls, no goal,
 *  no flow field. Each boid steers from three rule-derived forces
 *  computed over its local neighborhood:
 *
 *   - **Separation** — steer to avoid crowding nearby flockmates.
 *   - **Alignment** — steer toward the average heading of nearby
 *     flockmates.
 *   - **Cohesion** — steer toward the average position of nearby
 *     flockmates.
 *
 *  Reynolds, C. (1987), "Flocks, herds, and schools: a distributed
 *  behavioral model," *SIGGRAPH '87*, 25–34.
 *
 *  ## Why this example exists
 *
 *  Pedagogical: shows the same Pattern C / per-`dt` integration loop
 *  from §11.2 producing recognizable collective behavior in a problem
 *  that's *structurally identical* to social-force pedestrians but
 *  *parametrically completely different*. Inputs are unit-mass boids
 *  in a toroidal world with three steering rules and no obstacles;
 *  the pedestrian example uses 80 kg pedestrians in a walled room
 *  with Helbing forces and a flow-field goal.
 *
 *  Strategic: this is the second of the two data points that motivate
 *  the Phase 5.4 lift of the integration loop and force composition
 *  into reusable `Force<A>` / `Dynamics<A>` primitives. Forces here
 *  are written *inline*, deliberately, matching the pedestrian
 *  example's style. The duplication will be obvious when both files
 *  are open side-by-side; that's the point.
 *
 *  ## World
 *
 *  Toroidal continuous space of [worldSize] × [worldSize]. Boids wrap
 *  at the edges, so the simulation can run indefinitely without
 *  losing the population. Distances and inter-boid deltas are
 *  computed with the shortest-path-around-the-torus convention.
 *
 *  ## Emergent behavior
 *
 *  Tune weights and radii and observe:
 *   - **Polarization** — initially random velocities converge to a
 *     coherent flock direction. Measured by [polarization].
 *   - **Splitting and merging** — sub-flocks form, separate, and
 *     re-merge over time.
 *   - **Compact flocks** vs. **loose flocks** — driven by the ratio
 *     of [cohesionWeight] to [separationWeight].
 *   - **"Beating heart"** — at certain parameter combinations the
 *     flock periodically expands and contracts.
 *
 *  None of these are coded; they emerge from the three force terms.
 */
class FlockingExample(parent: ModelElement, name: String? = null) :
    AgentModel(parent, name) {

    // ── Tunable parameters (initialized from Defaults; setters re-validate) ──

    var population: Int by positive(Defaults.population)
    var dt: Double by positive(Defaults.dt)
    var worldSize: Double by positive(Defaults.worldSize)

    var maxSpeed: Double by positive(Defaults.maxSpeed)
    var minSpeed: Double by positive(Defaults.minSpeed)
    var mass: Double by positive(Defaults.mass)

    var separationRadius: Double by positive(Defaults.separationRadius)
    var alignmentRadius: Double by positive(Defaults.alignmentRadius)
    var cohesionRadius: Double by positive(Defaults.cohesionRadius)

    var separationWeight: Double by nonNegative(Defaults.separationWeight)
    var alignmentWeight: Double by nonNegative(Defaults.alignmentWeight)
    var cohesionWeight: Double by nonNegative(Defaults.cohesionWeight)

    var initialSpeed: Double by positive(Defaults.initialSpeed)

    /**
     *  Mutable global defaults for [FlockingExample]. Tuned values
     *  produce a single coherent flock most of the time, with
     *  occasional splits. All members validate via property
     *  delegates and throw `IllegalArgumentException` on invalid
     *  assignment.
     */
    companion object Defaults {
        // Population & integration
        /** Number of boids to spawn per replication. Must be positive. */
        var population: Int by positive(80)
        /** Integration time step. Must be positive. */
        var dt: Double by positive(0.05)
        /** Side length of the toroidal world. Must be positive. */
        var worldSize: Double by positive(100.0)

        // Boid physics
        /** Hard upper bound on velocity magnitude. Must be positive. */
        var maxSpeed: Double by positive(4.0)
        /** Lower bound — boids never come to rest. Must be positive. */
        var minSpeed: Double by positive(1.0)
        /** Boid mass (unit-mass is conventional in boids literature). Must be positive. */
        var mass: Double by positive(1.0)

        // Per-rule radii (defined small to large: separation < alignment < cohesion)
        /** Distance within which boids actively repel each other. Must be positive. */
        var separationRadius: Double by positive(3.0)
        /** Distance within which boids match each other's heading. Must be positive. */
        var alignmentRadius: Double by positive(8.0)
        /** Distance within which boids steer toward the group center. Must be positive. */
        var cohesionRadius: Double by positive(10.0)

        // Per-rule weights (zero turns the rule off; tune relatively)
        /** Multiplier on the separation force. Must be non-negative. */
        var separationWeight: Double by nonNegative(1.5)
        /** Multiplier on the alignment force. Must be non-negative. */
        var alignmentWeight: Double by nonNegative(1.0)
        /** Multiplier on the cohesion force. Must be non-negative. */
        var cohesionWeight: Double by nonNegative(1.0)

        // Initialization
        /** Initial speed magnitude given to every boid (random direction). Must be positive. */
        var initialSpeed: Double by positive(2.0)
    }

    // ── Continuous space ────────────────────────────────────────────────────

    private val sky: Context<Boid> = Context("boids")
    val space: ContinuousProjection<Boid> = ContinuousProjection(
        sky,
        xRange = 0.0..worldSize,
        yRange = 0.0..worldSize,
        torus = true,
    )

    // ── Responses ──────────────────────────────────────────────────────────

    /**
     *  Polarization order parameter — magnitude of the population's
     *  mean velocity divided by the mean speed. Ranges from 0 (random
     *  velocities cancelling) to 1 (perfectly aligned flock). The
     *  textbook order parameter for boid simulations.
     */
    val polarization: TWResponse = TWResponse(this, "Polarization")

    /** Average number of neighbors inside [cohesionRadius] across the flock. */
    val avgNeighborCount: TWResponse = TWResponse(this, "AvgNeighborCount")

    /** Average speed across the flock. */
    val avgSpeed: TWResponse = TWResponse(this, "AvgSpeed")

    // ── Boid ────────────────────────────────────────────────────────────────

    private var nextId: Int = 0

    inner class Boid : Agent("boid-${++nextId}") {
        val script: KSLProcess = process(isDefaultProcess = true) {
            while (true) {
                space.positionOf(this@Boid) ?: break

                // One Euler step from the three Reynolds forces.
                val (vNew, candidate) = dynamics.step(this@Boid, dt)

                // Apply: wrap candidate onto the torus, store velocity.
                dynamics.setVelocity(this@Boid, vNew)
                space.moveTo(this@Boid, wrapPosition(candidate))

                delay(dt)
            }
        }
    }

    /** Wrap [p] into [0, worldSize) on both axes (positive-mod semantics). */
    private fun wrapPosition(p: Point2D): Point2D = Point2D(
        ((p.x % worldSize) + worldSize) % worldSize,
        ((p.y % worldSize) + worldSize) % worldSize,
    )

    // ── Lifecycle ──────────────────────────────────────────────────────────

    val boids: MutableList<Boid> = mutableListOf()

    /** Built fresh in [initialize] from the current tunable values. */
    private lateinit var dynamics: Dynamics<Boid>

    override fun initialize() {
        super.initialize()

        // Build the dynamics with the three Reynolds rules, each
        // weighted independently. Torus-aware deltas in separation
        // and cohesion come from `ContinuousProjection.delta` because
        // `space` was constructed with `torus = true`.
        dynamics = Dynamics(
            space = space,
            mass = { mass },
            maxSpeed = maxSpeed,
            minSpeed = minSpeed,
        )
        dynamics.addForce(weighted(separation<Boid>(radius = separationRadius), separationWeight))
        dynamics.addForce(weighted(alignment<Boid>(radius = alignmentRadius), alignmentWeight))
        dynamics.addForce(weighted(cohesion<Boid>(radius = cohesionRadius), cohesionWeight))

        boids.clear()
        val rng = defaultRNStream
        repeat(population) {
            val b = Boid()
            sky.add(b)
            // Random position in the torus, random heading at the initial-speed magnitude.
            val pos = Point2D(rng.randU01() * worldSize, rng.randU01() * worldSize)
            val angle = rng.randU01() * 2.0 * Math.PI
            space.placeAt(b, pos)
            dynamics.setVelocity(b, Point2D(cos(angle) * initialSpeed, sin(angle) * initialSpeed))
            activate(b.script)
            boids.add(b)
        }

        // Schedule a recurring stats sample so we get meaningful TWResponse traces.
        scheduleStatsSample()
    }

    private fun scheduleStatsSample() {
        schedule(statsSampleAction, 0.5)
    }

    private val statsSampleAction = object : EventAction<Nothing>() {
        override fun action(event: ksl.simulation.KSLEvent<Nothing>) {
            updateStats()
            // Continue sampling until the replication ends.
            schedule(0.5)
        }
    }

    /**
     *  Snapshot the three population-level statistics. Called by a
     *  recurring event so the time-weighted responses see regular
     *  samples even though boids update on their own per-`dt`
     *  schedules.
     */
    private fun updateStats() {
        if (boids.isEmpty()) return
        var sumVx = 0.0
        var sumVy = 0.0
        var sumSpeed = 0.0
        var sumNeighbors = 0.0
        for (b in boids) {
            val v = dynamics.velocityOf(b) ?: Point2D.ORIGIN
            sumVx += v.x
            sumVy += v.y
            sumSpeed += v.magnitude
            val pos = space.positionOf(b) ?: continue
            sumNeighbors += (space.within(pos, cohesionRadius).size - 1).coerceAtLeast(0)
        }
        val meanVelMag = Point2D(sumVx, sumVy).magnitude / boids.size
        val meanSpeed = sumSpeed / boids.size
        polarization.value = if (sumSpeed > 0.0) (meanVelMag / meanSpeed) else 0.0
        avgSpeed.value = meanSpeed
        avgNeighborCount.value = sumNeighbors / boids.size
    }
}

fun main() {
    val model = Model("FlockingExample")
    val sys = FlockingExample(model, "flock")
    model.lengthOfReplication = 60.0
    model.numberOfReplications = 1
    model.simulate()
    model.print()
    println("Final polarization: ${sys.polarization.value}")
    println("Final avg neighbors: ${sys.avgNeighborCount.value}")
}
