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

/**
 *  A force-producing rule in a continuous-time agent simulation. Given
 *  an agent and the [Dynamics] it belongs to, return the force vector
 *  contributed by this rule at the current simulation step.
 *
 *  Force is `fun interface` so concrete instances can be created with
 *  SAM-lambda syntax — see the canonical force library in
 *  `Forces.kt`:
 *
 *  ```kotlin
 *  val gravity: Force<Boid> = Force { _, _, _ -> Point2D(0.0, -9.8) }
 *  ```
 *
 *  The force receives `dt` in case it depends on the integration time
 *  step (rare but useful for impulse-style forces). Most forces
 *  ignore it.
 *
 *  Forces are summed by the [Dynamics] each step; integration happens
 *  outside the force, so a force shouldn't mutate any state. Read
 *  positions via `dynamics.space.positionOf(other)`, neighbors via
 *  `dynamics.space.within(pos, radius)`, and other agents'
 *  velocities via `dynamics.velocityOf(other)`.
 */
fun interface Force<A : AgentLike> {
    fun compute(agent: A, dynamics: Dynamics<A>, dt: Double): Point2D
}
