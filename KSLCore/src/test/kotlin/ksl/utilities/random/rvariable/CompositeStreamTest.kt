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

package ksl.utilities.random.rvariable

import ksl.utilities.Interval
import ksl.utilities.distributions.Exponential
import ksl.utilities.distributions.Uniform
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  A variable built from component variables draws from its own one stream, and its components draw from it in
 *  turn. So its antithetic instance pairs with it coordinate by coordinate, its coordinates stay independent, and
 *  its stream controls govern what it samples. Before R1.8 the components obtained their own streams by number:
 *  on the default stream those were other streams, and in an antithetic instance each component had its own copy,
 *  so the components repeated one uniform.
 */
class CompositeStreamTest {

    private val correlation = arrayOf(doubleArrayOf(1.0, 0.5), doubleArrayOf(0.5, 1.0))

    /** Every multivariate composite, by name, built on a stream number of a provider. */
    private val multivariate: Map<String, (Int, RNStreamProviderIfc) -> MVRVariableIfc> = mapOf(
        "MVIndependentMarginals" to { s, p -> MVIndependentMarginals(listOf(UniformRV(0.0, 1.0), ExponentialRV(1.0)), s, p) },
        "MVIUniform" to { s, p -> MVIUniform(listOf(Interval(0.0, 1.0), Interval(0.0, 1.0)), s, p) },
        "MVIndependentRV" to { s, p -> MVIndependentRV(2, UniformRV(0.0, 1.0), s, p) },
        "MVNormalRV" to { s, p -> MVNormalRV(doubleArrayOf(1.0, -2.0), correlation, s, p) },
        "MVGaussianCopula" to { s, p -> MVGaussianCopula(correlation, s, p) },
        "MVGaussianCopulaRV" to { s, p -> MVGaussianCopulaRV(listOf(Exponential(1.0), Uniform(0.0, 1.0)), correlation, s, p) },
        "BVGaussianCopula" to { s, p -> BVGaussianCopula(0.5, s, p) },
        "BVGaussianCopulaRV" to { s, p -> BVGaussianCopulaRV(Exponential(1.0), Uniform(0.0, 1.0), 0.5, s, p) },
        "BivariateLogNormalRV" to { s, p -> BivariateLogNormalRV(1.0, 1.0, 2.0, 1.0, 0.5, s, p) },
    )

    /** For each stream number: draws pairs from a variable and its antithetic instance, made at once. */
    private fun pairs(build: (Int, RNStreamProviderIfc) -> MVRVariableIfc, n: Int): List<Pair<DoubleArray, DoubleArray>> =
        listOf(0, 5).flatMap { s ->
            val mv = build(s, RNStreamProvider())
            val anti = mv.antitheticInstance()
            List(n) { mv.sample() to anti.sample() }
        }

    @Test
    @DisplayName("The antithetic instance of independent uniforms gives 1 - u in every coordinate")
    fun antitheticUniformsPairCoordinateByCoordinate() {
        for ((x, y) in pairs(multivariate.getValue("MVIUniform"), 100)) {
            for (i in x.indices) assertEquals(1.0, x[i] + y[i], 1e-12, "coordinate $i: ${x.toList()} and ${y.toList()}")
        }
    }

    @Test
    @DisplayName("The antithetic instance of a normal vector is its reflection about the mean")
    fun antitheticNormalReflectsAboutTheMean() {
        for ((x, y) in pairs(multivariate.getValue("MVNormalRV"), 100)) {
            assertEquals(2.0, x[0] + y[0], 1e-9)
            assertEquals(-4.0, x[1] + y[1], 1e-9)
        }
    }

    @Test
    @DisplayName("The antithetic instance of a Gaussian copula gives 1 - u in every coordinate")
    fun antitheticCopulaPairsCoordinateByCoordinate() {
        for (name in listOf("MVGaussianCopula", "BVGaussianCopula")) {
            for ((x, y) in pairs(multivariate.getValue(name), 100)) {
                for (i in x.indices) assertEquals(1.0, x[i] + y[i], 1e-9, "$name coordinate $i")
            }
        }
    }

