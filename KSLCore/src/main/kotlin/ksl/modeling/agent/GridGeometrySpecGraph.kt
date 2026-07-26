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

package ksl.modeling.agent

/*
 * The graph-rebuilding half of the GridGeometrySpec round-trip, kept apart from the spec itself.
 *
 * GridGeometrySpec is a serializable description carried inside an AnimationLayout, so it is compiled
 * for a non-JVM target as well to drive a web renderer. Rebuilding a GridGraph from it pulls in the
 * whole pathfinding graph, which belongs to the modeling side only. Splitting them lets the spec travel
 * with the layout while the graph stays here. Same package, so `spec.toGridGraph()` is unchanged.
 */

/**
 * Rebuilds a [GridGraph] from this structural spec — the consume side of the round-trip (P5b/G2). A modeler
 * who wants the layout to be authoritative for geometry loads the layout, looks up the spec
 * (`AnimationLayout.gridGeometry(name)`), and builds the graph from it. Reproduces dims, topology, obstacles,
 * and costs exactly; the physical placement fields (`originX`/`originY`/`cellSize`) are layout/render concerns
 * and are not part of the graph.
 */
fun GridGeometrySpec.toGridGraph(): GridGraph =
    GridGraph(cols, rows, torus = torus, movementRule = movementRule, allowCornerCutting = allowCornerCutting).also { g ->
        blockedCells.forEach { g.block(it) }
        cellCosts.forEach { g.setCellCost(Cell(it.col, it.row), it.cost) }
    }

