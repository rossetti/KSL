/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.Lognormal
import ksl.utilities.distributions.metalog.MetalogBoundedness
import ksl.utilities.distributions.metalog.MetalogDistribution
import ksl.utilities.distributions.fitting.estimators.MetalogParameterEstimator
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.RVType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  End-to-end exercise of the metalog family through the whole fitting pipeline: estimation,
 *  reconstruction of a distribution from the estimated parameters, scoring against the default
 *  goodness-of-fit models, and ranking.
 *
 *  The individual pieces are covered by [MetalogParameterEstimatorTest]. What this suite adds is
 *  that they compose — that a metalog survives the trip through `PDFModeler` the way every other
 *  family does, and that it can be ranked alongside them.
 */
class MetalogPDFModelerTest {

    private val metalogTypes = setOf(
        RVType.Metalog2P, RVType.Metalog3P, RVType.Metalog4P, RVType.Metalog5P, RVType.Metalog6P
    )

    /** A reproducible sample from a source distribution, drawn on its own provider. */
    private fun sampleFrom(
        distribution: ContinuousDistributionIfc,
        size: Int,
        streamNum: Int
    ): DoubleArray {
        val rv = distribution.randomVariable(streamNum, RNStreamProvider())
        return DoubleArray(size) { rv.value }
    }

    private fun lognormalSample(size: Int = 200, streamNum: Int = 1): DoubleArray =
        sampleFrom(Lognormal(mean = 10.0, variance = 25.0), size, streamNum)

    // -------- estimation across the whole opted-in set --------

    @Test
    fun theOptedInSetHoldsEveryArityAndBoundednessPair() {
        val estimators = PDFModeler.metalogEstimators
        assertEquals(20, estimators.size)
        val pairs = estimators
            .filterIsInstance<MetalogParameterEstimator>()
            .map { Pair(it.numTerms, it.boundedness) }
            .toSet()
        assertEquals(20, pairs.size, "the opted-in set does not cover twenty distinct pairs")
    }

    @Test
    fun everyMetalogEstimatorProducesAnEstimationResult() {
        val modeler = PDFModeler(lognormalSample())
        val results = modeler.estimateParameters(PDFModeler.metalogEstimators)
        assertEquals(20, results.size, "one result per estimator was expected")
        val failures = results.filter { !it.success }
        assertTrue(
            failures.isEmpty(),
            "estimation failed for: " + failures.joinToString { "${it.estimator.name}: ${it.message}" }
        )
    }

    @Test
    fun estimatedParametersAreNamedAndComplete() {
        val modeler = PDFModeler(lognormalSample())
        for (result in modeler.estimateParameters(PDFModeler.metalogEstimators)) {
            val parameters = assertNotNull(result.parameters, "${result.estimator.name} produced no parameters")
            val map = parameters.asDoubleMap()
            val numTerms = (result.estimator as MetalogParameterEstimator).numTerms
            // Every coefficient plus both bounds, whether or not this member uses them.
            assertEquals(numTerms + 2, map.size, "${result.estimator.name} parameter count")
            for (i in 1..numTerms) {
                assertTrue("a$i" in map, "${result.estimator.name} is missing a$i")
                assertTrue(map.getValue("a$i").isFinite(), "${result.estimator.name} a$i was not finite")
            }
            assertTrue("lowerBound" in map)
            assertTrue("upperBound" in map)
        }
    }

    // -------- scoring and ranking --------

    @Test
    fun everySuccessfulEstimateIsScoredAndRanked() {
        val modeler = PDFModeler(lognormalSample())
        val results = modeler.estimateAndEvaluateScores(PDFModeler.metalogEstimators)
        assertEquals(
            results.estimationResults.count { it.success && it.parameters != null },
            results.scoringResults.size,
            "a successful estimate was dropped before scoring"
        )
        assertEquals(20, results.scoringResults.size)
        for (scoringResult in results.scoringResults) {
            assertTrue(scoringResult.rvType in metalogTypes, "unexpected rv type ${scoringResult.rvType}")
            assertTrue(
                scoringResult.distribution is MetalogDistribution,
                "${scoringResult.name} was reconstructed as ${scoringResult.distribution::class.simpleName}"
            )
            assertTrue(scoringResult.scores.isNotEmpty(), "${scoringResult.name} carries no scores")
            assertTrue(scoringResult.weightedValue.isFinite(), "${scoringResult.name} has no finite value")
        }
    }

    @Test
    fun rankingIsATotalOrderOverTheScoredResults() {
        val results = PDFModeler(lognormalSample()).estimateAndEvaluateScores(PDFModeler.metalogEstimators)
        val sorted = results.resultsSortedByScoring
        assertEquals(results.scoringResults.size, sorted.size)
        for (i in 1 until sorted.size) {
            assertTrue(
                sorted[i - 1].weightedValue >= sorted[i].weightedValue,
                "results are not sorted by weighted value at position $i"
            )
        }
        val ranks = results.resultsAndRanksByScore()
        assertEquals(sorted.size, ranks.size)
        assertEquals((1..sorted.size).toSet(), ranks.values.toSet(), "ranks are not a permutation")
        assertEquals(1, ranks[results.topResultByScore])
        assertTrue(results.topRVTypeByScore in metalogTypes)
    }

