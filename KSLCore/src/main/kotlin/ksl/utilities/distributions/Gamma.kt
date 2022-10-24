/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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
import ksl.utilities.exceptions.KSLTooManyIterationsException
import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GammaRV
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.*

/** Models random variables that have gamma distribution
 * For more information on the gamma distribution and its related functions, see
 * "Object-Oriented Numerical Methods" by D. Besset
 * @param theShape The shape parameter of the distribution, must be greater than 0
 * @param theScale The scale parameter of the distribution, must be greater than 0
 * @param name an optional name/label
 */
class Gamma (theShape: Double = 1.0, theScale: Double = 1.0, name: String? = null) :
    Distribution<Gamma>(name), ContinuousDistributionIfc, LossFunctionDistributionIfc, InverseCDFIfc, GetRVariableIfc {

    init {
        require(theShape > 0) { "Shape parameter must be positive" }
        require(theScale > 0) { "Scale parameter must be positive" }
    }

    /**
     *  the shape must be greater than 0.0
     */
    var shape = theShape
        set(value) {
            require(value > 0) { "Shape parameter must be positive" }
            field = value
        }

    /**
     *  the scale must be greater than 0.0
     */
    var scale = theScale
        set(value) {
            require(value > 0) { "Scale parameter must be positive" }
            field = value
        }

    /** Constructs a gamma distribution with
     * shape = parameters[0] and scale = parameters[1]
     * @param parameters An array with the shape and scale
     */
    constructor(parameters: DoubleArray) : this(parameters[0], parameters[1], null)

    override fun instance(): Gamma {
        return Gamma(shape, scale)
    }

    override fun domain(): Interval {
        return Interval(0.0, Double.POSITIVE_INFINITY)
    }

    override fun mean(): Double {
        return shape * scale
    }

    /**
     *
     * @return the 2nd moment
     */
    val moment2: Double
        get() = scale * mean() * (shape + 1.0)

    /**
     *
     * @return the 3rd moment
     */
    val moment3: Double
        get() = scale * moment2 * (shape + 2.0)

    /**
     *
     * @return the 4th moment
     */
    val moment4: Double
        get() = scale * moment3 * (shape + 3.0)

    /**
     *
     * @param n the desired moment
     * @return the nth moment
     */
    fun moment(n: Int): Double {
        require(n >= 1) { "The moment should be >= 1" }
        if (n == 1) {
            return mean()
        }
        val y = scale.pow(n.toDouble())
        val t = gammaFunction(shape + n)
        val b = gammaFunction(shape)
        return y * (t / b)
    }

    override fun variance(): Double {
        return shape * scale * scale
    }

    override fun cdf(x: Double): Double {
        return if (x <= 0) {
            0.0
        } else incompleteGammaFunction(x / scale, shape, maxNumIterations, numericalPrecision)
    }

    /** Provides the inverse cumulative distribution function for the distribution
     * This is based on a numerical routine that computes the percentage points for the chi-squared distribution
     * @param p The probability to be evaluated for the inverse, p must be [0,1] or
     * an IllegalArgumentException is thrown
     * p = 0.0 returns 0.0
     * p = 1.0 returns Double.POSITIVE_INFINITY
     *
     * @return The inverse cdf evaluated at p
     */
    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Probability must be [0,1]" }
        if (p <= 0.0) {
            return 0.0
        }
        if (p >= 1.0) {
            return Double.POSITIVE_INFINITY
        }
        val x: Double
        // ...special case: exponential distribution
        if (shape == 1.0) {
            x = -scale * ln(1.0 - p)
            return x
        }
        // ...compute the gamma(alpha, beta) inverse.
        //    ...compute the chi-square inverse with 2*alpha degrees of
        //      freedom, which is equivalent to gamma(alpha, 2).
        val v = 2.0 * shape
//        val g = myLNGammaOfShape
//        val chi2 = invChiSquareDistribution(p, v, g, myMaxIterations, myNumericalPrecision)
        val chi2 = invChiSquareDistribution(p, v, maxIncGammaIterations = maxNumIterations, EPS = numericalPrecision)

        // ...transfer chi-square to gamma.
        x = scale * chi2 / 2.0
        return x
    }

    override fun pdf(x: Double): Double {
        return if (x > 0.0) {
            //double norm = Math.log(myScale) * myShape + logGammaFunction(myShape);
            //val norm = norm
            val norm = ln(scale) * shape + logGammaFunction(shape)
            exp(ln(x) * (shape - 1.0) - x / scale - norm)
        } else {
            0.0
        }
    }

