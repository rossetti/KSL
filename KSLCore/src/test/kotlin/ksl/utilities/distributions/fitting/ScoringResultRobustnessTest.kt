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

package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.fitting.estimators.NormalMLEParameterEstimator
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.statistic.Statistic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 *  Scoring a list of candidate fits must survive a candidate that cannot be turned into a
 *  distribution.
 *
 *  The guard in `scoringResults` was `createDistribution(...) ?: continue`, which covers only the
 *  case the function's own documentation described: an unknown random variable type, for which null
 *  comes back. It did not cover a KNOWN type whose estimated parameter VALUES are invalid — a normal
 *  with zero variance, say — because there the distribution's own `require` throws. An estimator
 *  reporting `success = true` does not guarantee parameters a distribution will accept, and constant
 *  or near-constant data reaches exactly that state, so one unusable candidate ended the scoring of
 *  every other candidate in the list.
 */
class ScoringResultRobustnessTest {

    private val estimator = NormalMLEParameterEstimator

    /** An estimation result claiming success while carrying the supplied normal parameters. */
    private fun normalResult(mean: Double, variance: Double, data: DoubleArray): EstimationResult {
        val parameters = RVType.Normal.rvParameters
        parameters.changeDoubleParameter("mean", mean)
        parameters.changeDoubleParameter("variance", variance)
        return EstimationResult(
            originalData = data,
            statistics = Statistic(data),
            parameters = parameters,
            message = "fabricated for this test",
            success = true,
            estimator = estimator
        )
    }

    private fun usableData(): DoubleArray = DoubleArray(50) { 1.0 + 0.1 * it }

    /**
     *  The fixture. Constant data really does produce a zero variance, and a normal really does
     *  reject it, so the condition under test is reachable rather than contrived. Without this the
     *  test below could pass because nothing ever threw.
     */
    @Test
    @DisplayName("A zero-variance normal is rejected by its own constructor")
    fun zeroVarianceNormalIsRejected() {
        val parameters = RVType.Normal.rvParameters
        parameters.changeDoubleParameter("mean", 5.0)
        parameters.changeDoubleParameter("variance", 0.0)
        val error = assertThrows(IllegalArgumentException::class.java) {
            PDFModeler.createDistribution(parameters)
        }
        assertTrue(error.message!!.contains("ariance")) { "unexpected message: ${error.message}" }
    }

    /**
     *  The behaviour. One candidate that cannot be constructed is dropped; the others are still
     *  scored and returned.
     */
    @Test
    @DisplayName("An unconstructable candidate is skipped rather than ending the scoring")
    fun scoringSurvivesAnUnconstructableCandidate() {
        val data = usableData()
        val modeler = PDFModeler(data)
        val good = normalResult(mean = 3.5, variance = 2.0, data = data)
        val bad = normalResult(mean = 5.0, variance = 0.0, data = data)

        val results = modeler.scoringResults(listOf(bad, good))

        assertEquals(1, results.size) {
            "expected the good candidate to survive alone, got ${results.map { it.name }}"
        }
        assertNotNull(results.single().distribution)
    }

    /** The order of the list must not decide whether the call succeeds. */
    @Test
    @DisplayName("The bad candidate is skipped wherever it sits in the list")
    fun theSkipDoesNotDependOnPosition() {
        val data = usableData()
        val modeler = PDFModeler(data)
        val good = normalResult(mean = 3.5, variance = 2.0, data = data)
        val bad = normalResult(mean = 5.0, variance = 0.0, data = data)

        assertEquals(1, modeler.scoringResults(listOf(bad, good)).size)
        assertEquals(1, modeler.scoringResults(listOf(good, bad)).size)
    }

    /** Every candidate being unusable yields an empty list, not an exception. */
    @Test
    @DisplayName("A list of nothing but unconstructable candidates scores to empty")
    fun allCandidatesUnconstructableGivesAnEmptyList() {
        val data = usableData()
        val modeler = PDFModeler(data)
        val bad = normalResult(mean = 5.0, variance = 0.0, data = data)

        assertTrue(modeler.scoringResults(listOf(bad, bad)).isEmpty())
    }
}
