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

import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.*
import kotlin.math.*

/**
 * Represents a Poisson random variable. A Poisson random
 * variable represents the number of occurrences of an event with time or space.
 * @param theMean the mean rate
 * @param name an optional label/name
 */
class Poisson(theMean: Double = 1.0, name: String? = null) : Distribution<Poisson>(name),
    DiscretePMFInRangeDistributionIfc, LossFunctionDistributionIfc, GetRVariableIfc, RVParametersTypeIfc by RVType.Poisson {

    init {
        require(theMean > 0.0) { "Mean must be > 0)" }
    }

    /**
     * indicates whether pmf and cdf calculations are
     * done by recursive (iterative) algorithm based on logarithms
     * or via beta incomplete function and binomial coefficients.
     */
    var useRecursiveAlgorithm = true

    /**
     * the mean (parameter) of the poisson
     */
    var mean = theMean
        set(value) {
            require(value > 0.0) { "Mean must be > 0)" }
            field = value
        }

    /**
     * Constructs a Poisson using the supplied parameter
     *
     * @param parameters A array that holds the parameters, parameters[0] should be the mean rate
     */
    constructor(parameters: DoubleArray) : this(parameters[0], null)

    override fun instance(): Poisson {
        return Poisson(mean)
    }

    override fun mean(): Double {
        return mean
    }

    override fun variance(): Double {
        return mean
    }

    /**
     * @return the mode of the distribution
     */
    val mode: Int
        get() = if (floor(mean) == mean) {
            mean.toInt() - 1
        } else {
            floor(mean).toInt()
        }

    fun cdf(x: Int): Double {
        return poissonCDF(x, mean, useRecursiveAlgorithm)
    }

    override fun cdf(x: Double): Double {
        if ((x == Double.POSITIVE_INFINITY) || (x >= Integer.MAX_VALUE)){
            return 1.0
        }
        if (x < 0.0){
            return 0.0
        }
        return cdf(x.toInt())
    }

    override fun firstOrderLossFunction(x: Double): Double {
        val mu = mean
        return if (x < 0.0) {
            floor(abs(x)) + mu
        } else if (x > 0.0) {
            val g0 = complementaryCDF(x)
            val g = pmf(x)
            val g1 = -1.0 * (x - mu) * g0 + mu * g
            g1
        } else  // x== 0.0
        {
            mu
        }
    }

    override fun secondOrderLossFunction(x: Double): Double {
        val mu = mean
        val sbm = 0.5 * (mu * mu) // 1/2 the 2nd binomial moment
        return if (x < 0.0) {
            var s = 0.0
            var y = 0
            while (y > x) {
                s = s + firstOrderLossFunction(y.toDouble())
                y--
            }
            s + sbm
        } else if (x > 0.0) {
            val g0 = complementaryCDF(x)
            val g = pmf(x)
            val g2 = 0.5 * (((x - mu) * (x - mu) + x) * g0 - mu * (x - mu) * g)
            g2
        } else {
            // x == 0.0
            sbm
        }
    }

    fun thirdOrderLossFunction(x: Double): Double {
        val term1 = mean.pow(3.0) * complementaryCDF(x - 3)
        val term2 = 3 * mean * mean * x * complementaryCDF(x - 2)
        val term3 = 3 * mean * x * (x + 1) * complementaryCDF(x - 1)
        val term4 = x * (x + 1) * (x + 2) * complementaryCDF(x)
        return (term1 - term2 + term3 - term4) / 3.0
    }

    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Supplied probability was $p Probability must be [0,1]" }
        if (p <= 0.0) {
            return 0.0
        }
        return if (p >= 1.0) {
            Double.POSITIVE_INFINITY
        } else poissonInvCDF(p, mean, useRecursiveAlgorithm).toDouble()
    }

    override fun pmf(i: Int): Double {
        return poissonPMF(i, mean, useRecursiveAlgorithm)
    }

    /**
     *  Computes the sum of the probabilities over the provided range.
     *  If the range is closed a..b then the end point b is included in the
     *  sum. If the range is open a..&ltb then the point b is not included
     *  in the sum.
     */
    override fun probIn(range: IntRange) : Double {
        if (range.last < 0){
            return 0.0
        }
        if (range.last == Int.MAX_VALUE){
            return 1.0 - strictlyLessCDF(range.first.toDouble())
        }
        var sum = 0.0
        for (i in range){
            val p = pmf(i)
            if ((i > KSLMath.maxNumIterations) && KSLMath.equal(p, 0.0)){
                break
            }
            sum = sum + p
        }
        return sum
    }

    /**
     * Sets the parameters for the distribution
     * parameters[0] should be the mean rate
     *
     * @param params an array of doubles representing the parameters for
     * the distribution
     */
    override fun parameters(params: DoubleArray) {
        mean = params[0]
    }

    /**
     * Gets the parameters for the distribution
     *
     * @return Returns an array of the parameters for the distribution
     */
    override fun parameters(): DoubleArray {
        return doubleArrayOf(mean)
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return PoissonRV(mean, stream)
    }

    override fun toString(): String {
        return "Poisson(mean=$mean)"
    }

    companion object {
        /** Used in the calculation of the incomplete gamma function
         *
         */
        const val DEFAULT_MAX_ITERATIONS = 5000

        /**
         *  @param moments the moments to check
         *  @return true if the moment can be matched, false otherwise
         */
        fun canMatchMoments(vararg moments: Double): Boolean {
            require(moments.isNotEmpty()) { "Must provide a mean." }
            val mean = moments[0]
            return mean > 0
        }

        /**
         * @param moments the moments to check
         * @return an array holding the moments
         */
        fun parametersFromMoments(vararg moments: Double): DoubleArray {
            require(canMatchMoments(*moments)) { "Mean must be positive. You provided " + moments[0] + "." }
            return doubleArrayOf(moments[0])
        }

        fun createFromMoments(vararg moments: Double): Poisson {
            val prob = parametersFromMoments(*moments)
            return Poisson(prob)
        }

        /**
         * Computes the probability mass function at j using a
         * recursive (iterative) algorithm using logarithms
         *
         * @param j    the value to evaluate
         * @param mean the mean
         * @return the PMF value
         */
        fun recursivePMF(j: Int, mean: Double): Double {
            require(mean > 0.0) { "Mean must be > 0)" }
            if (j < 0) {
                return 0.0
            }
            var lnp = -mean
            val lnmu = ln(mean)
            for (i in 1..j) {
                lnp = lnmu - ln(i.toDouble()) + lnp
            }
            require(lnp <= 0) { "Term overflow will cause probability > 1" }
            return if (lnp <= KSLMath.smallestExponentialArgument) {
                0.0
            } else exp(lnp)
        }

        /**
         * Computes the cdf at j using a
         * recursive (iterative) algorithm using logarithms
         *
         * @param j    the value to evaluate
         * @param mean the mean
         * @return the CDF value
         */
        fun recursiveCDF(j: Int, mean: Double): Double {
            require(mean > 0.0) { "Mean must be > 0)" }
            if (j < 0) {
                return 0.0
            }
            var lnp = -mean
            if (j == 0) {
                return if (lnp <= KSLMath.smallestExponentialArgument) {
                    0.0
                } else {
                    exp(lnp)
                }
            }
            val lnmu = Math.log(mean)
            var sum = if (lnp <= KSLMath.smallestExponentialArgument) {
                0.0
            } else {
                exp(lnp)
            }
            for (i in 1..j) {
                lnp = lnmu - ln(i.toDouble()) + lnp
                sum = if (lnp <= KSLMath.smallestExponentialArgument) {
                    sum + 0.0
                } else {
                    sum + exp(lnp)
                }
            }
            return sum
        }

        /**
         * Allows static computation of prob mass function
         * assumes that distribution's range is {0,1, ...}
         *
         * @param j         value for which prob is needed
         * @param mean      of the distribution
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the PMF value
         */
        fun poissonPMF(j: Int, mean: Double, recursive: Boolean = true): Double {
            require(mean > 0.0) { "Mean must be > 0)" }
            if (j < 0) {
                return 0.0
            }
            if (recursive) {
                return recursivePMF(j, mean)
            }
            if (j == 0) {
                val lnp = -mean
                return if (lnp <= KSLMath.smallestExponentialArgument) {
                    0.0
                } else {
                    exp(lnp)
                }
            }
            val lnp = j * ln(mean) - mean - ln(j.toDouble()) - Gamma.logGammaFunction(j.toDouble())
            return if (lnp <= KSLMath.smallestExponentialArgument) {
                0.0
            } else exp(lnp)
        }

        /**
         * Allows static computation of cdf
         * assumes that distribution's range is {0,1, ...}
         * false indicated the use of the incomplete gamma function
         * It yields about 7 digits of accuracy, the recursive
         * algorithm has more accuracy
         *
         * @param j         value for which prob is needed
         * @param mean      of the distribution
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the cdf value
         */
        fun poissonCDF(j: Int, mean: Double, recursive: Boolean = true): Double {
            require(mean > 0.0) { "Mean must be > 0)" }
            if (j < 0) {
                return 0.0
            }
            if (recursive) {
                return recursiveCDF(j, mean)
            }
            val eps = KSLMath.defaultNumericalPrecision
            val ccdf = Gamma.incompleteGammaFunction(mean, (j + 1).toDouble(), DEFAULT_MAX_ITERATIONS, eps)
            return 1.0 - ccdf
        }

        /**
         * Allows static computation of complementary cdf function
         * assumes that distribution's range is {0,1, ...}
         *
         * @param j         value for which ccdf is needed
         * @param mean      of the distribution
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the complimentary CDF value
         */
        fun poissonCCDF(j: Int, mean: Double, recursive: Boolean = true): Double {
            require(mean > 0.0) { "Mean must be > 0)" }
            return if (j < 0) {
                1.0
            } else 1.0 - poissonCDF(j, mean, recursive)
        }

        /**
         * Computes the first order loss function for the
         * distribution function for given value of x, G1(x) = E[max(X-x,0)]
         *
         * @param x         The value to be evaluated
         * @param mean      of the distribution
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return The loss function value, E[max(X-x,0)]
         */
        fun poissonLF1(x: Double, mean: Double, recursive: Boolean = true): Double {
            require(mean > 0.0) { "Mean must be > 0)" }
            return if (x < 0.0) {
                floor(abs(x)) + mean
            } else if (x > 0.0) {
                val g0 = poissonCCDF(x.toInt(), mean, recursive)
                val g = poissonPMF(x.toInt(), mean, recursive)
                val g1 = -1.0 * (x - mean) * g0 + mean * g
                g1
            } else {
                // x== 0.0
                mean
            }
        }

        /**
         * Computes the 2nd order loss function for the
         * distribution function for given value of x, G2(x) = (1/2)E[max(X-x,0)*max(X-x-1,0)]
         *
         * @param x         The value to be evaluated
         * @param mean      of the distribution
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return The loss function value, (1/2)E[max(X-x,0)*max(X-x-1,0)]
         */
        fun poissonLF2(x: Double, mean: Double, recursive: Boolean = true): Double {
            require(mean > 0.0) { "Mean must be > 0)" }
            val sbm = 0.5 * (mean * mean) // 1/2 the 2nd binomial moment
            return if (x < 0.0) {
                var s = 0.0
                var y = 0
                while (y > x) {
                    s = s + poissonLF1(y.toDouble(), mean, recursive)
                    y--
                }
                s + sbm
            } else if (x > 0.0) {
                val g0 = poissonCCDF(x.toInt(), mean, recursive)
                val g = poissonPMF(x.toInt(), mean, recursive)
                val g2 = 0.5 * (((x - mean) * (x - mean) + x) * g0 - mean * (x - mean) * g)
                g2
            } else {// x == 0.0
                sbm
            }
        }

        /**
         * Returns the quantile associated with the supplied probablity, x
         * assumes that distribution's range is {0,1, ...}
         *
         * @param p         The probability that the quantile is needed for
         * @param mean      of the distribution
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the quantile associated with the supplied probablity
         */
        fun poissonInvCDF(p: Double, mean: Double, recursive: Boolean = true): Int {
            require(mean > 0.0) { "Mean must be > 0)" }
            require(!(p < 0.0 || p > 1.0)) { "Supplied probability was $p Probability must be [0,1]" }
            if (p <= 0.0) {
                return 0
            }
            if (p >= 1.0) {
                return Int.MAX_VALUE
            }

            // get approximate quantile from normal approximation
            // and Cornish-Fisher expansion
            val start = invCDFViaNormalApprox(p, mean)
            val cdfAtStart = poissonCDF(start, mean, recursive)

            //System.out.println("start = " + start);
            //System.out.println("cdfAtStart = " + cdfAtStart);
            //System.out.println("p = " + p);
            //System.out.println();
            return if (p >= cdfAtStart) {
                searchUpCDF(p, mean, start, cdfAtStart, recursive)
            } else {
                searchDownCDF(p, mean, start, cdfAtStart, recursive)
            }
        }

        /**
         * @param p          the probability to search
         * @param mean       the mean of the distribution
         * @param start      the starting point of the search
         * @param cdfAtStart the CDF at the starting point
         * @param recursive  true indicates that the recursive logarithmic algorithm should be used
         * @return the found value
         */
        private fun searchUpCDF(
            p: Double, mean: Double,
            start: Int, cdfAtStart: Double, recursive: Boolean = true
        ): Int {
            var i = start
            var cdf = cdfAtStart
            while (p > cdf) {
                i++
                cdf = cdf + poissonPMF(i, mean, recursive)
            }
            return i
        }

        /**
         * @param p          the probability to search
         * @param mean       the mean of the distribution
         * @param start      the starting point of the search
         * @param cdfAtStart the CDF at the starting point
         * @param recursive  true indicates that the recursive logarithmic algorithm should be used
         * @return the found value
         */
        private fun searchDownCDF(
            p: Double, mean: Double,
            start: Int, cdfAtStart: Double, recursive: Boolean = true
        ): Int {
            var i = start
            var cdfi = cdfAtStart
            while (i > 0) {
                val cdfim1 = cdfi - poissonPMF(i, mean, recursive)
                if (cdfim1 <= p && p < cdfi) {
                    return if (KSLMath.equal(cdfim1, p)) // must handle invCDF(cdf(x) = x)
                    {
                        i - 1
                    } else {
                        i
                    }
                }
                cdfi = cdfim1
                i--
            }
            return i
        }

        /**
         * @param p    the probability to search
         * @param mean the mean of the distribution
         * @return the inverse via a normal approximation
         */
        fun invCDFViaNormalApprox(p: Double, mean: Double): Int {
            require(mean > 0.0) { "Mean must be > 0)" }
            /* y := approx.value (Cornish-Fisher expansion) :  */
            val z = Normal.stdNormalInvCDF(p)
            val sigma = sqrt(mean)
            val g = 1.0 / sigma
            val y = floor(mean + sigma * (z + g * (z * z - 1.0) / 6.0) + 0.5)
            return if (y < 0) {
                0
            } else {
                y.toInt()
            }
        }

    }


}