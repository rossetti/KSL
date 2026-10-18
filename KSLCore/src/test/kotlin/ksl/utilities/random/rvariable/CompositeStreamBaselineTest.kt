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
import ksl.utilities.distributions.PDFIfc
import ksl.utilities.distributions.Uniform
import ksl.utilities.mcintegration.MC1DIntegration
import ksl.utilities.mcintegration.MCMultiVariateIntegration
import ksl.utilities.random.mcmc.FunctionMVIfc
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.zip.CRC32
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.assertEquals

/**
 *  Pins what the composite random variables (variables built from component variables) sample, so that a change
 *  to how they obtain their streams can be checked against the behaviour it started from.
 *
 *  Each composite has two pins, each on a fresh stream provider:
 *  - **explicit stream:** four samples of the variable built on stream 5;
 *  - **default stream and antithetic:** the variable built on the default stream 0, its antithetic instance made at
 *    once, four samples of each, then `resetStartStream()` on both and two more samples of each.
 *
 *  A pin is a checksum of the samples written to nine decimals.
 *
 *  As pinned, a composite's components obtain their own streams from the provider by the composite's stream
 *  number. On the default stream that gives them streams other than the composite's, so its antithetic instance
 *  and its stream controls miss what it draws from; in an antithetic instance each component gets its own copy of
 *  the antithetic stream, so the components repeat one uniform. Both integration estimates are biased as pinned:
 *  1.1665 for an exact 1, and 7.008 for an exact 6.
 */
class CompositeStreamBaselineTest {

    private fun checksum(values: List<Double>): Long {
        val crc = CRC32()
        crc.update(values.joinToString(",") { "%.9f".format(it) }.toByteArray())
        return crc.value
    }

    /** The default-stream-and-antithetic samples of a univariate variable made by [build]. */
    private fun univariateDefault(build: (Int, RNStreamProviderIfc) -> RVariableIfc): List<Double> {
        val rv = build(0, RNStreamProvider())
        val anti = rv.antitheticInstance()
        val values = mutableListOf<Double>()
        repeat(4) { values += rv.value }
        repeat(4) { values += anti.value }
        rv.resetStartStream()
        anti.resetStartStream()
        repeat(2) { values += rv.value }
        repeat(2) { values += anti.value }
        return values
    }

    private fun univariateExplicit(build: (Int, RNStreamProviderIfc) -> RVariableIfc): List<Double> {
        val rv = build(5, RNStreamProvider())
        return List(4) { rv.value }
    }

    private fun multivariateDefault(build: (Int, RNStreamProviderIfc) -> MVRVariableIfc): List<Double> {
        val mv = build(0, RNStreamProvider())
        val anti = mv.antitheticInstance()
        val values = mutableListOf<Double>()
        repeat(4) { values += mv.sample().toList() }
        repeat(4) { values += anti.sample().toList() }
        mv.resetStartStream()
        anti.resetStartStream()
        repeat(2) { values += mv.sample().toList() }
        repeat(2) { values += anti.sample().toList() }
        return values
    }

    private fun multivariateExplicit(build: (Int, RNStreamProviderIfc) -> MVRVariableIfc): List<Double> {
        val mv = build(5, RNStreamProvider())
        return (1..4).flatMap { mv.sample().toList() }
    }

    // ---- the composites --------------------------------------------------------------------------------------

    private val independentMarginals = { s: Int, p: RNStreamProviderIfc ->
        MVIndependentMarginals(listOf(UniformRV(0.0, 1.0, s, p), ExponentialRV(1.0, s, p)), s, p)
    }
    private val iUniform = { s: Int, p: RNStreamProviderIfc ->
        MVIUniform(listOf(Interval(0.0, 1.0), Interval(0.0, 1.0)), s, p)
    }

