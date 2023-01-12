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
import ksl.utilities.random.rvariable.BinomialRV
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.*

class Binomial(pSuccess: Double = 0.5, nTrials: Int = 1, name: String? = null) : Distribution<Binomial>(name),
    DiscreteDistributionIfc, LossFunctionDistributionIfc {

    init {
        require(!(pSuccess < 0.0 || pSuccess > 1.0)) { "Success Probability must be [0,1]" }
        require(nTrials > 0) { "Number of trials must be >= 1" }
    }

    /** The probability of success
     *
     */
    var probOfSuccess = pSuccess
        set(prob) {
            require(!(prob <= 0.0 || prob >= 1.0)) { "Probability must be (0,1)" }
            field = prob
        }

    /** The number of trials
     *
     */
    var numTrials = nTrials
        set(value) {
            require(value > 0) { "Number of trials must be >= 1" }
            field = value
        }

    /** indicates whether pmf and cdf calculations are
     * done by recursive (iterative) algorithm based on logarithms
     * or via beta incomplete function and binomial coefficients.
     *
     */
    var useRecursiveAlgorithm = true

    override fun instance(): Binomial {
        return Binomial(probOfSuccess, numTrials)
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return BinomialRV(probOfSuccess, numTrials, stream)
    }

    override fun cdf(x: Double): Double {
        return cdf(floor(x).toInt())
    }

    fun cdf(x: Int): Double {
        return binomialCDF(x, numTrials, probOfSuccess, useRecursiveAlgorithm)
    }

    override fun pmf(x: Double): Double {
        return if (floor(x) == x) {
            pmf(x.toInt())
        } else {
            0.0
        }
    }

    /**
     *
     * @param x value to evaluate
     * @return the associated probability
     */
    fun pmf(x: Int): Double {
        return binomialPMF(x, numTrials, probOfSuccess, useRecursiveAlgorithm)
    }

    override fun mean(): Double {
        return (numTrials * probOfSuccess)
    }

    override fun variance(): Double {
        return (numTrials * probOfSuccess * (1.0 - probOfSuccess))
    }

    override fun invCDF(p: Double): Double {
        return binomialInvCDF(p, numTrials, probOfSuccess, useRecursiveAlgorithm).toDouble()
    }

    override fun firstOrderLossFunction(x: Double): Double {
        return if (x < 0.0) {
            floor(abs(x)) + mean()
        } else if (x > 0.0) {
            mean() - sumCCDF(x)
        } else  // x== 0.0
        {
            mean()
        }
    }

    override fun secondOrderLossFunction(x: Double): Double {
        val mu: Double = mean()
        val g2: Double = 0.5 * (variance() + mu * mu - mu) // 1/2 the 2nd binomial moment
        return if (x < 0.0) {
            var s = 0.0
            var y = 0
            while (y > x) {
                s = s + firstOrderLossFunction(y.toDouble())
                y--
            }
            s + g2
        } else if (x > 0.0) {
            g2 - sumFirstLoss(x)
        } else  // x == 0.0
        {
            g2
        }
    }

    /** Returns the sum of the complementary CDF
     * from 0 up to but not including x
     *
     * @param x the value to evaluate
     * @return the sum of the complementary CDF
     */
    private fun sumCCDF(x: Double): Double {
        if (x <= 0.0) {
            return 0.0
        }
        val n : Int = if (x > numTrials) {
            numTrials
        } else {
            x.toInt()
        }
        var c = 0.0
        var i = 0
        while (i < n) {
            c = c + complementaryCDF(i.toDouble())
            i++
        }
        return c
    }

    /** Sums the first order loss function from
     * 1 up to and including x. x is interpreted
     * as an integer
     *
     * @param x the x to evaluate
     * @return the first order loss functio
     */
    private fun sumFirstLoss(x: Double): Double {
        val n = x.toInt()
        var sum = 0.0
        for (i in 1..n) {
            sum = sum + firstOrderLossFunction(i.toDouble())
        }
        return sum
    }


    override fun parameters(params: DoubleArray) {
        require(params.size == 2){"There must be two parameters (probOfSuccess, numTrials) for the Binomial distribution!"}
        probOfSuccess = params[0]
        numTrials = params[1].toInt()
    }

    override fun parameters(): DoubleArray {
        return doubleArrayOf(probOfSuccess, numTrials.toDouble())
    }

    companion object {
        /**
         *
         * @param moments the mean is in element 0 and the variance is in element 1, must not be null
         * @return true if n and p can be set to match te moments
         */
        fun canMatchMoments(vararg moments: Double): Boolean {
            require(moments.size >= 2) { "Must provide a mean and a variance. You provided " + moments.size + " moments." }
            val m = moments[0]
            val v = moments[1]
            val validN = v >= m * (1 - m)
            return v > 0 && v < m && validN
        }

        /**
         *
         * @param moments the mean is in element 0 and the variance is in element 1, must not be null
         * @return the values of n and p that match the moments with p as element 0 and n as element 1
         */
        fun computeParametersFromMoments(vararg moments: Double): DoubleArray {
            require(canMatchMoments(*moments)) { "Mean and variance must be positive, mean > variance, and variance >= mean*(1-mean). Your mean: " + moments[0] + " and variance: " + moments[1] }
            val m = moments[0]
            val v = moments[1]
            val n = (m * m / (m - v) + 0.5).toInt()
            val p = m / n
            return doubleArrayOf(p, n.toDouble())
        }

        /**
         *
         * @param moments the mean is in element 0 and the variance is in element 1, must not be null
         * @return if the moments can be matched a properly configured Binomial is returned
         */
//        fun createFromMoments(vararg moments: Double): Binomial? {
//            val param = getParametersFromMoments(*moments)
//            return Binomial(param)
//        }

        /** Computes the probability mass function at j using a
         * recursive (iterative) algorithm using logarithms
         *
         * @param j the value to evaluate
         * @param n number of trials
         * @param p success probability
         * @return probability of j
         */
        fun recursivePMF(j: Int, n: Int, p: Double): Double {
            require(n > 0) { "The number of trials must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            if (j < 0) {
                return 0.0
            }
            if (j > n) {
                return 0.0
            }
            val q = 1.0 - p
            val lnq = ln(q)
            var f = n * lnq
            if (j == 0) {
                return if (f <= KSLMath.smallestExponentialArgument) {
                    0.0
                } else {
                    exp(f)
                }
            }
            val lnp = ln(p)
            if (j == n) {
                val g = n * lnp
                return if (g <= KSLMath.smallestExponentialArgument) {
                    0.0
                } else {
                    exp(g)
                }
            }
            val c = lnp - lnq
            for (i in 1..j) {
                f = c + ln(n - i + 1.0) - ln(i.toDouble()) + f
            }
            require(f < KSLMath.largestExponentialArgument) { "Term overflow due to input parameters" }
            return if (f <= KSLMath.smallestExponentialArgument) {
                0.0
            } else exp(f)
        }

        /** Computes the probability mass function at j using a
         * recursive (iterative) algorithm using logarithms
         *
         * @param j the value to evaluate
         * @param n number of trials
         * @param p success probability
         * @return cumulative probability of j
         */
        fun recursiveCDF(j: Int, n: Int, p: Double): Double {
            require(n > 0) { "The number of trials must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            if (j < 0) {
                return 0.0
            }
            if (j >= n) {
                return 1.0
            }
            val q = 1.0 - p
            val lnq = ln(q)
            var f = n * lnq
            if (j == 0) {
                return if (f <= KSLMath.smallestExponentialArgument) {
                    0.0
                } else {
                    exp(f)
                }
            }
            val lnp = ln(p)
            val c = lnp - lnq
            var sum = exp(f)
            for (i in 1..j) {
                f = c + ln(n - i + 1.0) - ln(i.toDouble()) + f
                require(f < KSLMath.largestExponentialArgument) { "Term overflow due to input parameters" }
                sum = if (f <= KSLMath.smallestExponentialArgument) {
                    continue
                } else {
                    sum + exp(f)
                }
            }
            return sum
        }

        /** Allows static computation of prob mass function
         * assumes that distribution's range is {0,1, ..., n}
         * Uses the recursive logarithmic algorithm
         *
         * @param j value for which prob is needed
         * @param n num of trials
         * @param p prob of success
         * @return the probability at j
         */
        fun binomialPMF(j: Int, n: Int, p: Double): Double {
            return binomialPMF(j, n, p, true)
        }

        /** Allows static computation of prob mass function
         * assumes that distribution's range is {0,1, ..., n}
         *
         * @param j value for which prob is needed
         * @param n num of successes
         * @param p prob of success
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the probability at j
         */
        fun binomialPMF(j: Int, n: Int, p: Double, recursive: Boolean): Double {
            require(n > 0) { "The number of trials must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            if (j < 0) {
                return 0.0
            }
            if (j > n) {
                return 0.0
            }
            if (recursive) {
                return recursivePMF(j, n, p)
            }
            val q = 1.0 - p
            val lnq = ln(q)
            val f = n * lnq
            if (j == 0) {
                return if (f <= KSLMath.smallestExponentialArgument) {
                    0.0
                } else {
                    exp(f)
                }
            }
            val lnp = ln(p)
            if (j == n) {
                val g = n * lnp
                return if (g <= KSLMath.smallestExponentialArgument) {
                    0.0
                } else {
                    exp(g)
                }
            }
            val lnpj = j * lnp
            if (lnpj <= KSLMath.smallestExponentialArgument) {
                return 0.0
            }
            val lnqnj = (n - j) * lnq
            if (lnqnj <= KSLMath.smallestExponentialArgument) {
                return 0.0
            }
            val c = KSLMath.binomialCoefficient(n, j)
            val pj = exp(lnpj)
            val qnj = exp(lnqnj)
            return c * pj * qnj
        }

        /** Allows static computation of the CDF
         * assumes that distribution's range is {0,1, ...,n}
         * Uses the recursive logarithmic algorithm
         *
         * @param j value for which cdf is needed
         * @param n num of trials
         * @param p prob of success
         * @return the cumulative probability at j
         */
        fun binomialCDF(j: Int, n: Int, p: Double): Double {
            return binomialCDF(j, n, p, true)
        }

        /** Allows static computation of the CDF
         * assumes that distribution's range is {0,1, ..., n}
         *
         * @param j value for which cdf is needed
         * @param n num of trials
         * @param p prob of success
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the cumulative probability at j
         */
        fun binomialCDF(j: Int, n: Int, p: Double, recursive: Boolean): Double {
            require(n > 0) { "The number of trials must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            if (j < 0) {
                return 0.0
            }
            if (j >= n) {
                return 1.0
            }
            return if (recursive) {
                recursiveCDF(j, n, p)
            } else Beta.regularizedIncompleteBetaFunction(1.0 - p, (n - j).toDouble(), (j + 1).toDouble())
        }

        /** Allows static computation of complementary cdf function
         * assumes that distribution's range is {0,1, ..., n}
         * Uses the recursive logarithmic algorithm
         *
         * @param j value for which ccdf is needed
         * @param n num of trials
         * @param p prob of success
         * @return the complementary CDF at j
         */
        fun binomialCCDF(j: Int, n: Int, p: Double): Double {
            return binomialCCDF(j, n, p, true)
        }

        /** Allows static computation of complementary cdf function
         * assumes that distribution's range is {0,1, ...,n}
         *
         * @param j value for which ccdf is needed
         * @param n num of trials
         * @param p prob of success
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the complementary CDF at j
         */
        fun binomialCCDF(j: Int, n: Int, p: Double, recursive: Boolean): Double {
            require(n > 0) { "The number of trials must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            if (j < 0) {
                return 1.0
            }
            return if (j >= n) {
                0.0
            } else 1.0 - binomialCDF(j, n, p, recursive)
        }

        /** Allows static computation of 1st order loss function
         * assumes that distribution's range is {0,1, ..., n}
         * Uses the recursive logarithmic algorithm
         *
         * @param j value for which 1st order loss function is needed
         * @param n num of trial
         * @param p prob of success
         * @return the first order loss function at j
         */
        fun binomialLF1(j: Double, n: Int, p: Double): Double {
            return binomialLF1(j, n, p, true)
        }

        /** Allows static computation of 1st order loss function
         * assumes that distribution's range is {0,1, ...,n}
         *
         * @param j value for which 1st order loss function is needed
         * @param n num of trials
         * @param p prob of success
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the first order loss function at j
         */
        fun binomialLF1(j: Double, n: Int, p: Double, recursive: Boolean): Double {
            require(n > 0) { "The number of trial must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            val mu = n * p // the mean
            return if (j < 0) {
                Math.floor(Math.abs(j)) + mu
            } else if (j > 0) {
                mu - sumCCDF_(j, n, p, recursive)
            } else { // j == 0
                mu
            }
        }

        /** Returns the sum of the complementary CDF
         * from 0 up to but not including x
         *
         * @param x the value to evaluate
         * @param nTrials the number of trials
         * @param recursive the flag to use the recursive algorithm
         * @param p the probability of success
         * @return the sum of the complementary CDF
         */
        protected fun sumCCDF_(x: Double, nTrials: Int, p: Double, recursive: Boolean): Double {
            if (x <= 0.0) {
                return 0.0
            }
            val n : Int = if (x > nTrials) {
                nTrials
            } else { // 0 < x <= nTrials
                x.toInt()
            }
            var c = 0.0
            var i = 0
            while (i < n) {
                c = c + binomialCCDF(i, nTrials, p, recursive)
                i++
            }
            return c
        }

        /** Allows static computation of 2nd order loss function
         * assumes that distribution's range is {0,1, ..., n}
         * Uses the recursive logarithmic algorithm
         *
         * @param j value for which 2nd order loss function is needed
         * @param n num of trials
         * @param p prob of success
         * @return the 2nd order loss function at j
         */
        fun binomialLF2(j: Double, n: Int, p: Double): Double {
            return binomialLF2(j, n, p, true)
        }

        /** Allows static computation of 2nd order loss function
         * assumes that distribution's range is {0,1, ...,n}
         *
         * @param j value for which 2nd order loss function is needed
         * @param n num of trials
         * @param p prob of success
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the 2nd order loss function at j
         */
        fun binomialLF2(j: Double, n: Int, p: Double, recursive: Boolean): Double {
            require(n > 0) { "The number of trials must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            val mu = n * p
            val v = n * p * (1.0 - p)
            val sbm = 0.5 * (v + mu * mu - mu) // 1/2 the 2nd binomial moment
            return if (j < 0) {
                var s = 0.0
                var y = 0
                while (y > j) {
                    s = s + binomialLF1(y.toDouble(), n, p, recursive)
                    y--
                }
                s + sbm
            } else if (j > 0) {
                sbm - sumFirstLoss_(j, n, p, recursive)
            } else { // j== 0
                sbm
            }
        }

        /** Sums the first order loss function from
         * 1 up to and including x. x is interpreted
         * as an integer
         *
         * @param x the value to evaluate
         * @param n the number of trials
         * @param p the probability of success
         * @param recursive true if recursive algorithm is to be used
         * @return the sum
         */
        protected fun sumFirstLoss_(x: Double, n: Int, p: Double, recursive: Boolean): Double {
            val k = x.toInt()
            var sum = 0.0
            for (i in 1..k) {
                sum = sum + binomialLF1(i.toDouble(), n, p, recursive)
            }
            return sum
        }

        /** Returns the quantile associated with the supplied probability, x
         * assumes that distribution's range is {0,1, ..., n}
         * Uses the recursive logarithmic algorithm
         *
         * @param x The probability that the quantile is needed for
         * @param n The number of trials
         * @param p The probability of success, must be in range [0,1)
         * @return the quantile associated with the supplied probability
         */
        fun binomialInvCDF(x: Double, n: Int, p: Double): Int {
            return binomialInvCDF(x, n, p, true)
        }

        /** Returns the quantile associated with the supplied probability, x
         * assumes that distribution's range is {0,1, ...,n}
         *
         * @param x The probability that the quantile is needed for
         * @param n The number of trials
         * @param p The probability of success, must be in range [0,1)
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the quantile associated with the supplied probability
         */
        fun binomialInvCDF(x: Double, n: Int, p: Double, recursive: Boolean): Int {
            require(n > 0) { "The number of successes must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            require(!(x < 0.0 || x > 1.0)) { "Supplied probability was $x Probability must be [0,1]" }
            if (x <= 0.0) {
                return 0
            }
            if (x >= 1.0) {
                return n
            }

            // get approximate quantile from normal approximation
            // and Cornish-Fisher expansion
            val start = invCDFViaNormalApprox(x, n, p)
            val cdfAtStart = binomialCDF(start, n, p, recursive)

            return if (x >= cdfAtStart) {
                searchUpCDF(x, n, p, start, cdfAtStart, recursive)
            } else {
                searchDownCDF(x, n, p, start, cdfAtStart, recursive)
            }
        }

        /** Approximates the quantile of x using a normal distribution
         *
         * @param x the value to evaluate
         * @param n the number of trials
         * @param p the probability of success
         * @return the approximate inverse CDF value
         */
        fun invCDFViaNormalApprox(x: Double, n: Int, p: Double): Int {
            require(n > 0) { "The number of trial must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            require(!(x < 0.0 || x > 1.0)) { "Supplied probability was $x Probability must be [0,1]" }
            if (x <= 0.0) {
                return 0
            }
            if (x >= 1.0) {
                return n
            }
            val q = 1.0 - p
            val mu = n * p
            val sigma = sqrt(mu * q)
            val g = (q - p) / sigma

            /* y := approx.value (Cornish-Fisher expansion) :  */
            val z = Normal.stdNormalInvCDF(x)
            val y = floor(mu + sigma * (z + g * (z * z - 1.0) / 6.0) + 0.5)
            if (y < 0) {
                return 0
            }
            return if (y > n) {
                n
            } else y.toInt()
        }

        protected fun searchUpCDF(
            x: Double, n: Int, p: Double,
            start: Int, cdfAtStart: Double, recursive: Boolean
        ): Int {
            var i = start
            var cdf = cdfAtStart
            while (x > cdf) {
                i++
                cdf = cdf + binomialPMF(i, n, p, recursive)
            }
            return i
        }

        protected fun searchDownCDF(
            x: Double, n: Int, p: Double,
            start: Int, cdfAtStart: Double, recursive: Boolean
        ): Int {
            var i = start
            var cdfi = cdfAtStart
            while (i > 0) {
                val cdfim1 = cdfi - binomialPMF(i, n, p, recursive)
                if (cdfim1 <= x && x < cdfi) {
                    return if (KSLMath.equal(cdfim1, x)) // must handle invCDF(cdf(x) = x)
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

    }


}