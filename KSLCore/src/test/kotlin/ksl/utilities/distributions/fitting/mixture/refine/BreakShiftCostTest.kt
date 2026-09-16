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

import ksl.utilities.distributions.fitting.estimators.ExponentialMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.GammaMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.LognormalMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.NormalMLEParameterEstimator
import ksl.utilities.distributions.fitting.estimators.ParameterEstimatorIfc
import ksl.utilities.distributions.fitting.estimators.WeibullMLEParameterEstimator
import ksl.utilities.distributions.fitting.mixture.AdmissibilityCertificate
import ksl.utilities.distributions.fitting.mixture.ComponentFitCache
import ksl.utilities.distributions.fitting.mixture.DataPartition
import ksl.utilities.distributions.fitting.mixture.GroupFitResult
import ksl.utilities.distributions.fitting.mixture.PDFComponentFitter
import ksl.utilities.distributions.fitting.mixture.partition.JenksPartitionGenerator
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureBICCriterion
import ksl.utilities.distributions.fitting.mixture.scoring.MixtureCriterionIfc
import ksl.utilities.distributions.fitting.mixture.search.FamilySelectionResult
import ksl.utilities.distributions.fitting.mixture.search.FamilySelectorIfc
import ksl.utilities.distributions.fitting.mixture.search.SeparableCMLSelector
import ksl.utilities.random.rvariable.NormalRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  The cut-shifting refiner scores a proposed partition at every admissible shift of every cut on
 *  every sweep, so whatever it does to score one partition is multiplied by a large number. It once
 *  enumerated the family combinations there, which raised the catalog size to the number of
 *  components inside that loop and made the tier unrunnable past a handful of components — not
 *  slowly, but to the point of never finishing.
 *
 *  Wall-clock assertions would catch that only on a machine slow enough and would be flaky
 *  everywhere else. These check the structure instead: count what the refiner assembles, and
 *  require that it stay linear in the catalog rather than exponential in the component count.
 */
class BreakShiftCostTest {

    private val estimators: Set<ParameterEstimatorIfc> = setOf(
        NormalMLEParameterEstimator,
        LognormalMLEParameterEstimator,
        ExponentialMLEParameterEstimator,
        GammaMLEParameterEstimator(),
        WeibullMLEParameterEstimator()
    )

    /** Wraps a selector and counts what it was asked to do. */
    private class CountingSelector(
        private val delegate: FamilySelectorIfc = SeparableCMLSelector()
    ) : FamilySelectorIfc {

        var numSelections: Int = 0
            private set

        var numMixturesAssembled: Int = 0
            private set

        override val name: String get() = delegate.name

        override fun select(
            sortedData: DoubleArray,
            partition: DataPartition,
            groupFits: List<GroupFitResult>,
            criterion: MixtureCriterionIfc
        ): FamilySelectionResult {
            numSelections++
            val result = delegate.select(sortedData, partition, groupFits, criterion)
            numMixturesAssembled += result.numMixturesEvaluated
            return result
        }
    }

    private fun sample(n: Int, numComponents: Int): DoubleArray {
        val streams = (0 until numComponents).map { NormalRV(10.0 + 12.0 * it, 1.0, streamNum = 61 + it) }
        return DoubleArray(n) { streams[it % numComponents].value }.sortedArray()
    }

    @Test
    fun `scoring a partition assembles one mixture however large the catalog`() {
        val sorted = sample(400, 6)
        val certificate = AdmissibilityCertificate(sorted)
        val initial = JenksPartitionGenerator(sorted, 6).generate(sorted, 6, certificate)!!
        val counter = CountingSelector()
        val refiner = BreakShiftRefiner(criterion = MixtureBICCriterion(), selector = counter)
        val result = refiner.refine(sorted, initial, certificate, ComponentFitCache(PDFComponentFitter(estimators)))

        assertTrue(counter.numSelections > 0, "the refiner must have scored something")
        // one assembled mixture per scored partition; the catalog enters linearly through the
        // per-group choice, never as a product across groups
        assertEquals(
            counter.numSelections, counter.numMixturesAssembled,
            "scoring a partition must assemble exactly one mixture, not one per family combination"
        )
        assertEquals(6, result.partition.numGroups)
    }

    @Test
    fun `the work stays bounded by the neighborhood rather than the catalog`() {
        val sorted = sample(400, 6)
        val certificate = AdmissibilityCertificate(sorted)
        val initial = JenksPartitionGenerator(sorted, 6).generate(sorted, 6, certificate)!!
        val counter = CountingSelector()
        val refiner = BreakShiftRefiner(
            maxShift = 5, maxIterations = 10,
            criterion = MixtureBICCriterion(), selector = counter
        )
        refiner.refine(sorted, initial, certificate, ComponentFitCache(PDFComponentFitter(estimators)))

        // an upper bound on the neighborhood: one initial score, plus every sweep offering each of
        // the five cuts two shifts either way. Exponential enumeration would exceed this by orders
        // of magnitude, which is the regression being guarded against.
        val bound = 1 + 10 * 5 * (2 * 5)
        assertTrue(
            counter.numSelections <= bound,
            "scored ${counter.numSelections} partitions, which exceeds the neighborhood bound $bound"
        )
    }
}
