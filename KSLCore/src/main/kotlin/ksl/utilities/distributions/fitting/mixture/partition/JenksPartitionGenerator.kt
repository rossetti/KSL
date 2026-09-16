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
 *  Produces the contiguous partition of sorted data that minimizes the total within-group sum
 *  of squared deviations, by the exact dynamic program of Fisher (1958), popularized in
 *  cartography as Jenks natural breaks.
 *
 *  With prefix sums the sum of squared deviations of any index range is available in constant
 *  time, so the recursion
 *
 *      D(i, g) = min over t of { D(t, g-1) + SS(t, i) }
 *
 *  is evaluated in O(n^2 k) time using O(n k) space. The tables are built once for the largest
 *  requested number of groups and reused for every smaller number, because the recursion for
 *  g groups is a prefix of the recursion for more.
 *
 *  Two departures from the prototype this replaces are deliberate. There is no `optimize`
 *  function that selects a number of groups by minimizing the objective: the total within-group
 *  sum of squares is non-increasing in the number of groups, so such a function always returns
 *  the largest number offered, which is not a model selection procedure. Choosing the number of
 *  components is a model selection question addressed by the scoring layer. Second, cuts are
 *  restricted to positions that do not split a run of equal values, so the result is always a
 *  partition by value rather than by index.
 *
 *  @param sortedData the observations in non-decreasing order, must not be empty
 *  @param maximumGroups the largest number of groups the tables should support, must be positive
 */