    /** Built from a variable of the caller's on stream 7 of the same provider. */
    private val independentRV = { s: Int, p: RNStreamProviderIfc -> MVIndependentRV(2, UniformRV(0.0, 1.0, 7, p), s, p) }
    private val normal = { s: Int, p: RNStreamProviderIfc ->
        MVNormalRV(doubleArrayOf(0.0, 0.0), arrayOf(doubleArrayOf(1.0, 0.5), doubleArrayOf(0.5, 1.0)), s, p)
    }
    private val gaussianCopula = { s: Int, p: RNStreamProviderIfc ->
        MVGaussianCopula(arrayOf(doubleArrayOf(1.0, 0.5), doubleArrayOf(0.5, 1.0)), s, p)
    }
    private val gaussianCopulaRV = { s: Int, p: RNStreamProviderIfc ->
        MVGaussianCopulaRV(listOf(Exponential(1.0), Uniform(0.0, 1.0)), arrayOf(doubleArrayOf(1.0, 0.5), doubleArrayOf(0.5, 1.0)), s, p)
    }
    private val bvCopula = { s: Int, p: RNStreamProviderIfc -> BVGaussianCopula(0.5, s, p) }
    private val bvCopulaRV = { s: Int, p: RNStreamProviderIfc -> BVGaussianCopulaRV(Exponential(1.0), Uniform(0.0, 1.0), 0.5, s, p) }
    private val bvLogNormal = { s: Int, p: RNStreamProviderIfc -> BivariateLogNormalRV(1.0, 1.0, 2.0, 1.0, 0.5, s, p) }

    private val sum = { s: Int, p: RNStreamProviderIfc -> ExponentialRV(1.0, s, p) + ExponentialRV(1.0, s, p) }
    private val transformed = { s: Int, p: RNStreamProviderIfc -> abs(NormalRV(0.0, 1.0, s, p)) }
    private val shifted = { s: Int, p: RNStreamProviderIfc -> ShiftedRV(2.0, ExponentialRV(1.0, s, p), s, p) }

    /** Built from two variables of the caller's on streams 7 and 8 of the same provider. */
    private val mixture = { s: Int, p: RNStreamProviderIfc ->
        MixtureRV(listOf(ExponentialRV(1.0, 7, p), UniformRV(0.0, 1.0, 8, p)), doubleArrayOf(0.4, 1.0), s, p)
    }

    /** The standard normal by ratio of uniforms, with f(x) = exp(-x^2/2). */
    private val ratioOfUniforms = { s: Int, p: RNStreamProviderIfc ->
        val b = sqrt(2.0 / exp(1.0))
        RatioOfUniformsRV(1.0, -b, b, HalfNormalShape, s, p)
    }

    /** The density 0.75(1 - x^2) on [-1, 1] by acceptance-rejection from Uniform(-1, 1). */
    private val acceptanceRejection = { s: Int, p: RNStreamProviderIfc ->
        AcceptanceRejectionRV(Uniform(-1.0, 1.0), 1.5, Epanechnikov, s, p)
    }

    private object HalfNormalShape : PDFIfc {
        override fun pdf(x: Double): Double = exp(-x * x / 2.0)
        override fun domain(): Interval = Interval(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
    }

    private object Epanechnikov : PDFIfc {
        override fun pdf(x: Double): Double = if (x < -1.0 || x > 1.0) 0.0 else 0.75 * (1.0 - x * x)
        override fun domain(): Interval = Interval(-1.0, 1.0)
    }

    // ---- pins -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Multivariate composites, default stream and antithetic instance")
    fun multivariateDefaultAndAntithetic() {
        val actual = mapOf(
            "MVIndependentMarginals" to checksum(multivariateDefault(independentMarginals)),
            "MVIUniform" to checksum(multivariateDefault(iUniform)),
            "MVIndependentRV" to checksum(multivariateDefault(independentRV)),
            "MVNormalRV" to checksum(multivariateDefault(normal)),
            "MVGaussianCopula" to checksum(multivariateDefault(gaussianCopula)),
            "MVGaussianCopulaRV" to checksum(multivariateDefault(gaussianCopulaRV)),
            "BVGaussianCopula" to checksum(multivariateDefault(bvCopula)),
            "BVGaussianCopulaRV" to checksum(multivariateDefault(bvCopulaRV)),
            "BivariateLogNormalRV" to checksum(multivariateDefault(bvLogNormal)),
        )
        assertEquals(mapOf(
            "MVIndependentMarginals" to 2255609275L,
            "MVIUniform" to 2737567969L,
            "MVIndependentRV" to 3285106702L,
            "MVNormalRV" to 2170920885L,
            "MVGaussianCopula" to 3268498440L,
            "MVGaussianCopulaRV" to 1999737977L,
            "BVGaussianCopula" to 3612963942L,
            "BVGaussianCopulaRV" to 1674548943L,
            "BivariateLogNormalRV" to 3481885677L,
        ), actual)
    }

