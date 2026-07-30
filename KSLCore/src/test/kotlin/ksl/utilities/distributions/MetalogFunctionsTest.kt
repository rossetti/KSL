package ksl.utilities.distributions

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  Golden values in this suite were produced by an independent reference implementation and
 *  cross-checked against Keelin (2016) and against the pymetalog package's recursions.
 */
class MetalogFunctionsTest {

    private fun assertNearly(
        expected: Double,
        actual: Double,
        relTol: Double = 1e-12,
        absTol: Double = 1e-12,
        message: String = "",
    ) {
        val diff = abs(expected - actual)
        val threshold = max(absTol, relTol * max(1.0, abs(expected)))
        assertTrue(
            diff <= threshold,
            "$message Expected $expected, actual $actual, diff $diff, threshold $threshold",
        )
    }

    // -------- basis terms --------

    @Test
    fun basisTermsAtOneQuarter() {
        // Terms 1..7 evaluated at y = 0.25, where L = ln(1/3) and c = -0.25
        val expected = doubleArrayOf(
            1.0,
            -1.0986122886681098,
            0.27465307216702745,
            -0.25,
            0.0625,
            -0.06866326804175686,
            -0.015625,
        )
        for (i in expected.indices) {
            assertNearly(expected[i], MetalogFunctions.basisTerm(i + 1, 0.25), message = "term ${i + 1}:")
        }
    }

    @Test
    fun basisTermDerivativesAtOneQuarter() {
        val expected = doubleArrayOf(
            0.0,
            5.333333333333333,
            -2.431945622001443,
            1.0,
            -0.5,
            0.8826394776673883,
            0.1875,
        )
        for (i in expected.indices) {
            assertNearly(
                expected[i],
                MetalogFunctions.basisTermDerivative(i + 1, 0.25),
                message = "derivative of term ${i + 1}:",
            )
        }
    }

    @Test
    fun basisTermsMatchTheirDefinitionsAtTheMedian() {
        // At y = 0.5 the logit is zero and c is zero, so only terms 1 and 4 survive.
        assertNearly(1.0, MetalogFunctions.basisTerm(1, 0.5))
        assertNearly(0.0, MetalogFunctions.basisTerm(2, 0.5))
        assertNearly(0.0, MetalogFunctions.basisTerm(3, 0.5))
        assertNearly(0.0, MetalogFunctions.basisTerm(4, 0.5))
        for (i in 5..12) {
            assertNearly(0.0, MetalogFunctions.basisTerm(i, 0.5), message = "term $i:")
        }
    }

    @Test
    fun derivativesAtTheMedianAreFinite() {
        // The odd terms above four involve c raised to one less than their power, which is c
        // itself for term five. Nothing should produce a zero-to-the-zero indeterminate form.
        for (i in 1..12) {
            val d = MetalogFunctions.basisTermDerivative(i, 0.5)
            assertTrue(d.isFinite(), "derivative of term $i at the median was $d")
        }
    }

    @Test
    fun basisTermDerivativesAgreeWithFiniteDifferences() {
        val h = 1.0e-7
        for (y in doubleArrayOf(0.05, 0.2, 0.35, 0.5, 0.65, 0.8, 0.95)) {
            for (i in 1..10) {
                val analytic = MetalogFunctions.basisTermDerivative(i, y)
                val numeric = (MetalogFunctions.basisTerm(i, y + h) -
                        MetalogFunctions.basisTerm(i, y - h)) / (2.0 * h)
                assertNearly(
                    numeric, analytic, relTol = 1e-5, absTol = 1e-5,
                    message = "term $i at y = $y:",
                )
            }
        }
    }

    // -------- quantile function and its derivative --------

    @Test
    fun quantileIsTheCoefficientWeightedSumOfBasisTerms() {
        val a = doubleArrayOf(1.5, 2.0, -0.3, 0.4, 0.1, -0.2, 0.05)
        for (y in doubleArrayOf(0.01, 0.25, 0.5, 0.75, 0.99)) {
            var expected = 0.0
            for (i in a.indices) {
                expected += a[i] * MetalogFunctions.basisTerm(i + 1, y)
            }
            assertNearly(expected, MetalogFunctions.quantile(a, y), message = "at y = $y:")
        }
    }

