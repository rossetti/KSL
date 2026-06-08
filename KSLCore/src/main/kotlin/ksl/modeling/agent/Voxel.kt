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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 *  A discrete 3D cell in a [VoxelProjection] / [VoxelGraph],
 *  addressed by integer column / row / layer coordinates. The 3D
 *  analog of [Cell].
 *
 *  Convention used throughout the agent layer's 3D primitives: [col]
 *  is the x-direction, [row] is the y-direction, and [layer] is the
 *  z-direction (typically altitude in UAV models). Distance helpers
 *  cover the four standard 3D grid metrics — Chebyshev3D, Manhattan3D,
 *  Euclidean3D, and the exact Octile3D for uniform-cost 26-neighbor
 *  movement; see [VoxelMetric].
 */
data class Voxel(val col: Int, val row: Int, val layer: Int) {

    /**
     *  Chebyshev3D (king-move) distance — `max(|Δcol|, |Δrow|, |Δlayer|)`.
     *  This is the radius used by the Moore-26 neighborhood: voxels
     *  within Chebyshev3D distance 1 form the 26-voxel shell around
     *  this one.
     */
    fun chebyshevDistanceTo(other: Voxel): Int =
        max(max(abs(col - other.col), abs(row - other.row)), abs(layer - other.layer))

    /**
     *  Manhattan3D (taxicab) distance — `|Δcol| + |Δrow| + |Δlayer|`.
     *  This is the radius used by the Von Neumann-6 neighborhood:
     *  voxels within Manhattan3D distance 1 form the 6-voxel
     *  axis-aligned cross around this one.
     */
    fun manhattanDistanceTo(other: Voxel): Int =
        abs(col - other.col) + abs(row - other.row) + abs(layer - other.layer)

    /**
     *  Straight-line (Euclidean) distance, computed in voxel units.
     */
    fun euclideanDistanceTo(other: Voxel): Double {
        val dc = (col - other.col).toDouble()
        val dr = (row - other.row).toDouble()
        val dl = (layer - other.layer).toDouble()
        return sqrt(dc * dc + dr * dr + dl * dl)
    }

    /**
     *  Octile3D distance: the exact minimum cost from this voxel to
     *  [other] on a uniform-cost Moore-26 movement grid with
     *  orthogonal step cost 1, face-diagonal cost √2, and body-
     *  diagonal cost √3. The natural admissible heuristic for A* on
     *  such a grid.
     *
     *  Given sorted absolute deltas `dMin ≤ dMid ≤ dMax`, the optimal
     *  path uses:
     *   - `dMin` body-diagonal steps (all three axes change),
     *   - `dMid - dMin` face-diagonal steps (two axes change),
     *   - `dMax - dMid` orthogonal steps (one axis changes).
     *
     *  ```
     *  octile3D = dMin * √3 + (dMid - dMin) * √2 + (dMax - dMid) * 1
     *  ```
     */
    fun octileDistanceTo(other: Voxel): Double {
        val dc = abs(col - other.col)
        val dr = abs(row - other.row)
        val dl = abs(layer - other.layer)
        val dMin = min(min(dc, dr), dl)
        val dMax = max(max(dc, dr), dl)
        val dMid = dc + dr + dl - dMin - dMax
        return dMin * sqrt(3.0) + (dMid - dMin) * sqrt(2.0) + (dMax - dMid)
    }
}

/**
 *  Choice of distance metric for [VoxelProjection] neighborhood and
 *  volumetric-query operations. The 3D analog of [GridMetric].
 *
 *  - [CHEBYSHEV] — king-move distance. Within radius 1 = Moore-26
 *    neighborhood (26 surrounding voxels).
 *  - [MANHATTAN] — taxicab distance. Within radius 1 = Von Neumann-6
 *    neighborhood (6 surrounding voxels).
 *  - [EUCLIDEAN] — straight-line distance in voxel units. The
 *    "neighbors within distance r" set is a discrete approximation
 *    of a sphere.
 */
enum class VoxelMetric {
    CHEBYSHEV,
    MANHATTAN,
    EUCLIDEAN,
}
