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
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureBICCriterion
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureCriterionIfc
import ksl.utilities.distributions.fitting.mixture.search.FamilySelectorIfc
import ksl.utilities.distributions.fitting.mixture.search.SeparableCMLSelector

/**
 *  Improves a partition by moving one cut at a time to a nearby position, keeping any move that
 *  improves the criterion.
 *
 *  This is a coordinate descent over cut positions, generalizing the neighborhood the original
 *  prototype searched. The prototype shifted a fixed number of observations across one boundary
 *  at a time and evaluated the result once; this repeats until no single move helps, which is
 *  the least that can be called a search.
 *
 *  It is retained as a comparison tier rather than as a recommendation. The quantity it searches
 *  for has an exact solution: with the components fitted, the best contiguous assignment is
 *  computable directly, which is what the classification refiner does. The value of keeping this
 *  is that the difference between a local neighborhood search and an exact one can be measured
 *  rather than asserted.
 *
 *  Scoring a proposed partition means choosing families for its groups, and that choice is made
 *  separably. It has to be: a move is proposed for every admissible shift of every cut on every
 *  sweep, so the cost of scoring one partition is multiplied by a number that grows with the
 *  component count and the sample size. Enumerating the family combinations at each of those
 *  points raises the catalog size to the number of components inside the innermost loop, which is
 *  not a large constant but an intractable one — at eight components and a catalog of five it is
 *  nearly four hundred thousand mixtures per proposed move. Separable selection makes it linear in
 *  the catalog, which is what allows this tier to run at all.
 *
 *  @param maxShift the furthest a cut may move in a single step, in observations
 *  @param maxIterations the cap on complete sweeps over all cuts
 *  @param criterion the criterion a move must improve
 *  @param selector how families are chosen when a proposed partition is scored
 */
class BreakShiftRefiner(
    val maxShift: Int = defaultMaxShift,
    val maxIterations: Int = defaultMaxIterations,
    val criterion: MixtureCriterionIfc = MixtureBICCriterion(),
    val selector: FamilySelectorIfc = SeparableCMLSelector()
) : PartitionRefinerIfc {

    init {
        require(maxShift > 0) { "The maximum shift must be > 0" }
        require(maxIterations > 0) { "The maximum number of iterations must be > 0" }
    }

    override val name: String = "BreakShift"

    override fun refine(
        sortedData: DoubleArray,
        initial: DataPartition,
        certificate: AdmissibilityCertificate,
        fitter: ComponentFitterIfc
    ): RefinementResult {
        if (initial.numGroups == 1) {
            return RefinementResult(initial, RefinementOutcome.CONVERGED, 0, emptyList())
        }
        var current = initial
        var currentValue = evaluate(sortedData, current, fitter)
            ?: return RefinementResult(
                initial, RefinementOutcome.OBJECTIVE_UNAVAILABLE, 0, emptyList()
            )
        val trace = mutableListOf(currentValue)

        for (iteration in 1..maxIterations) {
            var improvedThisSweep = false
            for (cutIndex in 0 until current.numGroups - 1) {
                val origin = current.cutPositions[cutIndex]
                var bestPartition = current
                var bestValue = currentValue
                for (delta in -maxShift..maxShift) {
                    if (delta == 0) continue
                    val moved = origin + delta
                    if (moved <= 0 || moved >= current.numObservations) continue
                    if (!certificate.isAdmissibleCut(moved)) continue
                    val proposed = try {
                        current.withCut(cutIndex, moved)
                    } catch (e: IllegalArgumentException) {
                        continue      // the move would break the ordering of the cuts
                    }
                    if (!isValid(proposed, certificate)) continue
                    val value = evaluate(sortedData, proposed, fitter) ?: continue
                    if (isBetter(value, bestValue)) {
                        bestValue = value
                        bestPartition = proposed
                    }
                }
                if (bestPartition != current) {
                    current = bestPartition
                    currentValue = bestValue
                    trace.add(currentValue)
                    improvedThisSweep = true
                }
            }
            if (!improvedThisSweep) {
                return RefinementResult(current, RefinementOutcome.CONVERGED, iteration, trace)
            }
        }
        return RefinementResult(current, RefinementOutcome.ITERATION_LIMIT, maxIterations, trace)
    }

    private fun isBetter(candidate: Double, incumbent: Double): Boolean {
        return if (criterion.smallerIsBetter) candidate < incumbent else candidate > incumbent
    }

    private fun isValid(partition: DataPartition, certificate: AdmissibilityCertificate): Boolean {
        for (g in 0 until partition.numGroups) {
            if (!certificate.isValidGroup(partition.startIndex(g), partition.endIndex(g))) {
                return false
            }
        }
        return true
    }

    /**
     *  The criterion value of the mixture the selector assembles on the supplied partition, or null
     *  when no mixture can be assembled or none is comparable.
     *
     *  The value is returned in the criterion's own direction, so that a trace of these values
     *  reads the way the criterion does rather than the way the selector's internal comparison
     *  does.
     */
    private fun evaluate(
        sortedData: DoubleArray,
        partition: DataPartition,
        fitter: ComponentFitterIfc
    ): Double? {
        val fits = fitter.fitAll(sortedData, partition)
        if (fits.any { !it.hasCandidates }) return null
        val selection = selector.select(sortedData, partition, fits, criterion)
        if (selection.candidate == null) return null
        return if (criterion.smallerIsBetter) selection.criterionValue else -selection.criterionValue
    }

    override fun toString(): String {
        return "BreakShiftRefiner(maxShift=$maxShift, maxIterations=$maxIterations, " +
                "criterion=${criterion.name}, selector=${selector.name})"
    }

    companion object {

        /**
         *  The furthest a cut may move in a single step, in observations.
         */
        var defaultMaxShift: Int = 5
            set(value) {
                require(value > 0) { "The default maximum shift must be > 0" }
                field = value
            }

        /**
         *  The cap on complete sweeps over all cuts.
         */
        var defaultMaxIterations: Int = 20
            set(value) {
                require(value > 0) { "The default maximum iterations must be > 0" }
                field = value
            }
    }
}
