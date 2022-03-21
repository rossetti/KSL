package ksl.utilities.distributions

import ksl.utilities.math.KSLMath
import kotlin.math.*

class Poisson {
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
            require(moments.size >= 1) { "Must provide a mean." }
            val mean = moments[0]
            return mean > 0
        }

        /**
         * @param moments the moments to check
         * @return an array holding the moments
         */
        fun getParametersFromMoments(vararg moments: Double): DoubleArray {
            require(canMatchMoments(*moments)) { "Mean must be positive. You provided " + moments[0] + "." }
            return doubleArrayOf(moments[0])
        }

//        fun createFromMoments(vararg moments: Double): Poisson? {
//            val prob = getParametersFromMoments(*moments)
//            return Poisson(prob)
//        }

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
            var sum = 0.0
            sum = if (lnp <= KSLMath.smallestExponentialArgument) {
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
         * Uses the recursive logarithmic algorithm
         *
         * @param j    value for which prob is needed
         * @param mean of the distribution
         * @return the PMF value
         */
        fun poissonPMF(j: Int, mean: Double): Double {
            return poissonPMF(j, mean, true)
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
        fun poissonPMF(j: Int, mean: Double, recursive: Boolean): Double {
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
         * Uses the recursive logarithmic algorithm
         *
         * @param j    value for which prob is needed
         * @param mean of the distribution
         * @return the cdf value
         */
        fun poissonCDF(j: Int, mean: Double): Double {
            return poissonPMF(j, mean, true)
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
        fun poissonCDF(j: Int, mean: Double, recursive: Boolean): Double {
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
         * Uses the recursive logarithmic algorithm
         *
         * @param j    value for which ccdf is needed
         * @param mean of the distribution
         * @return the complimentary CDF value
         */
        fun poissonCCDF(j: Int, mean: Double): Double {
            return poissonCCDF(j, mean, true)
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
        fun poissonCCDF(j: Int, mean: Double, recursive: Boolean): Double {
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
        fun poissonLF1(x: Double, mean: Double, recursive: Boolean): Double {
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
        fun poissonLF2(x: Double, mean: Double, recursive: Boolean): Double {
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
         * Uses the recursive logarithmic algorithm
         *
         * @param p    The probability that the quantile is needed for
         * @param mean of the distribution
         * @return the quantile associated with the supplied probablity
         */
        fun poissonInvCDF(p: Double, mean: Double): Int {
            return poissonInvCDF(p, mean, true)
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
        fun poissonInvCDF(p: Double, mean: Double, recursive: Boolean): Int {
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
            start: Int, cdfAtStart: Double, recursive: Boolean
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
            start: Int, cdfAtStart: Double, recursive: Boolean
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