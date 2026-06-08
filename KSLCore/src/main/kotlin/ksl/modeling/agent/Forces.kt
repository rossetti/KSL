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

/**
 *  Canonical [Force] factories. Each function constructs a [Force]
 *  closure parameterized by the supplied tunables. Use with
 *  [Dynamics.addForce] to compose motion behavior:
 *
 *  ```kotlin
 *  dynamics.addForce(separation(radius = 3.0))
 *  dynamics.addForce(alignment(radius = 8.0))
 *  dynamics.addForce(cohesion(radius = 10.0))
 *  ```
 *
 *  Forces are summed by [Dynamics] each integration step. Weight a
 *  force by multiplying its tunables (e.g., `separation(radius =
 *  3.0, weight = 1.5)`) — weights live on the factory's parameters,
 *  not on [Dynamics], so each rule can be tuned independently of how
 *  it composes with others.
 */

/**
 *  Peer-peer repulsion. For each neighbor inside [radius] (excluding
 *  self), apply a force along the direction *away from* the neighbor
 *  with magnitude given by [falloff] of the inter-agent distance.
 *  Distances are clamped at [minDistance] to keep [falloff] finite
 *  at contact.
 *
 *  Use for both Reynolds-style separation (`1/d` falloff) and
 *  Helbing-style social-force peer repulsion (`A * exp((r-d)/B)`
 *  falloff):
 *
 *  ```kotlin
 *  // Boids — inverse distance.
 *  dynamics.addForce(peerRepulsion(radius = 3.0) { d -> 1.0 / d })
 *
 *  // Pedestrians — Helbing exponential.
 *  dynamics.addForce(peerRepulsion(radius = 2.0, minDistance = 0.05) { d ->
 *      2000.0 * exp((0.6 - d) / 0.08)
 *  })
 *  ```
 */
fun <A : AgentLike> peerRepulsion(
    radius: Double,
    minDistance: Double = 0.0,
    falloff: (distance: Double) -> Double,
): Force<A> {
    require(radius > 0.0) { "radius must be positive; was $radius" }
    require(minDistance >= 0.0) { "minDistance must be non-negative; was $minDistance" }
    return Force { agent, dynamics, _ ->
        val pos = dynamics.space.positionOf(agent) ?: return@Force Point2D.ORIGIN
        var fx = 0.0
        var fy = 0.0
        for (other in dynamics.space.within(pos, radius)) {
            if (other === agent) continue
            val otherPos = dynamics.space.positionOf(other) ?: continue
            // delta is signed direction FROM other TO agent (i.e., away from other).
            val delta = dynamics.space.delta(otherPos, pos)
            val rawD = delta.magnitude
            val d = max(rawD, minDistance)
            if (d <= 0.0) continue
            // Coincident agents (rawD == 0) have no separation direction;
            // substitute a deterministic antisymmetric jitter so the pair
            // reliably pushes apart instead of staying stuck. The force is
            // finite only because minDistance > 0 cleared the guard above;
            // with minDistance == 0 a coincident pair is skipped (falloff
            // is singular at 0).
            val unit = if (rawD > 0.0) {
                Point2D(delta.x / rawD, delta.y / rawD)
            } else {
                pairJitter2D(agent.name, other.name)
            }
            val mag = falloff(d)
            fx += unit.x * mag
            fy += unit.y * mag
        }
        Point2D(fx, fy)
    }
}

/**
 *  Exponential repulsion from blocked cells (walls) in a [GridGraph].
 *  Walls are treated as solid cell-sized bounding boxes: the force
 *  per cell uses the closest point on that cell's box to the agent's
 *  position. Scans a square of cells of radius
 *  `ceil(scanRadius/cellSize)` around the agent's current cell;
 *  cells outside that scan don't contribute.
 *
 *  @param graph the lattice whose blocked cells produce force
 *  @param cellSize side length of each cell in continuous space
 *  @param origin continuous-space anchor of cell (0, 0)
 *  @param scanRadius distance beyond which cells stop contributing
 *  @param minDistance lower clamp on `d` to keep falloff finite at contact
 *  @param falloff a function returning force magnitude given clamped distance
 */