    @Test
    fun quantileDerivativeIsTheCoefficientWeightedSumOfTermDerivatives() {
        val a = doubleArrayOf(1.5, 2.0, -0.3, 0.4, 0.1, -0.2, 0.05)
        for (y in doubleArrayOf(0.01, 0.25, 0.5, 0.75, 0.99)) {
            var expected = 0.0
            for (i in a.indices) {
                expected += a[i] * MetalogFunctions.basisTermDerivative(i + 1, y)
            }
            assertNearly(expected, MetalogFunctions.quantileDerivative(a, y), message = "at y = $y:")
        }
    }

    @Test
    fun twoTermMetalogIsTheLogisticQuantileFunction() {
        val location = 12.0
        val scale = 3.0
        val a = doubleArrayOf(location, scale)
        for (y in doubleArrayOf(0.001, 0.1, 0.5, 0.9, 0.999)) {
            val expected = location + scale * ln(y / (1.0 - y))
            assertNearly(expected, MetalogFunctions.quantile(a, y), message = "at y = $y:")
        }
    }

    @Test
    fun densityIsTheReciprocalOfTheQuantileDerivative() {
        val a = doubleArrayOf(0.0, 1.0, 0.3, 0.2)
        for (y in doubleArrayOf(0.05, 0.3, 0.5, 0.7, 0.95)) {
            val derivative = MetalogFunctions.quantileDerivative(a, y)
            assertNearly(1.0 / derivative, MetalogFunctions.density(a, y), message = "at y = $y:")
        }
    }

    @Test
    fun quantileMatchesSPTClosedFormFromKeelinProposition1() {
        // Symmetric percentile triplet (3, 10, 30) at alpha = 0.1
        val alpha = 0.1
        val qLower = 3.0
        val median = 10.0
        val qUpper = 30.0
        val r = (median - qLower) / (qUpper - qLower)
        val logRatio = ln((1.0 - alpha) / alpha)
        val a1 = median
        val a2 = 0.5 / logRatio * (qUpper - qLower)
        val a3 = (1.0 - 2.0 * alpha).let { 1.0 / it } / logRatio * (1.0 - 2.0 * r) * (qUpper - qLower)
        // Reference coefficients from the independent implementation
        assertNearly(10.0, a1)
        assertNearly(6.144114779731152, a2)
        assertNearly(7.395693716343115, a3)

        val a = doubleArrayOf(a1, a2, a3)
        // The triplet must be reproduced exactly, since three terms fit three points exactly.
        assertNearly(qLower, MetalogFunctions.quantile(a, alpha), relTol = 1e-10)
        assertNearly(median, MetalogFunctions.quantile(a, 0.5), relTol = 1e-10)
        assertNearly(qUpper, MetalogFunctions.quantile(a, 1.0 - alpha), relTol = 1e-10)
        // Reference interior values
        assertNearly(5.281249999999983, MetalogFunctions.quantile(a, 0.25), relTol = 1e-10)
        assertNearly(0.06764669956401198, MetalogFunctions.density(a, 0.25), relTol = 1e-10)
    }

    // -------- design matrix --------

    @Test
    fun designMatrixRowsHoldTheBasisTerms() {
        val probabilities = doubleArrayOf(0.1, 0.25, 0.5, 0.75, 0.9)
        val numTerms = 5
        val matrix = MetalogFunctions.designMatrix(probabilities, numTerms)
        assertEquals(probabilities.size, matrix.size)
        for (row in probabilities.indices) {
            assertEquals(numTerms, matrix[row].size)
            for (column in 0 until numTerms) {
                assertNearly(
                    MetalogFunctions.basisTerm(column + 1, probabilities[row]),
                    matrix[row][column],
                    message = "row $row column $column:",
                )
            }
        }
    }

