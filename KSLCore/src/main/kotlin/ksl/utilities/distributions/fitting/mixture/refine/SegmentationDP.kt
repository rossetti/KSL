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
package ksl.utilities.distributions.fitting.mixture.refine

import ksl.utilities.distributions.fitting.mixture.AdmissibilityCertificate
import ksl.utilities.distributions.fitting.mixture.DataPartition

/**
 *  Whether a segmentation problem had a solution, and of what kind.
 */
internal enum class SegmentationStatus {

    /** No partition satisfies the structural constraints. */
    INFEASIBLE,

    /** At least one partition satisfies the constraints; the optimum may be finite or not. */
    FEASIBLE
}

/**
 *  The outcome of an exact segmentation.
 *
 *  Reachability and value are reported separately and deliberately. A feasible set can be
 *  non-empty while every feasible partition scores negative infinity, and that case must yield a
 *  partition rather than be mistaken for infeasibility. Collapsing the two produces a
 *  segmentation that silently returns nothing for a perfectly well posed problem.
 *
 *  @param status whether any partition satisfies the constraints
 *  @param value the maximized objective, which may be negative infinity when feasible
 *  @param partition an achieving partition, null only when infeasible
 */
internal data class SegmentationResult(
    val status: SegmentationStatus,
    val value: Double,
    val partition: DataPartition?
) {
    /** Indicates whether a partition was found, whatever its value. */
    val isFeasible: Boolean
        get() = status == SegmentationStatus.FEASIBLE

    /** Indicates whether the objective is a finite number. */
    val isFinite: Boolean
        get() = value.isFinite()
}

/**
 *  Maximizes an additive per-observation objective over contiguous partitions of sorted data,
 *  exactly, in time proportional to the number of observations times the number of groups.
 *
 *  The objective is supplied as a score for each observation under each group position. Because
 *  that score depends only on the observation and the group index, the contribution of a group is
 *  a difference of prefix sums, and the optimal segmentation follows from a dynamic program.
 *
 *  Four structural constraints are enforced inside the recursion rather than by rejecting a
 *  result afterwards, because post-hoc rejection can discard the optimum:
 *
 *  1. groups are contiguous;
 *  2. a cut may not split a run of equal values;
 *  3. every group holds at least the minimum number of observations;
 *  4. every group holds at least the minimum number of distinct values.
 *
 *  Scores may be negative infinity, which propagates: a group containing such an observation
 *  scores negative infinity. They may not be positive infinity or not-a-number, and are rejected
 *  as a precondition. A prefix-sum representation cannot express the subtraction of two positive
 *  infinities, and an unguarded recursion would return not-a-number together with a
 *  plausible-looking partition, which is worse than either failure alone.
 *
 *  @param certificate the structural facts of the sample, supplying admissible cuts, distinct
 *  value counts, and the group requirements
 */
