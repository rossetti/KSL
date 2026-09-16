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

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.fitting.mixture.AdmissibilityCertificate
import ksl.utilities.distributions.fitting.mixture.ComponentFitterIfc
import ksl.utilities.distributions.fitting.mixture.DataPartition
import ksl.utilities.distributions.fitting.mixture.MixtureLogLikelihood
import ksl.utilities.io.KSL
import kotlin.math.abs
import kotlin.math.ln

/**
 *  Improves a partition by alternating exact reassignment with refitting, in the manner of
 *  classification expectation maximization.
 *
 *  One iteration refits each group's parameters, recomputes the mixing weights from the group
 *  sizes, and then reassigns every observation by maximizing the classification log-likelihood
 *  over contiguous partitions. The reassignment is exact rather than heuristic: the segmentation
 *  it uses solves the constrained problem in time proportional to the number of observations
 *  times the number of groups.
 *
 *  Two design decisions distinguish this from a naive alternation, and both were arrived at the
 *  hard way.
 *
 *  The component families are fixed for the duration of the run. Choosing a family inside the
 *  loop, by any criterion that penalizes parameter count, can lower the very objective the loop
 *  is supposed to raise: a one-parameter family may win on a penalized score while fitting the
 *  group worse. Family search belongs outside, where each proposal starts a fresh run.
 *
 *  Reassignment is over contiguous partitions, not over all labelings. Assigning each observation
 *  to whichever component gives it the largest weighted density can produce a labeling no set of
 *  cuts can express, at which point the procedure has left the space it claims to search. Two
 *  equal-variance normals of different means do not exhibit this; a narrow and a broad normal
 *  sharing a mean do, since the broad one wins in both tails.
 *
 *  Monotonicity is a theorem only when every refit returns an exact maximum likelihood estimate
 *  and reassignment is exact. The second holds here; the first does not hold for the estimator
 *  catalog, several members of which are unbiased or moment-based rather than likelihood
 *  maximizing. Rather than assume a property the library does not provide, the iteration accepts
 *  a step only when the objective strictly improves, so monotonicity holds by construction and
 *  termination follows from the iteration cap regardless.
 *
 *  @param tolerance the least improvement in the objective that will be accepted, relative to
 *  the current magnitude
 *  @param maxIterations the iteration cap; reaching it is reported rather than treated as success
 */
