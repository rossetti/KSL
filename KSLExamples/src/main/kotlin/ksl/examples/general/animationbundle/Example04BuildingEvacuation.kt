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
import ksl.animation.animationInventory
import ksl.examples.general.agent.BuildingEvacuationExample
import ksl.simulation.Model

/**
 * Example 4 — grid evacuation. Pedestrians on a 15×15 grid descend a distance field to one of two
 * exits (top-left, bottom-right) and **leave the building**. Chosen to surface the **agent-removal**
 * gap: the model calls `pedestrians.remove(...)` on evacuation, but there is no `AgentRemoved`
 * event, so the renderer has no way to know an agent left — evacuated pedestrians **ghost forever**
 * at the exit cells. Also exercises hand-authored **walls** (the layout duplicates geometry the
 * model holds in its `GridGraph`, since there's no geometry import).
 */
object Example04BuildingEvacuation {

    private const val GRID = 15

    fun buildModel(): Model {
        val m = Model("BuildingEvacuationModel")
        BuildingEvacuationExample(m, "evacuation").apply { population = 40 }
        m.numberOfReplications = 1
        m.lengthOfReplication = 90.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Building Evacuation (agent demo)"
        size(GRID.toDouble(), GRID + 4.0)
        clock(0.3, 0.7, fontSize = GRID * 0.045)

        objectClass("Pedestrian") { color = "#1f77b4"; size = 0.6 }
        gridSpace("floor", cols = GRID, rows = GRID, cellSize = 1.0)

        // Walls now come straight from the model's GridGraph (P5/G2) — no hand-duplication. The model linked its
        // wall graph via attachGeometry; here we extract those obstacles and draw them as filled cells.
        model.animationInventory().spaces.mapNotNull { it.geometry }.forEach { gridGeometry(it) }

        // The two exits.
        station("ExitTL", 0.0, 0.0, label = "Exit")
        station("ExitBR", GRID.toDouble(), GRID.toDouble(), label = "Exit")

        bar("PopulationInBuilding", 0.5, GRID + 0.6) { width = 14.0; height = 0.7; maxValue = 40.0; label = "In building" }
    }

}