    @Test
    fun designMatrixTimesCoefficientsReproducesTheQuantiles() {
        val probabilities = doubleArrayOf(0.05, 0.25, 0.5, 0.75, 0.95)
        val a = doubleArrayOf(0.0, 0.9100904483018099, -0.25739931871162386, -1.3993462012153528, 1.9311282185255625)
        val matrix = MetalogFunctions.designMatrix(probabilities, a.size)
        for (row in probabilities.indices) {
            var product = 0.0
            for (column in a.indices) {
                product += matrix[row][column] * a[column]
            }
            assertNearly(
                MetalogFunctions.quantile(a, probabilities[row]),
                product,
                relTol = 1e-10,
                message = "row $row:",
            )
        }
    }

    @Test
    fun exactInterpolationWhenTermsEqualDataPoints() {
        // Reference fit: five terms through five points, so the fit is exact.
        val probabilities = doubleArrayOf(0.05, 0.25, 0.5, 0.75, 0.95)
        val values = doubleArrayOf(-2.0, -0.6, 0.0, 0.7, 2.1)
        val a = doubleArrayOf(
            0.0,
            0.9100904483018099,
            -0.25739931871162386,
            -1.3993462012153528,
            1.9311282185255625,
        )
        for (i in probabilities.indices) {
            assertNearly(
                values[i],
                MetalogFunctions.quantile(a, probabilities[i]),
                relTol = 1e-9,
                absTol = 1e-9,
                message = "at y = ${probabilities[i]}:",
            )
        }
    }

    // -------- logit helper --------

    @Test
    fun logitIsSymmetricAboutTheMedian() {
        for (y in doubleArrayOf(0.001, 0.1, 0.3, 0.49)) {
            assertNearly(-MetalogFunctions.logit(y), MetalogFunctions.logit(1.0 - y), message = "at y = $y:")
        }
        assertNearly(0.0, MetalogFunctions.logit(0.5))
    }

    @Test
    fun logitInvertsTheLogisticFunction() {
        for (y in doubleArrayOf(0.01, 0.25, 0.5, 0.75, 0.99)) {
            val l = MetalogFunctions.logit(y)
            assertNearly(y, 1.0 / (1.0 + exp(-l)), message = "at y = $y:")
        }
    }

    // -------- argument validation --------

    @Test
    fun probabilitiesOutsideTheOpenUnitIntervalAreRejected() {
        val a = doubleArrayOf(0.0, 1.0)
        for (bad in doubleArrayOf(0.0, 1.0, -0.1, 1.1, Double.NaN)) {
            assertFailsWith<IllegalArgumentException>("probability $bad should be rejected") {
                MetalogFunctions.quantile(a, bad)
            }
            assertFailsWith<IllegalArgumentException>("probability $bad should be rejected") {
                MetalogFunctions.quantileDerivative(a, bad)
            }
        }
    }

    @Test
    fun fewerThanTwoCoefficientsIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            MetalogFunctions.quantile(doubleArrayOf(1.0), 0.5)
        }
        assertFailsWith<IllegalArgumentException> {
            MetalogFunctions.quantileDerivative(doubleArrayOf(1.0), 0.5)
        }
    }

    @Test
    fun designMatrixArgumentsAreValidated() {
        assertFailsWith<IllegalArgumentException>("too few terms") {
            MetalogFunctions.designMatrix(doubleArrayOf(0.5), 1)
        }
        assertFailsWith<IllegalArgumentException>("empty probabilities") {
            MetalogFunctions.designMatrix(doubleArrayOf(), 3)
        }
        assertFailsWith<IllegalArgumentException>("probability out of range") {
            MetalogFunctions.designMatrix(doubleArrayOf(0.5, 1.0), 3)
        }
    }

    @Test
    fun termNumbersBelowOneAreRejected() {
        assertFailsWith<IllegalArgumentException> { MetalogFunctions.basisTerm(0, 0.5) }
        assertFailsWith<IllegalArgumentException> { MetalogFunctions.basisTermDerivative(0, 0.5) }
    }
}
