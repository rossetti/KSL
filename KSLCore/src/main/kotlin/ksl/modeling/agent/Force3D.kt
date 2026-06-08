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
 *  3D analog of [Force]. Given an agent and the [Dynamics3D] it
 *  belongs to, return the 3D force vector contributed by this rule
 *  at the current simulation step.
 *
 *  Force3D is `fun interface` so concrete instances can be created
 *  with SAM-lambda syntax — see the canonical force library in
 *  `Forces3D.kt`:
 *
 *  ```kotlin
 *  val gravity: Force3D<Drone> = Force3D { _, _, _ -> Point3D(0.0, 0.0, -9.8) }
 *  ```
 *
 *  Same semantics as [Force]: read positions via
 *  `dynamics.space.positionOf(other)`, neighbors via
 *  `dynamics.space.within(pos, radius)`, and other agents'
 *  velocities via `dynamics.velocityOf(other)`. Forces shouldn't
 *  mutate any state — integration happens outside the force.
 */
fun interface Force3D<A : AgentLike> {
    fun compute(agent: A, dynamics: Dynamics3D<A>, dt: Double): Point3D
}