    /** E[XY] for independent unit uniforms is 1/4; the components of an antithetic instance repeating one uniform gave 1/3. */
    @Test
    @DisplayName("The coordinates of an antithetic instance stay independent")
    fun antitheticCoordinatesStayIndependent() {
        val anti = MVIndependentMarginals(listOf(UniformRV(0.0, 1.0), UniformRV(0.0, 1.0)), 3, RNStreamProvider())
            .antitheticInstance()
        val n = 100_000
        var sum = 0.0
        repeat(n) { val v = anti.sample(); sum += v[0] * v[1] }
        assertEquals(0.25, sum / n, 0.005)
    }

    @Test
    @DisplayName("resetStartStream() rewinds a composite and its antithetic instance, on any stream number")
    fun resetRewindsWhatIsSampled() {
        for ((name, build) in multivariate) for (s in listOf(0, 5)) {
            val mv = build(s, RNStreamProvider())
            val anti = mv.antitheticInstance()
            val first = List(3) { mv.sample().toList() }
            val firstAnti = List(3) { anti.sample().toList() }
            mv.resetStartStream()
            anti.resetStartStream()
            assertEquals(first, List(3) { mv.sample().toList() }, "$name on stream $s")
            assertEquals(firstAnti, List(3) { anti.sample().toList() }, "$name antithetic on stream $s")
        }
    }

    @Test
    @DisplayName("A composite does not use the caller's variable: sampling one leaves the other where it was")
    fun aCompositeHasItsOwnComponents() {
        val provider = RNStreamProvider()
        val callers = UniformRV(0.0, 1.0, 7, provider)
        val mv = MVIndependentRV(2, callers, 9, provider)
        val expected = UniformRV(0.0, 1.0, 7, RNStreamProvider()).let { r -> List(4) { r.value } }
        repeat(10) { mv.sample() }
        assertContentEquals(expected, List(4) { callers.value })
    }

    /** A component that cannot draw from a given stream is refused, and canShareStream() says so beforehand. */
    @Test
    @DisplayName("A marginal that cannot share the composite's stream is refused, with a check beforehand")
    fun foreignMarginalIsRefused() {
        val foreign = object : RVariableIfc by UniformRV(0.0, 1.0) {}
        assertFalse(foreign.canShareStream())
        assertTrue(UniformRV(0.0, 1.0).canShareStream())
        assertFailsWith<IllegalArgumentException> { MVIndependentMarginals(listOf(foreign, UniformRV(0.0, 1.0))) }
        assertFailsWith<IllegalArgumentException> { MVIndependentRV(2, foreign) }
    }

    // ---- univariate composites -------------------------------------------------------------------------------

    private object Epanechnikov : ksl.utilities.distributions.PDFIfc {
        override fun pdf(x: Double): Double = if (x < -1.0 || x > 1.0) 0.0 else 0.75 * (1.0 - x * x)
        override fun domain(): Interval = Interval(-1.0, 1.0)
    }

    private object NormalShape : ksl.utilities.distributions.PDFIfc {
        override fun pdf(x: Double): Double = kotlin.math.exp(-x * x / 2.0)
        override fun domain(): Interval = Interval(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
    }

    /** Every univariate composite, by name, built on a stream number of a provider. */
    private val univariate: Map<String, (Int, RNStreamProviderIfc) -> RVariableIfc> = mapOf(
        "RVFunction" to { s, p -> UniformRV(0.0, 1.0, s, p) + UniformRV(0.0, 1.0, s, p) },
        "RVUFunction" to { s, p -> RVUFunction(UniformRV(0.0, 1.0, s, p)) { 3.0 * it } },
        "ShiftedRV" to { s, p -> ShiftedRV(2.0, UniformRV(0.0, 1.0), s, p) },
        "MixtureRV" to { s, p -> MixtureRV(listOf(ExponentialRV(1.0), UniformRV(0.0, 1.0)), doubleArrayOf(0.4, 1.0), s, p) },
        "RatioOfUniformsRV" to { s, p ->
            val b = kotlin.math.sqrt(2.0 / kotlin.math.exp(1.0))
            RatioOfUniformsRV(1.0, -b, b, NormalShape, s, p)
        },
        "AcceptanceRejectionRV" to { s, p -> AcceptanceRejectionRV(Uniform(-1.0, 1.0), 1.5, Epanechnikov, s, p) },
    )