class JenksPartitionGenerator(
    sortedData: DoubleArray,
    maximumGroups: Int
) : PartitionGeneratorIfc {

    override val name: String = "Jenks"

    private val myNumObservations: Int = sortedData.size
    private val myMaximumGroups: Int
    private val myPrefixSum: DoubleArray
    private val myPrefixSumSquares: DoubleArray

    /** The minimal total within-group sum of squares of partitioning the first i observations
     *  into g+1 groups, indexed by group count then observation count; Double.MAX_VALUE marks an
     *  unreachable state. */
    private val myCost: Array<DoubleArray>

    /** The start index of the last group in the optimal g+1 group partition of the first i
     *  observations, indexed by group count then observation count. */
    private val myBack: Array<IntArray>

    init {
        require(sortedData.isNotEmpty()) { "The data must not be empty" }
        require(maximumGroups > 0) { "The maximum number of groups must be > 0" }
        for (i in 1 until sortedData.size) {
            require(sortedData[i - 1] <= sortedData[i]) {
                "The data must be sorted in non-decreasing order"
            }
        }
        myMaximumGroups = minOf(maximumGroups, myNumObservations)
        val n = myNumObservations
        myPrefixSum = DoubleArray(n + 1)
        myPrefixSumSquares = DoubleArray(n + 1)
        for (i in 0 until n) {
            myPrefixSum[i + 1] = myPrefixSum[i] + sortedData[i]
            myPrefixSumSquares[i + 1] = myPrefixSumSquares[i] + sortedData[i] * sortedData[i]
        }
        myCost = Array(myMaximumGroups) { DoubleArray(n + 1) { Double.MAX_VALUE } }
        myBack = Array(myMaximumGroups) { IntArray(n + 1) { -1 } }
        buildTables()
    }

    /**
     *  The sum of squared deviations from the mean of the observations in the index range from
     *  `start` until `end`, computed in constant time from prefix sums. An empty range has
     *  zero sum of squares.
     *
     *  @param start the inclusive start index
     *  @param end the exclusive end index
     */
    fun sumOfSquares(start: Int, end: Int): Double {
        require(start >= 0) { "The start index must be >= 0. It was $start" }
        require(end <= myNumObservations) {
            "The end index must be <= the number of observations. It was $end"
        }
        require(start <= end) { "The start ($start) must be <= the end ($end)" }
        val count = end - start
        if (count <= 1) return 0.0
        val sum = myPrefixSum[end] - myPrefixSum[start]
        val sumSquares = myPrefixSumSquares[end] - myPrefixSumSquares[start]
        val value = sumSquares - (sum * sum) / count
        // guard the tiny negative that can arise from cancellation on near-constant ranges
        return if (value < 0.0) 0.0 else value
    }

    private fun buildTables() {
        val n = myNumObservations
        for (i in 1..n) {
            myCost[0][i] = sumOfSquares(0, i)
            myBack[0][i] = 0
        }
        for (g in 1 until myMaximumGroups) {
            for (i in (g + 1)..n) {
                var best = Double.MAX_VALUE
                var bestStart = -1
                for (t in g until i) {
                    val previous = myCost[g - 1][t]
                    if (previous == Double.MAX_VALUE) continue
                    val candidate = previous + sumOfSquares(t, i)
                    if (candidate < best) {
                        best = candidate
                        bestStart = t
                    }
                }
                myCost[g][i] = best
                myBack[g][i] = bestStart
            }
        }
    }

    /**
     *  The minimal total within-group sum of squares achievable with the supplied number of
     *  groups, ignoring the structural constraints of a certificate. Non-increasing in the
     *  number of groups, which is why it must not be used to select that number.
     *
     *  @param numGroups the number of groups, within 1..maximumGroups
     */
    fun totalWithinGroupSumOfSquares(numGroups: Int): Double {
        requireValidGroupCount(numGroups)
        return myCost[numGroups - 1][myNumObservations]
    }

    /**
     *  The unconstrained optimal partition into the supplied number of groups. Cuts may fall
     *  anywhere, including inside a run of equal values, so this is the classical Jenks result
     *  rather than a partition by value. Use `generate` for a partition that respects ties and
     *  the group requirements.
     *
     *  @param numGroups the number of groups, within 1..maximumGroups
     */
    fun classify(numGroups: Int): DataPartition {
        requireValidGroupCount(numGroups)
        val cuts = IntArray(numGroups - 1)
        var end = myNumObservations
        for (g in (numGroups - 1) downTo 1) {
            val start = myBack[g][end]
            check(start >= 0) { "The dynamic program has no solution for $numGroups groups" }
            cuts[g - 1] = start
            end = start
        }
        return DataPartition(myNumObservations, cuts)
    }

    /**
     *  The optimal partition into the supplied number of groups subject to the certificate:
     *  cuts fall only at value-block boundaries, and every group meets the size and
     *  distinct-value requirements. Returns null when no such partition exists.
     *
     *  This is a separate dynamic program rather than a repair of the unconstrained one,
     *  because moving an unconstrained cut to the nearest admissible position does not in
     *  general yield the constrained optimum.
     *
     *  @param sortedData the observations in non-decreasing order
     *  @param numGroups the requested number of groups, must be positive
     *  @param certificate the structural constraints
     */
    override fun generate(
        sortedData: DoubleArray,
        numGroups: Int,
        certificate: AdmissibilityCertificate
    ): DataPartition? {
        require(sortedData.size == myNumObservations) {
            "The data size (${sortedData.size}) must match the size used to build the tables " +
                    "($myNumObservations)"
        }
        require(certificate.numObservations == myNumObservations) {
            "The certificate is for ${certificate.numObservations} observations but the tables " +
                    "are for $myNumObservations"
        }
        require(numGroups > 0) { "The number of groups must be > 0" }
        if (!certificate.isFeasible(numGroups)) return null

        val admissible = certificate.admissibleCuts
        val n = myNumObservations
        // cost[g][i] over admissible endpoints only
        val cost = Array(numGroups) { DoubleArray(n + 1) { Double.MAX_VALUE } }
        val back = Array(numGroups) { IntArray(n + 1) { -1 } }
        for (i in admissible) {
            if (i > 0 && certificate.isValidGroup(0, i)) {
                cost[0][i] = sumOfSquares(0, i)
                back[0][i] = 0
            }
        }
        for (g in 1 until numGroups) {
            for (i in admissible) {
                if (i == 0) continue
                var best = Double.MAX_VALUE
                var bestStart = -1
                for (t in admissible) {
                    if (t >= i) break
                    if (cost[g - 1][t] == Double.MAX_VALUE) continue
                    if (!certificate.isValidGroup(t, i)) continue
                    val candidate = cost[g - 1][t] + sumOfSquares(t, i)
                    if (candidate < best) {
                        best = candidate
                        bestStart = t
                    }
                }
                cost[g][i] = best
                back[g][i] = bestStart
            }
        }
        if (back[numGroups - 1][n] < 0) return null
        val cuts = IntArray(numGroups - 1)
        var end = n
        for (g in (numGroups - 1) downTo 1) {
            val start = back[g][end]
            if (start < 0) return null
            cuts[g - 1] = start
            end = start
        }
        return DataPartition(n, cuts)
    }

    private fun requireValidGroupCount(numGroups: Int) {
        require(numGroups >= 1) { "The number of groups must be >= 1. It was $numGroups" }
        require(numGroups <= myMaximumGroups) {
            "The number of groups must be <= the maximum built ($myMaximumGroups). It was $numGroups"
        }
    }

    override fun toString(): String {
        return "JenksPartitionGenerator(n=$myNumObservations, maxGroups=$myMaximumGroups)"
    }
}
