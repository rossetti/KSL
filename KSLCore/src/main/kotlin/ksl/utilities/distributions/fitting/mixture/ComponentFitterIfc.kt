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

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.random.rvariable.RVParametersTypeIfc

/**
 *  One family successfully fitted to one group, retained as a candidate component.
 *
 *  @param distribution the fitted distribution, ready to be placed in a mixture
 *  @param estimationResult the underlying KSL estimation result, kept so that the fitting
 *  provenance travels with the candidate
 *  @param rvType the family that was fitted
 *  @param numParameters the number of estimated parameters this component contributes to the
 *  effective parameter count of a mixture, including an estimated shift when one was applied
 *  @param name a readable label for the fitted distribution, including any shift
 */
data class ComponentCandidate(
    val distribution: ContinuousDistributionIfc,
    val estimationResult: EstimationResult,
    val rvType: RVParametersTypeIfc,
    val numParameters: Int,
    val name: String
)

/**
 *  One family that was attempted for a group and rejected, with the estimator's own explanation.
 *
 *  The estimator's diagnostic string is the most informative failure record available, so it is
 *  carried rather than replaced by a generic message. Failure rates by family and group size are
 *  a reportable experimental result, not log noise.
 *
 *  @param rvType the family that was attempted, or null when the estimator produced no parameters
 *  @param message the estimator's own explanation of the failure
 */
data class ComponentRejection(
    val rvType: RVParametersTypeIfc?,
    val message: String
)

/**
 *  The outcome of fitting every candidate family to one group: those that succeeded, and those
 *  that did not together with why.
 *
 *  @param startIndex the inclusive start index of the group within the sorted data
 *  @param endIndex the exclusive end index of the group within the sorted data
 *  @param candidates the families that fitted successfully
 *  @param rejections the families that did not, with the estimator's explanation
 */
data class GroupFitResult(
    val startIndex: Int,
    val endIndex: Int,
    val candidates: List<ComponentCandidate>,
    val rejections: List<ComponentRejection>
) {
    /**
     *  The number of observations in the group.
     */
    val groupSize: Int
        get() = endIndex - startIndex

    /**
     *  Indicates whether any family fitted this group.
     *
     *  This is the condition on which a whole candidate partition lives or dies. For a single
     *  distribution a failed family merely shrinks the candidate list and the survivors still
     *  produce a recommendation; a mixture needs a component for every group, so an empty
     *  candidate list here invalidates the entire partition rather than one component of it.
     */
    val hasCandidates: Boolean
        get() = candidates.isNotEmpty()
}

/**
 *  Fits the catalog of candidate families to a contiguous group of sorted observations.
 *
 *  The interface is stated over an index range into the caller's sorted data rather than over a
 *  distribution type, so that a discrete implementation over probability mass functions can be
 *  added later without disturbing the partition, refinement, or search layers above it.
 */
interface ComponentFitterIfc {

    /**
     *  Fits every candidate family to the observations in the index range from `startIndex`
     *  until `endIndex`, returning those that succeeded and those that did not.
     *
     *  Implementations must not throw when an individual family fails to fit; a failure is an
     *  expected outcome to be reported, not an exceptional one.
     *
     *  @param sortedData the observations in non-decreasing order
     *  @param startIndex the inclusive start index of the group
     *  @param endIndex the exclusive end index of the group
     */
    fun fitGroup(sortedData: DoubleArray, startIndex: Int, endIndex: Int): GroupFitResult

    /**
     *  Fits every group of the supplied partition, in group order.
     *
     *  @param sortedData the observations in non-decreasing order
     *  @param partition the partition whose groups should be fitted
     */
    fun fitAll(sortedData: DoubleArray, partition: DataPartition): List<GroupFitResult> {
        return (0 until partition.numGroups).map { g ->
            fitGroup(sortedData, partition.startIndex(g), partition.endIndex(g))
        }
    }
}
