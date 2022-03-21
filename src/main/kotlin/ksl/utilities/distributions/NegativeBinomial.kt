package ksl.utilities.distributions

import ksl.utilities.math.KSLMath
import kotlin.math.*

class NegativeBinomial {

    companion object {
        /** Computes the binomial coefficient.  Computes the number of combinations of size k
         * that can be formed from n distinct objects.
         * @param n The total number of distinct items
         * @param k The number of subsets
         * @return the coefficient
         */
        private fun binomialCoefficient(n: Double, k: Double): Double {
            return exp(logFactorial(n) - logFactorial(k) - logFactorial(n - k))
        }

        /** Computes the natural logarithm of the factorial operator.
         * ln(n!)
         * @param n The value to be operated on.
         * @return the natural log of the factorial
         */
        private fun logFactorial(n: Double): Double {
            return Gamma.logGammaFunction(n + 1.0)
        }

        /** Computes the probability mass function at j using a
         * recursive (iterative) algorithm using logarithms
         *
         * @param j the value to be evaluated
         * @param r number of successes
         * @param p the probability, must be in range (0,1)
         * @return the probability
         */
        fun recursivePMF(j: Int, r: Double, p: Double): Double {
            require(r > 0) { "The number of successes must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            if (j < 0) {
                return 0.0
            }
            var y = r * ln(p)
            val lnq = ln(1.0 - p)
            for (i in 1..j) {
                y = ln(r - 1.0 + i) - +ln(i.toDouble()) + lnq + y
            }
            require(y < KSLMath.largestExponentialArgument) { "Term overflow due to input parameters" }
            return if (y <= KSLMath.smallestExponentialArgument) {
                0.0
            } else exp(y)
        }

        /** Computes the cdf at j using a recursive (iterative) algorithm using logarithms
         *
         * @param j the value to be evaluated
         * @param r number of successes
         * @param p the probability, must be in range (0,1)
         * @return the probability
         */
        fun recursiveCDF(j: Int, r: Double, p: Double): Double {
            require(r > 0) { "The number of successes must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            if (j < 0) {
                return 0.0
            }
            var y = r * ln(p)
            if (j == 0) {
                require(y < KSLMath.largestExponentialArgument) { "Term overflow due to input parameters" }
                if (y <= KSLMath.smallestExponentialArgument) {
                    return 0.0
                }
            }
            val lnq = ln(1.0 - p)
            var sum = exp(y)
            for (i in 1..j) {
                y = ln(r - 1.0 + i) - +ln(i.toDouble()) + lnq + y
                require(y < KSLMath.largestExponentialArgument) { "Term overflow due to input parameters" }
                sum = if (y <= KSLMath.smallestExponentialArgument) {
                    continue
                } else {
                    sum + exp(y)
                }
            }
            return sum
        }

        /** Allows static computation of prob mass function
         * assumes that distribution's range is {0,1, ...}
         * Uses the recursive logarithmic algorithm
         *
         * @param j value for which prob is needed
         * @param r num of successes
         * @param p prob of success, must be in range (0,1)
         * @return the probability mass function evaluated at j
         */
        fun negBinomialPMF(j: Int, r: Double, p: Double): Double {
            return negBinomialPMF(j, r, p, true)
        }

        /** Allows static computation of prob mass function
         * assumes that distribution's range is {0,1, ...}
         *
         * @param j value for which prob is needed
         * @param r num of successes
         * @param p prob of success, must be in range (0,1)
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the probability
         */
        fun negBinomialPMF(j: Int, r: Double, p: Double, recursive: Boolean): Double {
            require(r > 0) { "The number of successes must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            if (j < 0) {
                return 0.0
            }
            if (recursive) {
                return recursivePMF(j, r, p)
            }
            val k = r - 1.0
            val bc = binomialCoefficient(j + k, k)
            val lny = r * ln(p) + j * ln(1.0 - p)
            val y = exp(lny)
            return bc * y
        }

        /** Allows static computation of the CDF
         * assumes that distribution's range is {0,1, ...}
         * Uses the recursive logarithmic algorithm
         *
         * @param j value for which cdf is needed
         * @param r num of successes
         * @param p prob of success, must be in range (0,1)
         * @return the probability
         */
        fun negBinomialCDF(j: Int, r: Double, p: Double): Double {
            return negBinomialCDF(j, r, p, true)
        }

        /** Allows static computation of the CDF
         * assumes that distribution's range is {0,1, ...}
         *
         * @param j value for which cdf is needed
         * @param r num of successes
         * @param p prob of success, must be in range (0,1)
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the probability
         */
        fun negBinomialCDF(j: Int, r: Double, p: Double, recursive: Boolean): Double {
            require(r > 0) { "The number of successes must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            if (j < 0) {
                return 0.0
            }
            return if (recursive) {
                recursiveCDF(j, r, p)
            } else Beta.regularizedIncompleteBetaFunction(p, r, (j + 1).toDouble())
        }

        /** Allows static computation of complementary cdf function
         * assumes that distribution's range is {0,1, ...}
         * Uses the recursive logarithmic algorithm
         *
         * @param j value for which ccdf is needed
         * @param r num of successes
         * @param p prob of success, must be in range (0,1)
         * @return the probability
         */
        fun negBinomialCCDF(j: Int, r: Double, p: Double): Double {
            return negBinomialCCDF(j, r, p, true)
        }

        /** Allows static computation of complementary cdf function
         * assumes that distribution's range is {0,1, ...}
         *
         * @param j value for which ccdf is needed
         * @param r num of successes
         * @param p prob of success, must be in range (0,1)
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the probability
         */
        fun negBinomialCCDF(j: Int, r: Double, p: Double, recursive: Boolean): Double {
            require(r > 0) { "The number of successes must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            return if (j < 0) {
                1.0
            } else 1.0 - negBinomialCDF(j, r, p, recursive)
        }

        /** Allows static computation of 1st order loss function
         * assumes that distribution's range is {0,1, ...}
         * Uses the recursive logarithmic algorithm
         *
         * @param j value for which 1st order loss function is needed
         * @param r num of successes
         * @param p prob of success, must be in range (0,1)
         * @return the loss function value
         */
        fun negBinomialLF1(j: Int, r: Double, p: Double): Double {
            return negBinomialLF1(j, r, p, true)
        }

        /** Allows static computation of 1st order loss function
         * assumes that distribution's range is {0,1, ...}
         *
         * @param j value for which 1st order loss function is needed
         * @param r num of successes
         * @param p prob of success, must be in range (0,1)
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the loss function value
         */
        fun negBinomialLF1(j: Int, r: Double, p: Double, recursive: Boolean): Double {
            require(r > 0) { "The number of successes must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            val mu = r * (1.0 - p) / p // the mean
            return if (j < 0) {
                floor(abs(j).toDouble()) + mu
            } else if (j > 0) {
                val b = (1.0 - p) / p
                val g = negBinomialPMF(j, r, p, recursive)
                val g0 = negBinomialCCDF(j, r, p, recursive)
                val g1 = -1.0 * (j - r * b) * g0 + (j + r) * b * g
                g1
            } else { // j == 0
                mu
            }
        }

        /** Allows static computation of 2nd order loss function
         * assumes that distribution's range is {0,1, ...}
         * Uses the recursive logarithmic algorithm
         *
         * @param j value for which 2nd order loss function is needed
         * @param r num of successes
         * @param p prob of success, must be in range (0,1)
         * @return the loss function value
         */
        fun negBinomialLF2(j: Int, r: Double, p: Double): Double {
            return negBinomialLF2(j, r, p, true)
        }

        /** Allows static computation of 2nd order loss function
         * assumes that distribution's range is {0,1, ...}
         *
         * @param j value for which 2nd order loss function is needed
         * @param r num of successes
         * @param p prob of success
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the loss function value
         */
        fun negBinomialLF2(j: Int, r: Double, p: Double, recursive: Boolean): Double {
            require(r > 0) { "The number of successes must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            val mu = r * (1.0 - p) / p
            val v = mu / p
            val sbm = 0.5 * (v + mu * mu - mu) // 1/2 the 2nd binomial moment
            return if (j < 0) {
                var s = 0.0
                for (y in 0 downTo j + 1) {
                    s = s + negBinomialLF1(y, r, p, recursive)
                }
                s + sbm
            } else if (j > 0) {
                val g0: Double
                val g: Double
                var g2: Double
                val b = (1.0 - p) / p
                if (j < 0) {
                    g0 = 1.0
                    g = 0.0
                } else {
                    g = negBinomialPMF(j, r, p, recursive)
                    g0 = negBinomialCCDF(j, r, p, recursive)
                }
                g2 = (r * (r + 1) * b * b - 2.0 * r * b * j + j * (j + 1)) * g0
                g2 = g2 + ((r + 1) * b - j) * (j + r) * b * g
                g2 = 0.5 * g2
                g2
            } else { // j== 0
                sbm
            }
        }

        /** Returns the quantile associated with the supplied probability, x
         * assumes that distribution's range is {0,1, ...}
         * Uses the recursive logarithmic algorithm
         *
         * @param x The probability that the quantile is needed for
         * @param r The number of successes parameter
         * @param p The probability of success, must be in range (0,1)
         * @return the inverse CDF value
         */
        fun negBinomialInvCDF(x: Double, p: Double, r: Double): Int {
            return negBinomialInvCDF(x, p, r, true)
        }

        /** Returns the quantile associated with the supplied probability, x
         * assumes that distribution's range is {0,1, ...}
         *
         * @param x The probability that the quantile is needed for
         * @param r The number of successes parameter
         * @param p The probability of success, must be in range (0,1)
         * @param recursive true indicates that the recursive logarithmic algorithm should be used
         * @return the inverse CDF value
         */
        fun negBinomialInvCDF(x: Double, p: Double, r: Double, recursive: Boolean): Int {
            require(r > 0) { "The number of successes must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            require(!(x < 0.0 || x > 1.0)) { "Supplied probability was $x Probability must be [0,1]" }
            if (x <= 0.0) {
                return 0
            }
            if (x >= 1.0) {
                return Int.MAX_VALUE
            }

            // check for geometric case
            if (KSLMath.equal(r, 1.0)) {
                return ceil(ln(1.0 - x) / ln(1.0 - p) - 1.0).toInt()
            }

            // get approximate quantile from normal approximation
            // and Cornish-Fisher expansion
            val start = invCDFViaNormalApprox(x, r, p)
            val cdfAtStart = negBinomialCDF(start, r, p, recursive)

            //System.out.println("start = " + start);
            //System.out.println("cdfAtStart = " + cdfAtStart);
            //System.out.println("p = " + p);
            //System.out.println();
            return if (x >= cdfAtStart) {
                searchUpCDF(x, r, p, start, cdfAtStart, recursive)
            } else {
                searchDownCDF(x, r, p, start, cdfAtStart, recursive)
            }
        }

        /**
         *
         * @param x the value to evaluate
         * @param r the trial number
         * @param p the probability of success
         * @param start the starting place for search
         * @param cdfAtStart the CDF at the starting place
         * @param recursive true for using recursive algorithm
         * @return the found value
         */
        private fun searchUpCDF(
            x: Double, r: Double, p: Double,
            start: Int, cdfAtStart: Double, recursive: Boolean
        ): Int {
            var i = start
            var cdf = cdfAtStart
            while (x > cdf) {
                i++
                cdf = cdf + negBinomialPMF(i, r, p, recursive)
            }
            return i
        }

        /**
         *
         * @param x the value to evaluate
         * @param r the trial number
         * @param p the probability of success
         * @param start the starting place for search
         * @param cdfAtStart the CDF at the starting place
         * @param recursive true for using recursive algorithm
         * @return the found value
         */
        private fun searchDownCDF(
            x: Double, r: Double, p: Double,
            start: Int, cdfAtStart: Double, recursive: Boolean
        ): Int {
            var i = start
            var cdfi = cdfAtStart
            while (i > 0) {
                val cdfim1 = cdfi - negBinomialPMF(i, r, p, recursive)
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

        /**
         *
         * @param x the value to evaluate
         * @param r the trial number
         * @param p the probability of success
         * @return the inverse value at the point x
         */
        protected fun invCDFViaNormalApprox(x: Double, r: Double, p: Double): Int {
            require(r > 0) { "The number of successes must be > 0" }
            require(!(p <= 0.0 || p >= 1.0)) { "Success Probability must be in (0,1)" }
            require(!(x < 0.0 || x > 1.0)) { "Supplied probability was $x Probability must be [0,1]" }
            if (x <= 0.0) {
                return 0
            }
            if (x >= 1.0) {
                return Int.MAX_VALUE
            }
            val dQ = 1.0 / p
            val dP = (1.0 - p) * dQ
            val mu = r * dP
            val sigma = sqrt(r * dP * dQ)
            val g = (dQ + dP) / sigma

            /* y := approx.value (Cornish-Fisher expansion) :  */
            val z = Normal.stdNormalInvCDF(x)
            val y = floor(mu + sigma * (z + g * (z * z - 1.0) / 6.0) + 0.5)
            return if (y < 0) {
                0
            } else {
                y.toInt()
            }
        }

        fun canMatchMoments(vararg moments: Double): Boolean {
            require(moments.size >= 2) { "Must provide a mean and a variance. You provided " + moments.size + " moments." }
            val mean = moments[0]
            val v = moments[1]
            return (mean > 0) && (v > 0) && (mean < v)
        }

        fun getParametersFromMoments(vararg moments: Double): DoubleArray {
            require(canMatchMoments(*moments)) { "Mean and variance must be positive and mean < variance. Your mean: " + moments[0] + " and variance: " + moments[1] }
            val mean = moments[0]
            val v = moments[1]
            val vmr = v / mean
            return doubleArrayOf(1 / vmr, mean / (vmr - 1))
        }

//        fun createFromMoments(vararg moments: Double): NegativeBinomial? {
//            val param = getParametersFromMoments(*moments)
//            return NegativeBinomial(param[0], param[1])
//        }

    }
}