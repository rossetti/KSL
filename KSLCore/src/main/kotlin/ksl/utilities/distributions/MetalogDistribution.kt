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

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.InverseCDFRV
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 *  Shared behavior for the metalog distributions of Keelin (2016).
 *
 *  A metalog is defined by its quantile function, which is a linear combination of basis terms
 *  in the cumulative probability. The coefficients of that combination are held here as an
 *  array so the basis functions can be applied without repacking on each evaluation, while the
 *  concrete subclasses expose them as individually named parameters.
 *
 *  Boundedness is not a property of the subclass. It follows from which of the two bounds is
 *  finite, so a single subclass per term count covers the unbounded, semi-bounded, and bounded
 *  members of the family. A bound left infinite is absent.
 *
 *  The coefficients must always define a strictly increasing quantile function. Construction
 *  and every mutator enforce that, and a rejected change leaves the instance untouched, so an
 *  instance of this class is always a valid probability distribution.
 *
 *  Two consequences of the construction are worth knowing. There is no closed-form cumulative
 *  distribution function, so the cumulative and density functions solve the quantile equation
 *  numerically; monotonicity guarantees this converges. And the semi-bounded members need not
 *  possess the moments one might assume, because they exponentiate the quantile function; the
 *  mean and variance report a non-finite value rather than a fabricated one in that case.
 *
 *  @param coefficients the metalog scaling constants, of which there must be at least two
 *  @param lowerBound the lower bound, or negative infinity when unbounded below
 *  @param upperBound the upper bound, or positive infinity when unbounded above
 *  @param name an optional name
 */
