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
 *  An immutable contiguous partition of sorted data into k groups, represented by the
 *  interior cut positions rather than by copying the data.
 *
 *  A partition of n observations into k groups is determined by k-1 interior cuts
 *  0 &lt; t(1) &lt; t(2) &lt; ... &lt; t(k-1) &lt; n, with group j occupying the half-open index
 *  range (t(j-1), t(j)] in 1-based terms, or indices t(j-1) until t(j) in 0-based terms,
 *  where t(0) = 0 and t(k) = n.
 *
 *  No observation data is held or copied. Groups are described by index ranges into the
 *  caller's sorted array, which makes a partition cheap to create, compare, and use as a
 *  cache key. This matters because the refinement loop creates many partitions that differ
 *  in only one cut.
 *
 *  Instances are immutable. Derived partitions are produced by the functions that return a
 *  new instance rather than by mutation.
 *
 *  @param numObservations the number n of observations being partitioned, must be positive
 *  @param cuts the k-1 interior cut positions, strictly increasing and within (0, n)
 */
class DataPartition(
    val numObservations: Int,
    cuts: IntArray
) {

    private val myCuts: IntArray = cuts.copyOf()

    init {
        require(numObservations > 0) { "The number of observations must be > 0" }
        for (c in myCuts) {
            require(c > 0) { "A cut position must be > 0. It was $c" }
            require(c < numObservations) {
                "A cut position must be < the number of observations ($numObservations). It was $c"
            }
        }
        for (i in 1 until myCuts.size) {
            require(myCuts[i - 1] < myCuts[i]) {
                "Cut positions must be strictly increasing. Found ${myCuts[i - 1]} then ${myCuts[i]}"
            }
        }
    }

    /**
     *  The number of groups in the partition. This is one more than the number of interior cuts.
     */
    val numGroups: Int
        get() = myCuts.size + 1

    /**
     *  A defensive copy of the interior cut positions, in increasing order.
     */
    val cutPositions: IntArray
        get() = myCuts.copyOf()

    /**
     *  The inclusive starting index, into the sorted data, of the group with the supplied
     *  zero-based index.
     *
     *  @param group the zero-based group index, must be within 0 until numGroups
     */
    fun startIndex(group: Int): Int {
        requireValidGroup(group)
        return if (group == 0) 0 else myCuts[group - 1]
    }

    /**
     *  The exclusive ending index, into the sorted data, of the group with the supplied
     *  zero-based index.
     *
     *  @param group the zero-based group index, must be within 0 until numGroups
     */
    fun endIndex(group: Int): Int {
        requireValidGroup(group)
        return if (group == myCuts.size) numObservations else myCuts[group]
    }

    /**
     *  The number of observations assigned to the group with the supplied zero-based index.
     *
     *  @param group the zero-based group index, must be within 0 until numGroups
     */
    fun sizeOf(group: Int): Int {
        return endIndex(group) - startIndex(group)
    }

    /**
     *  The sizes of all groups, in group order. The elements sum to the number of observations.
     */
    val groupSizes: IntArray
        get() = IntArray(numGroups) { sizeOf(it) }

    /**
     *  The empirical group proportions, in group order. These are the natural initial mixing
     *  weights: proportion j is the fraction of the sample assigned to group j. The elements
     *  sum to 1.0.
     */
    val proportions: DoubleArray
        get() {
            val n = numObservations.toDouble()
            return DoubleArray(numGroups) { sizeOf(it) / n }
        }

    /**
     *  The smallest group size in the partition.
     */
    val minimumGroupSize: Int
        get() = (0 until numGroups).minOf { sizeOf(it) }

    /**
     *  Returns a copy of the observations assigned to the supplied group. This allocates,
     *  and is intended for handing a group to an estimator. Prefer the index accessors when
     *  only the range is needed.
     *
     *  @param data the sorted observations that this partition indexes, its size must equal
     *  the number of observations
     *  @param group the zero-based group index, must be within 0 until numGroups
     */
    fun groupData(data: DoubleArray, group: Int): DoubleArray {
        require(data.size == numObservations) {
            "The data size (${data.size}) must equal the number of observations ($numObservations)"
        }
        return data.copyOfRange(startIndex(group), endIndex(group))
    }

    /**
     *  Returns a new partition with the cut at the supplied index moved to a new position.
     *  The receiver is not modified. The resulting cut positions must remain strictly
     *  increasing and within the data, otherwise an exception is thrown.
     *
     *  @param cutIndex the zero-based index of the interior cut to move, must be within
     *  0 until (numGroups - 1)
     *  @param newPosition the new position for that cut
     */
    fun withCut(cutIndex: Int, newPosition: Int): DataPartition {
        require(cutIndex >= 0) { "The cut index must be >= 0. It was $cutIndex" }
        require(cutIndex < myCuts.size) {
            "The cut index must be < the number of interior cuts (${myCuts.size}). It was $cutIndex"
        }
        val newCuts = myCuts.copyOf()
        newCuts[cutIndex] = newPosition
        return DataPartition(numObservations, newCuts)
    }

    /**
     *  A stable key identifying the index range of the supplied group. Two partitions that
     *  place a group over the same range yield the same key, which is what permits a fit
     *  computed for one partition to be reused by another.
     *
     *  @param group the zero-based group index, must be within 0 until numGroups
     */
    fun rangeKey(group: Int): Long {
        requireValidGroup(group)
        return (startIndex(group).toLong() shl 32) or endIndex(group).toLong()
    }

    private fun requireValidGroup(group: Int) {
        require(group >= 0) { "The group index must be >= 0. It was $group" }
        require(group < numGroups) {
            "The group index must be < the number of groups ($numGroups). It was $group"
        }
    }

    override fun toString(): String {
        return buildString {
            append("DataPartition(n=$numObservations, k=$numGroups, cuts=[")
            append(myCuts.joinToString())
            append("], sizes=[")
            append(groupSizes.joinToString())
            append("])")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataPartition) return false
        if (numObservations != other.numObservations) return false
        return myCuts.contentEquals(other.myCuts)
    }

    override fun hashCode(): Int {
        return 31 * numObservations + myCuts.contentHashCode()
    }

    companion object {

        /**
         *  Creates the single-group partition of the supplied number of observations.
         *
         *  @param numObservations the number of observations, must be positive
         */
        fun single(numObservations: Int): DataPartition {
            return DataPartition(numObservations, IntArray(0))
        }

        /**
         *  Creates a partition whose groups are as close to equal in size as the number of
         *  observations permits. This is a convenient starting point and a baseline
         *  initialization, not a fitted partition.
         *
         *  @param numObservations the number of observations, must be at least numGroups
         *  @param numGroups the desired number of groups, must be positive
         */
        fun equalSized(numObservations: Int, numGroups: Int): DataPartition {
            require(numGroups > 0) { "The number of groups must be > 0" }
            require(numObservations >= numGroups) {
                "The number of observations ($numObservations) must be >= the number of groups ($numGroups)"
            }
            val cuts = IntArray(numGroups - 1) { i ->
                (((i + 1).toLong() * numObservations) / numGroups).toInt()
            }
            // Equal division can repeat a position when groups are small; nudge to keep the
            // cuts strictly increasing so the resulting partition is always well formed.
            for (i in 1 until cuts.size) {
                if (cuts[i] <= cuts[i - 1]) {
                    cuts[i] = cuts[i - 1] + 1
                }
            }
            return DataPartition(numObservations, cuts)
        }
    }
}
