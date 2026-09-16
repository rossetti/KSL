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

/**
 *  Structural facts about a sorted sample that determine which partitions can exist, computed
 *  once in O(n) and reused by every partition generator, refiner, and search over that sample.
 *
 *  The purpose is prevention rather than handling. Several conditions that would otherwise have
 *  to be detected and recovered from inside the fitting loop are properties of the data and the
 *  configuration alone, so they can be decided up front and the offending configurations simply
 *  refused. In particular, if the number of components is restricted to be no greater than
 *  `maximumFeasibleGroups`, then no partition search can be handed an infeasible problem.
 *
 *  Three structural constraints are represented:
 *
 *  1. A cut may not split a run of equal values. A partition is defined by value intervals,
 *     so tied observations must stay together. The permissible cut positions are the block
 *     boundaries, available as `admissibleCuts`.
 *  2. Every group must contain at least `minimumGroupSize` observations.
 *  3. Every group must contain at least `minimumDistinctValues` distinct values. A group whose
 *     observations are all equal has zero sample variance and admits no finite normal or
 *     lognormal maximum likelihood estimate, so a value of 2 excludes that failure by
 *     construction rather than by catching it later.
 *
 *  @param sortedData the observations in non-decreasing order, must not be empty. The array is
 *  not retained; only structural summaries are kept.
 *  @param minimumGroupSize the least number of observations permitted in a group, must be positive
 *  @param minimumDistinctValues the least number of distinct values permitted in a group,
 *  must be positive
 */