//    protected fun setNorm(scale: Double, shape: Double) {
//        myLNGammaOfShape = logGammaFunction(shape)
//        norm = Math.log(scale) * shape + myLNGammaOfShape
//    }

    /** Gets the kurtosis of the distribution
     * @return the kurtosis
     */
    val kurtosis: Double
        get() = 6.0 / shape

    /** Gets the skewness of the distribution
     * @return the skewness
     */
    val skewness: Double
        get() = 2.0 / sqrt(shape)

    /**
     * the maximum number of iterations for the gamma functions
     */
    var maxNumIterations: Int = DEFAULT_MAX_ITERATIONS
        set(iterations) {
            field = max(iterations, DEFAULT_MAX_ITERATIONS)
        }

    /**
     * the numerical precision used in computing the gamma functions
     */
    var numericalPrecision: Double = KSLMath.defaultNumericalPrecision
        set(precision) {
            field = if (precision < KSLMath.machinePrecision) {
                KSLMath.defaultNumericalPrecision
            } else {
                precision
            }
        }

    /** Sets the parameters for the distribution with
     * shape = parameters[0] and scale = parameters[1]
     *
     * @param params an array of doubles representing the parameters for the distribution
     */
    override fun parameters(params: DoubleArray) {
        shape = params[0]
        scale = params[1]
    }

    /** Gets the parameters for the distribution
     *
     * @return Returns an array of the parameters for the distribution
     */
    override fun parameters(): DoubleArray {
        return doubleArrayOf(shape, scale)
    }

    override fun firstOrderLossFunction(x: Double): Double {
        if (x <= 0.0) {
            return mean() - x
        }
        val mu = 1.0 / scale
        return ((shape - mu * x) * complementaryCDF(x) + x * pdf(x)) / mu
    }

    override fun secondOrderLossFunction(x: Double): Double {
        if (x <= 0.0) {
            val m = mean()
            val m2 = variance() + m * m
            return 0.5 * (m2 - 2.0 * x * m + x * x)
        }
        val mu = 1.0 / scale
        var g2 = ((shape - mu * x) * (shape - mu * x) + shape) * complementaryCDF(x)
        g2 = g2 + (shape - mu * x + 1.0) * x * pdf(x)
        g2 = 0.5 * g2 / (mu * mu)
        return g2
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return GammaRV(shape, scale, stream)
    }

    companion object {
        const val DEFAULT_MAX_ITERATIONS = 5000

        /**
         * The maximum number of iterations permitted for the incomplete gamma function
         * evaluation process
         */
        const val INC_GAMMA_MAX_ITERATIONS = 5000

        /**
         *  The maximum number of iterations permitted in the chi-square cdf computation
         */
        const val CHISQ_CDF_SERIES_MAX_ITERATIONS = 500

        private val sqrt2Pi = sqrt(2.0 * PI)

        private val coefficients = doubleArrayOf(
            76.18009172947146, -86.50532032941677, 24.01409824083091,
            -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5
        )

        /** Algorithm AS 91   Appl. Statist. (1975) Vol.24, P.35
         *
         * To evaluate the percentage points of the chi-squared
         * probability distribution function.
         *
         * logGammaFunction(v/2.0))
         *
         * Incorporates the suggested changes in AS R85 (vol.40(1),
         * pp.233-5, 1991)
         *
         * Auxiliary routines required: PPND = AS 111 (or AS 241) and
         * GAMMAD = AS 239.
         *
         * @param p must lie in the range [0.0,1.0]
         * @param dof must be positive, degrees of freedom
         * @param maxSeriesIterations must be greater than 0. Maximum number of iterations
         * permitted in the series computation, default is 500
         * @param maxIncGammaIterations maximum number of iterations for incomplete gamma computation
         * @param EPS the numerical precision for convergence of series/continued fraction evaluation
         *
         * @return The quantile at p
         */
        fun invChiSquareDistribution(
            p: Double, dof: Double,
            maxSeriesIterations : Int = CHISQ_CDF_SERIES_MAX_ITERATIONS,
            maxIncGammaIterations: Int = INC_GAMMA_MAX_ITERATIONS,
            EPS: Double = KSLMath.defaultNumericalPrecision
        ): Double {
            require(maxSeriesIterations > 0){"The maximum number of iterations for the series must be > 0"}
            require(maxIncGammaIterations > 0){"The maximum number of iterations for the incomplete gamma computation must be > 0"}
            require(EPS >= KSLMath.machinePrecision) {
                "The precision must be >= ${KSLMath.machinePrecision}"
            }
            require(!(p < 0.0 || p > 1.0)) { "Probability must be [0,1]" }
            require(dof > 0) { "Degrees of Freedom must be >= 1" }

            val g = logGammaFunction(dof / 2.0)

            val aa = 0.6931471806
            val e = 0.0000005
            val half = 0.5
            val one = 1.0
            val two = 2.0
            val three = 3.0
            val six = 6.0
            val c1 = 0.01
            val c2 = 0.222222
            val c3 = 0.32
            val c4 = 0.4
            val c5 = 1.24
            val c6 = 2.2
            val c7 = 4.67
            val c8 = 6.66
            val c9 = 6.73
            val c10 = 13.32
            val c11 = 60.0
            val c12 = 70.0
            val c13 = 84.0
            val c14 = 105.0
            val c15 = 120.0
            val c16 = 127.0
            val c17 = 140.0
            val c18 = 1175.0
            val c19 = 210.0
            val c20 = 252.0
            val c21 = 2264.0
            val c22 = 294.0
            val c23 = 346.0
            val c24 = 420.0
            val c25 = 462.0
            val c26 = 606.0
            val c27 = 672.0
            val c28 = 707.0
            val c29 = 735.0
            val c30 = 889.0
            val c31 = 932.0
            val c32 = 966.0
            val c33 = 1141.0
            val c34 = 1182.0
            val c35 = 1278.0
            val c36 = 1740.0
            val c37 = 2520.0
            val c38 = 5040.0

            val ppch: Double
            var a: Double
            var b: Double
            var ch: Double
            var p1: Double
            var p2: Double
            var q: Double
            var s1: Double
            var s2: Double
            var s3: Double
            var s4: Double
            var s5: Double
            var s6: Double
            var t: Double
            val x: Double
            val xx: Double = half * dof
            val c = xx - one

            // ...starting approximation for small chi-squared

            if (dof >= -c5 * ln(p)) {
                //....starting approximation for v less than or equal to 0.32
                if (dof > c3) {
                    // call to algorithm AS 111 - note that p has been tested above.
                    //AS 241 could be used as an alternative.
                    x = Normal.stdNormalInvCDF(p)

                    // starting approximation using Wilson and Hilferty estimate
                    p1 = c2 / dof
                    ch = dof * (x * sqrt(p1) + one - p1).pow(3.0)

                    // starting approximation for p tending to 1
                    if (ch > c6 * dof + six) {
                        ch = -two * (ln(one - p) - c * ln(half * ch) + g)
                    }
                } else {
                    ch = c4
                    a = ln(one - p)
                    do {
                        q = ch
                        p1 = one + ch * (c7 + ch)
                        p2 = ch * (c9 + ch * (c8 + ch))
                        t = -half + (c7 + two * ch) / p1 - (c9 + ch * (c10
                                + three * ch)) / p2
                        ch = ch - (one - exp(a + g + half * ch + c * aa) * p2 / p1) / t
                    } while (abs(q / ch - one) > c1)
                }
            } else {
                ch = (p * xx * exp(g + xx * aa)).pow(one / xx)
                if (ch < e) {
                    ppch = ch
                    return ppch
                }
            }

            //....call to algorithm AS 239 and calculation of seven term Taylor series
            for (i in 1..maxSeriesIterations) {
                q = ch
                p1 = half * ch
                p2 = p - incompleteGammaFunction(p1, xx, maxIncGammaIterations, EPS)
                t = p2 * exp(xx * aa + g + p1 - c * ln(ch))
                b = t / ch
                a = half * t - b * c
                s1 = (c19 + a * (c17 + a * (c14 + a * (c13 + a * (c12
                        + c11 * a))))) / c24
                s2 = (c24 + a * (c29 + a * (c32 + a * (c33 + c35
                        * a)))) / c37
                s3 = (c19 + a * (c25 + a * (c28 + c31 * a))) / c37
                s4 = (c20 + a * (c27 + c34 * a) + c * (c22 + a * (c30
                        + c36 * a))) / c38
                s5 = (c13 + c21 * a + c * (c18 + c26 * a)) / c37
                s6 = (c15 + c * (c23 + c16 * c)) / c38
                ch = ch + t * (one + half * t * s1 - b * c * (s1 - b
                        * (s2 - b * (s3 - b * (s4 - b * (s5 - b * s6))))))
                if (abs(q / ch - one) > e) {
                    ppch = ch
                    return ppch
                }
            }
            ppch = ch
            return ppch
        }

        /** Computes the gamma function at x
         * @return The value of the gamma function evaluated at x
         * @param x The value to be evaluated, x must be &gt; 0
         */
        fun gammaFunction(x: Double): Double {
            require(x > 0.0) { "Argument must be > 0" }
            return if (x <= 1.0) {
                gammaFunction(x + 1.0) / x
            } else {
                exp(leadingFactor(x)) * series(x) * sqrt2Pi / x
            }
        }

        /** Computes the natural logarithm of the gamma function at x.  Useful
         * when x gets large.
         * @return The natural logarithm of the gamma function
         * @param x The value to be evaluated, x must be &gt; 0
         */
        fun logGammaFunction(x: Double): Double {
            require(x > 0.0) { "Argument must be > 0" }
            return if (x <= 1.0) {
                logGammaFunction(x + 1.0) - ln(x)
            } else {
                leadingFactor(x) + ln(series(x) * sqrt2Pi / x)
            }
        }

        /** Computes the incomplete gamma function at x
         * @return the value of the incomplete gamma function evaluated at x
         * @param x The value to be evaluated, must be &gt; 0
         * @param alpha must be &gt; 0
         * @param maxIterations maximum number of iterations for series/continued fraction evaluation
         * @param eps the numerical precision for convergence of series/continued fraction evaluation
         */
        fun incompleteGammaFunction(x: Double, alpha: Double,
                                    maxIterations: Int = INC_GAMMA_MAX_ITERATIONS,
                                    eps: Double = KSLMath.defaultNumericalPrecision): Double {
            require(maxIterations > 0) {
                "The maximum number of iterations for the incomplete gamma computation must be > 0"
            }
            require(eps >= KSLMath.machinePrecision) {
                "The precision must be >= ${KSLMath.machinePrecision}"
            }
            require(alpha > 0.0) { "Argument alpha must be > 0" }
            require(x >= 0.0) { "Argument x must be >= 0" }
            if (x == 0.0) {
                return 0.0
            }
            return if (x < alpha + 1.0) {
                incGammaSeries(x, alpha, maxIterations, eps) // use series expansion
            } else {
                1.0 - incGammaFraction(x, alpha, maxIterations, eps) // use continued fraction
            }
        }

        /** Computes the digamma function using AS 103
         *
         * Reference:
         * Jose Bernardo,
         * Algorithm AS 103:
         * Psi ( Digamma ) Function,
         * Applied Statistics,
         * Volume 25, Number 3, 1976, pages 315-317.
         * @param x the value to evaluate
         * @return the function value
         */
        fun diGammaFunction(x: Double): Double {
            require(x >= 0.0) { "Argument x must be >= 0" }
            val c = 8.5
            val d1 = -0.5772156649
            val s = 0.00001
            val s3 = 0.08333333333
            val s4 = 0.0083333333333
            val s5 = 0.003968253968
            var value = 0.0
            var y: Double = x
            //
            //  Use approximation if argument <= S.
            //
            if (y <= s) {
                value = d1 - 1.0 / y
                return value
            }
            //
            //  Reduce to DIGAMA(X + N) where (X + N) >= C.
            //
            while (y < c) {
                value = value - 1.0 / y
                y = y + 1.0
            }
            //
            //  Use Stirling's (actually de Moivre's) expansion if argument > C.
            //
            var r: Double = 1.0 / y
            value = value + ln(y) - 0.5 * r
            r = r * r
            value = value - r * (s3 - r * (s4 - r * s5))
            return value
        }

        /** Computes the digamma function
         * Mark Johnson, 2nd September 2007
         *
         * Computes the Î¨(x) or digamma function, i.e., the derivative of the
         * log gamma function, using a series expansion.
         *
         * http://www.cog.brown.edu/~mj/code/digamma.c
         *
         * @param argX the value to evaluate
         * @return the function value
         */
        fun digamma(argX: Double): Double {
            require(argX >= 0.0) { "Argument x must be >= 0" }
            var x = argX
            var result = 0.0
            while (x < 7) {
                result -= 1 / x
                ++x
            }
            x -= 1.0 / 2.0
            val xx: Double = 1.0 / x
            val xx2 = xx * xx
            val xx4: Double = xx2 * xx2
            result += ln(x) + 1.0 / 24.0 * xx2 - 7.0 / 960.0 * xx4 + 31.0 / 8064.0 * xx4 * xx2 - 127.0 / 30720.0 * xx4 * xx4
            return result
        }

        /** Evaluates the incomplete gamma series
         *
         * @param x the value to evaluate
         * @param alpha the shape
         * @param maxIterations the max number of iterations
         * @param eps the machine epsilon
         * @return the value of the incomplete gamma series
         */
        private fun incGammaSeries(x: Double, alpha: Double, maxIterations: Int, eps: Double): Double {
            require(x >= 0.0) { "Argument x must be >= 0" }
            // x must be gte 0 now
            var n: Int
            var sum: Double
            var del: Double
            var ap: Double
            if (x == 0.0) {
                return 0.0
            } else { // x > 0 now
                ap = alpha
                del = 1.0 / alpha
                sum = del
                n = 1
                while (n <= maxIterations) {
                    ap = ap + 1.0
                    del = del * x / ap
                    sum = sum + del
                    if (abs(del) / abs(sum) < eps) {
                        return sum * exp(-x + alpha * ln(x) - logGammaFunction(alpha))
                    }
                    n++
                }
            }
            throw KSLTooManyIterationsException("Too many iterations in computing incomplete gamma function, increase max iterations.")
        }

        /** Evaluates the incomplete gamma fraction
         *
         * @param x the value to evaluate
         * @param alpha the shape
         * @param maxIterations the max number of iterations
         * @param eps the machine epsilon
         * @return the value of the incomplete gamma fraction
         */
        private fun incGammaFraction(x: Double, alpha: Double, maxIterations: Int, eps: Double): Double {
            var gold = 0.0
            var g: Double
            var fac = 1.0
            var b1 = 1.0
            var b0 = 0.0
            var anf: Double
            var ana: Double
            var an: Double
            var a0 = 1.0
            var a1 = x
            var n = 1
            while (n <= maxIterations) {
                an = n.toDouble()
                ana = an - alpha
                a0 = (a1 + a0 * ana) * fac
                b0 = (b1 + b0 * ana) * fac
                anf = an * fac
                a1 = x * a0 + anf * a1
                b1 = x * b0 + anf * b1
                if (!KSLMath.equal(a1, 0.0)) { // I think this is okay
                    fac = 1.0 / a1
                    g = b1 * fac
                    if (abs((g - gold) / g) < eps) {
                        return exp(-x + alpha * ln(x) - logGammaFunction(alpha)) * g
                    }
                    gold = g
                }
                n++
            }
            throw KSLTooManyIterationsException("Too many iterations in computing incomplete gamma function, increase max iterations.")
        }

        /**
         * @return double		value of the series in Lanczos formula.
         * @param x double
         */
        private fun series(x: Double): Double {
            var answer = 1.000000000190015
            var term = x
            for (i in 0..5) {
                term += 1.0
                answer += coefficients[i] / term
            }
            return answer
        }

        private fun leadingFactor(x: Double): Double {
            val temp = x + 5.5
            return ln(temp) * (x + 0.5) - temp
        }

        /** Computes the parameters (shape and scale) by matching to mean and variance
         * element[0] = shape
         * element[1] = scale
         *
         * @param mean must be &gt; 0
         * @param variance must be &gt; 0
         * @return the parameter array
         */
        fun parametersFromMeanAndVariance(mean: Double, variance: Double): DoubleArray {
            require(mean > 0.0) { "The mean must be > 0" }
            require(variance > 0.0) { "The mean must be > 0" }
            val param = DoubleArray(2)
            val shape = mean * mean / variance
            val scale = variance / mean
            param[0] = shape
            param[1] = scale
            return param
        }

        /**
         * Computes the parameters (shape and scale) by matching to mean and
         * variance element[0] = shape element[1] = scale
         *
         */
        fun parametersFromMoments(vararg moments: Double): DoubleArray {
            require(moments.size >= 2) {
                "The mean and variance must be provided. You only provided $moments.size moments."
            }
            return parametersFromMeanAndVariance(moments[0], moments[1])
        }
    }
}

fun main() {
    val shape = 0.1
    val scale = 8.0
    val g = Gamma(shape, scale)
    println("shape (alpha) = " + g.shape)
    println("scale (beta)  = " + g.scale)
    println("mean = " + g.mean())
    println("var = " + g.variance())
    var x = 9.0
    println("g at  " + x + " = " + g.pdf(x))
    println("G at  " + x + " = " + g.cdf(x))
    println("G0 at  " + x + " = " + g.complementaryCDF(x))
    println("G1 at  " + x + " = " + g.firstOrderLossFunction(x))
    println("G2 at  " + x + " = " + g.secondOrderLossFunction(x))
    println("digamma(1) = " + Gamma.diGammaFunction(1.0))
    println("digamma(1) = " + Gamma.digamma(1.0))

    // test digamma function
    val n = 20
    x = 0.0
    val delta = 0.1
    for (i in 1..n) {
        x = x + delta
        println("digamma(x=" + x + ") = " + Gamma.digamma(x))
    }
}