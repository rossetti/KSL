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

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 *  A discrete cell in a [GridProjection] addressed by integer
 *  column / row coordinates.
 *
 *  Convention used throughout the agent layer: [col] is the
 *  x-direction (0 at the left, increasing right) and [row] is the
 *  y-direction (0 at the top or bottom — the projection treats them
 *  symmetrically). Distance helpers are provided for the three
 *  standard grid metrics; see [GridMetric].
 */
data class Cell(val col: Int, val row: Int) {

    /**
     *  Chebyshev (king-move) distance — `max(|Δcol|, |Δrow|)`. This
     *  is the radius used by the Moore neighborhood: cells within
     *  Chebyshev distance 1 form the 8-cell ring around this one.
     */
    fun chebyshevDistanceTo(other: Cell): Int =
        max(abs(col - other.col), abs(row - other.row))

    /**
     *  Manhattan (taxicab) distance — `|Δcol| + |Δrow|`. This is the
     *  radius used by the Von Neumann neighborhood: cells within
     *  Manhattan distance 1 form the 4-cell cross around this one.
     */
    fun manhattanDistanceTo(other: Cell): Int =
        abs(col - other.col) + abs(row - other.row)

    /**
     *  Straight-line (Euclidean) distance, computed in cell units.
     */
    fun euclideanDistanceTo(other: Cell): Double =
        hypot((col - other.col).toDouble(), (row - other.row).toDouble())

    /**
     *  Octile (king-move with √2 diagonals) distance: the exact
     *  minimum cost from this cell to [other] on a uniform-cost
     *  Moore-movement grid with orthogonal cost 1 and diagonal cost
     *  √2. The natural admissible heuristic for A* on such a grid.
     *
     *  For a path with `maxAxis - minAxis` orthogonal steps and
     *  `minAxis` diagonal steps:
     *  ```
     *  octile = (maxAxis - minAxis) * 1 + minAxis * √2
     *  ```
     */
    fun octileDistanceTo(other: Cell): Double {
        val dc = abs(col - other.col)
        val dr = abs(row - other.row)
        val minAxis = min(dc, dr)
        val maxAxis = max(dc, dr)
        return (maxAxis - minAxis) + minAxis * sqrt(2.0)
    }
}

/**
 *  Choice of distance metric for [GridProjection] neighborhood and
 *  spatial-query operations.
 *
 *  - [CHEBYSHEV] — king-move distance. Within radius 1 = Moore
 *    neighborhood (8 surrounding cells).
 *  - [MANHATTAN] — taxicab distance. Within radius 1 = Von Neumann
 *    neighborhood (4 surrounding cells).
 *  - [EUCLIDEAN] — straight-line distance in cell units. The
 *    "neighbors within distance r" set is a discrete approximation
 *    of a disk.
 */
enum class GridMetric {
    CHEBYSHEV,
    MANHATTAN,
    EUCLIDEAN,
}
