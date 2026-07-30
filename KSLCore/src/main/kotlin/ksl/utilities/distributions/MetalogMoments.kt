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

package ksl.utilities.distributions

import org.hipparchus.analysis.UnivariateFunction
import org.hipparchus.analysis.integration.IterativeLegendreGaussIntegrator
import kotlin.math.PI
import kotlin.math.pow

/**
 *  Moments of metalog distributions.
 *
 *  Because a metalog is defined by its quantile function, the k-th moment about the origin is
 *  the integral over the unit interval of the k-th power of that quantile function. Keelin
 *  (2016) section 3.4 gives closed-form polynomials for the low-order central moments of the
 *  unbounded metalog with five or fewer terms, and those are used where they apply. Everything
 *  else, including every bounded or semi-bounded case, is integrated numerically.
 *
 *  The closed forms also serve as the validation oracle for the numerical path, since the two
 *  must agree wherever both are available.
 *
 *  Numerical integration is segmented geometrically toward each endpoint. The quantile function
 *  of an unbounded metalog diverges logarithmically at zero and one, which is integrable but
 *  poorly served by a single quadrature rule spanning the whole interval.
 */
object MetalogMoments {

    /**
     *  How close to each endpoint the numerical integration reaches. The remaining tail is
     *  negligible for a logarithmic divergence.
     */
    const val DEFAULT_ENDPOINT_TOLERANCE: Double = 1.0E-10

    /**
     *  The number of Gauss-Legendre points used on each segment.
     */
    const val DEFAULT_QUADRATURE_POINTS: Int = 32

    /**
     *  The largest number of terms for which the closed-form moment expressions apply.
     */
    const val MAX_CLOSED_FORM_TERMS: Int = 5

    private const val RELATIVE_ACCURACY: Double = 1.0E-12
    private const val ABSOLUTE_ACCURACY: Double = 1.0E-12
    private const val MAX_EVALUATIONS: Int = 1_000_000

    /**
     *  True when a closed-form expression covers the supplied arity and boundedness. Only the
     *  unbounded member with five or fewer terms qualifies.
     */
    fun hasClosedForm(numTerms: Int, boundedness: MetalogBoundedness): Boolean {
        return (boundedness == MetalogBoundedness.Unbounded) && (numTerms <= MAX_CLOSED_FORM_TERMS)
    }

    /**
     *  The combined weight on the logit as the cumulative probability approaches one, which is
     *  the sum of every coefficient that multiplies the logit, each evaluated at the centered
     *  probability of one half. This governs how fast the quantile function grows in the upper
     *  tail: the quantile behaves like the reciprocal of one minus the probability, raised to
     *  this weight.
     */
    fun upperTailLogitWeight(coefficients: DoubleArray): Double {
        return logitWeightAt(coefficients, 0.5)
    }

    /**
     *  The combined weight on the logit as the cumulative probability approaches zero, formed
     *  the same way as the upper tail weight but at a centered probability of minus one half.
     */
    fun lowerTailLogitWeight(coefficients: DoubleArray): Double {
        return logitWeightAt(coefficients, -0.5)
    }

    /**
     *  Whether the moment of the given order is finite.
     *
     *  This matters because a semi-bounded metalog can easily fail to have the moments one
     *  would casually assume it has. The unbounded member diverges only logarithmically at each
     *  endpoint, so all of its moments are finite, and the bounded member has compact support,
     *  so all of its moments are finite. The semi-bounded members, however, exponentiate the
     *  quantile function, which converts that logarithmic divergence into a power law. A lower
     *  bounded metalog whose upper tail logit weight is one half has a finite mean but an
     *  infinite variance, and one whose weight reaches one has no finite mean at all.
     *
     *  Numerical integration cannot detect this on its own. Truncating the integral near the
     *  endpoint always yields a finite number, so quadrature would report a large value rather
     *  than reporting divergence. Callers should consult this function before trusting a
     *  quadrature result for a semi-bounded metalog.
     *
     *  The criterion is asymptotic, so a case sitting exactly on the boundary is reported as
     *  not existing, and accuracy degrades for cases approaching it.
     *
     *  @param j the order of the moment, which must be at least one
     *  @param coefficients the metalog coefficients
     *  @param boundedness which member of the family is in use
     */
    fun momentExists(
        j: Int,
        coefficients: DoubleArray,
        boundedness: MetalogBoundedness
    ): Boolean {
        require(j >= 1) { "The moment order $j must be at least 1" }
        return when (boundedness) {
            MetalogBoundedness.Unbounded -> true
            MetalogBoundedness.Bounded -> true
            MetalogBoundedness.LowerBounded -> j * upperTailLogitWeight(coefficients) < 1.0
            MetalogBoundedness.UpperBounded -> j * lowerTailLogitWeight(coefficients) < 1.0
        }
    }

    /**
     *  Sums the coefficients that multiply the logit, each scaled by the appropriate power of
     *  the supplied centered probability. Terms two and three carry the logit, as does every
     *  even-numbered term from six onward.
     */
    private fun logitWeightAt(coefficients: DoubleArray, c: Double): Double {
        require(coefficients.size >= MetalogFunctions.MIN_TERMS) {
            "There must be at least ${MetalogFunctions.MIN_TERMS} coefficients"
        }
        var sum = coefficients[1]
        if (coefficients.size > 2) {
            sum += coefficients[2] * c
        }
        var i = 6
        while (i <= coefficients.size) {
            sum += coefficients[i - 1] * c.pow(i / 2 - 1)
            i += 2
        }
        return sum
    }

