package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.Exponential
import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.Lognormal
import ksl.utilities.distributions.MetalogBoundedness
import ksl.utilities.distributions.MetalogDistribution
import ksl.utilities.distributions.MetalogFeasibilityChecker
import ksl.utilities.distributions.MetalogFunctions
import ksl.utilities.distributions.Weibull
import ksl.utilities.distributions.fitting.estimators.MetalogBoundProfiler
import ksl.utilities.distributions.fitting.estimators.MetalogLPSolver
import ksl.utilities.distributions.fitting.estimators.MetalogOLSSolver
import ksl.utilities.distributions.fitting.estimators.MetalogParameterEstimator
import ksl.utilities.distributions.fitting.estimators.MetalogPlottingPositions
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.statistic.Statistic
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetalogParameterEstimatorTest {

    private fun assertNearly(
        expected: Double,
        actual: Double,
        relTol: Double = 1e-9,
        absTol: Double = 1e-9,
        message: String = "",
    ) {
        val diff = abs(expected - actual)
        val threshold = max(absTol, relTol * max(1.0, abs(expected)))
        assertTrue(
            diff <= threshold,
            "$message Expected $expected, actual $actual, diff $diff, threshold $threshold",
        )
    }

    /** A reproducible sample from a source distribution, drawn on its own provider. */
    private fun sampleFrom(
        distribution: ksl.utilities.distributions.ContinuousDistributionIfc,
        size: Int,
        streamNum: Int
    ): DoubleArray {
        val rv = distribution.randomVariable(streamNum, RNStreamProvider())
        return DoubleArray(size) { rv.value }
    }

    /** The largest gap between a fitted metalog and the source distribution, in probability. */
    private fun kolmogorovDistance(
        fitted: MetalogDistribution,
        source: ksl.utilities.distributions.ContinuousDistributionIfc
    ): Double {
        var largest = 0.0
        var p = 0.01
        while (p < 1.0) {
            val x = fitted.invCDF(p)
            largest = max(largest, abs(source.cdf(x) - p))
            p += 0.01
        }
        return largest
    }

    // -------- plotting positions --------

    @Test
    fun plottingPositionsStayStrictlyInsideTheUnitInterval() {
        for (size in intArrayOf(2, 3, 10, 100)) {
            val positions = MetalogPlottingPositions.positions(size)
            assertEquals(size, positions.size)
            assertTrue(positions.all { (it > 0.0) && (it < 1.0) }, "size $size left the interval")
            for (i in 0 until positions.size - 1) {
                assertTrue(positions[i] < positions[i + 1], "size $size was not increasing")
            }
        }
    }

    @Test
    fun aSmallSampleIsUsedPointForPoint() {
        val data = doubleArrayOf(5.0, 1.0, 3.0, 2.0, 4.0)
        val (values, probabilities) = MetalogPlottingPositions.cdfData(data)
        assertEquals(5, values.size)
        assertEquals(5, probabilities.size)
        // The values come back sorted, since a cumulative distribution function is being described.
        assertTrue(values.contentEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)), values.joinToString())
        assertNearly(0.1, probabilities[0])
        assertNearly(0.9, probabilities[4])
    }

    @Test
    fun aLargeSampleIsResampledOntoAGridWithRefinedTails() {
        val rv = Lognormal(1.0, 0.5).randomVariable(1, RNStreamProvider())
        val data = DoubleArray(5_000) { rv.value }
        val (values, probabilities) = MetalogPlottingPositions.cdfData(data)
        assertTrue(probabilities.size < data.size, "the sample was not resampled")
        assertEquals(values.size, probabilities.size)
        assertTrue(probabilities.all { (it > 0.0) && (it < 1.0) })
        // The grid reaches an order of magnitude closer to each endpoint than its interior spacing.
        assertTrue(probabilities.first() <= 0.0011, "the grid started at ${probabilities.first()}")
        assertTrue(probabilities.last() >= 0.9989, "the grid ended at ${probabilities.last()}")
        for (i in 0 until probabilities.size - 1) {
            assertTrue(probabilities[i] < probabilities[i + 1], "the grid was not increasing")
        }
    }

    @Test
    fun plottingPositionArgumentsAreValidated() {
        assertFailsWith<IllegalArgumentException>("one observation") {
            MetalogPlottingPositions.cdfData(doubleArrayOf(1.0))
        }
        assertFailsWith<IllegalArgumentException>("a non-finite observation") {
            MetalogPlottingPositions.cdfData(doubleArrayOf(1.0, Double.NaN))
        }
    }

    // -------- the least squares solver --------

    @Test
    fun theSolverReproducesAnExactFit() {
        val probabilities = doubleArrayOf(0.05, 0.25, 0.5, 0.75, 0.95)
        val values = doubleArrayOf(-2.0, -0.6, 0.0, 0.7, 2.1)
        val design = MetalogFunctions.designMatrix(probabilities, 5)
        val a = MetalogOLSSolver().solveOrNull(design, values)
        assertTrue(a != null)
        val reference = doubleArrayOf(
            0.0, 0.9100904483018099, -0.25739931871162386, -1.3993462012153528, 1.9311282185255625,
        )
        for (i in reference.indices) {
            assertNearly(reference[i], a!![i], relTol = 1e-8, absTol = 1e-8, message = "a${i + 1}:")
        }
    }

    @Test
    fun theRidgeShrinksTheCoefficients() {
        val probabilities = doubleArrayOf(0.05, 0.25, 0.5, 0.75, 0.95)
        val values = doubleArrayOf(-2.0, -0.6, 0.0, 0.7, 2.1)
        val design = MetalogFunctions.designMatrix(probabilities, 5)
        val plain = MetalogOLSSolver().solveOrNull(design, values)!!
        val penalized = MetalogOLSSolver(ridge = 1.0).solveOrNull(design, values)!!
        val plainSize = plain.sumOf { it * it }
        val penalizedSize = penalized.sumOf { it * it }
        assertTrue(
            penalizedSize < plainSize,
            "the ridge should have shrunk the coefficients: $penalizedSize against $plainSize",
        )
    }

    @Test
    fun aNegativeRidgeIsRejected() {
        assertFailsWith<IllegalArgumentException> { MetalogOLSSolver(ridge = -1.0) }
    }

    // -------- the linear program fallback --------

    @Test
    fun theLinearProgramRescuesAFitLeastSquaresCannotMake() {
        // This is the case pinned in earlier phases: least squares returns coefficients whose
        // quantile function is not strictly increasing.
        val probabilities = doubleArrayOf(0.1, 0.5, 0.9)
        val values = doubleArrayOf(1.0, 2.0, 100.0)
        val design = MetalogFunctions.designMatrix(probabilities, 3)
        val leastSquares = MetalogOLSSolver().solveOrNull(design, values)!!
        assertNearly(2.0, leastSquares[0], relTol = 1e-6, absTol = 1e-6)
        assertNearly(22.52842085901421, leastSquares[1], relTol = 1e-6, absTol = 1e-6)
        assertNearly(55.18325311425224, leastSquares[2], relTol = 1e-6, absTol = 1e-6)
        assertFalse(
            MetalogFeasibilityChecker.defaultChecker.isFeasible(leastSquares),
            "the least squares fit was supposed to be invalid",
        )
        val program = MetalogLPSolver().solveOrNull(probabilities, values, 3)
        assertTrue(program != null, "the linear program found no fit")
        assertTrue(
            MetalogFeasibilityChecker.defaultChecker.isFeasible(program!!),
            "the linear program returned an invalid fit: ${program.joinToString()}",
        )
    }

    @Test
    fun theLinearProgramLeavesAValidFitValid() {
        val rv = Lognormal(2.0, 1.0).randomVariable(3, RNStreamProvider())
        val data = DoubleArray(40) { rv.value }.sortedArray()
        val probabilities = MetalogPlottingPositions.positions(data.size)
        for (numTerms in 2..6) {
            val program = MetalogLPSolver().solveOrNull(probabilities, data, numTerms)
            assertTrue(program != null, "$numTerms terms produced no fit")
            assertTrue(
                MetalogFeasibilityChecker.defaultChecker.isFeasible(program!!),
                "$numTerms terms produced an invalid fit",
            )
        }
    }

    @Test
    fun theDerivativeFloorIsScaleInvariant() {
        // The monotonicity floor carries the units of the data, so it is expressed relative to the
        // spread. Rescaling the data must not change whether a fit is found.
        val probabilities = doubleArrayOf(0.1, 0.5, 0.9)
        val base = doubleArrayOf(1.0, 2.0, 100.0)
        for (scale in doubleArrayOf(1e-6, 1e-3, 1.0, 1e3, 1e6)) {
            val scaled = DoubleArray(base.size) { base[it] * scale }
            val program = MetalogLPSolver().solveOrNull(probabilities, scaled, 3)
            assertTrue(program != null, "no fit at scale $scale")
            assertTrue(
                MetalogFeasibilityChecker.defaultChecker.isFeasible(program!!),
                "an invalid fit at scale $scale",
            )
        }
    }

    @Test
    fun aValidFitNeedsNoEscalationOfTheFloor() {
        val rv = Lognormal(2.0, 1.0).randomVariable(4, RNStreamProvider())
        val data = DoubleArray(40) { rv.value }.sortedArray()
        val probabilities = MetalogPlottingPositions.positions(data.size)
        val solver = MetalogLPSolver()
        solver.solveOrNull(probabilities, data, 4)
        assertEquals(1, solver.escalationsUsed, "the floor should not have needed raising")
    }

    @Test
    fun theLinearProgramArgumentsAreValidated() {
        assertFailsWith<IllegalArgumentException>("mismatched lengths") {
            MetalogLPSolver().solveOrNull(doubleArrayOf(0.5), doubleArrayOf(1.0, 2.0), 3)
        }
        assertFailsWith<IllegalArgumentException>("too few terms") {
            MetalogLPSolver().solveOrNull(doubleArrayOf(0.1, 0.5), doubleArrayOf(1.0, 2.0), 1)
        }
        assertFailsWith<IllegalArgumentException>("a non-positive floor") {
            MetalogLPSolver(initialRelativeFloor = 0.0)
        }
    }

    // -------- the bound profiler --------

    @Test
    fun everyLowerBoundCandidateClearsTheSampleMinimum() {
        val data = doubleArrayOf(3.0, 5.0, 9.0, 20.0)
        val candidates = MetalogBoundProfiler().lowerBoundCandidates(data)
        assertTrue(candidates.isNotEmpty())
        assertTrue(
            candidates.all { it < data.min() },
            "a candidate did not clear the minimum: ${candidates.joinToString()}",
        )
    }

    @Test
    fun everyUpperBoundCandidateClearsTheSampleMaximum() {
        val data = doubleArrayOf(3.0, 5.0, 9.0, 20.0)
        val candidates = MetalogBoundProfiler().upperBoundCandidates(data)
        assertTrue(candidates.all { it > data.max() }, candidates.joinToString())
    }

    @Test
    fun zeroIsOfferedAsALowerBoundForPositiveData() {
        val candidates = MetalogBoundProfiler().lowerBoundCandidates(doubleArrayOf(3.0, 5.0, 20.0))
        assertTrue(candidates.any { it == 0.0 }, "zero was not offered: ${candidates.joinToString()}")
    }

    @Test
    fun zeroIsNotOfferedWhenTheDataStraddlesIt() {
        val candidates = MetalogBoundProfiler().lowerBoundCandidates(doubleArrayOf(-3.0, 5.0, 20.0))
        assertFalse(candidates.any { it == 0.0 }, "zero should not clear a negative minimum")
    }

    @Test
    fun identicalObservationsStillYieldCandidatesWithClearance() {
        val candidates = MetalogBoundProfiler().lowerBoundCandidates(doubleArrayOf(7.0, 7.0, 7.0))
        assertTrue(candidates.all { it < 7.0 }, candidates.joinToString())
    }

    // -------- estimator behavior --------

    @Test
    fun everyEstimatorDeclinesToShiftTheData() {
        for (estimator in MetalogParameterEstimator.allEstimators()) {
            assertFalse(estimator.checkRange, "${estimator.name} asked for a range check")
        }
    }

    @Test
    fun theEstimatorSetCoversEveryTermCountAndVariant() {
        val all = MetalogParameterEstimator.allEstimators()
        assertEquals(20, all.size, "the set held ${all.size} estimators")
        for (boundedness in MetalogBoundedness.entries) {
            for (terms in 2..6) {
                assertTrue(
                    all.any { (it.numTerms == terms) && (it.boundedness == boundedness) },
                    "$terms terms of $boundedness was missing",
                )
            }
        }
    }

    @Test
    fun theModelerExposesTheSetWithoutAddingItToTheDefaults() {
        assertEquals(20, PDFModeler.metalogEstimators.size)
        // The decision was to keep these opt in, so that recommendations for existing data do not
        // change until the family has seen more use.
        val defaultNames = PDFModeler.allEstimators.map { it.name }.toSet()
        assertTrue(
            PDFModeler.metalogEstimators.none { defaultNames.contains(it.name) },
            "a metalog estimator leaked into the default set",
        )
    }

    @Test
    fun eachEstimatorReportsTheTypeForItsArity() {
        assertEquals(RVType.Metalog2P, MetalogParameterEstimator(2, MetalogBoundedness.Unbounded).rvType)
        assertEquals(RVType.Metalog5P, MetalogParameterEstimator(5, MetalogBoundedness.Bounded).rvType)
    }

    @Test
    fun theNamesMatchTheArityAndOnlyTheBoundsInUse() {
        assertEquals(
            listOf("a1", "a2", "a3"),
            MetalogParameterEstimator(3, MetalogBoundedness.Unbounded).names,
        )
        assertEquals(
            listOf("a1", "a2", "a3", "lowerBound"),
            MetalogParameterEstimator(3, MetalogBoundedness.LowerBounded).names,
        )
        assertEquals(
            listOf("a1", "a2", "upperBound"),
            MetalogParameterEstimator(2, MetalogBoundedness.UpperBounded).names,
        )
        assertEquals(
            listOf("a1", "a2", "lowerBound", "upperBound"),
            MetalogParameterEstimator(2, MetalogBoundedness.Bounded).names,
        )
    }

    @Test
    fun estimatorArgumentsAreValidated() {
        assertFailsWith<IllegalArgumentException>("too few terms") {
            MetalogParameterEstimator(1, MetalogBoundedness.Unbounded)
        }
        assertFailsWith<IllegalArgumentException>("too many terms") {
            MetalogParameterEstimator(7, MetalogBoundedness.Unbounded)
        }
        assertFailsWith<IllegalArgumentException>("a bound the variant does not use") {
            MetalogParameterEstimator(3, MetalogBoundedness.Unbounded, lowerBound = 0.0)
        }
        assertFailsWith<IllegalArgumentException>("an infinite supplied bound") {
            MetalogParameterEstimator(3, MetalogBoundedness.LowerBounded, lowerBound = Double.NEGATIVE_INFINITY)
        }
    }

    // -------- recovery of known sources --------

    @Test
    fun theUnboundedEstimatorRecoversANormalSample() {
        val source = ksl.utilities.distributions.Normal(50.0, 25.0)
        val data = sampleFrom(source, 300, 41)
        for (terms in 3..6) {
            val estimator = MetalogParameterEstimator(terms, MetalogBoundedness.Unbounded)
            val result = estimator.estimateParameters(data, Statistic(data))
            assertTrue(result.success, "$terms terms failed: ${result.message}")
            val fitted = PDFModeler.createDistribution(result.parameters!!) as MetalogDistribution
            val distance = kolmogorovDistance(fitted, source)
            assertTrue(distance < 0.1, "$terms terms was $distance away from the source")
        }
    }

    @Test
    fun theLowerBoundedEstimatorRecoversPositiveSources() {
        val sources = listOf(
            "lognormal" to Lognormal(4.0, 9.0),
            "exponential" to Exponential(3.0),
            "weibull" to Weibull(3.0, 3.0),
            "gamma" to Gamma(4.0, 2.0),
        )
        for ((label, source) in sources) {
            val data = sampleFrom(source, 250, 51)
            val estimator = MetalogParameterEstimator(4, MetalogBoundedness.LowerBounded)
            val result = estimator.estimateParameters(data, Statistic(data))
            assertTrue(result.success, "$label failed: ${result.message}")
            val fitted = PDFModeler.createDistribution(result.parameters!!) as MetalogDistribution
            assertEquals(MetalogBoundedness.LowerBounded, fitted.boundedness, label)
            val distance = kolmogorovDistance(fitted, source)
            assertTrue(distance < 0.12, "$label was $distance away from the source")
        }
    }

    @Test
    fun aProfiledLowerBoundClearsTheSampleMinimum() {
        val data = sampleFrom(Lognormal(4.0, 9.0), 300, 61)
        val estimator = MetalogParameterEstimator(4, MetalogBoundedness.LowerBounded)
        val result = estimator.estimateParameters(data, Statistic(data))
        assertTrue(result.success, result.message)
        val bound = result.parameters!!.doubleParameter("lowerBound")
        assertTrue(bound < data.min(), "the bound $bound did not clear the minimum ${data.min()}")
        assertTrue(bound.isFinite(), "the bound was not finite")
    }

    @Test
    fun aSuppliedBoundIsUsedRatherThanProfiled() {
        val data = sampleFrom(Exponential(3.0), 300, 71)
        val estimator = MetalogParameterEstimator(4, MetalogBoundedness.LowerBounded, lowerBound = 0.0)
        val result = estimator.estimateParameters(data, Statistic(data))
        assertTrue(result.success, result.message)
        assertNearly(0.0, result.parameters!!.doubleParameter("lowerBound"))
    }

    @Test
    fun theBoundedEstimatorKeepsTheDataInside() {
        val data = sampleFrom(ksl.utilities.distributions.Beta(2.0, 5.0), 300, 81)
        val estimator = MetalogParameterEstimator(4, MetalogBoundedness.Bounded)
        val result = estimator.estimateParameters(data, Statistic(data))
        assertTrue(result.success, result.message)
        val lower = result.parameters!!.doubleParameter("lowerBound")
        val upper = result.parameters.doubleParameter("upperBound")
        assertTrue(lower < data.min(), "the lower bound $lower did not clear ${data.min()}")
        assertTrue(upper > data.max(), "the upper bound $upper did not clear ${data.max()}")
    }

    // -------- every successful result is a usable distribution --------

    @Test
    fun everySuccessfulResultBuildsAValidDistribution() {
        // The contract that matters most: an estimator must never report success while handing back
        // coefficients that are not a distribution.
        // Two sources, one unbounded in shape and one strictly positive and skewed, is enough to
        // put every estimator through both an easy and an awkward fit without making this the
        // slowest class in the suite.
        val samples = listOf(
            "normal" to sampleFrom(ksl.utilities.distributions.Normal(10.0, 4.0), 120, 91),
            "lognormal" to sampleFrom(Lognormal(4.0, 9.0), 120, 92),
        )
        var successes = 0
        for ((label, data) in samples) {
            for (estimator in MetalogParameterEstimator.allEstimators()) {
                val result = estimator.estimateParameters(data, Statistic(data))
                if (!result.success) {
                    continue
                }
                successes++
                val parameters = result.parameters
                assertTrue(parameters != null, "${estimator.name} on $label reported success with no parameters")
                // Building the distribution revalidates, so this throws if the fit was invalid.
                val fitted = PDFModeler.createDistribution(parameters!!)
                assertTrue(
                    fitted is MetalogDistribution,
                    "${estimator.name} on $label produced $fitted",
                )
                assertEquals(estimator.numTerms, (fitted as MetalogDistribution).numTerms, estimator.name)
                assertEquals(estimator.boundedness, fitted.boundedness, estimator.name)
            }
        }
        assertTrue(successes > 20, "only $successes fits succeeded, too few to be meaningful")
    }

    @Test
    fun disablingTheFallbackTurnsAnInvalidFitIntoAFailure() {
        // Three points whose least squares fit is invalid. Without the fallback the estimator must
        // report failure rather than returning the invalid coefficients.
        val data = doubleArrayOf(1.0, 2.0, 100.0)
        val estimator = MetalogParameterEstimator(
            3, MetalogBoundedness.Unbounded, useLPFallback = false
        )
        val result = estimator.estimateParameters(data, Statistic(data))
        assertFalse(result.success, "the estimator reported success on an invalid fit")
        assertTrue(
            result.message!!.contains("not strictly increasing"),
            "unhelpful message: ${result.message}",
        )
    }

    @Test
    fun enablingTheFallbackTurnsTheSameCaseIntoASuccess() {
        val data = doubleArrayOf(1.0, 2.0, 100.0)
        val estimator = MetalogParameterEstimator(3, MetalogBoundedness.Unbounded)
        val result = estimator.estimateParameters(data, Statistic(data))
        assertTrue(result.success, "the fallback did not rescue the fit: ${result.message}")
        assertTrue(
            result.message!!.contains("linear program"),
            "the message should name the method used: ${result.message}",
        )
        val fitted = PDFModeler.createDistribution(result.parameters!!)
        assertTrue(fitted is MetalogDistribution)
    }

    // -------- degenerate input --------

    @Test
    fun tooFewObservationsFail() {
        val estimator = MetalogParameterEstimator(5, MetalogBoundedness.Unbounded)
        val data = doubleArrayOf(1.0, 2.0, 3.0)
        val result = estimator.estimateParameters(data, Statistic(data))
        assertFalse(result.success)
        assertTrue(result.message!!.contains("at least 5 observations"), result.message!!)
    }

    @Test
    fun identicalObservationsFail() {
        val estimator = MetalogParameterEstimator(3, MetalogBoundedness.Unbounded)
        val data = DoubleArray(20) { 7.0 }
        val result = estimator.estimateParameters(data, Statistic(data))
        assertFalse(result.success)
        assertTrue(result.message!!.contains("identical"), result.message!!)
    }

    @Test
    fun aFailedEstimationYieldsAnEmptyEstimateArray() {
        val estimator = MetalogParameterEstimator(5, MetalogBoundedness.Unbounded)
        assertEquals(0, estimator.estimate(doubleArrayOf(1.0, 2.0)).size)
    }

    // -------- bootstrapping --------

    @Test
    fun theEstimateArrayMatchesTheDeclaredNames() {
        val data = sampleFrom(Lognormal(4.0, 9.0), 200, 101)
        for (boundedness in MetalogBoundedness.entries) {
            val estimator = MetalogParameterEstimator(3, boundedness)
            val estimate = estimator.estimate(data)
            assertEquals(
                estimator.names.size, estimate.size,
                "${estimator.name} returned ${estimate.size} values for ${estimator.names.size} names",
            )
            assertTrue(estimate.all { it.isFinite() }, "${estimator.name} produced a non-finite value")
        }
    }

    @Test
    fun bootstrappingTheParametersCompletes() {
        // The parameter dimension is fixed per estimator, which is what makes this possible.
        val data = sampleFrom(Lognormal(4.0, 9.0), 200, 111)
        val estimator = MetalogParameterEstimator(3, MetalogBoundedness.LowerBounded)
        val result = estimator.estimateParameters(data, Statistic(data))
        assertTrue(result.success, result.message)
        val bootstrapped = result.bootstrapParameters(numBootstrapSamples = 20, streamNumber = 121)
        assertEquals(estimator.names.size, bootstrapped.size, bootstrapped.keys.toString())
        for (name in estimator.names) {
            assertTrue(bootstrapped.containsKey(name), "$name was missing from $bootstrapped")
        }
    }
}
