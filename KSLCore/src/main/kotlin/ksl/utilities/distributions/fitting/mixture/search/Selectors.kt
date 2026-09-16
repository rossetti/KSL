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

import ksl.utilities.distributions.fitting.mixture.ComponentCandidate
import ksl.utilities.distributions.fitting.mixture.DataPartition
import ksl.utilities.distributions.fitting.mixture.GroupFitResult
import ksl.utilities.distributions.fitting.mixture.MixtureCandidate
import ksl.utilities.distributions.fitting.mixture.MixtureLogLikelihood
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureCriterionIfc
import kotlin.math.ln

/**
 *  Chooses each group's family independently, by the largest penalized log-likelihood on that
 *  group alone.
 *
 *  With the assignment fixed, the classification log-likelihood is a sum of independent per-group
 *  terms, so the family that maximizes it for one group can be chosen without reference to any
 *  other group. Selection therefore costs the catalog size times the number of groups, and one
 *  mixture is assembled at the end. The exhaustive alternative costs the catalog size raised to
 *  the number of groups in assembled mixtures.
 *
 *  The penalty is each group's own Bayesian information criterion term. Without it the selection
 *  would favour whichever family has the most parameters, since more parameters can only raise a
 *  maximized likelihood; with it, separability is preserved because the penalty is itself a sum
 *  of per-group terms.
 *
 *  What this does *not* do is optimize the observed-data criterion. That criterion is not
 *  separable, so the family vector chosen here can differ from the one an exhaustive search would
 *  choose. How often, and by how much, is precisely the quantity the experiments measure.
 */
class SeparableCMLSelector : FamilySelectorIfc {

    override val name: String = "Separable"

    override fun select(
        sortedData: DoubleArray,
        partition: DataPartition,
        groupFits: List<GroupFitResult>,
        criterion: MixtureCriterionIfc
    ): FamilySelectionResult {
        require(groupFits.size == partition.numGroups) {
            "The number of group fits (${groupFits.size}) must equal the number of groups " +
                    "(${partition.numGroups})"
        }
        var considered = 0
        val chosen = mutableListOf<ComponentCandidate>()
        for (g in 0 until partition.numGroups) {
            val fit = groupFits[g]
            if (!fit.hasCandidates) {
                return FamilySelectionResult(null, Double.MAX_VALUE, 0, considered, false)
            }
            val start = partition.startIndex(g)
            val end = partition.endIndex(g)
            val groupSize = end - start
            var best: ComponentCandidate? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (candidate in fit.candidates) {
                considered++
                var logLikelihood = 0.0
                for (i in start until end) {
                    logLikelihood += MixtureLogLikelihood.logDensity(candidate.distribution, sortedData[i])
                    if (!logLikelihood.isFinite()) break
                }
                if (!logLikelihood.isFinite()) continue
                val penalized = logLikelihood - 0.5 * candidate.numParameters * ln(groupSize.toDouble())
                if (penalized > bestScore) {
                    bestScore = penalized
                    best = candidate
                }
            }
            if (best == null) {
                return FamilySelectionResult(null, Double.MAX_VALUE, 0, considered, false)
            }
            chosen.add(best)
        }
        // exactly one mixture is assembled and scored, whatever the catalog size
        val candidate = MixtureCandidate(partition, chosen)
        val value = criterion.evaluate(candidate, sortedData)
        if (!value.isComparable) {
            return FamilySelectionResult(null, Double.MAX_VALUE, 1, considered, false)
        }
        return FamilySelectionResult(
            candidate, orient(criterion, value.value), 1, considered, false
        )
    }

    override fun toString(): String = "SeparableCMLSelector"
}

/**
 *  Evaluates every combination of per-group families and keeps the best by the observed-data
 *  criterion.
 *
 *  This is the selection the criterion actually asks for, and it is retained as the reference
 *  against which the cheap alternative is measured rather than as a recommendation. The number of
 *  combinations is the product of the per-group candidate counts, so it grows as the catalog size
 *  raised to the number of groups and becomes impractical quickly.
 *
 *  Because it must remain runnable at the component counts the design reaches, the search is
 *  bounded. When the bound binds, the result says so: a capped search has not found the exhaustive
 *  optimum and must not be reported as though it had.
 *
 *  @param maxMixtures the most assembled mixtures to score before stopping
 */
class ExhaustiveProductSelector(
    val maxMixtures: Int = defaultMaxMixtures
) : FamilySelectorIfc {

    init {
        require(maxMixtures >= 1) { "The maximum number of mixtures must be >= 1" }
    }

    override val name: String = "Exhaustive"

    override fun select(
        sortedData: DoubleArray,
        partition: DataPartition,
        groupFits: List<GroupFitResult>,
        criterion: MixtureCriterionIfc
    ): FamilySelectionResult {
        require(groupFits.size == partition.numGroups) {
            "The number of group fits (${groupFits.size}) must equal the number of groups " +
                    "(${partition.numGroups})"
        }
        val considered = groupFits.sumOf { it.candidates.size }
        var best: MixtureCandidate? = null
        var bestValue = Double.MAX_VALUE
        var evaluated = 0
        var capped = false
        for (candidate in MixtureCandidate.allCombinations(partition, groupFits)) {
            if (evaluated >= maxMixtures) {
                capped = true
                break
            }
            evaluated++
            val value = criterion.evaluate(candidate, sortedData)
            if (!value.isComparable) continue
            val oriented = orient(criterion, value.value)
            if (oriented < bestValue) {
                bestValue = oriented
                best = candidate
            }
        }
        return FamilySelectionResult(best, bestValue, evaluated, considered, capped)
    }

    override fun toString(): String = "ExhaustiveProductSelector(maxMixtures=$maxMixtures)"

    companion object {

        /**
         *  The most assembled mixtures scored when no bound is given. Large enough that the
         *  smaller component counts in a design are searched exhaustively, small enough that the
         *  larger ones remain runnable.
         */
        var defaultMaxMixtures: Int = 20000
            set(value) {
                require(value >= 1) { "The default maximum mixtures must be >= 1" }
                field = value
            }
    }
}
