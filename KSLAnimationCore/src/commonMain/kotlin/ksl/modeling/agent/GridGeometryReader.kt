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

import kotlinx.serialization.Serializable

/**
 * The browser player's declaration of a grid obstacle overlay — **the part of it that gets drawn**.
 *
 * KSLCore declares the same thing in this package, alongside the pathfinding it exists to serve: movement
 * rules, corner-cutting, per-cell traversal costs, and a grid graph. None of that is needed to draw a wall.
 * What is needed is which cells are blocked and how a cell maps to the world, so that is all this declares,
 * and the layout codec's `ignoreUnknownKeys` skips the rest when a layout carries it.
 *
 * Declaring only the drawing subset is what keeps the player free of the agent-modelling machinery while
 * still letting it draw an obstacle map. The cost is a second declaration of part of a format, which is the
 * same bargain the layout reader itself makes and is guarded the same way — by conformance tests over
 * layouts the desktop app really produced.
 *
 * The names and the package match KSLCore's exactly, because the scene builder is one source file compiled
 * against KSLCore on the JVM and against this on Kotlin/JS. It has to see the same members either way.
 */
@Serializable
data class GridGeometrySpec(
    val spaceName: String,
    val cols: Int,
    val rows: Int,
    val blockedCells: List<Cell> = emptyList(),
    /** Where the grid's (0,0) cell sits in world coordinates; null lets the drawn space decide. */
    val originX: Double? = null,
    val originY: Double? = null,
    /** The physical size of a cell; null lets the drawn space decide. */
    val cellSize: Double? = null,
)

/** One cell of a grid, by column and row. */
@Serializable
data class Cell(val col: Int, val row: Int)
