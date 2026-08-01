package ksl.utilities.distributions

import ksl.utilities.distributions.metalog.MetalogBoundedness
import ksl.utilities.distributions.metalog.MetalogFeasibilityChecker
import ksl.utilities.distributions.metalog.MetalogFunctions
import ksl.utilities.distributions.metalog.MetalogMoments
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetalogMomentsTest {

    private fun assertNearly(
        expected: Double,
        actual: Double,
        relTol: Double = 1e-8,
        absTol: Double = 1e-8,
        message: String = "",
    ) {
        val diff = abs(expected - actual)
        val threshold = max(absTol, relTol * max(1.0, abs(expected)))
        assertTrue(
            diff <= threshold,
            "$message Expected $expected, actual $actual, diff $diff, threshold $threshold",
        )
    }

    /** The reference five-term fit used throughout the plan's golden values. */
    private val fiveTerm = doubleArrayOf(
        0.0,
        0.9100904483018099,
        -0.25739931871162386,
        -1.3993462012153528,
        1.9311282185255625,
    )

    // -------- closed forms --------

    @Test
    fun closedFormMomentsMatchTheReferenceValues() {
        assertNearly(0.03222769218798491, MetalogMoments.unboundedMean(fiveTerm))
        assertNearly(1.6175113199588393, MetalogMoments.unboundedVariance(fiveTerm))
    }

    @Test
    fun twoTermClosedFormsAreTheLogisticMoments() {
        // Two terms with infinite bounds is a logistic with location a1 and scale a2, whose
        // mean is the location and whose variance is the scale squared times pi squared over 3.
        val location = 7.0
        val scale = 2.5
        val a = doubleArrayOf(location, scale)
        assertNearly(location, MetalogMoments.unboundedMean(a))
        assertNearly(scale * scale * PI * PI / 3.0, MetalogMoments.unboundedVariance(a))
    }

    @Test
    fun theClosedFormsAgreeWithTheExistingLogisticDistribution() {
        val logistic = Logistic(location = -3.0, scale = 0.75)
        val a = doubleArrayOf(-3.0, 0.75)
        assertNearly(logistic.mean(), MetalogMoments.unboundedMean(a))
        assertNearly(logistic.variance(), MetalogMoments.unboundedVariance(a))
    }

    @Test
    fun fourTermUniformClosedFormsAreTheUniformMoments() {
        // With only the first and fourth coefficients nonzero the metalog is exactly uniform on
        // the interval of width a4 centered at a1.
        val center = 10.0
        val width = 4.0
        val a = doubleArrayOf(center, 0.0, 0.0, width)
        val uniform = Uniform(center - width / 2.0, center + width / 2.0)
        assertNearly(uniform.mean(), MetalogMoments.unboundedMean(a))
        assertNearly(uniform.variance(), MetalogMoments.unboundedVariance(a))
    }

    @Test
    fun paddingMakesFewerTermsAgreeWithMoreTermsThatAreZero() {
        val threeTerm = doubleArrayOf(1.0, 2.0, 0.5)
        val padded = doubleArrayOf(1.0, 2.0, 0.5, 0.0, 0.0)
        assertNearly(MetalogMoments.unboundedMean(threeTerm), MetalogMoments.unboundedMean(padded))
        assertNearly(
            MetalogMoments.unboundedVariance(threeTerm),
            MetalogMoments.unboundedVariance(padded),
        )
    }

    // -------- closed form versus quadrature --------

    @Test
    fun quadratureAgreesWithTheClosedFormMean() {
        val quadrature = MetalogMoments.rawMomentByQuadrature(1) { y ->
            MetalogFunctions.quantile(fiveTerm, y)
        }
        assertNearly(
            MetalogMoments.unboundedMean(fiveTerm), quadrature,
            relTol = 1e-6, absTol = 1e-6,
        )
    }

    @Test
    fun quadratureAgreesWithTheClosedFormVariance() {
        val mean = MetalogMoments.unboundedMean(fiveTerm)
        val quadrature = MetalogMoments.centralMomentByQuadrature(2, mean) { y ->
            MetalogFunctions.quantile(fiveTerm, y)
        }
        assertNearly(
            MetalogMoments.unboundedVariance(fiveTerm), quadrature,
            relTol = 1e-6, absTol = 1e-6,
        )
    }

    @Test
    fun quadratureAgreesWithTheClosedFormAcrossSeveralCoefficientVectors() {
        val cases = listOf(
            doubleArrayOf(0.0, 1.0),
            doubleArrayOf(5.0, 2.0),
            doubleArrayOf(0.0, 1.0, 0.5),
            doubleArrayOf(-2.0, 1.5, -0.8),
            doubleArrayOf(3.0, 1.0, 0.2, 0.6),
            doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.3),
        )
        for (a in cases) {
            val label = a.joinToString(prefix = "a = (", postfix = ")")
            assertTrue(
                MetalogFeasibilityChecker.defaultChecker.isFeasible(a),
                "$label should be feasible for this test to be meaningful",
            )
            val mean = MetalogMoments.rawMomentByQuadrature(1) { y ->
                MetalogFunctions.quantile(a, y)
            }
            assertNearly(
                MetalogMoments.unboundedMean(a), mean,
                relTol = 1e-6, absTol = 1e-6, message = "$label mean:",
            )
            val variance = MetalogMoments.centralMomentByQuadrature(2, mean) { y ->
                MetalogFunctions.quantile(a, y)
            }
            assertNearly(
                MetalogMoments.unboundedVariance(a), variance,
                relTol = 1e-6, absTol = 1e-6, message = "$label variance:",
            )
        }
    }

    // -------- quadrature on transformed members, where no closed form exists --------

    @Test
    fun quadratureRecoversTheMeanOfALogLogistic() {
        // With a lower bound of zero and two terms, the metalog is a log-logistic whose mean is
        // known in closed form as pi*s / sin(pi*s), where s is the second coefficient. The upper
        // tail behaves like a power law with exponent s, so smaller s is better conditioned for
        // an integral truncated near the endpoint.
        for (s in doubleArrayOf(0.1, 0.2, 0.3)) {
            val a = doubleArrayOf(0.0, s)
            assertTrue(
                MetalogMoments.momentExists(1, a, MetalogBoundedness.LowerBounded),
                "the mean should exist for a2 = $s",
            )
            val expected = PI * s / kotlin.math.sin(PI * s)
            val mean = MetalogMoments.rawMomentByQuadrature(1) { y ->
                MetalogBoundedness.LowerBounded.fromFittingSpace(
                    MetalogFunctions.quantile(a, y), 0.0, Double.POSITIVE_INFINITY
                )
            }
            assertNearly(expected, mean, relTol = 1e-5, absTol = 1e-5, message = "a2 = $s:")
        }
    }

    @Test
    fun quadratureLosesAccuracyApproachingTheExistenceBoundary() {
        // Documents the degradation rather than hiding it. At a2 = 0.5 the mean still exists but
        // the upper tail decays only as the inverse square root, so the truncated integral is
        // accurate to roughly 1e-4 rather than to 1e-6.
        val s = 0.5
        val a = doubleArrayOf(0.0, s)
        assertTrue(MetalogMoments.momentExists(1, a, MetalogBoundedness.LowerBounded))
        val expected = PI * s / kotlin.math.sin(PI * s)
        val mean = MetalogMoments.rawMomentByQuadrature(1) { y ->
            MetalogBoundedness.LowerBounded.fromFittingSpace(
                MetalogFunctions.quantile(a, y), 0.0, Double.POSITIVE_INFINITY
            )
        }
        assertNearly(expected, mean, relTol = 1e-4, absTol = 1e-4)
        assertTrue(
            abs(expected - mean) > 1e-8,
            "the truncation error was expected to be visible, but was ${abs(expected - mean)}",
        )
    }

    @Test
    fun theLogitParameterizedPathIsFarMoreAccurateThanTheProbabilityPath() {
        // Same integral, same distribution, two routes. Passing probabilities as doubles cannot
        // reach beyond about two parts in a billion from each endpoint, because the complement of
        // a probability near one is quantized; parameterizing by the logit has no such limit.
        val s = 0.5
        val a = doubleArrayOf(0.0, s)
        val expected = PI * s / kotlin.math.sin(PI * s)
        val viaProbability = MetalogMoments.rawMomentByQuadrature(1) { y ->
            MetalogBoundedness.LowerBounded.fromFittingSpace(
                MetalogFunctions.quantile(a, y), 0.0, Double.POSITIVE_INFINITY
            )
        }
        val viaLogit = MetalogMoments.rawMomentInLogit(1) { t ->
            MetalogBoundedness.LowerBounded.fromFittingSpace(
                MetalogFunctions.quantileFromLogit(a, t), 0.0, Double.POSITIVE_INFINITY
            )
        }
        val probabilityError = abs(expected - viaProbability)
        val logitError = abs(expected - viaLogit)
        assertTrue(
            logitError < probabilityError,
            "the logit path should be more accurate: logit error $logitError, " +
                    "probability error $probabilityError",
        )
        assertNearly(expected, viaLogit, relTol = 1e-6, absTol = 1e-6)
    }

    @Test
    fun theTwoQuantileParameterizationsAgreeAwayFromTheTails() {
        // The logit-parameterized quantile function must be the same function, just reached
        // differently, wherever the probability is representable enough to recover its logit.
        val a = doubleArrayOf(1.5, 2.0, -0.3, 0.4, 0.1)
        for (t in doubleArrayOf(-8.0, -3.0, -0.5, 0.0, 0.5, 3.0, 8.0)) {
            val y = 1.0 / (1.0 + kotlin.math.exp(-t))
            assertNearly(
                MetalogFunctions.quantile(a, y),
                MetalogFunctions.quantileFromLogit(a, t),
                relTol = 1e-9, absTol = 1e-9, message = "at logit $t:",
            )
        }
    }

    @Test
    fun theProbabilityDerivativeFromTheLogitIsTheProductOfProbabilityAndComplement() {
        for (t in doubleArrayOf(-6.0, -1.0, 0.0, 1.0, 6.0)) {
            val y = 1.0 / (1.0 + kotlin.math.exp(-t))
            assertNearly(
                y * (1.0 - y),
                MetalogFunctions.probabilityDerivativeFromLogit(t),
                relTol = 1e-12, absTol = 1e-14, message = "at logit $t:",
            )
        }
        assertNearly(0.25, MetalogFunctions.probabilityDerivativeFromLogit(0.0))
    }

    // -------- moment existence --------

    @Test
    fun everyMomentOfAnUnboundedOrBoundedMetalogExists() {
        val a = doubleArrayOf(0.0, 3.0, 0.5)
        for (j in 1..6) {
            assertTrue(MetalogMoments.momentExists(j, a, MetalogBoundedness.Unbounded), "unbounded j = $j")
            assertTrue(MetalogMoments.momentExists(j, a, MetalogBoundedness.Bounded), "bounded j = $j")
        }
    }

    @Test
    fun aLowerBoundedMetalogCanHaveAMeanButNoVariance() {
        // The log-logistic with a2 = 0.5: the mean is finite, the second moment is not, since the
        // second moment integral needs twice the tail weight to stay below one.
        val a = doubleArrayOf(0.0, 0.5)
        assertTrue(MetalogMoments.momentExists(1, a, MetalogBoundedness.LowerBounded))
        assertFalse(MetalogMoments.momentExists(2, a, MetalogBoundedness.LowerBounded))
    }

    @Test
    fun aLowerBoundedMetalogWithAHeavyUpperTailHasNoMean() {
        val a = doubleArrayOf(0.0, 1.5)
        assertFalse(MetalogMoments.momentExists(1, a, MetalogBoundedness.LowerBounded))
        // The unbounded member with the same coefficients has every moment.
        assertTrue(MetalogMoments.momentExists(1, a, MetalogBoundedness.Unbounded))
    }

    @Test
    fun theUpperBoundedMemberMirrorsTheLowerBoundedOne() {
        val a = doubleArrayOf(0.0, 0.5)
        assertTrue(MetalogMoments.momentExists(1, a, MetalogBoundedness.UpperBounded))
        assertFalse(MetalogMoments.momentExists(2, a, MetalogBoundedness.UpperBounded))
    }

    @Test
    fun tailWeightsAreTheLogitCoefficientsAtEachEnd() {
        // Only the terms that multiply the logit contribute: the second, the third, and every
        // even term from the sixth onward.
        val a = doubleArrayOf(9.0, 2.0, 0.4, 7.0, 8.0, 0.8)
        assertNearly(2.0 + 0.4 * 0.5 + 0.8 * 0.25, MetalogMoments.upperTailLogitWeight(a))
        assertNearly(2.0 - 0.4 * 0.5 + 0.8 * 0.25, MetalogMoments.lowerTailLogitWeight(a))
    }

    @Test
    fun tailWeightsAreSymmetricWhenTheSkewnessTermIsZero() {
        val a = doubleArrayOf(0.0, 1.0, 0.0, 5.0)
        assertNearly(
            MetalogMoments.upperTailLogitWeight(a),
            MetalogMoments.lowerTailLogitWeight(a),
        )
    }

    @Test
    fun aTwoTermTailWeightIsJustTheScale() {
        val a = doubleArrayOf(4.0, 1.25)
        assertNearly(1.25, MetalogMoments.upperTailLogitWeight(a))
        assertNearly(1.25, MetalogMoments.lowerTailLogitWeight(a))
    }

    @Test
    fun theTailWeightPredictsTheObservedGrowthOfTheQuantileFunction() {
        // A direct check that the weight really is the power-law exponent: the ratio of the
        // quantile at two probabilities approaching one should scale by the weight.
        val a = doubleArrayOf(0.0, 0.3, 0.1)
        val weight = MetalogMoments.upperTailLogitWeight(a)
        val quantileAt = { y: Double ->
            MetalogBoundedness.LowerBounded.fromFittingSpace(
                MetalogFunctions.quantile(a, y), 0.0, Double.POSITIVE_INFINITY
            )
        }
        val near = quantileAt(1.0 - 1.0e-8)
        val nearer = quantileAt(1.0 - 1.0e-10)
        // Shrinking the distance to one by a factor of a hundred should multiply the quantile by
        // a hundred raised to the tail weight.
        val observed = kotlin.math.ln(nearer / near) / kotlin.math.ln(100.0)
        assertNearly(weight, observed, relTol = 1e-3, absTol = 1e-3)
    }

    @Test
    fun momentExistenceValidatesTheOrder() {
        assertFailsWith<IllegalArgumentException> {
            MetalogMoments.momentExists(0, doubleArrayOf(0.0, 1.0), MetalogBoundedness.Unbounded)
        }
    }

    @Test
    fun quadratureRecoversTheMeanOfABoundedUniform() {
        // A two-term metalog on the unit interval with a2 = 1 is exactly uniform, so its mean is
        // one half and its variance one twelfth.
        val a = doubleArrayOf(0.0, 1.0)
        val quantile: (Double) -> Double = { y ->
            MetalogBoundedness.Bounded.fromFittingSpace(
                MetalogFunctions.quantile(a, y), 0.0, 1.0
            )
        }
        val mean = MetalogMoments.rawMomentByQuadrature(1, quantileFunction = quantile)
        assertNearly(0.5, mean, relTol = 1e-7, absTol = 1e-7)
        val variance = MetalogMoments.centralMomentByQuadrature(2, mean, quantileFunction = quantile)
        assertNearly(1.0 / 12.0, variance, relTol = 1e-6, absTol = 1e-6)
    }

    @Test
    fun quadratureIntegratesAConstantQuantileToThatConstant() {
        // A degenerate check on the quadrature machinery itself: the segments must partition the
        // unit interval exactly, so integrating a constant must return that constant.
        assertNearly(3.0, MetalogMoments.rawMomentByQuadrature(1) { 3.0 }, relTol = 1e-7, absTol = 1e-7)
    }

    @Test
    fun quadratureIntegratesTheIdentityQuantileToOneHalf() {
        // The quantile function of a standard uniform is the identity, whose first moment is
        // one half and whose second moment about the origin is one third.
        assertNearly(0.5, MetalogMoments.rawMomentByQuadrature(1) { y -> y }, relTol = 1e-7, absTol = 1e-7)
        assertNearly(
            1.0 / 3.0, MetalogMoments.rawMomentByQuadrature(2) { y -> y },
            relTol = 1e-7, absTol = 1e-7,
        )
    }

    @Test
    fun quadratureRecoversAnExponentialMean() {
        // The quantile function of an exponential with mean two is -2 ln(1-y). This exercises a
        // logarithmic divergence at the upper endpoint, the situation the segmentation exists for.
        val mean = MetalogMoments.rawMomentByQuadrature(1) { y -> -2.0 * kotlin.math.ln(1.0 - y) }
        assertNearly(2.0, mean, relTol = 1e-6, absTol = 1e-6)
    }

    // -------- closed-form availability --------

    @Test
    fun onlyTheUnboundedMemberWithFiveOrFewerTermsHasAClosedForm() {
        for (terms in 2..5) {
            assertTrue(MetalogMoments.hasClosedForm(terms, MetalogBoundedness.Unbounded), "$terms terms")
        }
        assertFalse(MetalogMoments.hasClosedForm(6, MetalogBoundedness.Unbounded))
        for (boundedness in listOf(
            MetalogBoundedness.LowerBounded,
            MetalogBoundedness.UpperBounded,
            MetalogBoundedness.Bounded,
        )) {
            assertFalse(MetalogMoments.hasClosedForm(3, boundedness), boundedness.name)
        }
    }

    // -------- argument validation --------

    @Test
    fun theClosedFormsRejectAritiesTheyDoNotCover() {
        assertFailsWith<IllegalArgumentException>("six terms") {
            MetalogMoments.unboundedMean(DoubleArray(6))
        }
        assertFailsWith<IllegalArgumentException>("one term") {
            MetalogMoments.unboundedMean(doubleArrayOf(1.0))
        }
        assertFailsWith<IllegalArgumentException>("six terms") {
            MetalogMoments.unboundedVariance(DoubleArray(6))
        }
    }

    @Test
    fun momentOrdersAreValidated() {
        assertFailsWith<IllegalArgumentException>("raw moment of order zero") {
            MetalogMoments.rawMomentByQuadrature(0) { 1.0 }
        }
        assertFailsWith<IllegalArgumentException>("central moment of order one") {
            MetalogMoments.centralMomentByQuadrature(1, 0.0) { 1.0 }
        }
    }

    @Test
    fun endpointToleranceIsValidated() {
        assertFailsWith<IllegalArgumentException>("non-positive tolerance") {
            MetalogMoments.rawMomentByQuadrature(1, endpointTolerance = 0.0) { y -> y }
        }
        assertFailsWith<IllegalArgumentException>("tolerance too large") {
            MetalogMoments.rawMomentByQuadrature(1, endpointTolerance = 0.5) { y -> y }
        }
    }

    // -------- boundedness transforms, exercised here because moments depend on them --------

    @Test
    fun theBoundednessTransformsInvertOneAnother() {
        val lower = 2.0
        val upper = 9.0
        for (x in doubleArrayOf(2.5, 4.0, 6.5, 8.9)) {
            val bounded = MetalogBoundedness.Bounded
            val z = bounded.toFittingSpace(x, lower, upper)
            assertNearly(x, bounded.fromFittingSpace(z, lower, upper), message = "bounded at $x:")
        }
        for (x in doubleArrayOf(2.5, 4.0, 100.0)) {
            val lowerBounded = MetalogBoundedness.LowerBounded
            val z = lowerBounded.toFittingSpace(x, lower, Double.POSITIVE_INFINITY)
            assertNearly(
                x, lowerBounded.fromFittingSpace(z, lower, Double.POSITIVE_INFINITY),
                message = "lower bounded at $x:",
            )
        }
        for (x in doubleArrayOf(-100.0, 0.0, 8.9)) {
            val upperBounded = MetalogBoundedness.UpperBounded
            val z = upperBounded.toFittingSpace(x, Double.NEGATIVE_INFINITY, upper)
            assertNearly(
                x, upperBounded.fromFittingSpace(z, Double.NEGATIVE_INFINITY, upper),
                message = "upper bounded at $x:",
            )
        }
    }

    @Test
    fun boundednessIsResolvedFromWhichBoundsAreFinite() {
        assertTrue(
            MetalogBoundedness.of(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY) ==
                    MetalogBoundedness.Unbounded
        )
        assertTrue(
            MetalogBoundedness.of(0.0, Double.POSITIVE_INFINITY) == MetalogBoundedness.LowerBounded
        )
        assertTrue(
            MetalogBoundedness.of(Double.NEGATIVE_INFINITY, 1.0) == MetalogBoundedness.UpperBounded
        )
        assertTrue(MetalogBoundedness.of(0.0, 1.0) == MetalogBoundedness.Bounded)
    }

    @Test
    fun boundsAreValidatedWhenResolvingBoundedness() {
        assertFailsWith<IllegalArgumentException>("NaN lower bound") {
            MetalogBoundedness.of(Double.NaN, 1.0)
        }
        assertFailsWith<IllegalArgumentException>("NaN upper bound") {
            MetalogBoundedness.of(0.0, Double.NaN)
        }
        assertFailsWith<IllegalArgumentException>("reversed bounds") {
            MetalogBoundedness.of(1.0, 0.0)
        }
        assertFailsWith<IllegalArgumentException>("equal bounds") {
            MetalogBoundedness.of(1.0, 1.0)
        }
    }

    @Test
    fun theBoundedTransformSaturatesRatherThanOverflowing() {
        val bounded = MetalogBoundedness.Bounded
        assertNearly(1.0, bounded.fromFittingSpace(2000.0, 0.0, 1.0))
        assertNearly(0.0, bounded.fromFittingSpace(-2000.0, 0.0, 1.0))
        assertTrue(bounded.fromFittingSpace(2000.0, 0.0, 1.0).isFinite())
        assertTrue(bounded.fromFittingSpace(-2000.0, 0.0, 1.0).isFinite())
    }

    @Test
    fun transformsRejectValuesOutsideTheirBounds() {
        assertFailsWith<IllegalArgumentException>("at the lower bound") {
            MetalogBoundedness.LowerBounded.toFittingSpace(0.0, 0.0, Double.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException>("at the upper bound") {
            MetalogBoundedness.UpperBounded.toFittingSpace(1.0, Double.NEGATIVE_INFINITY, 1.0)
        }
        assertFailsWith<IllegalArgumentException>("below the lower bound") {
            MetalogBoundedness.Bounded.toFittingSpace(-0.1, 0.0, 1.0)
        }
        assertFailsWith<IllegalArgumentException>("above the upper bound") {
            MetalogBoundedness.Bounded.toFittingSpace(1.1, 0.0, 1.0)
        }
    }

    @Test
    fun densityFactorsAreStrictlyPositive() {
        // This is what makes feasibility of the underlying metalog sufficient for every member.
        for (z in doubleArrayOf(-20.0, -1.0, 0.0, 1.0, 20.0)) {
            assertTrue(
                MetalogBoundedness.Unbounded.densityFactor(z, 0.0, 1.0) > 0.0, "unbounded at $z"
            )
            assertTrue(
                MetalogBoundedness.LowerBounded.densityFactor(z, 0.0, Double.POSITIVE_INFINITY) > 0.0,
                "lower bounded at $z",
            )
            assertTrue(
                MetalogBoundedness.UpperBounded.densityFactor(z, Double.NEGATIVE_INFINITY, 1.0) > 0.0,
                "upper bounded at $z",
            )
            assertTrue(
                MetalogBoundedness.Bounded.densityFactor(z, 0.0, 1.0) > 0.0, "bounded at $z"
            )
        }
    }

    @Test
    fun boundFlagsMatchTheMember() {
        assertFalse(MetalogBoundedness.Unbounded.hasLowerBound)
        assertFalse(MetalogBoundedness.Unbounded.hasUpperBound)
        assertTrue(MetalogBoundedness.LowerBounded.hasLowerBound)
        assertFalse(MetalogBoundedness.LowerBounded.hasUpperBound)
        assertFalse(MetalogBoundedness.UpperBounded.hasLowerBound)
        assertTrue(MetalogBoundedness.UpperBounded.hasUpperBound)
        assertTrue(MetalogBoundedness.Bounded.hasLowerBound)
        assertTrue(MetalogBoundedness.Bounded.hasUpperBound)
    }

    @Test
    fun theLowerBoundedTransformIsAnExponentialShift() {
        // Sanity anchor on the transform itself rather than on a round trip.
        assertNearly(
            5.0 + exp(1.5),
            MetalogBoundedness.LowerBounded.fromFittingSpace(1.5, 5.0, Double.POSITIVE_INFINITY),
        )
    }
}
