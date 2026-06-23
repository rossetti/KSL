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

import ksl.modeling.agent.AgentLike
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.ContinuousProjection
import ksl.modeling.agent.Point2D
import ksl.modeling.agent.positive
import ksl.modeling.entity.KSLProcess
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.UniformRV
import kotlin.math.min

/**
 *  A first worked example of the agent-layer's spatial Context /
 *  Projection model (Phase 3): pedestrians traverse a 100-unit-long
 *  corridor at constant velocity. The example demonstrates:
 *
 *   - A `Context<Pedestrian>` holding all live pedestrians.
 *   - A `ContinuousProjection` giving each pedestrian a 2D position.
 *   - Pedestrians arriving from a Poisson process at the left end
 *     and walking toward a randomly-chosen y-coordinate on the right
 *     end at uniform velocity (no social forces, no collisions —
 *     that's social-force pedestrian models, deferred).
 *   - Time-stepped motion via a process body that takes a small step
 *     toward the destination every dt and updates the projection.
 *   - Standard KSL responses: crossing time, instantaneous count of
 *     pedestrians in the corridor.
 *
 *  This is intentionally simple — enough to confirm that the
 *  spatial scaffolding works end-to-end. Richer behaviors (collision
 *  avoidance, route selection, group dynamics) layer on top without
 *  changing this skeleton.
 *
 *  Pedestrians are *transient* agents created at runtime by an
 *  arrivals process. They join the context on creation and leave it
 *  when they reach the destination.
 */
class CorridorPedestrianExample(parent: ModelElement, name: String? = null) :
    AgentModel(parent, name) {

    // ── World geometry (initialized from Defaults; setters re-validate) ────

    var corridorLength: Double by positive(Defaults.corridorLength)
    var corridorWidth: Double by positive(Defaults.corridorWidth)

    /** Mutable global defaults for [CorridorPedestrianExample]. */
    companion object Defaults {
        /** Corridor length (x-axis), coordinate units. Must be positive. */
        var corridorLength: Double by positive(100.0)
        /** Corridor width (y-axis), coordinate units. Must be positive. */
        var corridorWidth: Double by positive(10.0)
        /** Mean inter-arrival time at the left end. Must be positive. */
        var arrivalMean: Double by positive(1.5)
        /** Walking-speed lower bound. Must be positive. */
        var speedMin: Double by positive(1.0)
        /** Walking-speed upper bound. Must be positive. */
        var speedMax: Double by positive(1.5)
        /** Time between motion updates. Must be positive. */
        var stepDuration: Double by positive(0.25)
    }

    // ── Population infrastructure ───────────────────────────────────────────

    /** Context holding all currently-walking pedestrians. */
    val pedestrians: Context<AgentLike> = Context("pedestrians")

    /** Continuous 2D positions of all pedestrians. */
    val space: ContinuousProjection<AgentLike> = ContinuousProjection(
        context = pedestrians,
        xRange = 0.0..corridorLength,
        yRange = 0.0..corridorWidth,
    )

    // ── Modeling responses ──────────────────────────────────────────────────

    val crossingTime: Response = Response(this, "PedestrianCrossingTime")
    val numInCorridor: TWResponse = TWResponse(this, "NumInCorridor")

    // ── Random variables (default; user can override before simulate()) ─────

    /** Inter-arrival time at the left end. */
    var arrivalRV = ExponentialRV(Defaults.arrivalMean, streamNum = 1, streamProvider = streamProvider)

    /** Walking speed (units per simulated time unit). */
    var speedRV =
        UniformRV(Defaults.speedMin, Defaults.speedMax, streamNum = 2, streamProvider = streamProvider)

    /** Destination y-coordinate at the right end. */
    var destYRV = UniformRV(0.0, corridorWidth, streamNum = 3, streamProvider = streamProvider)

    /** Starting y-coordinate at the left end. */
    var startYRV = UniformRV(0.0, corridorWidth, streamNum = 4, streamProvider = streamProvider)

    /** Time step used for motion updates. Smaller = smoother + more events. */
    var stepDuration: Double by positive(Defaults.stepDuration)

    // ── Pedestrian agent ────────────────────────────────────────────────────

    private var nextId: Int = 0

    /**
     *  A single pedestrian. Holds its destination and speed; its
     *  process body steps toward the destination every `stepDuration`
     *  until close enough, then leaves the context.
     */
    inner class Pedestrian(
        startY: Double,
        private val destination: Point2D,
        private val speed: Double,
    ) : Agent("ped-${++nextId}") {

        private val createdAt: Double = currentTime

        val script: KSLProcess = process(isDefaultProcess = true) {
            pedestrians.add(this@Pedestrian)
            space.placeAt(this@Pedestrian, 0.0, startY)
            numInCorridor.increment()

            // Step until close to destination, then jump to destination.
            val arrivalThreshold = speed * stepDuration  // distance covered in one step
            while (true) {
                val pos = space.positionOf(this@Pedestrian) ?: break
                val remaining = pos.distanceTo(destination)
                if (remaining < arrivalThreshold) {
                    space.moveTo(this@Pedestrian, destination)
                    break
                }
                // Step toward destination by stepDuration * speed.
                val dir = (destination - pos).normalized()
                val stepDist = min(remaining, speed * stepDuration)
                space.moveTo(this@Pedestrian, pos + dir * stepDist)
                delay(stepDuration)
            }

            // Done — record stats and leave the context.
            crossingTime.value = currentTime - createdAt
            numInCorridor.decrement()
            pedestrians.remove(this@Pedestrian)
        }
    }

    // ── Arrivals ────────────────────────────────────────────────────────────

    inner class Arrivals : Agent("arrivals") {
        val script: KSLProcess = process(isDefaultProcess = true) {
            while (true) {
                delay(arrivalRV.value)
                val ped = Pedestrian(
                    startY = startYRV.value,
                    destination = Point2D(corridorLength, destYRV.value),
                    speed = speedRV.value,
                )
                activate(ped.script)
            }
        }
    }

    private val arrivals = Arrivals()

    override fun initialize() {
        super.initialize()
        activate(arrivals.script)
    }
}

fun main() {
    val model = Model("CorridorPedestrianExample")
    val sys = CorridorPedestrianExample(model, "corridor")
    model.lengthOfReplication = 500.0
    model.numberOfReplications = 1
    model.simulate()
    model.print()
    println("Pedestrians still in corridor at end: ${sys.pedestrians.size}")
}
