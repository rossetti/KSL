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

import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.Cell
import ksl.modeling.agent.GridGraph
import ksl.modeling.agent.GridProjection
import ksl.modeling.agent.MovementRule
import ksl.modeling.agent.positive
import ksl.modeling.entity.KSLProcess
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement

/**
 *  Building-evacuation model using the agent layer's grid-graph
 *  abstraction. A 2D layout with walls separates a population of
 *  pedestrians from two exits; everyone navigates to whichever exit
 *  is closest along the cell graph (respecting walls and the
 *  one-passage bottleneck) and leaves the building.
 *
 *  Pattern demonstrated: **multi-source distance field**.
 *
 *  Instead of having each pedestrian compute its own A* path every
 *  step (O(grid) per pedestrian per step), we compute *one*
 *  distance-from-any-exit field at model setup via
 *  [GridGraph.distanceField] with both exits as sources. Each
 *  pedestrian then picks the neighbor with the smallest field value
 *  in O(neighbors) per step — typically 8. This is the canonical
 *  pattern when many agents share a common goal (also used in
 *  flow-field pathfinding, goal-directed flocking, ant colony
 *  optimization with attractant gradients).
 *
 *  Composition:
 *   - [grid]: places pedestrians at cells, lets queries find
 *     "who's in this cell" (not used here but available for
 *     density-aware variants).
 *   - [graph]: models the building topology — walls are blocked
 *     cells, passable cells form the navigation lattice.
 *   - [exitDistanceField]: precomputed once at the start of each
 *     replication.
 *
 *  Layout (15x15):
 *  ```
 *  E.............
 *  ..............
 *  ..............
 *  ..............
 *  ..............
 *  ...X.X........        (X = wall)
 *  ...X.X........
 *  ...X.X........        Two big rooms separated by a wall
 *  ...X.X........        with a single doorway gap at (4, 7).
 *  ...X.X........
 *  ...X.X........
 *  ...X.X........
 *  ..............
 *  ..............
 *  .............E
 *  ```
 *  Two exits at the top-left (0, 0) and bottom-right (14, 14).
 */
class BuildingEvacuationExample(parent: ModelElement, name: String? = null) :
    AgentModel(parent, name) {

    // ── World geometry ──────────────────────────────────────────────────────

    val gridSize: Int = 15

    /** The two exit cells. */
    val exits: Set<Cell> = setOf(Cell(0, 0), Cell(gridSize - 1, gridSize - 1))

    var population: Int by positive(Defaults.population)
    var stepDuration: Double by positive(Defaults.stepDuration)

    /** Mutable global defaults for [BuildingEvacuationExample]. */
    companion object Defaults {
        /** Number of pedestrians to spawn per replication. Must be positive. */
        var population: Int by positive(30)
        /** Time per movement step (one cell traversal). Must be positive. */
        var stepDuration: Double by positive(1.0)
    }

    // ── Infrastructure ──────────────────────────────────────────────────────

    val pedestrians: Context<Pedestrian> = Context("pedestrians")

    val graph: GridGraph = GridGraph(gridSize, gridSize, movementRule = MovementRule.MOORE)
        .also { g ->
            // Build the wall: column 4, rows 5..11. Leave row 7 open
            // as the doorway between the two rooms.
            for (row in 5..11) {
                if (row == 7) continue
                g.block(Cell(4, row))
            }
            // Build a second wall: column 5, rows 5..11, same gap pattern.
            for (row in 5..11) {
                if (row == 7) continue
                g.block(Cell(5, row))
            }
        }

    val grid: GridProjection<Pedestrian> = GridProjection(
        context = pedestrians, columns = gridSize, rows = gridSize,
    )

    // ── Responses ──────────────────────────────────────────────────────────

    val evacuationTime: Response = Response(this, "EvacuationTime")
    val populationInBuilding: TWResponse = TWResponse(this, "PopulationInBuilding")

    // ── Distance field (one-time per-replication compute) ─────────────────

    /**
     *  Distance from each passable cell to the nearest exit.
     *  Recomputed at the start of each replication so that any
     *  setup-phase changes to [graph] (e.g., user changes a wall
     *  configuration) are reflected.
     */
    private lateinit var exitDistanceField: Map<Cell, Double>

    // ── Pedestrian ─────────────────────────────────────────────────────────

    private var nextId: Int = 0

    inner class Pedestrian : Agent("ped-${++nextId}") {
        private val createdAt: Double = currentTime

        val script: KSLProcess = process(isDefaultProcess = true) {
            while (true) {
                val currentCell = grid.cellOf(this@Pedestrian) ?: break
                if (currentCell in exits) {
                    // Reached an exit — record stats and leave.
                    evacuationTime.value = currentTime - createdAt
                    populationInBuilding.decrement()
                    pedestrians.remove(this@Pedestrian)
                    break
                }
                // Pick the passable neighbor with the smallest exit
                // distance — gradient descent on the distance field.
                val neighbors = graph.passableNeighbors(currentCell)
                if (neighbors.isEmpty()) {
                    // Sealed in; can't make progress. Record evacuation
                    // failure by leaving the population without an
                    // evacuationTime sample.
                    populationInBuilding.decrement()
                    pedestrians.remove(this@Pedestrian)
                    break
                }
                val nextCell = neighbors.minByOrNull {
                    exitDistanceField[it] ?: Double.POSITIVE_INFINITY
                } ?: break
                val nextDistance = exitDistanceField[nextCell] ?: Double.POSITIVE_INFINITY
                if (nextDistance == Double.POSITIVE_INFINITY) {
                    // No path to any exit. Same outcome as sealed-in case.
                    populationInBuilding.decrement()
                    pedestrians.remove(this@Pedestrian)
                    break
                }
                grid.moveTo(this@Pedestrian, nextCell)
                delay(stepDuration)
            }
        }
    }

    val populationList: MutableList<Pedestrian> = mutableListOf()

    override fun initialize() {
        super.initialize()

        // Compute the distance field once per replication.
        exitDistanceField = graph.distanceField(exits)

        // (Re)create the pedestrian population.
        populationList.clear()
        val stream = defaultRNStream
        val occupied = HashSet<Cell>()
        var count = 0
        // Place each pedestrian at a random passable, non-exit cell.
        // Avoid co-location for clarity — same cell more than once
        // is allowed by the MULTIPLE-occupancy GridProjection but
        // creates visualization mush.
        while (count < population) {
            val c = stream.randInt(0, gridSize - 1)
            val r = stream.randInt(0, gridSize - 1)
            val cell = Cell(c, r)
            if (!graph.isPassable(cell)) continue
            if (cell in exits) continue
            if (cell in occupied) continue
            // Only accept reachable cells (the field assigns them a
            // finite distance). Skips any region accidentally walled
            // off in setup.
            if (exitDistanceField[cell] == null) continue
            occupied.add(cell)
            val p = Pedestrian()
            populationList.add(p)
            pedestrians.add(p)
            grid.placeAt(p, cell)
            populationInBuilding.increment()
            activate(p.script)
            count += 1
        }
    }
}

fun main() {
    val model = Model("BuildingEvacuationExample")
    val sys = BuildingEvacuationExample(model, "evacuation")
    sys.population = 50
    model.lengthOfReplication = 100.0
    model.numberOfReplications = 1
    model.simulate()
    model.print()
    println("Pedestrians still in building at end: ${sys.pedestrians.size}")
}