    /**
     *  The mean of an unbounded metalog with five or fewer terms, from Keelin (2016)
     *  section 3.4. Coefficients beyond those supplied are treated as zero, which is why the
     *  same expression covers two through five terms.
     */
    fun unboundedMean(coefficients: DoubleArray): Double {
        val a = padToClosedForm(coefficients)
        return a[0] + a[2] / 2.0 + a[4] / 12.0
    }

    /**
     *  The variance of an unbounded metalog with five or fewer terms, from Keelin (2016)
     *  section 3.4. Coefficients beyond those supplied are treated as zero.
     */
    fun unboundedVariance(coefficients: DoubleArray): Double {
        val a = padToClosedForm(coefficients)
        val a2 = a[1]
        val a3 = a[2]
        val a4 = a[3]
        val a5 = a[4]
        val piSquared = PI * PI
        return (piSquared / 3.0) * a2 * a2 +
                (1.0 / 12.0 + piSquared / 36.0) * a3 * a3 +
                a2 * a4 +
                (a4 * a4) / 12.0 +
                (a3 * a5) / 12.0 +
                (a5 * a5) / 180.0
    }

    /**
     *  The j-th moment about the origin of a distribution described by the supplied quantile
     *  function, computed as the integral of the j-th power of that function over the unit
     *  interval.
     *
     *  @param j the order of the moment, which must be at least one
     *  @param endpointTolerance how close to zero and one the integration reaches
     *  @param quantileFunction maps a cumulative probability to the value of the random variable
     */
    fun rawMomentByQuadrature(
        j: Int,
        endpointTolerance: Double = DEFAULT_ENDPOINT_TOLERANCE,
        quantileFunction: (Double) -> Double
    ): Double {
        require(j >= 1) { "The moment order $j must be at least 1" }
        return integrateOverUnitInterval(endpointTolerance) { y ->
            quantileFunction(y).pow(j)
        }
    }

    /**
     *  The j-th moment about the supplied mean of a distribution described by the supplied
     *  quantile function.
     *
     *  @param j the order of the moment, which must be at least two
     *  @param mean the mean to take the moment about
     *  @param endpointTolerance how close to zero and one the integration reaches
     *  @param quantileFunction maps a cumulative probability to the value of the random variable
     */
    fun centralMomentByQuadrature(
        j: Int,
        mean: Double,
        endpointTolerance: Double = DEFAULT_ENDPOINT_TOLERANCE,
        quantileFunction: (Double) -> Double
    ): Double {
        require(j >= 2) { "The central moment order $j must be at least 2" }
        return integrateOverUnitInterval(endpointTolerance) { y ->
            (quantileFunction(y) - mean).pow(j)
        }
    }

    /**
     *  Integrates the supplied function over the unit interval, working segment by segment so
     *  that the regions approaching each endpoint receive their own refinement.
     */
    private fun integrateOverUnitInterval(
        endpointTolerance: Double,
        function: (Double) -> Double
    ): Double {
        require(endpointTolerance > 0.0) { "The endpoint tolerance must be positive" }
        require(endpointTolerance < 0.1) { "The endpoint tolerance must be less than 0.1" }
        val breakPoints = segmentBreakPoints(endpointTolerance)
        val integrand = UnivariateFunction { y -> function(y) }
        val integrator = IterativeLegendreGaussIntegrator(
            DEFAULT_QUADRATURE_POINTS, RELATIVE_ACCURACY, ABSOLUTE_ACCURACY
        )
        var total = 0.0
        for (i in 0 until breakPoints.size - 1) {
            total += integrator.integrate(
                MAX_EVALUATIONS, integrand, breakPoints[i], breakPoints[i + 1]
            )
        }
        return total
    }

    /**
     *  Segment boundaries running from the endpoint tolerance up to its complement, spaced
     *  geometrically near each end and coarsely through the middle.
     */
    private fun segmentBreakPoints(endpointTolerance: Double): DoubleArray {
        val lower = sortedSetOf(endpointTolerance)
        var scale = 0.1
        while (scale > endpointTolerance) {
            lower.add(scale)
            scale *= 0.01
        }
        val points = sortedSetOf<Double>()
        points.addAll(lower)
        points.add(0.5)
        for (y in lower) {
            points.add(1.0 - y)
        }
        return points.toDoubleArray()
    }

    /**
     *  Copies the supplied coefficients into a five-element array, padding with zeros, and
     *  rejects any arity the closed-form expressions do not cover.
     */
    private fun padToClosedForm(coefficients: DoubleArray): DoubleArray {
        require(coefficients.size >= MetalogFunctions.MIN_TERMS) {
            "There must be at least ${MetalogFunctions.MIN_TERMS} coefficients"
        }
        require(coefficients.size <= MAX_CLOSED_FORM_TERMS) {
            "The closed-form moments cover at most $MAX_CLOSED_FORM_TERMS terms, " +
                    "found ${coefficients.size}. Use the quadrature functions instead."
        }
        val padded = DoubleArray(MAX_CLOSED_FORM_TERMS)
        coefficients.copyInto(padded)
        return padded
    }
}