    @Test
    fun scoredAlternativesAreDistinctSoNoneIsLostInTheEvaluationModel() {
        // The MODA model keys alternatives by ScoringResult.name, which is the distribution's
        // toString(). Two fits sharing a name would collapse into one alternative.
        val results = PDFModeler(lognormalSample()).estimateAndEvaluateScores(PDFModeler.metalogEstimators)
        val names = results.scoringResults.map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate alternative names: $names")
    }

    // -------- reconstruction --------

    @Test
    fun theReconstructedDistributionCarriesTheEstimatedParameters() {
        val results = PDFModeler(lognormalSample()).estimateAndEvaluateScores(PDFModeler.metalogEstimators)
        for (scoringResult in results.scoringResults) {
            val estimated = assertNotNull(scoringResult.estimationResult.parameters).asDoubleMap()
            val rebuilt = (scoringResult.distribution as MetalogDistribution).parameters()
            val numTerms = rebuilt.size - 2
            for (i in 1..numTerms) {
                assertEquals(estimated.getValue("a$i"), rebuilt[i - 1], "${scoringResult.name} a$i")
            }
            assertEquals(estimated.getValue("lowerBound"), rebuilt[numTerms], "${scoringResult.name} lowerBound")
            assertEquals(estimated.getValue("upperBound"), rebuilt[numTerms + 1], "${scoringResult.name} upperBound")
        }
    }

    @Test
    fun aFittedMetalogSamplesAndInvertsWithinItsOwnSupport() {
        val results = PDFModeler(lognormalSample()).estimateAndEvaluateScores(PDFModeler.metalogEstimators)
        val best = results.topResultByScore.distribution as MetalogDistribution
        val support = best.domain()
        val rv = best.randomVariable(7, RNStreamProvider())
        repeat(500) {
            val x = rv.value
            assertTrue(support.contains(x), "sampled $x outside the support $support")
        }
        for (p in doubleArrayOf(0.01, 0.1, 0.5, 0.9, 0.99)) {
            val x = best.invCDF(p)
            assertEquals(p, best.cdf(x), 1e-8, "inversion round trip failed at p = $p")
        }
    }

    // -------- competing against the classical families --------

    @Test
    fun metalogRanksAlongsideTheDefaultFamiliesInOneRun() {
        val modeler = PDFModeler(lognormalSample())
        val estimators = PDFModeler.allEstimators + PDFModeler.metalogEstimators
        val results = modeler.estimateAndEvaluateScores(estimators)
        val types = results.scoringResults.map { it.rvType }.toSet()
        assertTrue(types.any { it in metalogTypes }, "no metalog was scored in the mixed run")
        assertTrue(types.any { it !in metalogTypes }, "no classical family was scored in the mixed run")
        val ranks = results.resultsAndRanksByScore()
        assertEquals((1..results.scoringResults.size).toSet(), ranks.values.toSet())
    }

    @Test
    fun theOptedInSetIsAbsentFromTheDefaultSet() {
        // Guards the opt-in contract: adding metalog to allEstimators would silently change the
        // recommended distribution for every existing caller.
        val defaultTypes = PDFModeler.allEstimators.map { it.rvType }.toSet()
        assertTrue(defaultTypes.none { it in metalogTypes })
    }

    // -------- a shape the classical families handle poorly --------

    @Test
    fun aBoundedFitKeepsItsBoundsThroughThePipeline() {
        // A sharply bimodal sample on a known interval. The claim is not that metalog beats the
        // classical families here, only that the bounded member survives the round trip through
        // estimation and reconstruction with a support that still contains the data.
        val provider = RNStreamProvider()
        val stream = provider.rnStream(11)
        val data = DoubleArray(400) {
            if (stream.randU01() < 0.5) 2.0 + 0.8 * stream.randU01() else 7.0 + 0.8 * stream.randU01()
        }
        val boundedEstimators = MetalogParameterEstimator.estimators(MetalogBoundedness.Bounded)
        val results = PDFModeler(data).estimateAndEvaluateScores(boundedEstimators.toSet())
        assertTrue(results.scoringResults.isNotEmpty(), "no bounded metalog was scored")
        for (scoringResult in results.scoringResults) {
            val fitted = scoringResult.distribution as MetalogDistribution
            assertTrue(fitted.lowerBound.isFinite(), "${scoringResult.name} lost its lower bound")
            assertTrue(fitted.upperBound.isFinite(), "${scoringResult.name} lost its upper bound")
            assertTrue(fitted.lowerBound <= data.min(), "${scoringResult.name} excludes the smallest observation")
            assertTrue(fitted.upperBound >= data.max(), "${scoringResult.name} excludes the largest observation")
        }
    }
}