internal class SegmentationDP(
    private val certificate: AdmissibilityCertificate
) {

    private val myNumObservations: Int = certificate.numObservations
    private val myAdmissibleCuts: IntArray = certificate.admissibleCuts
    private val myMinGroupSize: Int = certificate.minimumGroupSize
    private val myMinDistinct: Int = certificate.minimumDistinctValues

    /**
     *  Maximizes the objective over partitions into the supplied number of groups.
     *
     *  @param scores scores(g)(i) is the contribution of observation i when it is assigned to
     *  group g. The outer size must equal the number of groups and each inner size the number of
     *  observations.
     *  @param numGroups the number of groups
     *  @param incumbent a partition to retain when it also attains the optimum. Supplying the
     *  current partition makes an iteration change only on a strict improvement, which is what
     *  prevents a search from cycling between equally good partitions. It is validated against
     *  every constraint before being scored; an incumbent that violates one is ignored rather
     *  than returned.
     */
    private var myNumTransitionsExamined: Long = 0

    /**
     *  How many candidate predecessor positions the last call to `maximize` examined.
     *
     *  **The work the linear-time claim is about.** The optimal cut for a state is chosen from the
     *  positions that could precede it, and the whole reason this procedure is proportional to the
     *  number of observations rather than to its square is that each candidate is admitted once per
     *  group and evicted at most once, by a sliding window that never scans backwards. Replace that
     *  window with a scan over all predecessors and every answer stays correct while the cost
     *  becomes quadratic — a regression no correctness test can see.
     *
     *  Counted rather than timed. A wall clock measures the machine as much as the algorithm, and
     *  a load-sensitive assertion fails on a busy build for reasons that have nothing to do with
     *  the code.
     *
     *  Zero before the first call.
     */
    val numTransitionsExamined: Long
        get() = myNumTransitionsExamined

    fun maximize(
        scores: Array<DoubleArray>,
        numGroups: Int,
        incumbent: DataPartition? = null
    ): SegmentationResult {
        require(numGroups >= 1) { "The number of groups must be >= 1. It was $numGroups" }
        require(scores.size == numGroups) {
            "The number of score rows (${scores.size}) must equal the number of groups ($numGroups)"
        }
        for ((g, row) in scores.withIndex()) {
            require(row.size == myNumObservations) {
                "Score row $g has ${row.size} entries but there are $myNumObservations observations"
            }
            for (v in row) {
                require(!v.isNaN()) { "A score was not-a-number in row $g" }
                require(v != Double.POSITIVE_INFINITY) {
                    "A score was positive infinity in row $g. An unbounded component density " +
                            "signals a degenerate fit and must be handled before segmenting."
                }
            }
        }
        myNumTransitionsExamined = 0
        val n = myNumObservations
        val m = myMinGroupSize
        val mu = myMinDistinct

        // Finite-part prefix sums, counts of non-finite scores, and the index of the last
        // negative infinity at or before each position. The last is maintained during the same
        // scan; searching backwards for it would make the whole procedure quadratic while
        // still passing every correctness test.
        val prefix = Array(numGroups) { DoubleArray(n + 1) }
        val zeroCount = Array(numGroups) { IntArray(n + 1) }
        val lastNegative = Array(numGroups) { IntArray(n + 1) }
        for (g in 0 until numGroups) {
            var last = 0
            for (i in 1..n) {
                val v = scores[g][i - 1]
                val finite = v != Double.NEGATIVE_INFINITY
                prefix[g][i] = prefix[g][i - 1] + (if (finite) v else 0.0)
                zeroCount[g][i] = zeroCount[g][i - 1] + (if (finite) 0 else 1)
                if (!finite) last = i
                lastNegative[g][i] = last
            }
        }

        val cuts = myAdmissibleCuts

        // Reachability. Decides whether any partition satisfies the constraints, independently
        // of what it scores, and supplies a partition when every feasible one scores negative
        // infinity. This also replaces a separate feasibility pre-check: enumerating subsets of
        // admissible cuts to answer the same question is exponential in the number of groups.
        val reachable = Array(numGroups + 1) { BooleanArray(n + 1) }
        val reachBack = Array(numGroups + 1) { IntArray(n + 1) { -1 } }
        for (i in cuts) {
            if (i > 0 && certificate.isValidGroup(0, i)) {
                reachable[1][i] = true
                reachBack[1][i] = 0
            }
        }
        for (g in 2..numGroups) {
            var firstReachable = -1
            var pointer = 0
            for (i in cuts) {
                while (pointer < cuts.size && cuts[pointer] <= i - m) {
                    if (reachable[g - 1][cuts[pointer]] && firstReachable < 0) {
                        firstReachable = cuts[pointer]
                    }
                    pointer++
                }
                // No fallback scan is needed. The distinct-value prefix is non-decreasing, so if
                // the smallest reachable admitted cut leaves too few distinct values in the final
                // group, every larger one leaves fewer still. A search could not succeed, and
                // performing it would make this pass quadratic whenever the distinct requirement
                // grows with the sample.
                if (firstReachable >= 0 &&
                    certificate.distinctCount(firstReachable, i) >= mu
                ) {
                    reachable[g][i] = true
                    reachBack[g][i] = firstReachable
                }
            }
        }
        if (!reachable[numGroups][n]) {
            return SegmentationResult(SegmentationStatus.INFEASIBLE, Double.NaN, null)
        }

        // Value. Restricted to states whose groups all score finitely; the reachability pass
        // above covers the rest.
        val value = Array(numGroups + 1) { DoubleArray(n + 1) { Double.NEGATIVE_INFINITY } }
        val back = Array(numGroups + 1) { IntArray(n + 1) { -1 } }
        val finite = Array(numGroups + 1) { BooleanArray(n + 1) }
        for (i in cuts) {
            if (i > 0 && certificate.isValidGroup(0, i) && zeroCount[0][i] == 0) {
                value[1][i] = prefix[0][i]
                back[1][i] = 0
                finite[1][i] = true
            }
        }
        for (g in 2..numGroups) {
            // Sliding-window maximum. Both window bounds move monotonically in i, so a running
            // maximum is insufficient: it cannot retract when the lower bound advances past the
            // position that produced it. A monotonic deque handles both.
            val deque = ArrayDeque<Int>()
            val dequeValue = ArrayDeque<Double>()
            var pointer = 0
            for (i in cuts) {
                if (i < g * m || certificate.distinctPrefix(i) < g * mu) continue
                // Admit candidates whose upper bounds now permit them. Both the size bound and
                // the distinct-value bound are non-decreasing in i, so a candidate admitted once
                // is never revisited.
                while (pointer < cuts.size) {
                    myNumTransitionsExamined++
                    val t = cuts[pointer]
                    if (t > i - m) break
                    if (certificate.distinctPrefix(t) > certificate.distinctPrefix(i) - mu) break
                    if (finite[g - 1][t]) {
                        val v = value[g - 1][t] - prefix[g - 1][t]
                        // Strict comparison. On a tie the earlier position must survive so that
                        // the front of the deque is the smallest maximizing cut, which is the
                        // deterministic choice the refinement loop relies on.
                        while (dequeValue.isNotEmpty() && dequeValue.last() < v) {
                            deque.removeLast()
                            dequeValue.removeLast()
                        }
                        deque.addLast(t)
                        dequeValue.addLast(v)
                    }
                    pointer++
                }
                val lowerBound = maxOf((g - 1) * m, lastNegative[g - 1][i])
                while (deque.isNotEmpty() &&
                    (deque.first() < lowerBound ||
                            certificate.distinctPrefix(deque.first()) < (g - 1) * mu)
                ) {
                    myNumTransitionsExamined++
                    deque.removeFirst()
                    dequeValue.removeFirst()
                }
                if (deque.isNotEmpty()) {
                    value[g][i] = prefix[g - 1][i] + dequeValue.first()
                    back[g][i] = deque.first()
                    finite[g][i] = true
                }
            }
        }

        if (finite[numGroups][n]) {
            val optimum = value[numGroups][n]
            var chosen = reconstruct(back, numGroups, n)
            if (incumbent != null && incumbent != chosen) {
                val incumbentValue = evaluateIncumbent(incumbent, prefix, zeroCount, numGroups)
                if (incumbentValue is IncumbentValue.Finite &&
                    kotlin.math.abs(incumbentValue.value - optimum) < TIE_TOLERANCE
                ) {
                    chosen = incumbent
                }
            }
            return SegmentationResult(SegmentationStatus.FEASIBLE, optimum, chosen)
        }

        // Feasible, but every feasible partition scores negative infinity. All of them are
        // optimal, so an incumbent that is merely feasible must still be retained.
        if (incumbent != null &&
            evaluateIncumbent(incumbent, prefix, zeroCount, numGroups) !is IncumbentValue.Infeasible
        ) {
            return SegmentationResult(
                SegmentationStatus.FEASIBLE, Double.NEGATIVE_INFINITY, incumbent
            )
        }
        val partition = reconstruct(reachBack, numGroups, n)
        return SegmentationResult(
            SegmentationStatus.FEASIBLE, Double.NEGATIVE_INFINITY, partition
        )
    }

    private fun reconstruct(back: Array<IntArray>, numGroups: Int, n: Int): DataPartition {
        val cuts = IntArray(numGroups - 1)
        var end = n
        for (g in numGroups downTo 2) {
            val start = back[g][end]
            check(start >= 0) { "The dynamic program has no predecessor for group $g at $end" }
            cuts[g - 2] = start
            end = start
        }
        return DataPartition(n, cuts)
    }

    /**
     *  What an incumbent partition is worth: infeasible, feasible with a negative infinite
     *  score, or feasible with a finite score.
     *
     *  These are three outcomes and not two. Reporting a feasible partition that happens to
     *  score negative infinity as though it were infeasible is the same conflation the
     *  segmentation itself avoids, and it would discard an incumbent that must be retained,
     *  because when every feasible partition scores negative infinity all of them are optimal.
     */
    private sealed interface IncumbentValue {
        object Infeasible : IncumbentValue
        object NegativeInfinite : IncumbentValue
        data class Finite(val value: Double) : IncumbentValue
    }

    /**
     *  Evaluates a candidate partition against every structural constraint and then scores it.
     *
     *  An incumbent is supplied by a caller, so it may have any shape at all: the wrong number
     *  of groups, cuts out of order or out of range, or a cut splitting a run of equal values.
     *  Returning such a partition would place something outside the feasible set into a result
     *  that promises to be inside it, so validity is established before the score is formed.
     */
    private fun evaluateIncumbent(
        partition: DataPartition,
        prefix: Array<DoubleArray>,
        zeroCount: Array<IntArray>,
        numGroups: Int
    ): IncumbentValue {
        if (partition.numObservations != myNumObservations) return IncumbentValue.Infeasible
        if (partition.numGroups != numGroups) return IncumbentValue.Infeasible
        for (c in partition.cutPositions) {
            if (!certificate.isAdmissibleCut(c)) return IncumbentValue.Infeasible
        }
        for (g in 0 until numGroups) {
            if (!certificate.isValidGroup(partition.startIndex(g), partition.endIndex(g))) {
                return IncumbentValue.Infeasible
            }
        }
        var total = 0.0
        for (g in 0 until numGroups) {
            val start = partition.startIndex(g)
            val end = partition.endIndex(g)
            if (zeroCount[g][end] - zeroCount[g][start] > 0) return IncumbentValue.NegativeInfinite
            total += prefix[g][end] - prefix[g][start]
        }
        return IncumbentValue.Finite(total)
    }

    companion object {

        /**
         *  How close two objective values must be to count as tied, for the purpose of retaining
         *  an incumbent partition.
         */
        var TIE_TOLERANCE: Double = 1.0e-12
            set(value) {
                require(value >= 0.0) { "The tie tolerance must be >= 0.0" }
                field = value
            }
    }
}
