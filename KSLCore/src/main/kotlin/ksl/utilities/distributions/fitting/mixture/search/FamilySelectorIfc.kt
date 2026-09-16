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
package ksl.utilities.distributions.fitting.mixture.search

import ksl.utilities.distributions.fitting.mixture.DataPartition
import ksl.utilities.distributions.fitting.mixture.GroupFitResult
import ksl.utilities.distributions.fitting.mixture.MixtureCandidate
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureCriterionIfc

/**
 *  What a selector produced, with the work it took to produce it.
 *
 *  The counts are not diagnostics. The claim a selector makes is about cost, so the cost has to be
 *  measured rather than asserted, and the two counts are measured separately because they scale
 *  differently: the number of fits is linear in the catalog size while the number of assembled
 *  mixtures is the catalog size raised to the number of components.
 *
 *  @param candidate the chosen mixture, or null when none could be assembled
 *  @param criterionValue the criterion value of the chosen mixture, oriented so that smaller is
 *  better whatever the criterion's own direction
 *  @param numMixturesEvaluated how many assembled mixtures were scored
 *  @param numCandidatesConsidered how many component candidates were examined across all groups
 *  @param wasCapped whether a bound on the search stopped it before it was exhausted
 */
data class FamilySelectionResult(
    val candidate: MixtureCandidate?,
    val criterionValue: Double,
    val numMixturesEvaluated: Int,
    val numCandidatesConsidered: Int,
    val wasCapped: Boolean
)

/**
 *  Chooses one component family per group, given the families that fitted each group.
 *
 *  This is the decision the tractability argument is about. The observed-data criterion is not
 *  separable across components, because the logarithm sits outside the mixture sum, so choosing
 *  families to optimize it requires evaluating combinations — the catalog size raised to the
 *  number of components. The classification objective *is* separable, so choosing families to
 *  optimize that requires only catalog size times number of components.
 *
 *  The two implementations here make that difference measurable rather than argued: run both on
 *  the same fits and compare what they choose, what it scores, and what it cost.
 */
interface FamilySelectorIfc {

    /**
     *  A short name identifying the selector, used as a factor level when results are recorded.
     */
    val name: String

    /**
     *  Chooses a mixture from the supplied group fits.
     *
     *  @param sortedData the observations in non-decreasing order
     *  @param partition the partition the groups belong to
     *  @param groupFits the fitted candidates per group, in group order
     *  @param criterion the criterion by which an assembled mixture is judged
     */
    fun select(
        sortedData: DoubleArray,
        partition: DataPartition,
        groupFits: List<GroupFitResult>,
        criterion: MixtureCriterionIfc
    ): FamilySelectionResult

    /**
     *  Orients a criterion value so that smaller is always better, so that selectors can be
     *  compared without each one re-deriving the direction.
     */
    fun orient(criterion: MixtureCriterionIfc, value: Double): Double {
        return if (criterion.smallerIsBetter) value else -value
    }
}
