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

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 *  3D analogs of the canonical [Force] factories in `Forces.kt`.
 *  Each function constructs a [Force3D] closure parameterized by
 *  the supplied tunables. Use with [Dynamics3D.addForce] to compose
 *  3D motion behavior:
 *
 *  ```kotlin
 *  dynamics.addForce(separation3D(radius = 3.0))
 *  dynamics.addForce(alignment3D(radius = 8.0))
 *  dynamics.addForce(cohesion3D(radius = 10.0))
 *  ```
 *
 *  All factories validate their inputs at construction; see the
 *  corresponding 2D factory in `Forces.kt` for the same validation
 *  rules.
 */

/**
 *  3D peer-peer repulsion. For each neighbor inside [radius]
 *  (excluding self), apply a force along the direction *away from*
 *  the neighbor with magnitude given by [falloff] of the inter-
 *  agent distance. Distances are clamped at [minDistance] to keep
 *  [falloff] finite at contact. Uses [ContinuousVolume.delta] for
 *  the inter-agent direction so torus-wrapped 3D worlds compute the
 *  correct shortest-way vector.
 *
 *  Use for any 3D peer repulsion shape — Reynolds boids
 *  (`1.0 / d`), Helbing-style social-force (`A * exp((r - d) / B)`),
 *  drone collision-avoidance with custom falloff.
 *
 *  ```kotlin
 *  // Boids in 3D — inverse distance.
 *  dynamics.addForce(peerRepulsion3D(radius = 3.0) { d -> 1.0 / d })
 *
 *  // Drone TCAS-style avoidance — exponential.
 *  dynamics.addForce(peerRepulsion3D(radius = 8.0, minDistance = 0.1) { d ->
 *      500.0 * exp((2.0 - d) / 0.5)
 *  })
 *  ```
 */
fun <A : AgentLike> peerRepulsion3D(
    radius: Double,
    minDistance: Double = 0.0,
    falloff: (distance: Double) -> Double,
): Force3D<A> {
    require(radius > 0.0) { "radius must be positive; was $radius" }
    require(minDistance >= 0.0) { "minDistance must be non-negative; was $minDistance" }
    return Force3D { agent, dynamics, _ ->
        val pos = dynamics.space.positionOf(agent) ?: return@Force3D Point3D.ORIGIN
        var fx = 0.0; var fy = 0.0; var fz = 0.0
        for (other in dynamics.space.within(pos, radius)) {
            if (other === agent) continue
            val otherPos = dynamics.space.positionOf(other) ?: continue
            // delta is signed direction FROM other TO agent (away from other).
            val delta = dynamics.space.delta(otherPos, pos)
            val rawD = delta.magnitude
            val d = max(rawD, minDistance)
            if (d <= 0.0) continue
            // Coincident agents (rawD == 0) have no separation direction;
            // substitute a deterministic antisymmetric jitter so the pair
            // reliably pushes apart. Finite only because minDistance > 0
            // cleared the guard above.
            val ux: Double; val uy: Double; val uz: Double
            if (rawD > 0.0) {
                val invRaw = 1.0 / rawD
                ux = delta.x * invRaw; uy = delta.y * invRaw; uz = delta.z * invRaw
            } else {
                val j = pairJitter3D(agent.name, other.name)
                ux = j.x; uy = j.y; uz = j.z
            }
            val mag = falloff(d)
            fx += ux * mag; fy += uy * mag; fz += uz * mag
        }
        Point3D(fx, fy, fz)
    }
}

/**
 *  Exponential repulsion from blocked voxels (walls / obstacles /
 *  no-fly zones) in a [VoxelGraph]. Voxels are treated as solid 3D
 *  bounding boxes: the force per voxel uses the closest point on
 *  that voxel's box to the agent's 3D position. Scans a cube of
 *  voxels of half-side `ceil(scanRadius/cellSize)` around the
 *  agent's current voxel.
 *
 *  @param graph the 3D lattice whose blocked voxels produce force
 *  @param cellSize side length of each voxel in continuous space
 *  @param origin continuous-space anchor of voxel (0, 0, 0)
 *  @param scanRadius distance beyond which voxels stop contributing
 *  @param minDistance lower clamp on `d` to keep falloff finite at contact
 *  @param falloff a function returning force magnitude given clamped distance
 */
