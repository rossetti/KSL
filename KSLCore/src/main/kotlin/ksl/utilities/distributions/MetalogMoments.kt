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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
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
    const val DEFAULT_ENDPOINT_TOLERANCE: Double = 1.0E-15

    /**
     *  The furthest out in the logit variable that integration can go. Beyond this the recovered
     *  probability rounds to exactly one and the quantile function is no longer defined there.
     */
    const val MAX_LOGIT_LIMIT: Double = 36.0

    /**
     *  The largest product of moment order and tail weight for which a quadrature moment of a
     *  semi-bounded metalog is accurate to better than roughly one part in ten million. Past this
     *  the moment still exists, but the far tail contributes more than the reachable range of a
     *  double can capture.
     */
    const val RELIABLE_TAIL_WEIGHT_PRODUCT: Double = 0.5

    /**
     *  The furthest out in the logit variable that integration can go when the quantile function
     *  is parameterized by the probability rather than by the logit.
     *
     *  A probability supplied as a double cannot recover its own logit near either endpoint,
     *  because the complement of a probability close to one is quantized to multiples of machine
     *  epsilon. Past this limit the round trip error makes the integrand behave like a staircase
     *  rather than a smooth function, and the quadrature stops converging altogether. The limit
     *  corresponds to reaching about two parts in a billion from each endpoint, so a moment
     *  obtained this way carries a truncation error of that order even for a bounded integrand.
     *  The logit-parameterized entry points do not have this restriction.
     */
    const val MAX_ROUND_TRIP_LOGIT_LIMIT: Double = 20.0

    /**
     *  The number of Gauss-Legendre points used on each segment.
     */
    const val DEFAULT_QUADRATURE_POINTS: Int = 24

    /**
     *  The largest number of terms for which the closed-form moment expressions apply.
     */
    const val MAX_CLOSED_FORM_TERMS: Int = 5

    // The demanded accuracy has to be attainable. A fourth central moment of an unbounded
    // metalog reaches magnitudes near ten million on the segments closest to each endpoint, so
    // an absolute tolerance near machine epsilon can never be met and the integrator would
    // exhaust its evaluation budget. Relative accuracy governs instead, at a level far tighter
    // than any consumer of these moments requires.
    private const val RELATIVE_ACCURACY: Double = 1.0E-9
    private const val ABSOLUTE_ACCURACY: Double = 1.0E-13
    private const val MAX_EVALUATIONS: Int = 200_000

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
        return MetalogFunctions.logitWeightAt(coefficients, 0.5)
    }

    /**
     *  The combined weight on the logit as the cumulative probability approaches zero, formed
     *  the same way as the upper tail weight but at a centered probability of minus one half.
     */
    fun lowerTailLogitWeight(coefficients: DoubleArray): Double {
        return MetalogFunctions.logitWeightAt(coefficients, -0.5)
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
     *  Whether a numerically computed moment of the given order can be trusted to the accuracy
     *  the quadrature normally achieves.
     *
     *  A moment can exist and still be hard to compute. For a semi-bounded metalog the tail
     *  contribution falls off like the exponential of the tail weight less one times the logit,
     *  so as the product of moment order and tail weight approaches one, an ever larger share of
     *  the moment sits beyond the largest probability a double can represent. The moment is still
     *  finite and the returned value is still the best available estimate; it is simply less
     *  accurate. Consult this alongside the existence test when reporting a moment.
     */
    fun momentIsReliable(
        j: Int,
        coefficients: DoubleArray,
        boundedness: MetalogBoundedness
    ): Boolean {
        require(j >= 1) { "The moment order $j must be at least 1" }
        if (!momentExists(j, coefficients, boundedness)) {
            return false
        }
        return when (boundedness) {
            MetalogBoundedness.Unbounded -> true
            MetalogBoundedness.Bounded -> true
            MetalogBoundedness.LowerBounded ->
                j * upperTailLogitWeight(coefficients) <= RELIABLE_TAIL_WEIGHT_PRODUCT
            MetalogBoundedness.UpperBounded ->
                j * lowerTailLogitWeight(coefficients) <= RELIABLE_TAIL_WEIGHT_PRODUCT
        }
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
     *  The j-th moment about the origin, for a quantile function parameterized by the logit of the
     *  cumulative probability rather than by the probability.
     *
     *  Prefer this over the probability-based overload whenever the far tail carries weight. A
     *  probability supplied as a double cannot recover its own logit beyond about ten to the minus
     *  tenth from either endpoint, which makes the integrand noisy in the tail and can prevent the
     *  quadrature from converging at all.
     *
     *  @param j the order of the moment, which must be at least one
     *  @param endpointTolerance how close to zero and one the integration reaches
     *  @param quantileOfLogit maps the logit of a cumulative probability to the value of the
     *  random variable
     */
    fun rawMomentInLogit(
        j: Int,
        endpointTolerance: Double = DEFAULT_ENDPOINT_TOLERANCE,
        quantileOfLogit: (Double) -> Double
    ): Double {
        require(j >= 1) { "The moment order $j must be at least 1" }
        return integrateInLogit(endpointTolerance, MAX_LOGIT_LIMIT) { t ->
            quantileOfLogit(t).pow(j)
        }
    }

    /**
     *  The j-th moment about the supplied mean, for a quantile function parameterized by the logit
     *  of the cumulative probability. See the companion function for why this is preferred when
     *  the tails matter.
     *
     *  @param j the order of the moment, which must be at least two
     *  @param mean the mean to take the moment about
     *  @param endpointTolerance how close to zero and one the integration reaches
     *  @param quantileOfLogit maps the logit of a cumulative probability to the value of the
     *  random variable
     */
    fun centralMomentInLogit(
        j: Int,
        mean: Double,
        endpointTolerance: Double = DEFAULT_ENDPOINT_TOLERANCE,
        quantileOfLogit: (Double) -> Double
    ): Double {
        require(j >= 2) { "The central moment order $j must be at least 2" }
        return integrateInLogit(endpointTolerance, MAX_LOGIT_LIMIT) { t ->
            (quantileOfLogit(t) - mean).pow(j)
        }
    }

    /**
     *  Integrates the supplied function over the unit interval.
     *
     *  The integration is carried out in the logit variable rather than in the probability
     *  itself. Substituting the logistic function for the probability contributes a Jacobian that
     *  decays exponentially, which converts the endpoint singularity of a metalog quantile
     *  function into exponential decay of a smooth integrand. For a semi-bounded member the
     *  integrand then behaves like the exponential of the tail weight less one times the logit,
     *  so it decays precisely when the moment exists.
     *
     *  Working in the logit also reaches far closer to each endpoint than working in the
     *  probability can. The probability recovered at a logit of thirty-six is the largest value
     *  strictly below one that a double can represent, an endpoint distance of about two times
     *  ten to the minus sixteenth, against ten to the minus tenth for a practical grid in
     *  probability space.
     */
    private fun integrateOverUnitInterval(
        endpointTolerance: Double,
        function: (Double) -> Double
    ): Double {
        require(endpointTolerance > 0.0) { "The endpoint tolerance must be positive" }
        require(endpointTolerance < 0.1) { "The endpoint tolerance must be less than 0.1" }
        return integrateInLogit(endpointTolerance, MAX_ROUND_TRIP_LOGIT_LIMIT) { t ->
            function(probabilityFromLogit(t))
        }
    }

    /**
     *  Integrates a function of the logit of the cumulative probability over the whole real line,
     *  truncated at the supplied limit, including the Jacobian that converts the measure back to
     *  the probability.
     *
     *  Substituting the logistic function for the probability contributes a Jacobian that decays
     *  exponentially, which turns the endpoint singularity of a metalog quantile function into
     *  exponential decay of a smooth integrand. For a semi-bounded member the integrand behaves
     *  like the exponential of the tail weight less one times the logit, so it decays precisely
     *  when the moment exists.
     */
    private fun integrateInLogit(
        endpointTolerance: Double,
        maximumLimit: Double,
        integrandOfLogit: (Double) -> Double
    ): Double {
        require(endpointTolerance > 0.0) { "The endpoint tolerance must be positive" }
        require(endpointTolerance < 0.1) { "The endpoint tolerance must be less than 0.1" }
        val limit = min(maximumLimit, ln((1.0 - endpointTolerance) / endpointTolerance))
        val integrand = UnivariateFunction { t ->
            integrandOfLogit(t) * MetalogFunctions.probabilityDerivativeFromLogit(t)
        }
        val integrator = IterativeLegendreGaussIntegrator(
            DEFAULT_QUADRATURE_POINTS, RELATIVE_ACCURACY, ABSOLUTE_ACCURACY
        )
        val breakPoints = logitSegmentBreakPoints(limit)
        var total = 0.0
        for (i in 0 until breakPoints.size - 1) {
            total += integrator.integrate(
                MAX_EVALUATIONS, integrand, breakPoints[i], breakPoints[i + 1]
            )
        }
        return total
    }

    /**
     *  The probability corresponding to a logit, evaluated so that neither tail leaves the open
     *  unit interval.
     */
    private fun probabilityFromLogit(t: Double): Double {
        return if (t >= 0.0) {
            1.0 / (1.0 + exp(-t))
        } else {
            val e = exp(t)
            e / (1.0 + e)
        }
    }

    /**
     *  Segment boundaries in the logit variable. The integrand is smooth and decays
     *  exponentially, so evenly spread segments suffice, with finer spacing through the middle
     *  where the mass is concentrated.
     */
    private fun logitSegmentBreakPoints(limit: Double): DoubleArray {
        val points = sortedSetOf(-limit, limit)
        for (t in doubleArrayOf(-24.0, -16.0, -10.0, -5.0, -2.0, 0.0, 2.0, 5.0, 10.0, 16.0, 24.0)) {
            if (abs(t) < limit) {
                points.add(t)
            }
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
