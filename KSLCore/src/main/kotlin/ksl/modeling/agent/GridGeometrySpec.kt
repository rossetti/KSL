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

import kotlinx.serialization.Serializable

/**
 * A neutral, serializable description of a grid's *structural* geometry — its dimensions, topology, and the
 * obstacles/costs a [GridGraph] holds — keyed to the animated space it overlays by [spaceName] (P5a/G2).
 *
 * This is the round-trip currency between the model and the layout: a [GridGraph] exports one via
 * [GridGraph.toSpec], the animation layer carries it in `AnimationLayout.spaceGeometry`, and (P5b) a modeler
 * can rebuild a graph from one. It carries only *structural* fields — never display styling.
 *
 * [originX]/[originY]/[cellSize] are the grid's *physical* placement in the space's world coordinates. They
 * are distinct from a `SpatialSpaceDescriptor.Grid`'s *display* cell size (chosen for drawing). When null, the
 * renderer derives placement from the named space's bounds (`cellSize = span/cols`, `origin = bounds min`).
 */
@Serializable
data class GridGeometrySpec(
    val spaceName: String,
    val cols: Int,
    val rows: Int,
    val torus: Boolean = false,
    val movementRule: MovementRule = MovementRule.MOORE,
    val allowCornerCutting: Boolean = false,
    val blockedCells: List<Cell> = emptyList(),
    val cellCosts: List<CellCost> = emptyList(),
    val originX: Double? = null,
    val originY: Double? = null,
    val cellSize: Double? = null,
)

/** A per-cell traversal cost (terrain weight) carried by a [GridGeometrySpec] — TOML/JSON friendly. */
@Serializable
data class CellCost(val col: Int, val row: Int, val cost: Double)

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