class AdmissibilityCertificate(
    sortedData: DoubleArray,
    val minimumGroupSize: Int = defaultMinimumGroupSize,
    val minimumDistinctValues: Int = defaultMinimumDistinctValues
) {

    /**
     *  The number of observations summarized by this certificate.
     */
    val numObservations: Int = sortedData.size

    private val myAdmissibleCuts: IntArray
    private val myDistinctPrefix: IntArray

    init {
        require(sortedData.isNotEmpty()) { "The data must not be empty" }
        require(minimumGroupSize > 0) { "The minimum group size must be > 0" }
        require(minimumDistinctValues > 0) { "The minimum distinct values must be > 0" }
        for (i in 1 until sortedData.size) {
            require(sortedData[i - 1] <= sortedData[i]) {
                "The data must be sorted in non-decreasing order. Element ${i - 1} was " +
                        "${sortedData[i - 1]} and element $i was ${sortedData[i]}"
            }
        }
        for (v in sortedData) {
            require(v.isFinite()) { "The data must be finite. Found $v" }
        }
        val n = sortedData.size
        // distinctPrefix[i] counts distinct values among the first i observations
        val prefix = IntArray(n + 1)
        for (i in 1..n) {
            val isNewValue = (i == 1) || (sortedData[i - 1] > sortedData[i - 2])
            prefix[i] = prefix[i - 1] + if (isNewValue) 1 else 0
        }
        myDistinctPrefix = prefix
        // interior positions where the value changes, plus the two boundaries
        val cuts = mutableListOf(0)
        for (t in 1 until n) {
            if (sortedData[t - 1] < sortedData[t]) {
                cuts.add(t)
            }
        }
        cuts.add(n)
        myAdmissibleCuts = cuts.toIntArray()
    }

    /**
     *  The positions at which a cut may be placed without splitting a run of equal values,
     *  in increasing order. The first element is always 0 and the last is always the number of
     *  observations; these two are the outer boundaries rather than interior cuts.
     */
    val admissibleCuts: IntArray
        get() = myAdmissibleCuts.copyOf()

    /**
     *  The number of distinct values in the whole sample.
     */
    val numDistinctValues: Int
        get() = myDistinctPrefix[numObservations]

    /**
     *  The number of distinct values among the first `index` observations.
     *
     *  @param index the number of leading observations to count over, within 0..numObservations
     */
    fun distinctPrefix(index: Int): Int {
        require(index >= 0) { "The index must be >= 0. It was $index" }
        require(index <= numObservations) {
            "The index must be <= the number of observations ($numObservations). It was $index"
        }
        return myDistinctPrefix[index]
    }

    /**
     *  The number of distinct values within the index range from `start` until `end`. This is
     *  exact only when both endpoints are admissible cut positions, because otherwise a run of
     *  equal values straddles the boundary and is counted on one side only.
     *
     *  @param start the inclusive start index, within 0..numObservations
     *  @param end the exclusive end index, at least start and within 0..numObservations
     */
    fun distinctCount(start: Int, end: Int): Int {
        require(start <= end) { "The start ($start) must be <= the end ($end)" }
        return distinctPrefix(end) - distinctPrefix(start)
    }

    /**
     *  Indicates whether a cut at the supplied position would split a run of equal values.
     *  Positions 0 and the number of observations are treated as admissible boundaries.
     *
     *  @param position the candidate cut position
     */
    fun isAdmissibleCut(position: Int): Boolean {
        if (position < 0 || position > numObservations) return false
        return myAdmissibleCuts.binarySearch(position) >= 0
    }

    /**
     *  Indicates whether the index range from `start` until `end` satisfies the size and
     *  distinct-value requirements for a group.
     *
     *  @param start the inclusive start index
     *  @param end the exclusive end index
     */
    fun isValidGroup(start: Int, end: Int): Boolean {
        if (start < 0 || end > numObservations || start >= end) return false
        if ((end - start) < minimumGroupSize) return false
        return distinctCount(start, end) >= minimumDistinctValues
    }

    /**
     *  The largest number of groups for which some partition of this sample satisfies all three
     *  structural constraints.
     *
     *  Computed greedily: repeatedly take the shortest admissible prefix that satisfies both the
     *  size and distinct-value requirements. The greedy choice is optimal because taking the
     *  shortest valid first group leaves a suffix that contains the suffix left by any other
     *  valid choice, so no alternative can yield more groups. Because merging two adjacent valid
     *  groups yields a valid group, every group count from 1 up to this value is also feasible.
     */
    val maximumFeasibleGroups: Int by lazy { computeMaximumFeasibleGroups() }

    private fun computeMaximumFeasibleGroups(): Int {
        var groups = 0
        var start = 0
        while (start < numObservations) {
            var next = -1
            for (t in myAdmissibleCuts) {
                if (t > start && isValidGroup(start, t)) {
                    next = t
                    break
                }
            }
            if (next < 0) break
            groups++
            start = next
        }
        return groups
    }

    /**
     *  Indicates whether a partition of this sample into the supplied number of groups exists
     *  that satisfies all three structural constraints.
     *
     *  @param numGroups the candidate number of groups
     */
    fun isFeasible(numGroups: Int): Boolean {
        return (numGroups in 1..maximumFeasibleGroups)
    }

    /**
     *  Restricts a requested range of group counts to those that are structurally feasible for
     *  this sample. Searching only over the returned values guarantees that no partition search
     *  is ever handed an infeasible problem.
     *
     *  @param requested the group counts the caller would like to consider
     */
    fun feasibleGroupCounts(requested: Iterable<Int>): List<Int> {
        return requested.filter { isFeasible(it) }
    }

    /**
     *  Explains why the supplied number of groups is not feasible, or null when it is feasible.
     *  Intended for diagnostics recorded alongside experimental results, so that a case excluded
     *  by structure is distinguishable from one that failed for a numerical reason.
     *
     *  @param numGroups the candidate number of groups
     */
    fun infeasibilityReason(numGroups: Int): String? {
        if (isFeasible(numGroups)) return null
        if (numGroups < 1) return "The number of groups must be >= 1, it was $numGroups"
        return "Cannot form $numGroups groups from $numObservations observations having " +
                "$numDistinctValues distinct values with at least $minimumGroupSize observations " +
                "and $minimumDistinctValues distinct values per group. " +
                "The maximum feasible number of groups is $maximumFeasibleGroups."
    }

    override fun toString(): String {
        return "AdmissibilityCertificate(n=$numObservations, distinct=$numDistinctValues, " +
                "minGroupSize=$minimumGroupSize, minDistinct=$minimumDistinctValues, " +
                "maxFeasibleGroups=$maximumFeasibleGroups)"
    }

    companion object {

        /**
         *  The default least number of observations permitted in a group. Small groups produce
         *  unstable estimates regardless of which family is fitted.
         */
        var defaultMinimumGroupSize: Int = 5
            set(value) {
                require(value > 0) { "The default minimum group size must be > 0" }
                field = value
            }

        /**
         *  The default least number of distinct values permitted in a group. Two is the smallest
         *  value that guarantees a positive sample variance, and therefore the smallest value
         *  that admits a finite normal or lognormal maximum likelihood estimate.
         */
        var defaultMinimumDistinctValues: Int = 2
            set(value) {
                require(value > 0) { "The default minimum distinct values must be > 0" }
                field = value
            }

        /**
         *  Builds a certificate from data that is not known to be sorted. The data is copied and
         *  sorted; the caller's array is not modified.
         *
         *  @param data the observations, must not be empty
         *  @param minimumGroupSize the least number of observations permitted in a group
         *  @param minimumDistinctValues the least number of distinct values permitted in a group
         */
        fun fromUnsorted(
            data: DoubleArray,
            minimumGroupSize: Int = defaultMinimumGroupSize,
            minimumDistinctValues: Int = defaultMinimumDistinctValues
        ): AdmissibilityCertificate {
            return AdmissibilityCertificate(data.sortedArray(), minimumGroupSize, minimumDistinctValues)
        }
    }
}
