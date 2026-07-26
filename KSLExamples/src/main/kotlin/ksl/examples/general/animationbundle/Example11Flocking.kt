/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.animationbundle

import ksl.animation.AnimationLayout
import ksl.animation.animation
import ksl.examples.general.agent.FlockingExample
import ksl.simulation.Model

/**
 * Example 11 — flocking on a **torus** (8F.7). Boids steer by Reynolds forces in a continuous 100×100
 * space that **wraps at the edges**. Exercises (a) wrap-aware interpolation — a boid crossing an edge
 * is drawn taking the short way across the seam rather than streaking across the whole world; (b)
 * heading ticks (8F.5) on many continuously-moving agents; and (c) trace volume (80 agents at a small
 * `dt`).
 */
object Example11Flocking {

    private const val WORLD = 100.0

    fun buildModel(): Model {
        val m = Model("FlockingModel")
        FlockingExample(m, "flock").apply { population = 80; worldSize = WORLD }
        m.numberOfReplications = 1
        m.lengthOfReplication = 25.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Flocking on a torus (8F.7)"
        size(WORLD, WORLD + 16.0)
        clock(2.0, 4.0, fontSize = WORLD * 0.045)

        objectClass("Boid") { color = "#1f77b4"; size = 1.8 }
        continuousSpace("sky", xMin = 0.0, xMax = WORLD, yMin = 0.0, yMax = WORLD, torus = true)

        plot("Polarization", 2.0, WORLD + 2.0) { width = 44.0; height = 12.0; label = "Polarization" }
        plot("AvgSpeed", 52.0, WORLD + 2.0) { width = 44.0; height = 12.0; label = "Avg speed" }
    }

}
