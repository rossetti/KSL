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
import ksl.utilities.distributions.fitting.mixture.ComponentFitterIfc
import ksl.utilities.distributions.fitting.mixture.DataPartition

/**
 *  Why a refinement stopped.
 */
enum class RefinementOutcome {

    /** The partition was unchanged by an iteration; a fixed point was reached. */
    CONVERGED,

    /** No proposed partition improved the objective by more than the tolerance. */
    NO_IMPROVEMENT,

    /** The iteration limit was reached. This is a reportable outcome, not a success. */
    ITERATION_LIMIT,

    /** The objective could not be evaluated, so the previous partition was returned. */
    OBJECTIVE_UNAVAILABLE,

    /** No refinement was attempted. */
    NOT_ATTEMPTED
}

/**
 *  The result of refining a partition.
 *
 *  @param partition the partition to use, which is the incumbent when nothing improved on it
 *  @param outcome why the refinement stopped
 *  @param numIterations how many iterations were performed
 *  @param objectiveTrace the objective after each accepted iteration, beginning with the value
 *  of the initial partition. Retained because a refinement that claims to improve an objective
 *  monotonically should be able to show that it did.
 */
data class RefinementResult(
    val partition: DataPartition,
    val outcome: RefinementOutcome,
    val numIterations: Int,
    val objectiveTrace: List<Double>
) {
    /**
     *  Indicates whether the recorded objective never decreased between accepted iterations.
     *
     *  Monotonicity is a theorem only under hypotheses that the estimator catalog does not
     *  satisfy, so it is enforced by construction and then checked here rather than assumed.
     */
    val isMonotone: Boolean
        get() = objectiveTrace.zipWithNext().all { (a, b) -> b >= a || (a.isNaN() || b.isNaN()) }
}

/**
 *  Improves an initial partition with respect to a fitting objective.
 *
 *  Implementations range from doing nothing to running a full classification expectation
 *  maximization. They are interchangeable so that the contribution of refinement can be measured
 *  rather than assumed, which is the point of having more than one.
 */
interface PartitionRefinerIfc {

    /**
     *  A short name identifying the refiner, suitable as a factor level when results are recorded.
     */
    val name: String

    /**
     *  Refines the supplied partition.
     *
     *  Implementations must return a partition satisfying the certificate. When they cannot
     *  improve on the initial partition they must return it unchanged rather than something
     *  worse, so that a refiner can never make a result worse than not using it.
     *
     *  @param sortedData the observations in non-decreasing order
     *  @param initial the partition to improve
     *  @param certificate the structural constraints any partition must satisfy
     *  @param fitter the component fitter used to evaluate a partition
     */
    fun refine(
        sortedData: DoubleArray,
        initial: DataPartition,
        certificate: AdmissibilityCertificate,
        fitter: ComponentFitterIfc
    ): RefinementResult
}

/**
 *  Returns the initial partition unchanged.
 *
 *  This is the baseline tier: the partition produced by the generator is fitted as given. Every
 *  other refiner is measured against it, so it exists as a first-class implementation rather
 *  than as a null check at the call site.
 */
class NoRefinement : PartitionRefinerIfc {

    override val name: String = "None"

    override fun refine(
        sortedData: DoubleArray,
        initial: DataPartition,
        certificate: AdmissibilityCertificate,
        fitter: ComponentFitterIfc
    ): RefinementResult {
        return RefinementResult(initial, RefinementOutcome.NOT_ATTEMPTED, 0, emptyList())
    }

    override fun toString(): String = "NoRefinement"
}
