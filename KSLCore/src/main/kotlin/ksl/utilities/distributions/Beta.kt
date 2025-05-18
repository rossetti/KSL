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
import ksl.utilities.exceptions.KSLTooManyIterationsException
import ksl.utilities.math.FunctionIfc
import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.BetaRV
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.rootfinding.BisectionRootFinder
import ksl.utilities.rootfinding.RootFinder
import kotlin.math.*

/**
 * Create Beta distribution with the supplied parameters
 *
 * @param alphaShape the first shape parameter
 * @param betaShape the second shape parameter
 */
class Beta(
    alphaShape: Double,
    betaShape: Double,
    name: String? = null
) : Distribution(name), ContinuousDistributionIfc, InverseCDFIfc,
    RVParametersTypeIfc by RVType.Beta, MomentsIfc {
    init {
        require(alphaShape > 0) { "The 1st shape parameter must be > 0" }
        require(betaShape > 0) { "The 2nd shape parameter must be > 0" }
    }

    var alpha: Double = alphaShape
        private set

    var beta: Double = betaShape
        private set

    private var betaAlphaBeta = betaFunction(alpha, beta)
    private var lnBetaAlphaBeta = logBetaFunction(alpha, beta)

    /**
     * Changes the parameters to the supplied values
     *
     * @param alphaShape the first shape parameter
     * @param betaShape the second shape parameter
     */
    fun parameters(alphaShape: Double, betaShape: Double) {
        require(alphaShape > 0) { "The 1st shape parameter must be > 0" }
        require(betaShape > 0) { "The 2nd shape parameter must be > 0" }
        alpha = alphaShape
        beta = betaShape
        betaAlphaBeta = betaFunction(alpha, beta)
        lnBetaAlphaBeta = logBetaFunction(alpha, beta)
    }

    /**
     * Changes the parameters to the supplied values
     * params[0] the alpha shape parameter
     * params[1]the beta shape parameter
     */
    override fun parameters(params: DoubleArray) {
        parameters(params[0], params[1])
    }

    /**
     * Returns the parameters of the distribution.
     *
     * params[0] the alpha shape parameter
     * params[1]the beta shape parameter
     */
    override fun parameters(): DoubleArray {
        return doubleArrayOf(alpha, beta)
    }

    override fun mean(): Double {
        return alpha / (alpha + beta)
    }

    override fun variance(): Double {
        val n = alpha * beta
        val d = (alpha + beta) * (alpha + beta) * (alpha + beta + 1.0)
        return n / d
    }

    override fun cdf(x: Double): Double {
        return stdBetaCDF(x, alpha, beta, lnBetaAlphaBeta)
    }

    override fun pdf(x: Double): Double {
        return if (0.0 < x && x < 1.0) {
            val f1 = x.pow(alpha - 1.0)
            val f2 = (1.0 - x).pow(beta - 1.0)
            f1 * f2 / betaAlphaBeta
        } else {
            0.0
        }
    }

    override fun domain(): Interval = Interval(0.0, 1.0)

    override fun invCDF(p: Double): Double {
        return stdBetaInvCDF(p, alpha, beta, lnBetaAlphaBeta)
    }

    override fun instance(): Beta {
        return Beta(alpha, beta)
    }

    override fun randomVariable(streamNumber: Int, streamProvider: RNStreamProviderIfc): BetaRV {
        return BetaRV(alpha, beta, streamNumber, streamProvider)
    }

    override fun toString(): String {
        return "Beta(alpha=$alpha, beta=$beta)"
    }

    override val mean: Double
        get() = mean()
    override val variance: Double
        get() = variance()
    override val skewness: Double
        get() = (2.0 * (beta - alpha) * sqrt(alpha + beta + 1)) / ((alpha + beta + 2) * sqrt(alpha * beta))

    override val kurtosis: Double
        get() = (6.0 * ((((alpha - beta) * (alpha - beta) * (alpha + beta + 1.0)) - alpha * beta * (alpha + beta + 2.0)))) / (alpha * beta * (alpha + beta + 2.0) * (alpha + beta + 3.0))


    companion object {
        private val myContinuedFraction = IncompleteBetaFunctionFraction()

//        private val myInterval = Interval(0.0, 1.0)

//        private val myRootFinder: RootFinder = BisectionRootFinder()

        private const val delta = 0.01

        /**
         * Computes Beta(z1,z2)
         *
         * @param z1 the first parameter
         * @param z2 the second parameter
         * @return the computed value
         */
        fun betaFunction(z1: Double, z2: Double): Double {
            require(z1 > 0) { "The 1st parameter must be > 0" }
            require(z2 > 0) { "The 2nd parameter must be > 0" }
            val n1 = Gamma.gammaFunction(z1)
            val n2 = Gamma.gammaFunction(z2)
            val d = Gamma.gammaFunction(z1 + z2)
            return n1 * n2 / d
        }

        /**
         * The natural logarithm of Beta(z1,z2)
         *
         * @param z1 the first parameter
         * @param z2 the second parameter
         * @return natural logarithm of Beta(z1,z2)
         */
        fun logBetaFunction(z1: Double, z2: Double): Double {
            require(z1 > 0) { "The 1st parameter must be > 0" }
            require(z2 > 0) { "The 2nd parameter must be > 0" }
            val n1 = Gamma.logGammaFunction(z1)
            val n2 = Gamma.logGammaFunction(z2)
            val d = Gamma.logGammaFunction(z1 + z2)
            return n1 + n2 - d
        }

        /**
         * Computes the incomplete beta function at the supplied x Beta(x, a,
         * b)/Beta(a, b)
         *
         * @param x the point to be evaluated
         * @param a alpha 1
         * @param b alpha 2
         * @return the regularized beta function at the supplied x
         */
        fun incompleteBetaFunction(x: Double, a: Double, b: Double): Double {
            val beta = betaFunction(a, b)
            val rBeta = regularizedIncompleteBetaFunction(x, a, b)
            return rBeta * beta
        }

        /**
         * Computes the regularized incomplete beta function at the supplied x
         *
         * @param x the point to be evaluated
         * @param a alpha 1
         * @param b alpha 2
         * @param lnbeta the natural log of Beta(alpha1,alpha2)
         * @return the regularized incomplete beta function at the supplied x
         */
        fun regularizedIncompleteBetaFunction(
            x: Double, a: Double, b: Double,
            lnbeta: Double = logBetaFunction(a, b)
        ): Double {
            require(!(x < 0.0 || x > 1.0)) { "Argument x, must be in [0,1]" }
            require(a > 0) { "The 1st shape parameter must be > 0" }
            require(b > 0) { "The 2nd shape parameter must be > 0" }
            if (x == 0.0) {
                return 0.0
            }
            if (x == 1.0) {
                return 1.0
            }
            val bt = exp(-lnbeta + a * ln(x) + b * ln(1.0 - x))

            return if (x < (a + 1.0) / (a + b + 2.0)) {
                bt / (myContinuedFraction.evaluateFraction(x, a, b) * a)
            } else {
                1.0 - bt / (myContinuedFraction.evaluateFraction(1.0 - x, b, a) * b)
            }
        }

        /**
         * Computes the continued fraction for the incomplete beta function.
         *
         * @param x the point to be evaluated
         * @param a alpha 1
         * @param b alpha 2
         * @return the continued fraction
         */
        private fun betaContinuedFraction(x: Double, a: Double, b: Double): Double {
            var em: Double
            var tem: Double
            var d: Double
            var bm = 1.0
            var bp: Double
            var bpp: Double
            var az = 1.0
            var am = 1.0
            var ap: Double
            var app: Double
            var aold: Double
            val qab = a + b
            val qap = a + 1.0
            val qam = a - 1.0
            var bz = 1.0 - qab * x / qap
            val maxi = KSLMath.maxNumIterations
            val eps = KSLMath.defaultNumericalPrecision
            for (i in 1..maxi) {
                em = i.toDouble()
                tem = em + em
                d = em * (b - em) * x / ((qam + tem) * (a + tem))
                ap = az + d * am
                bp = bz + d * bm
                d = -(a + em) * (qab + em) * x / ((qap + tem) * (a + tem))
                app = ap + d * az
                bpp = bp + d * bz
                aold = az
                am = ap / bpp
                bm = bp / bpp
                az = app / bpp
                bz = 1.0
                if (abs(az - aold) < eps * abs(az)) {
                    return az
                }
            }
            throw KSLTooManyIterationsException("Too many iterations in computing betaContinuedFraction, increase max iterations via setMaxNumIterations()")
        }

        /**
         * Computes the CDF of the standard beta distribution, has accuracy to about 10e-9
         *
         * @param x          the x value to be evaluated
         * @param alpha1     the first shape parameter, must be greater than 0
         * @param alpha2     the second shape parameter, must be greater than 0
         * @param lnBetaA1A2 the logBetaFunction(alpha1, alpha2)
         */
        fun stdBetaCDF(
            x: Double, alpha1: Double, alpha2: Double,
            lnBetaA1A2: Double = logBetaFunction(alpha1, alpha2)
        ): Double {
            require(alpha1 > 0) { "The 1st shape parameter must be > 0" }
            require(alpha2 > 0) { "The 2nd shape parameter must be > 0" }
            if (x <= 0.0) {
                return 0.0
            }
            return if (x >= 1.0) {
                1.0
            } else regularizedIncompleteBetaFunction(x, alpha1, alpha2, lnBetaA1A2)
        }

        /**
         * Computes the CDF of the standard beta distribution, has accuracy to about 10e-9
         *
         * @param p           the probability that needs to be evaluated
         * @param alpha      the first shape parameter, must be greater than 0
         * @param beta      the second shape parameter, must be greater than 0
         * @param lnBetaA1A2  the logBetaFunction(alpha1, alpha2)
         * @param initialX    an initial approximation for the returned value x
         * @param searchDelta the suggested delta around the initial approximation
         */
        fun stdBetaInvCDF(
            p: Double,
            alpha: Double,
            beta: Double,
            lnBetaA1A2: Double = logBetaFunction(alpha, beta),
            initialX: Double = approximateInvCDF(alpha, beta, p, lnBetaA1A2),
            searchDelta: Double = delta
        ): Double {
//            require(!(initialX < 0.0 || initialX > 1.0)) { "Supplied initial x was $initialX  must be [0,1]" }
            require(searchDelta > 0) { "The search delta must be > 0" }
            require(alpha > 0) { "The 1st shape parameter must be > 0" }
            require(beta > 0) { "The 2nd shape parameter must be > 0" }
            if (KSLMath.equal(p, 1.0)) {
                return 1.0
            }
            if (KSLMath.equal(p, 0.0)) {
                return 0.0
            }
            require(!(p < 0.0 || p > 1.0)) { "Supplied probability was $p Probability must be (0,1)" }
            // set up the search for the root
            // catch edge case of initialX = 0.0 or 1.0
            var xU = if (initialX >= 1.0) {
                1.0 // 0.9999999
            } else {
                Math.min(1.0, initialX + searchDelta)
            }
            var xL = if (initialX <= 0.0) {
                0.0 //0.0000001
            } else {
                Math.max(0.0, initialX - searchDelta)
            }
//            var xL = Math.max(0.0, initialX - searchDelta)
//            var xU = Math.min(1.0, initialX + searchDelta)
            val interval = Interval(xL, xU)

            //TODO should RootFunction and BisectionRootFinder be encapsulated in a class, made once and used many times?
            class RootFunction : FunctionIfc {
                override fun f(x: Double): Double {
                    return stdBetaCDF(x, alpha, beta, lnBetaA1A2) - p
                }
            }

            val rootFunction = RootFunction()
            val found = RootFinder.findInterval(rootFunction, interval)
            if (!found) {
                interval.setInterval(0.0, 1.0)
            } else {
                xL = Math.max(0.0, interval.lowerLimit)
                xU = Math.min(1.0, interval.upperLimit)
                interval.setInterval(xL, xU)
            }
            val rootFinder = BisectionRootFinder(rootFunction, interval)
            rootFinder.maximumIterations = 300
            rootFinder.evaluate()
            if (!rootFinder.hasConverged()) {
                throw KSLTooManyIterationsException("Unable to invert CDF for Beta: Beta(x,$alpha,$beta)=$p")
            }
            return rootFinder.result
        }

        /**
         * Computes an approximation of the invCDF for the Beta distribution Uses part
         * of algorithm AS109, Applied Statistics, vol 26, no 1, 1977, pp 111-114
         *
         * @param pp     Alpha 1 parameter
         * @param qq     Alpha 2 parameter
         * @param a      The point to be evaluated
         * @param lnbeta The log of Beta(alpha1,alpha2)
         * @return the approx cdf value
         */
        private fun approximateInvCDF(pp: Double, qq: Double, a: Double, lnbeta: Double): Double {
            var t: Double
            val s: Double
            val h: Double
            val w: Double
            val x: Double
            var r = sqrt(-ln(a * a))
            val y = r - (2.30753 + 0.27061 * r) / (1.0 + (0.99229 + 0.04481 * r) * r)
            if (pp > 1.0 && qq > 1.0) {
                r = (y * y - 3.0) / 6.0
                s = 1.0 / (pp + pp - 1.0)
                t = 1.0 / (qq + qq - 1.0)
                h = 2.0 / (s + t)
                w = y * sqrt(h + r) / h - (t - s) * (r + 5.0 / 6.0 - 2.0 / (3.0 * h))
                x = pp / (pp + qq * exp(w + w))
            } else {
                r = qq + qq
                t = 1.0 / (9.0 * qq)
                t = r * (1.0 - t + y * sqrt(t)).pow(3.0)
                if (t <= 0.0) {
                    x = 1.0 - exp(ln((1.0 - a) * qq) + lnbeta) / qq
                } else {
                    t = (4.0 * pp + r - 2.0) / t
                    x = if (t <= 1.0) {
                        exp((ln(a * pp) + lnbeta) / pp)
                    } else {
                        1.0 - 2.0 / (t + 1.0)
                    }
                }
            }
            if (x >= 1.0) return 1.0
            if (x <= 0.0) return 0.0
            return x
        }
    }


}