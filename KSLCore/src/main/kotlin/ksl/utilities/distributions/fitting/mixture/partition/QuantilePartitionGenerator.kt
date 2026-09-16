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
 *  Partitions sorted data into groups of as nearly equal count as the value structure permits,
 *  by placing each cut at the admissible position closest to the corresponding sample quantile.
 *
 *  This exists as a baseline. It uses no information about the shape of the data, so comparing a
 *  fitted result initialized this way against one initialized by a shape-aware generator
 *  isolates how much the initialization contributes. It is also the natural fallback when a
 *  shape-aware generator fails.
 *
 *  Cuts are snapped to admissible positions and then repaired left to right so that every group
 *  meets the size and distinct-value requirements. When the requirements cannot be met, null is
 *  returned rather than a partition that the caller would have to validate.
 */
class QuantilePartitionGenerator : PartitionGeneratorIfc {

    override val name: String = "Quantile"

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

        val n = sortedData.size
        val admissible = certificate.admissibleCuts
        val cuts = IntArray(numGroups - 1)
        var previous = 0
        for (g in 1 until numGroups) {
            val target = ((g.toLong() * n) / numGroups).toInt()
            // the smallest admissible position that is > previous and makes the group just
            // closed valid; among those prefer the one nearest the quantile target
            var chosen = -1
            var bestDistance = Int.MAX_VALUE
            for (t in admissible) {
                if (t <= previous || t >= n) continue
                if (!certificate.isValidGroup(previous, t)) continue
                // leave enough room for the groups that follow
                if (!canComplete(certificate, t, numGroups - g)) continue
                val distance = kotlin.math.abs(t - target)
                if (distance < bestDistance) {
                    bestDistance = distance
                    chosen = t
                }
            }
            if (chosen < 0) return null
            cuts[g - 1] = chosen
            previous = chosen
        }
        if (!certificate.isValidGroup(previous, n)) return null
        val partition = DataPartition(n, cuts)
        return if (satisfies(partition, certificate)) partition else null
    }

    /**
     *  Indicates whether the suffix beginning at `start` can be divided into the supplied number
     *  of valid groups, by the same greedy argument the certificate uses for the whole sample.
     */
    private fun canComplete(
        certificate: AdmissibilityCertificate,
        start: Int,
        groupsRemaining: Int
    ): Boolean {
        var count = 0
        var position = start
        val n = certificate.numObservations
        val admissible = certificate.admissibleCuts
        while (position < n && count < groupsRemaining) {
            var next = -1
            for (t in admissible) {
                if (t > position && certificate.isValidGroup(position, t)) {
                    next = t
                    break
                }
            }
            if (next < 0) break
            count++
            position = next
        }
        // the final group may absorb whatever remains, so reaching the count is sufficient
        return count >= groupsRemaining
    }

    override fun toString(): String = "QuantilePartitionGenerator"
}
