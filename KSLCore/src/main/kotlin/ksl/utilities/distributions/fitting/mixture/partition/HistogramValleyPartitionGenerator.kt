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
 *  **Bins are spaced by rank, not by value, and a bin's height is its density.** Equal-width
 *  bins have no resolution where a skewed sample actually lives: on a production lead-time
 *  sample of 10,000 spanning four decades, fifty equal-width bins put 54% of the observations
 *  in the first bin, and every valley detectable in the remainder lay above the 95th percentile,
 *  so the generator could only ever report tail noise. Spacing the edges at equally spaced order
 *  statistics puts the resolution where the observations are and makes the rule scale-free, which
 *  matters because a positive quantity spanning decades is the ordinary case in input modeling
 *  rather than an awkward one.
 *
 *  Unequal widths make the two changes inseparable. With equal-width bins a count is
 *  proportional to a density and either may be compared; with rank-spaced bins every count is
 *  the same by construction, so counts carry no information at all and the height compared must
 *  be the count divided by the bin's width. A sparse stretch of the line then shows as a wide
 *  bin of low density, which is the gap this generator is looking for.
 *
 *  The bin count is set explicitly rather than left to automatic selection, since KSL's automatic
 *  binning is tuned for display and can return as few as two bins for a widely gapped sample.
 *  The default rule is the square root of the sample size, bounded to a sensible range.
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

        val histogram = binByRank(sortedData) ?: return null
        val density = histogram.density
        if (density.size < 3) return null

        // A valley is an interior bin no taller than both neighbours. Depth is measured against
        // the smaller of the two surrounding peaks, so a shallow dip beside a tall mode does not
        // outrank a genuine trough between two modest ones. A monotone tail therefore yields no
        // valley at all, which is correct: a density that only falls has no trough in it.
        //
        // Depth is a ratio, taken as a difference of logarithms, rather than a difference of
        // densities. Over a sample spanning decades the densities do too, so an absolute
        // difference makes the deepest valley the one nearest the tallest mode whatever its
        // shape, and the generator returns a cluster of cuts packed around the mode. What makes
        // a trough a trough is how far the density falls relative to what surrounds it.
        val candidates = mutableListOf<Pair<Int, Double>>()
        for (b in 1 until density.size - 1) {
            if (density[b] <= density[b - 1] && density[b] <= density[b + 1]) {
                val leftPeak = (0 until b).maxOf { density[it] }
                val rightPeak = ((b + 1) until density.size).maxOf { density[it] }
                val shallower = minOf(leftPeak, rightPeak)
                val depth = if (density[b] > 0.0 && shallower > 0.0) {
                    kotlin.math.ln(shallower) - kotlin.math.ln(density[b])
                } else if (shallower > 0.0) {
                    Double.MAX_VALUE     // an empty stretch is the deepest gap there is
                } else 0.0
                if (depth > 0.0) {
                    candidates.add(b to depth)
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
            val position = admissibleNear(certificate, histogram.rightEdgeRank[bin])
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

    /**
     *  A histogram whose bins hold equal numbers of observations.
     *
     *  @param density the observations per unit of the line in each bin
     *  @param rightEdgeRank the index into the sorted data of the first observation beyond each
     *  bin, exact here because every edge is an order statistic
     */
    private class RankHistogram(val density: DoubleArray, val rightEdgeRank: IntArray)

    /**
     *  Bins the sample at equally spaced order statistics.
     *
     *  Ties can place two edges on the same value, giving a bin of zero width and an unbounded
     *  density. Such a bin is a spike rather than a valley, so it takes the largest finite
     *  density present instead of being dropped: that keeps the bin indices aligned with the
     *  edges, and keeps a run of identical values from ever being read as a gap.
     */
    private fun binByRank(sortedData: DoubleArray): RankHistogram? {
        val n = sortedData.size
        if (n < 3) return null
        if (sortedData.last() <= sortedData.first()) return null  // constant data has no valley
        val numBins = numberOfBins(n).coerceAtMost(n)
        if (numBins < 3) return null

        val edgeRank = IntArray(numBins + 1) { (it.toLong() * n / numBins).toInt() }
        edgeRank[numBins] = n
        val density = DoubleArray(numBins)
        for (b in 0 until numBins) {
            val count = edgeRank[b + 1] - edgeRank[b]
            val low = sortedData[edgeRank[b]]
            val high = sortedData[(edgeRank[b + 1] - 1).coerceAtLeast(edgeRank[b])]
            val width = high - low
            density[b] = if (width > 0.0) count / width else Double.POSITIVE_INFINITY
        }
        val largestFinite = density.filter { it.isFinite() }.maxOrNull() ?: return null
        for (b in density.indices) {
            if (!density[b].isFinite()) density[b] = largestFinite
        }
        return RankHistogram(density, IntArray(numBins) { edgeRank[it + 1] })
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