class ClassificationEMRefiner(
    val tolerance: Double = defaultTolerance,
    val maxIterations: Int = defaultMaxIterations
) : PartitionRefinerIfc {

    init {
        require(tolerance > 0.0) { "The tolerance must be > 0.0" }
        require(maxIterations > 0) { "The maximum number of iterations must be > 0" }
    }

    override val name: String = "CEM"

    /**
     *  The number of times the guard rejected a proposed step across the most recent run.
     *
     *  This is not a debugging statistic. It measures how far the estimator catalog departs from
     *  the exact maximum likelihood estimates the monotonicity theorem assumes, so it belongs in
     *  the experimental record.
     */
    var guardRejections: Int = 0
        private set

    override fun refine(
        sortedData: DoubleArray,
        initial: DataPartition,
        certificate: AdmissibilityCertificate,
        fitter: ComponentFitterIfc
    ): RefinementResult {
        guardRejections = 0
        val k = initial.numGroups
        if (k == 1) {
            return RefinementResult(initial, RefinementOutcome.CONVERGED, 0, emptyList())
        }
        val segmentation = SegmentationDP(certificate)
        var current = initial
        val trace = mutableListOf<Double>()

        // The families chosen for the initial partition are held for the whole run.
        val families = selectFamilies(sortedData, current, fitter)
            ?: return RefinementResult(
                initial, RefinementOutcome.OBJECTIVE_UNAVAILABLE, 0, emptyList()
            )

        var currentObjective = objectiveOf(sortedData, current, families, fitter)
            ?: return RefinementResult(
                initial, RefinementOutcome.OBJECTIVE_UNAVAILABLE, 0, emptyList()
            )
        trace.add(currentObjective)

        for (iteration in 1..maxIterations) {
            val scores = scoreMatrix(sortedData, current, families, fitter)
                ?: return RefinementResult(
                    current, RefinementOutcome.OBJECTIVE_UNAVAILABLE, iteration - 1, trace
                )
            // The incumbent is supplied so that an equally good partition is never swapped for
            // another, which is what stops the iteration cycling between ties.
            val result = segmentation.maximize(scores, k, incumbent = current)
            val proposed = result.partition
            if (proposed == null || !result.isFinite) {
                return RefinementResult(
                    current, RefinementOutcome.OBJECTIVE_UNAVAILABLE, iteration - 1, trace
                )
            }
            if (proposed == current) {
                return RefinementResult(current, RefinementOutcome.CONVERGED, iteration, trace)
            }
            val proposedObjective = objectiveOf(sortedData, proposed, families, fitter)
            if (proposedObjective == null) {
                guardRejections++
                return RefinementResult(
                    current, RefinementOutcome.OBJECTIVE_UNAVAILABLE, iteration, trace
                )
            }
            // The guard. Accept only a strict improvement, measured against the current
            // magnitude so that the test means the same thing at every scale.
            val threshold = tolerance * maxOf(1.0, abs(currentObjective))
            if (proposedObjective <= currentObjective + threshold) {
                guardRejections++
                KSL.logger.warn {
                    "$name: step rejected at iteration $iteration; objective would move from " +
                            "$currentObjective to $proposedObjective"
                }
                return RefinementResult(current, RefinementOutcome.NO_IMPROVEMENT, iteration, trace)
            }
            current = proposed
            currentObjective = proposedObjective
            trace.add(currentObjective)
        }
        KSL.logger.warn { "$name: the iteration cap of $maxIterations was reached" }
        return RefinementResult(current, RefinementOutcome.ITERATION_LIMIT, maxIterations, trace)
    }

    /**
     *  Chooses one family per group, by the largest penalized log-likelihood on that group.
     *
     *  The penalty is the group's own Bayesian information criterion term. Selection is
     *  separable across groups because, with the assignment fixed, the classification
     *  log-likelihood is a sum of independent per-group terms. That is what removes the need to
     *  enumerate combinations of families across groups.
     */
    private fun selectFamilies(
        sortedData: DoubleArray,
        partition: DataPartition,
        fitter: ComponentFitterIfc
    ): List<ContinuousDistributionIfc>? {
        val chosen = mutableListOf<ContinuousDistributionIfc>()
        for (g in 0 until partition.numGroups) {
            val fit = fitter.fitGroup(sortedData, partition.startIndex(g), partition.endIndex(g))
            if (!fit.hasCandidates) return null
            val groupSize = partition.sizeOf(g)
            val best = fit.candidates.maxByOrNull { candidate ->
                var sum = 0.0
                for (i in partition.startIndex(g) until partition.endIndex(g)) {
                    sum += MixtureLogLikelihood.logDensity(candidate.distribution, sortedData[i])
                }
                if (!sum.isFinite()) Double.NEGATIVE_INFINITY
                else sum - 0.5 * candidate.numParameters * ln(groupSize.toDouble())
            } ?: return null
            chosen.add(best.distribution)
        }
        return chosen
    }

    /**
     *  Refits the fixed families to the supplied partition and returns the classification
     *  log-likelihood, or null when some group admits no fit of its assigned family.
     */
    private fun objectiveOf(
        sortedData: DoubleArray,
        partition: DataPartition,
        families: List<ContinuousDistributionIfc>,
        fitter: ComponentFitterIfc
    ): Double? {
        val refitted = refit(sortedData, partition, families, fitter) ?: return null
        var total = 0.0
        val weights = partition.proportions
        for (g in 0 until partition.numGroups) {
            val logWeight = ln(weights[g])
            for (i in partition.startIndex(g) until partition.endIndex(g)) {
                val value = logWeight + MixtureLogLikelihood.logDensity(refitted[g], sortedData[i])
                if (!value.isFinite()) return null
                total += value
            }
        }
        return total
    }

    /**
     *  The per-observation scores the segmentation maximizes: the log of the mixing weight plus
     *  the log density of the observation under each group's fitted component.
     */
    private fun scoreMatrix(
        sortedData: DoubleArray,
        partition: DataPartition,
        families: List<ContinuousDistributionIfc>,
        fitter: ComponentFitterIfc
    ): Array<DoubleArray>? {
        val refitted = refit(sortedData, partition, families, fitter) ?: return null
        val weights = partition.proportions
        return Array(partition.numGroups) { g ->
            val logWeight = ln(weights[g])
            DoubleArray(sortedData.size) { i ->
                val value = logWeight + MixtureLogLikelihood.logDensity(refitted[g], sortedData[i])
                // A positive infinity would break the prefix-sum representation the segmentation
                // relies on. It signals a degenerate fit, so it is treated as unusable here
                // rather than passed on to be rejected as a precondition violation.
                if (value == Double.POSITIVE_INFINITY) Double.NEGATIVE_INFINITY else value
            }
        }
    }

    /**
     *  Refits each group with the family it was assigned, returning null when any group's family
     *  no longer admits a fit. Changing the family here would change the model mid-run and void
     *  the guarantee the run is operating under, so the run ends instead.
     */
    private fun refit(
        sortedData: DoubleArray,
        partition: DataPartition,
        families: List<ContinuousDistributionIfc>,
        fitter: ComponentFitterIfc
    ): List<ContinuousDistributionIfc>? {
        val out = mutableListOf<ContinuousDistributionIfc>()
        for (g in 0 until partition.numGroups) {
            val fit = fitter.fitGroup(sortedData, partition.startIndex(g), partition.endIndex(g))
            val wanted = families[g]::class
            val match = fit.candidates.firstOrNull { it.distribution::class == wanted }
                ?: return null
            out.add(match.distribution)
        }
        return out
    }

    override fun toString(): String {
        return "ClassificationEMRefiner(tolerance=$tolerance, maxIterations=$maxIterations)"
    }

    companion object {

        /**
         *  The least relative improvement in the objective that will be accepted.
         */
        var defaultTolerance: Double = 1.0e-8
            set(value) {
                require(value > 0.0) { "The default tolerance must be > 0.0" }
                field = value
            }

        /**
         *  The iteration cap. Termination is guaranteed by this rather than by the tolerance,
         *  which is why it exists at all.
         */
        var defaultMaxIterations: Int = 100
            set(value) {
                require(value > 0) { "The default maximum iterations must be > 0" }
                field = value
            }
    }
}
