/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.utilities.distributions.fitting.mixture

import kotlin.math.floor

/**
 *  A half-open range of observation indices from which a component is estimated.
 *
 *  Distinct from the group it belongs to. A group is an *assignment* — which component each
 *  observation is attributed to, and therefore what the mixing weights are. A window is an
 *  *estimation* range, and it may reach past the group into its neighbours. Every observation
 *  belongs to exactly one group; it may inform more than one estimate.
 *
 *  @param startIndex the first index, inclusive
 *  @param endIndex the last index, exclusive
 */
data class EstimationWindow(val startIndex: Int, val endIndex: Int) {

    init {
        require(startIndex >= 0) { "The start index must be non-negative" }
        require(endIndex > startIndex) { "The end index must be > the start index" }
    }

    /** The number of observations the window spans. */
    val size: Int
        get() = endIndex - startIndex
}

/**
 *  Computes estimation windows from assignment groups.
 *
 *  The window for a group spanning observations from `startIndex` to `endIndex` reaches
 *  `floor(delta * m)` observations into each neighbour, where `m` is the group's own size,
 *  clipped at the ends of the sample. At `delta` of zero the window is the group.
 *
 *  **The window is defined from the group's own size, not its neighbours', and that is not a
 *  detail.** The exact segmentation dynamic program requires the cost of a segment to depend
 *  on its own two boundaries and nothing else. A window defined as a fraction of the
 *  neighbours' sizes would depend on the boundaries either side of those, the recursion would
 *  no longer be a chain, and the linear-time result would be lost. A fraction of the group's
 *  own size keeps the dependence local.
 */
object EstimationWindows {

    /**
     *  The estimation window for one group.
     *
     *  @param startIndex the group's first index, inclusive
     *  @param endIndex the group's last index, exclusive
     *  @param numObservations the size of the whole sample, used to clip the window
     *  @param delta how far to reach into each neighbour, as a fraction of the group's size
     */
    fun windowFor(
        startIndex: Int,
        endIndex: Int,
        numObservations: Int,
        delta: Double
    ): EstimationWindow {
        require(delta >= 0.0) { "The window fraction must be non-negative. It was $delta" }
        require(delta.isFinite()) { "The window fraction must be finite" }
        require(startIndex >= 0) { "The start index must be non-negative" }
        require(endIndex > startIndex) { "The end index must be > the start index" }
        require(endIndex <= numObservations) {
            "The end index ($endIndex) must be <= the number of observations ($numObservations)"
        }
        if (delta == 0.0) {
            // returned without arithmetic, so that the zero case cannot differ from the
            // group by a rounding step. The equivalence of delta = 0 to the unwidened
            // method is the property everything else rests on.
            return EstimationWindow(startIndex, endIndex)
        }
        val reach = floor(delta * (endIndex - startIndex)).toInt()
        return EstimationWindow(
            maxOf(0, startIndex - reach),
            minOf(numObservations, endIndex + reach)
        )
    }

    /**
     *  The windows for every group of a partition, in group order.
     *
     *  @param partition the assignment partition
     *  @param delta how far to reach into each neighbour, as a fraction of each group's size
     */
    fun windowsFor(partition: DataPartition, delta: Double): List<EstimationWindow> {
        return (0 until partition.numGroups).map { g ->
            windowFor(
                partition.startIndex(g), partition.endIndex(g),
                partition.numObservations, delta
            )
        }
    }
}