fun <A : AgentLike> wallRepulsion3D(
    graph: VoxelGraph,
    cellSize: Double,
    origin: Point3D,
    scanRadius: Double,
    minDistance: Double = 0.0,
    falloff: (distance: Double) -> Double,
): Force3D<A> {
    require(cellSize > 0.0) { "cellSize must be positive; was $cellSize" }
    require(scanRadius > 0.0) { "scanRadius must be positive; was $scanRadius" }
    require(minDistance >= 0.0) { "minDistance must be non-negative; was $minDistance" }
    val cellsToScan = ceil(scanRadius / cellSize).toInt()
    return Force3D { agent, dynamics, _ ->
        val pos = dynamics.space.positionOf(agent) ?: return@Force3D Point3D.ORIGIN
        val agentCol = ((pos.x - origin.x) / cellSize).toInt()
        val agentRow = ((pos.y - origin.y) / cellSize).toInt()
        val agentLayer = ((pos.z - origin.z) / cellSize).toInt()
        var fx = 0.0; var fy = 0.0; var fz = 0.0
        for (dl in -cellsToScan..cellsToScan) {
            for (dr in -cellsToScan..cellsToScan) {
                for (dc in -cellsToScan..cellsToScan) {
                    val v = Voxel(agentCol + dc, agentRow + dr, agentLayer + dl)
                    if (v.col !in 0 until graph.columns) continue
                    if (v.row !in 0 until graph.rows) continue
                    if (v.layer !in 0 until graph.layers) continue
                    if (graph.isPassable(v)) continue
                    // Closest point on v's 3D bounding box to pos.
                    val xMin = origin.x + v.col * cellSize
                    val yMin = origin.y + v.row * cellSize
                    val zMin = origin.z + v.layer * cellSize
                    val cx = pos.x.coerceIn(xMin, xMin + cellSize)
                    val cy = pos.y.coerceIn(yMin, yMin + cellSize)
                    val cz = pos.z.coerceIn(zMin, zMin + cellSize)
                    val deltaX = pos.x - cx
                    val deltaY = pos.y - cy
                    val deltaZ = pos.z - cz
                    val rawD = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)
                    val d = max(rawD, minDistance)
                    if (d > scanRadius) continue
                    if (d <= 0.0) continue
                    // When the agent sits exactly on/inside the wall
                    // voxel (rawD == 0) there is no outward direction;
                    // push out along the least-penetration face normal.
                    val ux: Double; val uy: Double; val uz: Double
                    if (rawD > 0.0) {
                        val invRaw = 1.0 / rawD
                        ux = deltaX * invRaw; uy = deltaY * invRaw; uz = deltaZ * invRaw
                    } else {
                        val penXLo = pos.x - xMin; val penXHi = (xMin + cellSize) - pos.x
                        val penYLo = pos.y - yMin; val penYHi = (yMin + cellSize) - pos.y
                        val penZLo = pos.z - zMin; val penZHi = (zMin + cellSize) - pos.z
                        val minPen = minOf(penXLo, penXHi, penYLo, penYHi, penZLo, penZHi)
                        when (minPen) {
                            penXLo -> { ux = -1.0; uy = 0.0; uz = 0.0 }
                            penXHi -> { ux = 1.0; uy = 0.0; uz = 0.0 }
                            penYLo -> { ux = 0.0; uy = -1.0; uz = 0.0 }
                            penYHi -> { ux = 0.0; uy = 1.0; uz = 0.0 }
                            penZLo -> { ux = 0.0; uy = 0.0; uz = -1.0 }
                            else -> { ux = 0.0; uy = 0.0; uz = 1.0 }
                        }
                    }
                    val mag = falloff(d)
                    fx += ux * mag; fy += uy * mag; fz += uz * mag
                }
            }
        }
        Point3D(fx, fy, fz)
    }
}

/**
 *  Helbing-style desired-velocity relaxation force in 3D:
 *  `F = mass * (v_desired - v_current) / tau`. Pulls the agent's
 *  velocity toward `direction * speed` over a timescale of [tau].
 *
 *  The [direction] function should return a *unit vector* —
 *  typically `flow.directionAt(pos)` from a [FlowField3D]. Zero
 *  direction yields zero force.
 *
 *  ```kotlin
 *  dynamics.addForce(desiredVelocity3D(speed = 5.0, tau = 0.5) { agent, dyn ->
 *      val pos = dyn.space.positionOf(agent) ?: return@desiredVelocity3D Point3D.ORIGIN
 *      flow.directionAt(pos)
 *  })
 *  ```
 */