    private fun univariatePairs(build: (Int, RNStreamProviderIfc) -> RVariableIfc, n: Int): List<Pair<Double, Double>> =
        listOf(0, 5).flatMap { s ->
            val rv = build(s, RNStreamProvider())
            val anti = rv.antitheticInstance()
            List(n) { rv.value to anti.value }
        }

    @Test
    @DisplayName("The antithetic instance of a function of uniforms uses 1 - u for every uniform it draws")
    fun antitheticFunctionsPairExactly() {
        for ((x, y) in univariatePairs(univariate.getValue("RVFunction"), 100)) assertEquals(2.0, x + y, 1e-12)
        for ((x, y) in univariatePairs(univariate.getValue("RVUFunction"), 100)) assertEquals(3.0, x + y, 1e-12)
        for ((x, y) in univariatePairs(univariate.getValue("ShiftedRV"), 100)) assertEquals(5.0, x + y, 1e-12)
    }

    private fun meanAndVariance(rv: RVariableIfc, n: Int = 100_000): Pair<Double, Double> {
        val x = DoubleArray(n) { rv.value }
        val mean = x.average()
        return mean to x.sumOf { (it - mean) * (it - mean) } / (n - 1)
    }

    /**
     *  An antithetic instance samples the right distribution. Before R1.8 the components of one repeated a uniform:
     *  Exp + Exp became 2 Exp (variance 4, not 2), and the ratio-of-uniforms and acceptance-rejection variables drew
     *  their two numbers equal.
     */
    @Test
    @DisplayName("An antithetic instance samples the same distribution as the variable")
    fun antitheticInstancesHaveTheRightDistribution() {
        val sum = (ExponentialRV(1.0, 3, RNStreamProvider()) + ExponentialRV(1.0, 3, RNStreamProvider())).antitheticInstance()
        val (sumMean, sumVariance) = meanAndVariance(sum)
        assertEquals(2.0, sumMean, 0.03)
        assertEquals(2.0, sumVariance, 0.08)
        val (zMean, zVariance) = meanAndVariance(univariate.getValue("RatioOfUniformsRV")(3, RNStreamProvider()).antitheticInstance())
        assertEquals(0.0, zMean, 0.02)
        assertEquals(1.0, zVariance, 0.03)
        val (eMean, eVariance) = meanAndVariance(univariate.getValue("AcceptanceRejectionRV")(3, RNStreamProvider()).antitheticInstance())
        assertEquals(0.0, eMean, 0.01)
        assertEquals(0.2, eVariance, 0.01)
    }

    @Test
    @DisplayName("resetStartStream() rewinds a univariate composite and its antithetic instance, on any stream number")
    fun univariateResetRewindsWhatIsSampled() {
        for ((name, build) in univariate) for (s in listOf(0, 5)) {
            val rv = build(s, RNStreamProvider())
            val anti = rv.antitheticInstance()
            val first = List(3) { rv.value }
            val firstAnti = List(3) { anti.value }
            rv.resetStartStream()
            anti.resetStartStream()
            assertEquals(first, List(3) { rv.value }, "$name on stream $s")
            assertEquals(firstAnti, List(3) { anti.value }, "$name antithetic on stream $s")
        }
    }

    @Test
    @DisplayName("A mixture samples its own copies of the variables it is given, not the caller's")
    fun aMixtureHasItsOwnComponents() {
        val provider = RNStreamProvider()
        val callers = UniformRV(0.0, 1.0, 7, provider)
        val mixture = MixtureRV(listOf(callers, ExponentialRV(1.0, 8, provider)), doubleArrayOf(0.5, 1.0), 9, provider)
        val expected = UniformRV(0.0, 1.0, 7, RNStreamProvider()).let { r -> List(4) { r.value } }
        repeat(10) { mixture.value }
        assertContentEquals(expected, List(4) { callers.value })
    }
}

