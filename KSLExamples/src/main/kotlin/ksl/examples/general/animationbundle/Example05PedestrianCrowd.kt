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
import ksl.examples.general.agent.PedestrianCrowdExample
import ksl.simulation.Model

/**
 * Example 5 — continuous-space crowd. Pedestrians evacuate a 25×25 m room through a 3-cell doorway
 * in a wall (column 15), driven by Helbing social-force dynamics at `dt = 0.05`. Surfaces:
 *  - **Continuous motion** of agents (smooth, real-valued positions) — the continuous-space analog
 *    of Example 4's grid hops.
 *  - **Trace volume**: ~40 agents updating every 0.05 time units → tens of thousands of
 *    `AgentPositionChanged` events; a stress test for trace size and per-frame redraw.
 *  - **Removal gap again**: evacuated pedestrians `crowd.remove(...)` but ghost at the doorway.
 *  - **No heading**: dense flow at the doorway reads poorly as undifferentiated dots (no direction).
 */
object Example05PedestrianCrowd {

    fun buildModel(): Model {
        val m = Model("PedestrianCrowdModel")
        PedestrianCrowdExample(m, "crowd").apply { population = 40 }
        m.numberOfReplications = 1
        m.lengthOfReplication = 60.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Pedestrian Crowd (agent demo)"
        size(25.0, 29.0)
        clock(0.3, 0.7, fontSize = 1.16)

        objectClass("Pedestrian") { color = "#1f77b4"; size = 0.5 }
        continuousSpace("room", xMin = 0.0, xMax = 25.0, yMin = 0.0, yMax = 25.0)

        // Wall on column 15 with a doorway at rows 11..13 (matches the model). Two rectangles.
        rect(15.0, 0.0, 15.4, 11.0, color = "#444444", strokeWidth = 2.0)
        rect(15.0, 14.0, 15.4, 25.0, color = "#444444", strokeWidth = 2.0)
        station("Exit", 16.5, 12.0, label = "Exit")

        bar("PopulationInRoom", 0.5, 26.0) { width = 11.0; height = 0.8; maxValue = 40.0; label = "In room" }
        bar("NumEvacuated", 13.0, 26.0) { width = 11.0; height = 0.8; maxValue = 40.0; color = "#2ca02c"; label = "Evacuated" }
    }

}
