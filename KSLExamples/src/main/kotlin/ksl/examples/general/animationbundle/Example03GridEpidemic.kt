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
import ksl.examples.general.agent.GridEpidemicExample
import ksl.simulation.Model

/**
 * Example 3 — the first **agent-based** demo: an SIR epidemic on a 20×20 grid torus
 * ([GridEpidemicExample]). Its purpose is to drive the *agent* paradigm end-to-end and make the
 * agent-rendering gaps concrete (it is the validation behind the plan's would-be "8F" items).
 *
 * What this exercises and what to look for:
 *  - Agents emit `AgentPositionChanged` with **cell coordinates** (x = column, y = row), so the
 *    layout uses a world where **1 unit = 1 cell** to line agents up with the drawn grid.
 *  - The current renderer draws each agent as a dot colored by its **name**, so the 50 agents come
 *    out in 8 cycling palette colors — there is **no SIR state coloring** (Susceptible/Infected/
 *    Recovered), even though the engine emits `AgentStateEntered/Exited`. That missing state-based
 *    styling is exactly the agent gap we're validating.
 *  - The three `TWResponse`s (NumSusceptible/NumInfected/NumRecovered) flow through the normal
 *    response path, so the SIR populations show as live bars (the population-by-state display that
 *    process-view Phase 8 already covers).
 */
object Example03GridEpidemic {

    private const val GRID = 20

    fun buildModel(): Model {
        val m = Model("GridEpidemicModel")
        GridEpidemicExample(m, "epidemic").apply {
            gridSize = GRID
            population = 50
            initialInfected = 4
        }
        m.numberOfReplications = 1
                // The last infection clears at t = 38, after which the survivors wander a picture that no
        // longer changes. Fifty shows the whole epidemic with a tail, rather than eighty units of nothing.
        m.lengthOfReplication = 50.0
        return m
    }

    /**
     * Layout in **cell units**: a GRID×GRID grid space drawn 1 world-unit per cell so the agent
     * (column, row) positions register on it, three SIR population bars below the grid, and a clock.
     * Agent dots are sized in world units (sub-cell). (The need to hand-match the world scale to
     * cell coordinates is itself a finding: the renderer has no grid-cell coordinate awareness yet.)
     */
    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "SIR Epidemic on a Grid (agent demo)"
        // World is the grid (0..GRID) plus a strip below it for the SIR bars.
        size(GRID.toDouble(), GRID + 4.0)
        clock(0.3, 0.7, fontSize = GRID * 0.045)

        objectClass("Person") { color = "#1f77b4"; size = 0.7 }

        // SIR state coloring (8I.2): the model reports "Susceptible"/"Infected"/"Recovered" via
        // reportAnimationState, and these recolor each agent as the epidemic progresses.
        agentStateColor("Susceptible", "#1f77b4") // blue
        agentStateColor("Infected", "#d62728")    // red
        agentStateColor("Recovered", "#2ca02c")   // green

        gridSpace("people", cols = GRID, rows = GRID, cellSize = 1.0, originX = 0.0, originY = 0.0)

        // SIR populations as live bars, below the grid (green/red/gray); names match the model.
        bar("NumSusceptible", 0.5, GRID + 0.6) { width = 6.0; height = 0.7; maxValue = 50.0; color = "#2ca02c"; label = "Susceptible" }
        bar("NumInfected", 7.0, GRID + 0.6) { width = 6.0; height = 0.7; maxValue = 50.0; color = "#d62728"; label = "Infected" }
        bar("NumRecovered", 13.5, GRID + 0.6) { width = 6.0; height = 0.7; maxValue = 50.0; color = "#7f7f7f"; label = "Recovered" }
    }

}