    @Test
    @DisplayName("Multivariate composites, explicit stream")
    fun multivariateExplicitStream() {
        val actual = mapOf(
            "MVIndependentMarginals" to checksum(multivariateExplicit(independentMarginals)),
            "MVIUniform" to checksum(multivariateExplicit(iUniform)),
            "MVIndependentRV" to checksum(multivariateExplicit(independentRV)),
            "MVNormalRV" to checksum(multivariateExplicit(normal)),
            "MVGaussianCopula" to checksum(multivariateExplicit(gaussianCopula)),
            "MVGaussianCopulaRV" to checksum(multivariateExplicit(gaussianCopulaRV)),
            "BVGaussianCopula" to checksum(multivariateExplicit(bvCopula)),
            "BVGaussianCopulaRV" to checksum(multivariateExplicit(bvCopulaRV)),
            "BivariateLogNormalRV" to checksum(multivariateExplicit(bvLogNormal)),
        )
        assertEquals(mapOf(
            "MVIndependentMarginals" to 2414878693L,
            "MVIUniform" to 1444688561L,
            "MVIndependentRV" to 2463742740L,
            "MVNormalRV" to 3969352928L,
            "MVGaussianCopula" to 65484669L,
            "MVGaussianCopulaRV" to 890306250L,
            "BVGaussianCopula" to 65484669L,
            "BVGaussianCopulaRV" to 890306250L,
            "BivariateLogNormalRV" to 3284951075L,
        ), actual)
    }

    @Test
    @DisplayName("Univariate composites, default stream and antithetic instance")
    fun univariateDefaultAndAntithetic() {
        val actual = mapOf(
            "RVFunction" to checksum(univariateDefault(sum)),
            "RVUFunction" to checksum(univariateDefault(transformed)),
            "ShiftedRV" to checksum(univariateDefault(shifted)),
            "MixtureRV" to checksum(univariateDefault(mixture)),
            "RatioOfUniformsRV" to checksum(univariateDefault(ratioOfUniforms)),
            "AcceptanceRejectionRV" to checksum(univariateDefault(acceptanceRejection)),
        )
        assertEquals(mapOf(
            "RVFunction" to 2248043381L,
            "RVUFunction" to 2607406461L,
            "ShiftedRV" to 532045970L,
            "MixtureRV" to 1387454269L,
            "RatioOfUniformsRV" to 1182090799L,
            "AcceptanceRejectionRV" to 1731027418L,
        ), actual)
    }

    @Test
    @DisplayName("Univariate composites, explicit stream")
    fun univariateExplicitStream() {
        val actual = mapOf(
            "RVFunction" to checksum(univariateExplicit(sum)),
            "RVUFunction" to checksum(univariateExplicit(transformed)),
            "ShiftedRV" to checksum(univariateExplicit(shifted)),
            "MixtureRV" to checksum(univariateExplicit(mixture)),
            "RatioOfUniformsRV" to checksum(univariateExplicit(ratioOfUniforms)),
            "AcceptanceRejectionRV" to checksum(univariateExplicit(acceptanceRejection)),
        )
        assertEquals(mapOf(
            "RVFunction" to 2742046619L,
            "RVUFunction" to 2650341878L,
            "ShiftedRV" to 3929785863L,
            "MixtureRV" to 3550063089L,
            "RatioOfUniformsRV" to 1868219307L,
            "AcceptanceRejectionRV" to 1652708483L,
        ), actual)
    }

    /** The integral of 4x^2 y + y^2 over the unit square, exactly 1, with antithetic sampling, 2000 pairs. Biased as pinned. */
    @Test
    @DisplayName("Multivariate integration with antithetic sampling")
    fun multivariateIntegration() {
        val f = object : FunctionMVIfc {
            override val dimension: Int = 2
            override fun f(x: DoubleArray): Double = 4.0 * x[0] * x[0] * x[1] + x[1] * x[1]
        }
        val mc = MCMultiVariateIntegration(f, iUniform(0, RNStreamProvider()))
        mc.desiredHWErrorBound = 1.0e-9
        mc.maxSampleSize = 2000
        assertEquals(1.1664662127034517, mc.runSimulation())
    }

    /**
     *  E[X^2] for X the sum of two unit exponentials, exactly 6, with antithetic sampling, 2000 pairs. Biased as
     *  pinned: the antithetic instance samples 2 x Exp, whose E[X^2] is 8.
     */
    @Test
    @DisplayName("One-dimensional integration with antithetic sampling")
    fun oneDimensionalIntegration() {
        val mc = MC1DIntegration({ x -> x * x }, sum(0, RNStreamProvider()))
        mc.desiredHWErrorBound = 1.0e-9
        mc.maxSampleSize = 2000
        assertEquals(7.0078676152112624, mc.runSimulation())
    }
}
