package ksl.utilities.distributions.metalog

import ksl.utilities.distributions.Normal
import org.hipparchus.linear.MatrixUtils
import org.hipparchus.linear.QRDecomposition
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetalogDistributionTest {

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

    /**
     *  Least squares fit of metalog coefficients, done here rather than through the estimator that
     *  a later phase delivers, so the distribution classes can be checked against published
     *  numbers now. A QR solve is used rather than the normal equations.
     */
    private fun fitCoefficients(
        values: DoubleArray,
        probabilities: DoubleArray,
        numTerms: Int,
        boundedness: MetalogBoundedness = MetalogBoundedness.Unbounded,
        lowerBound: Double = Double.NEGATIVE_INFINITY,
        upperBound: Double = Double.POSITIVE_INFINITY
    ): DoubleArray {
        val z = DoubleArray(values.size) {
            boundedness.toFittingSpace(values[it], lowerBound, upperBound)
        }
        val design = MatrixUtils.createRealMatrix(
            MetalogFunctions.designMatrix(probabilities, numTerms)
        )
        val solver = QRDecomposition(design).solver
        return solver.solve(MatrixUtils.createRealVector(z)).toArray()
    }

    /** The four boundedness variants of a standard logistic-shaped metalog. */
    private fun oneOfEachVariant(): List<MetalogDistribution> = listOf(
        Metalog3P(0.0, 1.0, 0.2),
        Metalog3P(0.0, 0.3, 0.05, lowerBound = 4.0),
        Metalog3P(0.0, 0.3, 0.05, upperBound = 20.0),
        Metalog3P(0.0, 0.6, 0.1, lowerBound = -2.0, upperBound = 9.0),
    )

    // -------- inversion round trips --------

    @Test
    fun theCumulativeFunctionInvertsTheQuantileFunction() {
        for (d in oneOfEachVariant()) {
            for (p in doubleArrayOf(1e-6, 1e-4, 0.01, 0.1, 0.3, 0.5, 0.7, 0.9, 0.99, 1.0 - 1e-4, 1.0 - 1e-6)) {
                val x = d.invCDF(p)
                assertNearly(
                    p, d.cdf(x), relTol = 1e-7, absTol = 1e-9,
                    message = "${d.boundedness} at p = $p (x = $x):",
                )
            }
        }
    }

    @Test
    fun theRoundTripHoldsForEveryArity() {
        val distributions = listOf(
            Metalog2P(1.0, 2.0),
            Metalog3P(1.0, 2.0, 0.3),
            Metalog4P(1.0, 2.0, 0.3, 0.5),
            Metalog5P(1.0, 2.0, 0.3, 0.5, 0.1),
            Metalog6P(1.0, 2.0, 0.3, 0.5, 0.1, 0.05),
        )
        for (d in distributions) {
            assertTrue(
                MetalogFeasibilityChecker.defaultChecker.isFeasible(d.coefficients()),
                "${d.numTerms} terms should be feasible for this test to mean anything",
            )
            for (p in doubleArrayOf(0.001, 0.05, 0.25, 0.5, 0.75, 0.95, 0.999)) {
                assertNearly(
                    p, d.cdf(d.invCDF(p)), relTol = 1e-7, absTol = 1e-9,
                    message = "${d.numTerms} terms at p = $p:",
                )
            }
        }
    }

    @Test
    fun theQuantileFunctionIsStrictlyIncreasing() {
        for (d in oneOfEachVariant()) {
            var previous = d.invCDF(1e-9)
            var p = 1e-5
            while (p < 1.0) {
                val current = d.invCDF(p)
                assertTrue(
                    current > previous,
                    "${d.boundedness}: the quantile function decreased at p = $p, " +
                            "from $previous to $current",
                )
                previous = current
                p += 0.0005
            }
        }
    }

    // -------- density and cumulative function consistency --------

    @Test
    fun theDensityIsTheDerivativeOfTheCumulativeFunction() {
        for (d in oneOfEachVariant()) {
            for (p in doubleArrayOf(0.05, 0.2, 0.5, 0.8, 0.95)) {
                val x = d.invCDF(p)
                val h = max(1e-6, abs(x) * 1e-6)
                val numeric = (d.cdf(x + h) - d.cdf(x - h)) / (2.0 * h)
                assertNearly(
                    numeric, d.pdf(x), relTol = 1e-4, absTol = 1e-8,
                    message = "${d.boundedness} at p = $p (x = $x):",
                )
            }
        }
    }

    @Test
    fun theDensityIntegratesToOneOverTheSupport() {
        for (d in oneOfEachVariant()) {
            // Integrate in probability space, where the mass is spread evenly, using the identity
            // that the density at a quantile times the derivative of that quantile is one. This
            // checks the density and the quantile function against each other over the whole range.
            val steps = 20_000
            var total = 0.0
            for (i in 0 until steps) {
                val pLow = (i + 0.0) / steps
                val pHigh = (i + 1.0) / steps
                val lower = d.invCDF(max(pLow, 1e-12))
                val upper = d.invCDF(kotlin.math.min(pHigh, 1.0 - 1e-12))
                val middle = 0.5 * (lower + upper)
                if (middle.isFinite() && (upper - lower).isFinite()) {
                    total += d.pdf(middle) * (upper - lower)
                }
            }
            assertNearly(
                1.0, total, relTol = 1e-3, absTol = 1e-3,
                message = "${d.boundedness} density integral:",
            )
        }
    }

    @Test
    fun theDensityVanishesOutsideTheSupport() {
        val bounded = Metalog3P(0.0, 0.6, 0.1, lowerBound = -2.0, upperBound = 9.0)
        assertNearly(0.0, bounded.pdf(-2.0))
        assertNearly(0.0, bounded.pdf(-3.0))
        assertNearly(0.0, bounded.pdf(9.0))
        assertNearly(0.0, bounded.pdf(10.0))
        val lowerBounded = Metalog3P(0.0, 0.3, 0.05, lowerBound = 4.0)
        assertNearly(0.0, lowerBounded.pdf(4.0))
        assertNearly(0.0, lowerBounded.pdf(3.0))
    }

    @Test
    fun theCumulativeFunctionSaturatesOutsideTheSupport() {
        val bounded = Metalog3P(0.0, 0.6, 0.1, lowerBound = -2.0, upperBound = 9.0)
        assertNearly(0.0, bounded.cdf(-2.0))
        assertNearly(0.0, bounded.cdf(-5.0))
        assertNearly(1.0, bounded.cdf(9.0))
        assertNearly(1.0, bounded.cdf(12.0))
    }

    @Test
    fun nonNumericInputIsPropagatedRatherThanThrowing() {
        val d = Metalog3P(0.0, 1.0, 0.2)
        assertTrue(d.cdf(Double.NaN).isNaN())
        assertTrue(d.pdf(Double.NaN).isNaN())
    }

    // -------- support and bounds --------

    @Test
    fun aBoundedMetalogStaysStrictlyInsideItsBounds() {
        val d = Metalog4P(0.0, 0.8, 0.2, 0.3, lowerBound = 10.0, upperBound = 30.0)
        for (p in doubleArrayOf(1e-9, 1e-4, 0.5, 1.0 - 1e-4, 1.0 - 1e-9)) {
            val x = d.invCDF(p)
            assertTrue(x > 10.0, "at p = $p the value $x was not above the lower bound")
            assertTrue(x < 30.0, "at p = $p the value $x was not below the upper bound")
        }
        assertNearly(10.0, d.invCDF(0.0))
        assertNearly(30.0, d.invCDF(1.0))
    }

    @Test
    fun theSupportMatchesTheBoundednessVariant() {
        assertTrue(Metalog3P(0.0, 1.0, 0.2).domain().lowerLimit == Double.NEGATIVE_INFINITY)
        assertTrue(Metalog3P(0.0, 1.0, 0.2).domain().upperLimit == Double.POSITIVE_INFINITY)
        val lowerBounded = Metalog3P(0.0, 0.3, 0.05, lowerBound = 4.0)
        assertNearly(4.0, lowerBounded.domain().lowerLimit)
        assertTrue(lowerBounded.domain().upperLimit == Double.POSITIVE_INFINITY)
        val upperBounded = Metalog3P(0.0, 0.3, 0.05, upperBound = 20.0)
        assertTrue(upperBounded.domain().lowerLimit == Double.NEGATIVE_INFINITY)
        assertNearly(20.0, upperBounded.domain().upperLimit)
        val bounded = Metalog3P(0.0, 0.6, 0.1, lowerBound = -2.0, upperBound = 9.0)
        assertNearly(-2.0, bounded.domain().lowerLimit)
        assertNearly(9.0, bounded.domain().upperLimit)
    }

    @Test
    fun boundednessIsDerivedFromTheBounds() {
        assertEquals(MetalogBoundedness.Unbounded, Metalog2P(0.0, 1.0).boundedness)
        assertEquals(MetalogBoundedness.LowerBounded, Metalog2P(0.0, 1.0, lowerBound = 0.0).boundedness)
        assertEquals(MetalogBoundedness.UpperBounded, Metalog2P(0.0, 1.0, upperBound = 5.0).boundedness)
        assertEquals(
            MetalogBoundedness.Bounded,
            Metalog2P(0.0, 1.0, lowerBound = 0.0, upperBound = 5.0).boundedness
        )
    }

    @Test
    fun changingABoundChangesTheVariantAndTheSupport() {
        val d = Metalog2P(0.0, 0.5)
        assertEquals(MetalogBoundedness.Unbounded, d.boundedness)
        d.lowerBound = 0.0
        assertEquals(MetalogBoundedness.LowerBounded, d.boundedness)
        assertNearly(0.0, d.domain().lowerLimit)
        d.upperBound = 100.0
        assertEquals(MetalogBoundedness.Bounded, d.boundedness)
        assertNearly(100.0, d.domain().upperLimit)
    }

    // -------- validation --------

    @Test
    fun infeasibleCoefficientsAreRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException>("a negative scale") {
            Metalog2P(0.0, -1.0)
        }
        assertFailsWith<IllegalArgumentException>("a zero scale") {
            Metalog2P(0.0, 0.0)
        }
        assertFailsWith<IllegalArgumentException>("too much skewness for three terms") {
            Metalog3P(0.0, 1.0, 2.0)
        }
        assertFailsWith<IllegalArgumentException>("the known infeasible least squares fit") {
            Metalog3P(2.0, 22.52842085901421, 55.18325311425224)
        }
    }

    @Test
    fun theRejectionMessageExplainsWhatWentWrong() {
        val error = assertFailsWith<IllegalArgumentException> { Metalog3P(0.0, 1.0, 3.0) }
        val message = error.message ?: ""
        assertTrue(message.contains("not strictly increasing"), "unhelpful message: $message")
        assertTrue(message.contains("derivative"), "unhelpful message: $message")
    }

    @Test
    fun aRejectedCoefficientChangeLeavesTheDistributionUntouched() {
        val d = Metalog3P(5.0, 1.0, 0.5)
        val before = d.coefficients()
        assertFailsWith<IllegalArgumentException> { d.a3 = 5.0 }
        assertTrue(before.contentEquals(d.coefficients()), "the coefficients were mutated anyway")
        assertNearly(0.5, d.a3)
    }

    @Test
    fun aRejectedBoundChangeLeavesTheDistributionUntouched() {
        val d = Metalog2P(0.0, 1.0, lowerBound = 0.0, upperBound = 10.0)
        assertFailsWith<IllegalArgumentException>("a lower bound above the upper bound") {
            d.lowerBound = 20.0
        }
        assertNearly(0.0, d.lowerBound)
        assertNearly(10.0, d.upperBound)
        assertEquals(MetalogBoundedness.Bounded, d.boundedness)
    }

    @Test
    fun reversedOrEqualBoundsAreRejected() {
        assertFailsWith<IllegalArgumentException>("reversed") {
            Metalog2P(0.0, 1.0, lowerBound = 5.0, upperBound = 1.0)
        }
        assertFailsWith<IllegalArgumentException>("equal") {
            Metalog2P(0.0, 1.0, lowerBound = 3.0, upperBound = 3.0)
        }
        assertFailsWith<IllegalArgumentException>("not a number") {
            Metalog2P(0.0, 1.0, lowerBound = Double.NaN)
        }
    }

    @Test
    fun anAcceptableCoefficientChangeTakesEffect() {
        val d = Metalog3P(5.0, 1.0, 0.5)
        d.a1 = 7.0
        assertNearly(7.0, d.a1)
        assertNearly(7.0, d.invCDF(0.5))
        d.a3 = -0.5
        assertNearly(-0.5, d.a3)
    }

    @Test
    fun probabilitiesOutsideTheClosedUnitIntervalAreRejected() {
        val d = Metalog3P(0.0, 1.0, 0.2)
        assertFailsWith<IllegalArgumentException> { d.invCDF(-0.1) }
        assertFailsWith<IllegalArgumentException> { d.invCDF(1.1) }
        assertFailsWith<IllegalArgumentException> { d.invCDF(Double.NaN) }
    }

    // -------- parameters --------

    @Test
    fun theParameterArrayIsTheCoefficientsFollowedByTheBounds() {
        val d = Metalog4P(1.0, 2.0, 0.3, 0.4, lowerBound = -5.0, upperBound = 25.0)
        val p = d.parameters()
        assertEquals(6, p.size)
        assertNearly(1.0, p[0])
        assertNearly(2.0, p[1])
        assertNearly(0.3, p[2])
        assertNearly(0.4, p[3])
        assertNearly(-5.0, p[4])
        assertNearly(25.0, p[5])
    }

    @Test
    fun theParameterArrayRoundTrips() {
        val d = Metalog5P(1.0, 2.0, 0.3, 0.4, 0.1, lowerBound = -5.0)
        val original = d.parameters()
        val other = Metalog5P()
        other.parameters(original)
        assertTrue(original.contentEquals(other.parameters()), "the parameters did not round trip")
        assertNearly(d.invCDF(0.3), other.invCDF(0.3))
    }

    @Test
    fun theParameterCountForEachArityIsTheTermsPlusTwo() {
        assertEquals(4, Metalog2P().parameters().size)
        assertEquals(5, Metalog3P().parameters().size)
        assertEquals(6, Metalog4P().parameters().size)
        assertEquals(7, Metalog5P().parameters().size)
        assertEquals(8, Metalog6P().parameters().size)
    }

    @Test
    fun aWrongLengthParameterArrayIsRejected() {
        val d = Metalog3P()
        assertFailsWith<IllegalArgumentException>("too few") { d.parameters(doubleArrayOf(1.0, 1.0)) }
        assertFailsWith<IllegalArgumentException>("too many") {
            d.parameters(doubleArrayOf(1.0, 1.0, 0.0, 0.0, 0.0, 0.0))
        }
    }

    @Test
    fun aRejectedParameterArrayLeavesTheDistributionUntouched() {
        val d = Metalog3P(5.0, 1.0, 0.5)
        val before = d.parameters()
        assertFailsWith<IllegalArgumentException>("an infeasible coefficient set") {
            d.parameters(doubleArrayOf(5.0, 1.0, 9.0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY))
        }
        assertTrue(before.contentEquals(d.parameters()), "the parameters were mutated anyway")
    }

    // -------- moments --------

    @Test
    fun theMomentCacheIsInvalidatedByEveryMutator() {
        val d = Metalog3P(0.0, 1.0, 0.0)
        val originalMean = d.mean()
        d.a1 = 100.0
        assertNearly(originalMean + 100.0, d.mean())
        val varianceBefore = d.variance()
        d.a2 = 2.0
        assertTrue(
            d.variance() > varianceBefore,
            "doubling the scale should have increased the variance",
        )
    }

    @Test
    fun changingABoundInvalidatesTheMomentCache() {
        val d = Metalog2P(0.0, 0.2)
        val unboundedMean = d.mean()
        d.lowerBound = 0.0
        assertTrue(
            abs(d.mean() - unboundedMean) > 1e-6,
            "the mean should have changed when the variant did",
        )
    }

    @Test
    fun aHeavyTailedLowerBoundedMetalogReportsANonFiniteMean() {
        val d = Metalog2P(0.0, 1.5, lowerBound = 0.0)
        assertFalse(MetalogMoments.momentExists(1, d.coefficients(), d.boundedness))
        assertTrue(d.mean() == Double.POSITIVE_INFINITY, "the mean was ${d.mean()}")
        assertTrue(d.variance() == Double.POSITIVE_INFINITY, "the variance was ${d.variance()}")
    }

    @Test
    fun aHeavyTailedUpperBoundedMetalogDivergesDownward() {
        val d = Metalog2P(0.0, 1.5, upperBound = 0.0)
        assertTrue(d.mean() == Double.NEGATIVE_INFINITY, "the mean was ${d.mean()}")
    }

    @Test
    fun aMetalogWithAFiniteMeanButNoVarianceReportsBothHonestly() {
        val d = Metalog2P(0.0, 0.6, lowerBound = 0.0)
        assertTrue(d.mean().isFinite(), "the mean should exist, but was ${d.mean()}")
        assertTrue(d.variance() == Double.POSITIVE_INFINITY, "the variance was ${d.variance()}")
    }

    @Test
    fun reliabilityIsReportedForNumericallyObtainedMoments() {
        // Closed form, so exact.
        assertTrue(Metalog3P(0.0, 1.0, 0.2).momentsAreReliable())
        // A light tail integrates accurately.
        assertTrue(Metalog2P(0.0, 0.1, lowerBound = 0.0).momentsAreReliable())
        // A heavy tail does not, even though the variance exists.
        assertFalse(Metalog2P(0.0, 0.45, lowerBound = 0.0).momentsAreReliable())
    }

    @Test
    fun theSkewnessOfASymmetricMetalogIsZero() {
        val d = Metalog4P(3.0, 1.0, 0.0, 0.5)
        assertNearly(0.0, d.skewness, relTol = 1e-5, absTol = 1e-5)
    }

    @Test
    fun aRightSkewedMetalogHasPositiveSkewness() {
        val right = Metalog3P(0.0, 1.0, 1.0)
        val left = Metalog3P(0.0, 1.0, -1.0)
        assertTrue(right.skewness > 0.05, "the skewness was ${right.skewness}")
        assertTrue(left.skewness < -0.05, "the skewness was ${left.skewness}")
        assertNearly(right.skewness, -left.skewness, relTol = 1e-5, absTol = 1e-5)
    }

    // -------- factory, instance, and description --------

    @Test
    fun theFactoryDispatchesOnTheNumberOfCoefficients() {
        assertTrue(MetalogDistribution.create(doubleArrayOf(0.0, 1.0)) is Metalog2P)
        assertTrue(MetalogDistribution.create(doubleArrayOf(0.0, 1.0, 0.1)) is Metalog3P)
        assertTrue(MetalogDistribution.create(doubleArrayOf(0.0, 1.0, 0.1, 0.2)) is Metalog4P)
        assertTrue(MetalogDistribution.create(doubleArrayOf(0.0, 1.0, 0.1, 0.2, 0.0)) is Metalog5P)
        assertTrue(MetalogDistribution.create(doubleArrayOf(0.0, 1.0, 0.1, 0.2, 0.0, 0.0)) is Metalog6P)
    }

    @Test
    fun theFactoryCarriesTheBoundsThrough() {
        val d = MetalogDistribution.create(doubleArrayOf(0.0, 0.5, 0.1), lowerBound = 2.0)
        assertEquals(MetalogBoundedness.LowerBounded, d.boundedness)
        assertNearly(2.0, d.lowerBound)
    }

    @Test
    fun theFactoryRejectsAnArityWithNoRegisteredClass() {
        assertFailsWith<IllegalArgumentException>("seven terms") {
            MetalogDistribution.create(DoubleArray(7) { if (it == 1) 1.0 else 0.0 })
        }
        assertFailsWith<IllegalArgumentException>("one term") {
            MetalogDistribution.create(doubleArrayOf(1.0))
        }
    }

    @Test
    fun anInstanceIsIndependentOfItsSource() {
        val original = Metalog3P(1.0, 2.0, 0.3, lowerBound = 0.0, name = "source")
        val copy = original.instance()
        assertNearly(original.invCDF(0.4), copy.invCDF(0.4))
        copy.a1 = 9.0
        assertNearly(1.0, original.a1)
        assertNearly(9.0, copy.a1)
    }

    @Test
    fun theDescriptionNamesEveryCoefficientAndBothBounds() {
        val text = Metalog3P(1.0, 2.0, 0.3, lowerBound = 0.5).toString()
        assertTrue(text.contains("Metalog3P"), text)
        assertTrue(text.contains("a1=1.0"), text)
        assertTrue(text.contains("a2=2.0"), text)
        assertTrue(text.contains("a3=0.3"), text)
        assertTrue(text.contains("lowerBound=0.5"), text)
        assertTrue(text.contains("upperBound=Infinity"), text)
    }

    @Test
    fun feasibilityMarginIsReportable() {
        val comfortable = Metalog3P(0.0, 1.0, 0.0).feasibility()
        val marginal = Metalog3P(0.0, 1.0, 1.66).feasibility()
        assertTrue(comfortable.feasible)
        assertTrue(marginal.feasible)
        assertTrue(
            marginal.minimumDerivative < comfortable.minimumDerivative,
            "the marginal fit should have less margin",
        )
    }

    // -------- random variate generation --------

    @Test
    fun theRandomVariableSamplesTheDistribution()
    {
        val d = Metalog3P(10.0, 2.0, 0.5)
        val rv = d.randomVariable(streamNumber = 3)
        val n = 20_000
        val sample = DoubleArray(n) { rv.value }
        sample.sort()
        // Compare the empirical distribution against the fitted one at several quantiles.
        for (p in doubleArrayOf(0.1, 0.25, 0.5, 0.75, 0.9)) {
            val empirical = sample[(p * n).toInt()]
            assertNearly(
                d.invCDF(p), empirical, relTol = 0.05, absTol = 0.3,
                message = "at p = $p:",
            )
        }
    }

    @Test
    fun theRandomVariableRespectsBounds() {
        val d = Metalog3P(0.0, 0.5, 0.1, lowerBound = 3.0, upperBound = 8.0)
        val rv = d.randomVariable(streamNumber = 5)
        repeat(5_000) {
            val x = rv.value
            assertTrue((x > 3.0) && (x < 8.0), "the variate $x left the bounds")
        }
    }

    // -------- reproduction of published accuracy figures --------

    @Test
    fun lowerBoundedFitsReproduceKeelinTableSixForTheLogNormal() {
        // Keelin (2016) Table 6 reports the Kolmogorov-Smirnov distance between a log metalog and
        // a log-normal source with a log scale of one half, fitted to the 105 cumulative
        // distribution function points listed in section 5. The published values are 0.035, 0.035,
        // 0.006 and 0.006 for two through five terms. Reproducing them exercises the basis
        // functions, the lower bounded transform and the least squares solve together against an
        // independent published result.
        val standardNormal = Normal(0.0, 1.0)
        val logScale = 0.5
        val probabilities = keelinProbabilities()
        val values = DoubleArray(probabilities.size) {
            exp(logScale * standardNormal.invCDF(probabilities[it]))
        }
        val expected = mapOf(2 to 0.035, 3 to 0.035, 4 to 0.006, 5 to 0.006)
        for ((numTerms, published) in expected) {
            val a = fitCoefficients(
                values, probabilities, numTerms,
                MetalogBoundedness.LowerBounded, lowerBound = 0.0
            )
            assertTrue(
                MetalogFeasibilityChecker.defaultChecker.isFeasible(a),
                "the $numTerms term fit was infeasible: ${a.joinToString()}",
            )
            val fitted = MetalogDistribution.create(a, lowerBound = 0.0)
            var distance = 0.0
            for (i in probabilities.indices) {
                val x = fitted.invCDF(probabilities[i])
                val sourceProbability = standardNormal.cdf(ln(x) / logScale)
                distance = max(distance, abs(sourceProbability - probabilities[i]))
            }
            // Published to three decimals, so compare at that resolution.
            assertNearly(
                published, distance, relTol = 0.0, absTol = 5e-4,
                message = "$numTerms terms, Keelin Table 6:",
            )
        }
    }

    @Test
    fun anExactFitReproducesItsOwnDataPoints() {
        // Five terms through five points is a square system, so the fit must be exact.
        val probabilities = doubleArrayOf(0.05, 0.25, 0.5, 0.75, 0.95)
        val values = doubleArrayOf(-2.0, -0.6, 0.0, 0.7, 2.1)
        val a = fitCoefficients(values, probabilities, 5)
        // Reference coefficients from an independent implementation.
        val reference = doubleArrayOf(
            0.0, 0.9100904483018099, -0.25739931871162386, -1.3993462012153528, 1.9311282185255625,
        )
        for (i in reference.indices) {
            assertNearly(reference[i], a[i], relTol = 1e-8, absTol = 1e-8, message = "a${i + 1}:")
        }
        val fitted = MetalogDistribution.create(a)
        for (i in probabilities.indices) {
            assertNearly(
                values[i], fitted.invCDF(probabilities[i]), relTol = 1e-8, absTol = 1e-8,
                message = "at p = ${probabilities[i]}:",
            )
        }
    }

    /** The 105 cumulative probabilities Keelin uses in section 5. */
    private fun keelinProbabilities(): DoubleArray {
        val list = mutableListOf(0.001, 0.003, 0.006)
        var tenth = 10
        while (tenth <= 990) {
            list.add(tenth / 1000.0)
            tenth += 10
        }
        list.addAll(listOf(0.994, 0.997, 0.999))
        return list.toDoubleArray()
    }
}