fun <A : AgentLike> wallRepulsion(
    graph: GridGraph,
    cellSize: Double,
    origin: Point2D,
    scanRadius: Double,
    minDistance: Double = 0.0,
    falloff: (distance: Double) -> Double,
): Force<A> {
    require(cellSize > 0.0) { "cellSize must be positive; was $cellSize" }
    require(scanRadius > 0.0) { "scanRadius must be positive; was $scanRadius" }
    require(minDistance >= 0.0) { "minDistance must be non-negative; was $minDistance" }
    val cellsToScan = ceil(scanRadius / cellSize).toInt()
    return Force { agent, dynamics, _ ->
        val pos = dynamics.space.positionOf(agent) ?: return@Force Point2D.ORIGIN
        val agentCol = ((pos.x - origin.x) / cellSize).toInt()
        val agentRow = ((pos.y - origin.y) / cellSize).toInt()
        var fx = 0.0
        var fy = 0.0
        for (dc in -cellsToScan..cellsToScan) for (dr in -cellsToScan..cellsToScan) {
            val c = Cell(agentCol + dc, agentRow + dr)
            if (c.col !in 0 until graph.columns || c.row !in 0 until graph.rows) continue
            if (graph.isPassable(c)) continue
            // Closest point on c's bounding box to pos.
            val xMin = origin.x + c.col * cellSize
            val yMin = origin.y + c.row * cellSize
            val cx = pos.x.coerceIn(xMin, xMin + cellSize)
            val cy = pos.y.coerceIn(yMin, yMin + cellSize)
            val deltaX = pos.x - cx
            val deltaY = pos.y - cy
            val rawD = kotlin.math.hypot(deltaX, deltaY)
            val d = max(rawD, minDistance)
            if (d > scanRadius) continue
            if (d <= 0.0) continue
            // When the agent sits exactly on/inside the wall cell
            // (rawD == 0) there is no outward direction from the closest
            // point; push out along the least-penetration face normal.
            val unitX: Double
            val unitY: Double
            if (rawD > 0.0) {
                unitX = deltaX / rawD
                unitY = deltaY / rawD
            } else {
                val xMax = xMin + cellSize
                val yMax = yMin + cellSize
                val penLeft = pos.x - xMin
                val penRight = xMax - pos.x
                val penDown = pos.y - yMin
                val penUp = yMax - pos.y
                val minPen = minOf(penLeft, penRight, penDown, penUp)
                when (minPen) {
                    penLeft -> { unitX = -1.0; unitY = 0.0 }
                    penRight -> { unitX = 1.0; unitY = 0.0 }
                    penDown -> { unitX = 0.0; unitY = -1.0 }
                    else -> { unitX = 0.0; unitY = 1.0 }
                }
            }
            val mag = falloff(d)
            fx += unitX * mag
            fy += unitY * mag
        }
        Point2D(fx, fy)
    }
}

/**
 *  Helbing-style desired-velocity relaxation force:
 *  `F = mass * (v_desired - v_current) / tau`. Pulls the agent's
 *  velocity toward `direction * speed` over a timescale of [tau].
 *
 *  The [direction] function should return a *unit vector* — e.g.,
 *  `field.directionAt(pos)` from a [FlowField]. If it returns a zero
 *  vector (agent at goal or in unreachable region) the force is
 *  zero.
 *
 *  ```kotlin
 *  dynamics.addForce(desiredVelocity(speed = 1.3, tau = 0.5) { agent, dyn ->
 *      val pos = dyn.space.positionOf(agent) ?: return@desiredVelocity Point2D.ORIGIN
 *      flow.directionAt(pos)
 *  })
 *  ```
 */
fun <A : AgentLike> desiredVelocity(
    speed: Double,
    tau: Double,
    direction: (agent: A, dynamics: Dynamics<A>) -> Point2D,
): Force<A> {
    require(speed > 0.0) { "speed must be positive; was $speed" }
    require(tau > 0.0) { "tau must be positive; was $tau" }
    return Force { agent, dynamics, _ ->
        val dir = direction(agent, dynamics)
        if (dir.x == 0.0 && dir.y == 0.0) return@Force Point2D.ORIGIN
        val vDesired = dir * speed
        val vCurrent = dynamics.velocityOf(agent) ?: Point2D.ORIGIN
        val m = dynamics.mass(agent)
        Point2D((vDesired.x - vCurrent.x) * m / tau, (vDesired.y - vCurrent.y) * m / tau)
    }
}

