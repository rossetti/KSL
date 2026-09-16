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
package ksl.utilities.distributions.fitting.mixture.partition

import ksl.utilities.distributions.fitting.mixture.AdmissibilityCertificate
import ksl.utilities.distributions.fitting.mixture.DataPartition
import ksl.utilities.statistic.Histogram

/**
 *  Places cuts at the deepest valleys of a histogram of the data.
 *
 *  A mixture of well separated components shows as modes separated by troughs, and the trough
 *  is close to where the component densities cross. This generator therefore encodes a
 *  different belief about where a good partition lies than the within-group sum of squares
 *  criterion does: it looks for gaps in the data rather than for compact groups. Comparing
 *  fits initialized both ways is how the experiment measures whether that belief pays.
 *
 *  Bin boundaries are mapped to admissible cut positions, valleys are ranked by depth relative
 *  to the larger neighbouring peak, and the deepest are taken subject to leaving every group
 *  valid. When too few usable valleys are found, null is returned; the caller is expected to
 *  fall back to another generator rather than receive a partition that means nothing.
 *
 *  The bin count is set explicitly rather than left to automatic selection. KSL's automatic
 *  binning is tuned for display and can return as few as two bins for a widely gapped sample,
 *  which leaves no interior bin in which a valley could be found. Since the whole purpose here
 *  is to locate a gap, the resolution must be fine enough to contain one; the default rule is
 *  the square root of the sample size, bounded to a sensible range.
 */
class HistogramValleyPartitionGenerator : PartitionGeneratorIfc {

    override val name: String = "HistogramValley"

    override fun generate(
        sortedData: DoubleArray,
        numGroups: Int,
        certificate: AdmissibilityCertificate
    ): DataPartition? {
        require(numGroups > 0) { "The number of groups must be > 0" }
        require(sortedData.size == certificate.numObservations) {
            "The data size (${sortedData.size}) must match the certificate " +
                    "(${certificate.numObservations})"
        }
        if (!certificate.isFeasible(numGroups)) return null
        if (numGroups == 1) return DataPartition.single(sortedData.size)

        val counts = binCounts(sortedData) ?: return null
        if (counts.size < 3) return null

        // A valley is an interior bin no larger than both neighbours. Depth is measured against
        // the smaller of the two surrounding peaks, so a shallow dip beside a tall mode does not
        // outrank a genuine trough between two modest ones.
        val candidates = mutableListOf<Pair<Int, Double>>()
        for (b in 1 until counts.size - 1) {
            if (counts[b] <= counts[b - 1] && counts[b] <= counts[b + 1]) {
                val leftPeak = (0 until b).maxOf { counts[it] }
                val rightPeak = ((b + 1) until counts.size).maxOf { counts[it] }
                val depth = minOf(leftPeak, rightPeak) - counts[b]
                if (depth > 0) {
                    candidates.add(b to depth.toDouble())
                }
            }
        }
        if (candidates.size < numGroups - 1) return null
        candidates.sortByDescending { it.second }

        // Map each candidate bin to the admissible cut nearest its right edge, then take the
        // deepest that keep every group valid.
        val chosen = sortedSetOfCuts()
        for ((bin, _) in candidates) {
            if (chosen.size == numGroups - 1) break
            val position = admissibleNear(certificate, binRightEdgeIndex(sortedData, counts, bin))
            if (position <= 0 || position >= sortedData.size) continue
            if (chosen.contains(position)) continue
            chosen.add(position)
            if (!allGroupsValid(certificate, chosen, sortedData.size)) {
                chosen.remove(position)
            }
        }
        if (chosen.size != numGroups - 1) return null
        val partition = DataPartition(sortedData.size, chosen.toIntArray())
        return if (satisfies(partition, certificate)) partition else null
    }

    private fun sortedSetOfCuts(): java.util.TreeSet<Int> = java.util.TreeSet()

    private fun binCounts(sortedData: DoubleArray): IntArray? {
        val n = sortedData.size
        val lower = sortedData.first()
        val upper = sortedData.last()
        if (upper <= lower) return null           // constant data has no valley
        val numBins = numberOfBins(n)
        return try {
            val breakPoints = Histogram.createBreakPoints(lower, upper, numBins)
            val histogram = Histogram(breakPoints)
            histogram.collect(sortedData)
            val bins = histogram.bins
            if (bins.isEmpty()) null else IntArray(bins.size) { bins[it].count.toInt() }
        } catch (e: IllegalArgumentException) {
            // Histogram rejecting the range or the bin count for degenerate data; the caller
            // falls back. Narrow so a defect in binning is not mistaken for degenerate input.
            null
        }
    }

    /**
     *  The number of bins used to look for valleys. Fine enough that a gap between components
     *  occupies at least one interior bin, coarse enough that sampling noise does not create
     *  spurious troughs.
     *
     *  @param n the number of observations
     */
    private fun numberOfBins(n: Int): Int {
        val root = kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt()
        return root.coerceIn(minimumBins, maximumBins)
    }

    /**
     *  The index into the sorted data of the first observation beyond the supplied bin, obtained
     *  by counting observations in the preceding bins.
     */
    private fun binRightEdgeIndex(sortedData: DoubleArray, counts: IntArray, bin: Int): Int {
        var index = 0
        for (b in 0..bin) {
            index += counts[b]
        }
        return index.coerceIn(0, sortedData.size)
    }

    private fun admissibleNear(certificate: AdmissibilityCertificate, position: Int): Int {
        if (certificate.isAdmissibleCut(position)) return position
        val admissible = certificate.admissibleCuts
        var best = -1
        var bestDistance = Int.MAX_VALUE
        for (t in admissible) {
            if (t <= 0 || t >= certificate.numObservations) continue
            val distance = kotlin.math.abs(t - position)
            if (distance < bestDistance) {
                bestDistance = distance
                best = t
            }
        }
        return best
    }

    private fun allGroupsValid(
        certificate: AdmissibilityCertificate,
        cuts: java.util.TreeSet<Int>,
        n: Int
    ): Boolean {
        var previous = 0
        for (c in cuts) {
            if (!certificate.isValidGroup(previous, c)) return false
            previous = c
        }
        return certificate.isValidGroup(previous, n)
    }

    override fun toString(): String = "HistogramValleyPartitionGenerator"

    companion object {

        /**
         *  The fewest bins used when searching for valleys. Below three there is no interior
         *  bin and no valley can be detected.
         */
        var minimumBins: Int = 8
            set(value) {
                require(value >= 3) { "The minimum number of bins must be >= 3" }
                field = value
            }

        /**
         *  The most bins used when searching for valleys. Bounding this keeps sampling noise
         *  from producing spurious troughs in large samples.
         */
        var maximumBins: Int = 50
            set(value) {
                require(value >= 3) { "The maximum number of bins must be >= 3" }
                field = value
            }
    }
}