fun <A : AgentLike> desiredVelocity3D(
    speed: Double,
    tau: Double,
    direction: (agent: A, dynamics: Dynamics3D<A>) -> Point3D,
): Force3D<A> {
    require(speed > 0.0) { "speed must be positive; was $speed" }
    require(tau > 0.0) { "tau must be positive; was $tau" }
    return Force3D { agent, dynamics, _ ->
        val dir = direction(agent, dynamics)
        if (dir.x == 0.0 && dir.y == 0.0 && dir.z == 0.0) return@Force3D Point3D.ORIGIN
        val vDesired = dir * speed
        val vCurrent = dynamics.velocityOf(agent) ?: Point3D.ORIGIN
        val m = dynamics.mass(agent)
        Point3D(
            (vDesired.x - vCurrent.x) * m / tau,
            (vDesired.y - vCurrent.y) * m / tau,
            (vDesired.z - vCurrent.z) * m / tau,
        )
    }
}

/**
 *  3D viscous drag opposing the current velocity:
 *  `F = -coefficient * velocity`. Useful for damping out runaway
 *  oscillations, modeling air resistance on UAVs, or stabilizing
 *  a hover.
 */
fun <A : AgentLike> viscousDrag3D(coefficient: Double): Force3D<A> {
    require(coefficient >= 0.0) { "coefficient must be non-negative; was $coefficient" }
    return Force3D { agent, dynamics, _ ->
        val v = dynamics.velocityOf(agent) ?: return@Force3D Point3D.ORIGIN
        Point3D(-coefficient * v.x, -coefficient * v.y, -coefficient * v.z)
    }
}

/**
 *  Reynolds-rule **separation** in 3D: peer-peer repulsion with
 *  inverse-distance falloff over [radius]. Convenience wrapper
 *  around [peerRepulsion3D].
 */
fun <A : AgentLike> separation3D(
    radius: Double,
    minDistance: Double = 0.001,
): Force3D<A> = peerRepulsion3D(radius, minDistance) { d -> 1.0 / d }

/**
 *  Reynolds-rule **alignment** in 3D: steer toward the average
 *  velocity of neighbors within [radius]. Force is
 *  `avg(neighbors.v) - my.v`.
 */
fun <A : AgentLike> alignment3D(radius: Double): Force3D<A> {
    require(radius > 0.0) { "radius must be positive; was $radius" }
    return Force3D { agent, dynamics, _ ->
        val pos = dynamics.space.positionOf(agent) ?: return@Force3D Point3D.ORIGIN
        var sumVx = 0.0; var sumVy = 0.0; var sumVz = 0.0
        var count = 0
        for (other in dynamics.space.within(pos, radius)) {
            if (other === agent) continue
            val v = dynamics.velocityOf(other) ?: continue
            sumVx += v.x; sumVy += v.y; sumVz += v.z
            count += 1
        }
        if (count == 0) return@Force3D Point3D.ORIGIN
        val myVel = dynamics.velocityOf(agent) ?: Point3D.ORIGIN
        Point3D(
            sumVx / count - myVel.x,
            sumVy / count - myVel.y,
            sumVz / count - myVel.z,
        )
    }
}

/**
 *  Reynolds-rule **cohesion** in 3D: steer toward the average
 *  position of neighbors within [radius]. Computed as the average
 *  of *torus-aware deltas* via [ContinuousVolume.delta] so it
 *  works correctly across 3D wrap boundaries.
 */
fun <A : AgentLike> cohesion3D(radius: Double): Force3D<A> {
    require(radius > 0.0) { "radius must be positive; was $radius" }
    return Force3D { agent, dynamics, _ ->
        val pos = dynamics.space.positionOf(agent) ?: return@Force3D Point3D.ORIGIN
        var sumDx = 0.0; var sumDy = 0.0; var sumDz = 0.0
        var count = 0
        for (other in dynamics.space.within(pos, radius)) {
            if (other === agent) continue
            val op = dynamics.space.positionOf(other) ?: continue
            val delta = dynamics.space.delta(pos, op)
            sumDx += delta.x; sumDy += delta.y; sumDz += delta.z
            count += 1
        }
        if (count == 0) return@Force3D Point3D.ORIGIN
        Point3D(sumDx / count, sumDy / count, sumDz / count)
    }
}

/**
 *  Apply a constant 3D force vector regardless of agent state.
 *  Useful for global influences like gravity (`Point3D(0, 0, -mg)`),
 *  wind, or a uniform attractor.
 */
fun <A : AgentLike> constantForce3D(force: Point3D): Force3D<A> =
    Force3D { _, _, _ -> force }

/**
 *  Scale [force]'s output by a fixed [weight] in 3D. Composes with
 *  any other 3D force factory.
 */
fun <A : AgentLike> weighted3D(force: Force3D<A>, weight: Double): Force3D<A> =
    Force3D { agent, dynamics, dt ->
        val f = force.compute(agent, dynamics, dt)
        Point3D(f.x * weight, f.y * weight, f.z * weight)
    }