/**
 *  Viscous drag opposing the current velocity:
 *  `F = -coefficient * velocity`. Useful for damping out runaway
 *  oscillations or modeling fluid resistance.
 */
fun <A : AgentLike> viscousDrag(coefficient: Double): Force<A> {
    require(coefficient >= 0.0) { "coefficient must be non-negative; was $coefficient" }
    return Force { agent, dynamics, _ ->
        val v = dynamics.velocityOf(agent) ?: return@Force Point2D.ORIGIN
        Point2D(-coefficient * v.x, -coefficient * v.y)
    }
}

/**
 *  Reynolds-rule **separation**: peer-peer repulsion with inverse-
 *  distance falloff over [radius]. Convenience wrapper around
 *  [peerRepulsion]; equivalent to
 *  `peerRepulsion(radius, minDistance) { d -> 1.0 / d }`.
 */
fun <A : AgentLike> separation(
    radius: Double,
    minDistance: Double = 0.001,
): Force<A> = peerRepulsion(radius, minDistance) { d -> 1.0 / d }

/**
 *  Reynolds-rule **alignment**: steer toward the average velocity of
 *  neighbors within [radius]. Force is `avg(neighbors.v) - my.v`, so
 *  the magnitude scales with how off-heading this agent is from the
 *  local consensus.
 */
fun <A : AgentLike> alignment(radius: Double): Force<A> {
    require(radius > 0.0) { "radius must be positive; was $radius" }
    return Force { agent, dynamics, _ ->
        val pos = dynamics.space.positionOf(agent) ?: return@Force Point2D.ORIGIN
        var sumVx = 0.0
        var sumVy = 0.0
        var count = 0
        for (other in dynamics.space.within(pos, radius)) {
            if (other === agent) continue
            val v = dynamics.velocityOf(other) ?: continue
            sumVx += v.x
            sumVy += v.y
            count += 1
        }
        if (count == 0) return@Force Point2D.ORIGIN
        val myVel = dynamics.velocityOf(agent) ?: Point2D.ORIGIN
        Point2D(sumVx / count - myVel.x, sumVy / count - myVel.y)
    }
}

/**
 *  Reynolds-rule **cohesion**: steer toward the average position of
 *  neighbors within [radius]. Computed as the average of *torus-
 *  aware deltas* (via [ContinuousProjection.delta]) so it works
 *  correctly across wrap boundaries — averaging absolute positions
 *  would break for a peer just over the wrap.
 */
fun <A : AgentLike> cohesion(radius: Double): Force<A> {
    require(radius > 0.0) { "radius must be positive; was $radius" }
    return Force { agent, dynamics, _ ->
        val pos = dynamics.space.positionOf(agent) ?: return@Force Point2D.ORIGIN
        var sumDx = 0.0
        var sumDy = 0.0
        var count = 0
        for (other in dynamics.space.within(pos, radius)) {
            if (other === agent) continue
            val op = dynamics.space.positionOf(other) ?: continue
            val delta = dynamics.space.delta(pos, op)
            sumDx += delta.x
            sumDy += delta.y
            count += 1
        }
        if (count == 0) return@Force Point2D.ORIGIN
        Point2D(sumDx / count, sumDy / count)
    }
}

/**
 *  Apply a constant force vector regardless of agent state. Useful
 *  for global influences like wind, gravity, or a uniform attractor.
 */
fun <A : AgentLike> constantForce(force: Point2D): Force<A> = Force { _, _, _ -> force }

/**
 *  Scale [force]'s output by a fixed [weight]. Composes with any
 *  other factory:
 *
 *  ```kotlin
 *  dynamics.addForce(weighted(separation(radius = 3.0), weight = 1.5))
 *  ```
 *
 *  Equivalent to baking the weight into the factory's parameters
 *  when feasible; useful when you want to retain the canonical
 *  factory call and adjust strength separately.
 */
fun <A : AgentLike> weighted(force: Force<A>, weight: Double): Force<A> = Force { agent, dynamics, dt ->
    val f = force.compute(agent, dynamics, dt)
    Point2D(f.x * weight, f.y * weight)
}
