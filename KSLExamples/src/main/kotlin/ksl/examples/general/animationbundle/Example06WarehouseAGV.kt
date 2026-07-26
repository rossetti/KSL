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
import ksl.examples.general.agent.WarehouseAGVExample
import ksl.simulation.Model

/**
 * Example 6 — autonomous AGVs in a warehouse. Four AGVs travel a 30×30 grid with rack obstacles,
 * bidding on pallet tasks (Contract-Net) and recharging at stations. The richest agent demo, chosen
 * to surface:
 *  - **Statechart states unrendered (the big agent gap):** each AGV cycles Idle → Bidding →
 *    Working → Charging (emitted as `AgentStateEntered/Exited`), but the renderer can only draw a
 *    single color — you cannot see what an AGV is *doing*. This is the agent analog of resource
 *    busy/idle coloring.
 *  - **Continuous, cell-centered motion works well:** AGV bodies travel via `travelThrough` to cell
 *    centers, so they render cleanly (unlike grid hops at cell corners).
 *  - **Brain/body split:** the AGV bodies have positions and render; the controller/dispatcher/
 *    task-generator agents have no position and do not appear — which is correct, but means an
 *    agent's presence in the animation depends on whether it is ever placed in a projection.
 *  - **Obstacle authoring friction:** racks and chargers are hand-authored to match the model's
 *    `GridGraph` (no geometry import).
 */
object Example06WarehouseAGV {

    private const val GRID = 30

    fun buildModel(): Model {
        val m = Model("WarehouseAGVModel")
        val sys = WarehouseAGVExample(m, "warehouse")
        // Enable per-state TWResponses (TimeInIdle/Bidding/Working/Charging) for each AGV.
        for (agv in sys.agvs) agv.collectPerformance()
        m.numberOfReplications = 1
        m.lengthOfReplication = 300.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Warehouse AGVs (agent demo)"
        size(GRID.toDouble(), GRID + 4.0)
        clock(0.3, 0.7, fontSize = GRID * 0.045)

        objectClass("AGV") { color = "#1f77b4"; size = 1.4 }
        // Color AGVs by their statechart state (8F.1): idle gray, bidding amber, working green,
        // charging red. Now visible because 8F.3 registers the AGV bodies and wires their charts.
        agentStateColor("Idle", "#999999")
        agentStateColor("Bidding", "#ff7f0e")
        agentStateColor("Working", "#2ca02c")
        agentStateColor("Charging", "#d62728")
        gridSpace("floor", cols = GRID, rows = GRID, cellSize = 1.0)

        // Rack obstacles (hand-authored to match the model): rows 5-6, 10-11, 15-16, 20-21, with
        // cross-aisle gaps at columns 9-11 and 19-21, and the left lane (col 0) kept clear.
        listOf(5, 10, 15, 20).forEach { r1 ->
            val y1 = r1.toDouble(); val y2 = (r1 + 2).toDouble()
            rect(1.0, y1, 9.0, y2, color = "#8c6d3f", strokeWidth = 1.0)
            rect(12.0, y1, 19.0, y2, color = "#8c6d3f", strokeWidth = 1.0)
            rect(22.0, y1, GRID.toDouble(), y2, color = "#8c6d3f", strokeWidth = 1.0)
        }
        // Charging stations on the left wall (cell centers).
        listOf(4, 14, 24).forEach { row -> station("Charger$row", 0.5, row + 0.5, label = "Charge") }

        bar("NumTasksCompleted", 0.5, GRID + 0.6) { width = 13.0; height = 0.8; maxValue = 60.0; color = "#2ca02c"; label = "Tasks done" }
        bar("NumChargingEvents", 15.0, GRID + 0.6) { width = 13.0; height = 0.8; maxValue = 30.0; color = "#d62728"; label = "Charges" }
    }

}