abstract class MetalogDistribution(
    coefficients: DoubleArray,
    lowerBound: Double = Double.NEGATIVE_INFINITY,
    upperBound: Double = Double.POSITIVE_INFINITY,
    name: String? = null
) : Distribution(name), ContinuousDistributionIfc, InverseCDFIfc, GetRVariableIfc, MomentsIfc {

    protected val myCoefficients: DoubleArray = coefficients.copyOf()

    private var myLowerBound: Double = lowerBound
    private var myUpperBound: Double = upperBound
    private var myBoundedness: MetalogBoundedness = MetalogBoundedness.of(lowerBound, upperBound)

    private var mySupport: Interval = Interval()
    private var myMean: Double? = null
    private var myVariance: Double? = null
    private var mySkewness: Double? = null
    private var myKurtosis: Double? = null

    init {
        require(myCoefficients.size >= MetalogFunctions.MIN_TERMS) {
            "There must be at least ${MetalogFunctions.MIN_TERMS} coefficients, " +
                    "found ${myCoefficients.size}"
        }
        requireFeasible(myCoefficients)
        refreshSupport()
    }

    /**
     *  How many metalog terms this distribution uses.
     */
    val numTerms: Int
        get() = myCoefficients.size

    /**
     *  Which member of the metalog family this instance represents, derived from its bounds.
     */
    val boundedness: MetalogBoundedness
        get() = myBoundedness

    /**
     *  The lower bound of the support, or negative infinity when unbounded below. Assigning a
     *  value that would not leave the lower bound strictly below the upper bound fails and
     *  leaves the distribution unchanged.
     */
    var lowerBound: Double
        get() = myLowerBound
        set(value) {
            val resolved = MetalogBoundedness.of(value, myUpperBound)
            myLowerBound = value
            myBoundedness = resolved
            clearMomentCache()
        }

    /**
     *  The upper bound of the support, or positive infinity when unbounded above. Assigning a
     *  value that would not leave the upper bound strictly above the lower bound fails and
     *  leaves the distribution unchanged.
     */
    var upperBound: Double
        get() = myUpperBound
        set(value) {
            val resolved = MetalogBoundedness.of(myLowerBound, value)
            myUpperBound = value
            myBoundedness = resolved
            clearMomentCache()
        }

    /**
     *  A defensive copy of the coefficients, in the order in which they multiply the basis
     *  terms.
     */
    fun coefficients(): DoubleArray = myCoefficients.copyOf()

    /**
     *  Whether the mean and variance reported by this distribution are exact or approximate, and
     *  in the approximate case whether they are trustworthy.
     *
     *  The unbounded member with five or fewer terms has closed-form moments. Everything else is
     *  integrated numerically, which is accurate except for a semi-bounded member whose tail is
     *  heavy enough that a large share of the moment lies beyond the largest probability a double
     *  can represent. Such a moment is still finite; it is simply less precise.
     */
    fun momentsAreReliable(order: Int = 2): Boolean {
        if (MetalogMoments.hasClosedForm(numTerms, myBoundedness)) {
            return true
        }
        return MetalogMoments.momentIsReliable(order, myCoefficients, myBoundedness)
    }

    /**
     *  Reports how much margin the current coefficients have against the feasibility boundary,
     *  and where that margin is smallest. Useful for diagnosing a fit that is only just valid.
     */
    fun feasibility(): MetalogFeasibilityResult {
        return MetalogFeasibilityChecker.defaultChecker.check(myCoefficients)
    }

    // -------- support --------

    /**
     *  The actual support of the distribution, which is not always the interval between the
     *  declared bounds.
     *
     *  A metalog whose declared bounds are infinite can still have a finite support. Keelin notes
     *  that the quantile function is bounded whenever every coefficient carrying the logit is
     *  zero; the four-term uniform is the standard example, and its support is finite even though
     *  neither bound is declared. The support is therefore derived from the coefficients rather
     *  than read off the bounds.
     */
    override fun domain(): Interval = mySupport

    /**
     *  Recomputes the cached support from the coefficients and the bounds. Called during
     *  construction and by every mutator.
     */
    private fun refreshSupport() {
        val zLower = MetalogFunctions.limitAsProbabilityApproachesZero(myCoefficients)
        val zUpper = MetalogFunctions.limitAsProbabilityApproachesOne(myCoefficients)
        mySupport = Interval(
            myBoundedness.fromFittingSpace(zLower, myLowerBound, myUpperBound),
            myBoundedness.fromFittingSpace(zUpper, myLowerBound, myUpperBound)
        )
    }

    // -------- quantile function --------

    override fun invCDF(p: Double): Double {
        require(!p.isNaN()) { "The probability must not be NaN" }
        require((p >= 0.0) && (p <= 1.0)) { "Supplied probability was $p, it must be within [0,1]" }
        if (p <= 0.0) {
            return mySupport.lowerLimit
        }
        if (p >= 1.0) {
            return mySupport.upperLimit
        }
        return quantileAtProbability(p)
    }

    /**
     *  The value of the random variable at the supplied cumulative probability, which must lie
     *  strictly inside the unit interval.
     */
    protected fun quantileAtProbability(y: Double): Double {
        val z = MetalogFunctions.quantile(myCoefficients, y)
        return myBoundedness.fromFittingSpace(z, myLowerBound, myUpperBound)
    }

    /**
     *  The density of the random variable at the value corresponding to the supplied cumulative
     *  probability. The metalog density in fitting space is scaled by the boundedness density
     *  factor, which is strictly positive.
     */
    /**
     *  The value of the random variable at the cumulative probability with the supplied logit.
     *
     *  This reaches far further into the tails than the probability-based route, because a
     *  probability held in a double cannot recover its own logit near either endpoint. Moment
     *  integration uses this so a heavy-tailed semi-bounded metalog is integrated over the range
     *  its moment actually needs.
     */
    protected fun quantileAtLogit(logit: Double): Double {
        val z = MetalogFunctions.quantileFromLogit(myCoefficients, logit)
        return myBoundedness.fromFittingSpace(z, myLowerBound, myUpperBound)
    }

    protected fun densityAtProbability(y: Double): Double {
        val z = MetalogFunctions.quantile(myCoefficients, y)
        val base = MetalogFunctions.density(myCoefficients, y)
        return base * myBoundedness.densityFactor(z, myLowerBound, myUpperBound)
    }

    // -------- cumulative distribution and density, by numerical inversion --------

    override fun cdf(x: Double): Double {
        if (x.isNaN()) {
            return Double.NaN
        }
        if (x <= mySupport.lowerLimit) {
            return 0.0
        }
        if (x >= mySupport.upperLimit) {
            return 1.0
        }
        return probabilityAt(x)
    }

    override fun pdf(x: Double): Double {
        if (x.isNaN()) {
            return Double.NaN
        }
        if ((x <= mySupport.lowerLimit) || (x >= mySupport.upperLimit)) {
            return 0.0
        }
        if (!x.isFinite()) {
            return 0.0
        }
        return densityAtProbability(probabilityAt(x))
    }

    /**
     *  Solves the quantile equation for the cumulative probability that produces the supplied
     *  value.
     *
     *  Feasibility guarantees the quantile function is strictly increasing, so a bracket always
     *  exists. The bracket is found by stepping geometrically toward whichever endpoint the
     *  value lies past, and the root is then refined by Newton iteration confined to that
     *  bracket. Because the derivative of the quantile function is the reciprocal of the
     *  density, the Newton step is the residual multiplied by the density. A step that would
     *  leave the bracket is replaced by bisection, so the iteration cannot run away.
     */
    protected fun probabilityAt(x: Double): Double {
        var lower = MIN_PROBABILITY
        var upper = MAX_PROBABILITY
        val middle = 0.5
        if (quantileAtProbability(middle) < x) {
            lower = middle
            var gap = 0.25
            var candidate = 1.0 - gap
            while ((gap > MIN_BRACKET_GAP) && (quantileAtProbability(candidate) < x)) {
                lower = candidate
                gap *= 0.5
                candidate = 1.0 - gap
            }
            upper = candidate
        } else {
            upper = middle
            var gap = 0.25
            var candidate = gap
            while ((gap > MIN_BRACKET_GAP) && (quantileAtProbability(candidate) > x)) {
                upper = candidate
                gap *= 0.5
                candidate = gap
            }
            lower = candidate
        }
        val scale = max(1.0, abs(x))
        var y = 0.5 * (lower + upper)
        for (iteration in 1..defaultMaxInversionIterations) {
            val residual = quantileAtProbability(y) - x
            if (abs(residual) <= defaultInversionTolerance * scale) {
                return y
            }
            if (residual < 0.0) {
                lower = y
            } else {
                upper = y
            }
            if ((upper - lower) <= PROBABILITY_TOLERANCE) {
                return 0.5 * (lower + upper)
            }
            val density = densityAtProbability(y)
            var next = if (density.isFinite() && (density > 0.0)) {
                y - residual * density
            } else {
                Double.NaN
            }
            if (next.isNaN() || (next <= lower) || (next >= upper)) {
                next = 0.5 * (lower + upper)
            }
            y = next
        }
        return y
    }

    // -------- moments --------

    override fun mean(): Double {
        myMean?.let { return it }
        val computed = computeMean()
        myMean = computed
        return computed
    }

    override fun variance(): Double {
        myVariance?.let { return it }
        val computed = computeVariance()
        myVariance = computed
        return computed
    }

    private fun computeMean(): Double {
        if (!MetalogMoments.momentExists(1, myCoefficients, myBoundedness)) {
            // A lower bounded metalog with too heavy an upper tail diverges upward; the upper
            // bounded mirror image diverges downward.
            return if (myBoundedness == MetalogBoundedness.UpperBounded) {
                Double.NEGATIVE_INFINITY
            } else {
                Double.POSITIVE_INFINITY
            }
        }
        if (MetalogMoments.hasClosedForm(numTerms, myBoundedness)) {
            return MetalogMoments.unboundedMean(myCoefficients)
        }
        return MetalogMoments.rawMomentInLogit(1) { t -> quantileAtLogit(t) }
    }

    private fun computeVariance(): Double {
        if (!MetalogMoments.momentExists(2, myCoefficients, myBoundedness)) {
            return Double.POSITIVE_INFINITY
        }
        if (MetalogMoments.hasClosedForm(numTerms, myBoundedness)) {
            return MetalogMoments.unboundedVariance(myCoefficients)
        }
        val m = mean()
        if (!m.isFinite()) {
            return Double.POSITIVE_INFINITY
        }
        return MetalogMoments.centralMomentInLogit(2, m) { t -> quantileAtLogit(t) }
    }

    override val mean: Double
        get() = mean()

    override val variance: Double
        get() = variance()

    /**
     *  The standardized third central moment. This is not available in closed form for the
     *  metalog, so it is integrated numerically and cached. It is not a number when the third
     *  moment does not exist.
     */
    override val skewness: Double
        get() {
            mySkewness?.let { return it }
            val computed = standardizedCentralMoment(3)
            mySkewness = computed
            return computed
        }

    /**
     *  The standardized fourth central moment. This is not available in closed form for the
     *  metalog, so it is integrated numerically and cached. It is not a number when the fourth
     *  moment does not exist.
     */
    override val kurtosis: Double
        get() {
            myKurtosis?.let { return it }
            val computed = standardizedCentralMoment(4)
            myKurtosis = computed
            return computed
        }

    private fun standardizedCentralMoment(order: Int): Double {
        if (!MetalogMoments.momentExists(order, myCoefficients, myBoundedness)) {
            return Double.NaN
        }
        val v = variance()
        if (!v.isFinite() || (v <= 0.0)) {
            return Double.NaN
        }
        val m = mean()
        val central = MetalogMoments.centralMomentInLogit(order, m) { t -> quantileAtLogit(t) }
        return central / sqrt(v).pow(order)
    }

    // -------- parameters --------

    /**
     *  The coefficients followed by the lower and upper bounds, so the array has two more
     *  elements than the number of terms. The bounds are reported even when infinite, because
     *  they are parameters of the distribution.
     */
    override fun parameters(): DoubleArray {
        val values = DoubleArray(numTerms + 2)
        myCoefficients.copyInto(values)
        values[numTerms] = myLowerBound
        values[numTerms + 1] = myUpperBound
        return values
    }

    /**
     *  Assigns the coefficients and both bounds from a single array laid out as the array
     *  returned by the no-argument overload. Everything is validated before anything is
     *  assigned, so a rejected array leaves the distribution unchanged.
     */
    override fun parameters(params: DoubleArray) {
        require(params.size == (numTerms + 2)) {
            "There must be ${numTerms + 2} parameters for a $numTerms term metalog " +
                    "(the coefficients followed by the lower and upper bounds), found ${params.size}"
        }
        val newCoefficients = params.copyOfRange(0, numTerms)
        val newLowerBound = params[numTerms]
        val newUpperBound = params[numTerms + 1]
        val resolved = MetalogBoundedness.of(newLowerBound, newUpperBound)
        requireFeasible(newCoefficients)
        newCoefficients.copyInto(myCoefficients)
        myLowerBound = newLowerBound
        myUpperBound = newUpperBound
        myBoundedness = resolved
        clearMomentCache()
    }

    /**
     *  Assigns one coefficient by its zero-based index. The change is rejected, leaving the
     *  distribution unchanged, when it would produce a quantile function that is not strictly
     *  increasing.
     */
    protected fun changeCoefficient(index: Int, value: Double) {
        require((index >= 0) && (index < numTerms)) {
            "The coefficient index $index is outside 0 until $numTerms"
        }
        if (myCoefficients[index] == value) {
            return
        }
        val trial = myCoefficients.copyOf()
        trial[index] = value
        requireFeasible(trial)
        myCoefficients[index] = value
        clearMomentCache()
    }

    /**
     *  Discards cached moments. Called by every mutator, since all four cached values depend on
     *  the coefficients and the bounds.
     */
    protected fun clearMomentCache() {
        refreshSupport()
        myMean = null
        myVariance = null
        mySkewness = null
        myKurtosis = null
    }

    /**
     *  Names each coefficient by its position and reports both bounds, so the family member is
     *  evident from the output whether or not the bounds are finite.
     */
    override fun toString(): String {
        val terms = myCoefficients.withIndex().joinToString { (index, value) -> "a${index + 1}=$value" }
        return "${this::class.simpleName}($terms, lowerBound=$myLowerBound, upperBound=$myUpperBound)"
    }

    private fun requireFeasible(coefficients: DoubleArray) {
        val result = MetalogFeasibilityChecker.defaultChecker.check(coefficients)
        require(result.feasible) {
            "The coefficients ${coefficients.joinToString()} do not define a valid metalog: " +
                    "the quantile function is not strictly increasing, with a derivative of " +
                    "${result.minimumDerivative} at a cumulative probability of " +
                    "${result.worstProbability}"
        }
    }

    // -------- random variate generation --------

    /**
     *  A random variable that samples this distribution by inverse transform. Each variate
     *  costs one evaluation of the closed-form quantile function, with no root finding and no
     *  rejection.
     */
    override fun randomVariable(
        streamNumber: Int,
        streamProvider: RNStreamProviderIfc
    ): RVariableIfc {
        return InverseCDFRV(this, streamNumber, streamProvider)
    }

    companion object {

        /**
         *  The relative tolerance on the quantile residual at which the inversion stops.
         */
        var defaultInversionTolerance: Double = 1.0E-12

        /**
         *  The most Newton and bisection steps the inversion will take.
         */
        var defaultMaxInversionIterations: Int = 200

        /**
         *  The narrowest bracket the inversion will refine to before returning its midpoint.
         */
        const val PROBABILITY_TOLERANCE: Double = 1.0E-15

        /**
         *  How close to an endpoint the bracket search will reach.
         */
        const val MIN_BRACKET_GAP: Double = 1.0E-15

        private const val MIN_PROBABILITY: Double = 1.0E-15
        private const val MAX_PROBABILITY: Double = 1.0 - 1.0E-15

        /**
         *  Builds a metalog of the appropriate arity for the supplied coefficients, which must
         *  number between two and six. This is the general entry point when the number of terms
         *  is decided at run time, as it is during distribution fitting.
         */
        fun create(
            coefficients: DoubleArray,
            lowerBound: Double = Double.NEGATIVE_INFINITY,
            upperBound: Double = Double.POSITIVE_INFINITY,
            name: String? = null
        ): MetalogDistribution {
            return when (coefficients.size) {
                2 -> Metalog2P(coefficients[0], coefficients[1], lowerBound, upperBound, name)
                3 -> Metalog3P(
                    coefficients[0], coefficients[1], coefficients[2],
                    lowerBound, upperBound, name
                )
                4 -> Metalog4P(
                    coefficients[0], coefficients[1], coefficients[2], coefficients[3],
                    lowerBound, upperBound, name
                )
                5 -> Metalog5P(
                    coefficients[0], coefficients[1], coefficients[2], coefficients[3],
                    coefficients[4], lowerBound, upperBound, name
                )
                6 -> Metalog6P(
                    coefficients[0], coefficients[1], coefficients[2], coefficients[3],
                    coefficients[4], coefficients[5], lowerBound, upperBound, name
                )
                else -> throw IllegalArgumentException(
                    "There is no metalog class for ${coefficients.size} terms; " +
                            "the registered classes cover 2 through 6 terms"
                )
            }
        }

        /**
         *  The largest number of terms for which a concrete class is registered.
         */
        const val MAX_REGISTERED_TERMS: Int = 6
    }
}
