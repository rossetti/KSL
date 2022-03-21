/*
* Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
*
*    Licensed under the Apache License, Version 2.0 (the "License");
*    you may not use this file except in compliance with the License.
*    You may obtain a copy of the License at
*
*        http://www.apache.org/licenses/LICENSE-2.0
*
*    Unless required by applicable law or agreed to in writing, software
*    distributed under the License is distributed on an "AS IS" BASIS,
*    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*    See the License for the specific language governing permissions and
*    limitations under the License.
*/
package ksl.utilities.random.rvariable

//TODO extension functions for array and collection work
//TODO change argument checks to require()

import ksl.utilities.distributions.*
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The purpose of this class is to facilitate random variate generation from
 * various distributions.
 *
 *
 * Each function marked rXXXX will generate random variates from the named
 * distribution. The user has the option of supplying a RNStreamIfc as the source of
 * the randomness. Functions that do not have a RNStreamIfc parameter use,
 * getDefaultRNStream() as the source of randomness. That is, they all **share** the same
 * stream, which is the default stream from the default random number stream provider.
 * The user has the option of supplying a stream number to identify the stream
 * from the underlying stream provider. By default, stream 1 is the default stream
 * for the default provider. Stream 2 refers to the 2nd stream, etc.
 *
 *
 * Also provides a number of methods for sampling with and without replacement
 * from arrays and lists as well as creating permutations of arrays and lists.
 *
 * @author rossetti
 */
object KSLRandom {
    var DefaultRNStreamProvider: RNStreamProviderIfc = RNStreamProvider()

    enum class AlgoType {
        Inverse, AcceptanceRejection
    }

    private var myBeta: Beta? = null

    /**
     * @return gets the next stream of pseudo random numbers from the default random
     * number stream provider
     */
    fun nextRNStream(): RNStreamIfc {
        return DefaultRNStreamProvider.nextRNStream()
    }

    /**
     *
     * @param stream a stream associated with the default stream provider
     * @return the number associated with the provided stream or -1 if the stream was not provided by the default provider
     */
    fun streamNumber(stream: RNStreamIfc): Int {
        return DefaultRNStreamProvider.streamNumber(stream)
    }

    /**
     *
     * @param streamNum the stream number associated with the stream
     * @return the stream associated with the stream number from the underlying stream provider
     */
    fun rnStream(streamNum: Int): RNStreamIfc {
        return DefaultRNStreamProvider.rnStream(streamNum)
    }

    /**
     * @return the default stream from the default random number stream provider
     */
    fun defaultRNStream(): RNStreamIfc = DefaultRNStreamProvider.defaultRNStream()

    /**
     * @param pSuccess  the probability of success, must be in (0,1)
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rBernoulli(pSuccess: Double, streamNum: Int): Double {
        return rBernoulli(pSuccess, rnStream(streamNum))
    }

    /**
     * @param pSuccess the probability of success, must be in (0,1)
     * @param rng      the RNStreamIfc
     * @return the random value
     */
    fun rBernoulli(pSuccess: Double, rng: RNStreamIfc = defaultRNStream()): Double {
        if (pSuccess <= 0.0 || pSuccess >= 1.0) {
            throw IllegalArgumentException("Success Probability must be (0,1)")
        }
        return (rng.randU01() <= pSuccess).toDouble()
    }

    /**
     * @param pSuccess  the probability of success, must be in (0,1)
     * @param nTrials   the number of trials, must be greater than 0
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rBinomial(pSuccess: Double, nTrials: Int, streamNum: Int): Int {
        return rBinomial(pSuccess, nTrials, rnStream(streamNum))
    }

    /**
     * @param pSuccess the probability of success, must be in (0,1)
     * @param nTrials  the number of trials, must be greater than 0
     * @param rng      the RNStreamIfc, must not be null
     * @return the random value
     */
    fun rBinomial(pSuccess: Double, nTrials: Int, rng: RNStreamIfc = defaultRNStream()): Int {
        if (nTrials <= 0) {
            throw IllegalArgumentException("Number of trials must be >= 1")
        }
        if (pSuccess <= 0.0 || pSuccess >= 1.0) {
            throw IllegalArgumentException("Success Probability must be (0,1)")
        }
        return Binomial.binomialInvCDF(rng.randU01(), nTrials, pSuccess)
    }

    /**
     * @param mean      the mean of the Poisson, must be greater than 0
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rPoisson(mean: Double, streamNum: Int): Int {
        return rPoisson(mean, rnStream(streamNum))
    }

    /**
     * @param mean the mean of the Poisson, must be greater than 0
     * @param rng  the RNStreamIfc, must not be null
     * @return the random value
     */
    fun rPoisson(mean: Double, rng: RNStreamIfc = defaultRNStream()): Int {
        return Poisson.poissonInvCDF(rng.randU01(), mean)
    }

    /**
     * Generates a discrete uniform over the range
     *
     * @param minimum   the minimum of the range
     * @param maximum   the maximum of the range
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rDUniform(minimum: Int, maximum: Int, streamNum: Int): Int {
        return rDUniform(minimum, maximum, rnStream(streamNum))
    }

    /**
     * Generates a discrete uniform over the range
     *
     * @param minimum the minimum of the range
     * @param maximum the maximum of the range
     * @param rng     the RNStreamIfc, must not be null
     * @return the random value
     */
    fun rDUniform(minimum: Int, maximum: Int, rng: RNStreamIfc = defaultRNStream()): Int {
        return rng.randInt(minimum, maximum)
    }

    /**
     * @param pSuccess  the probability of success, must be in (0,1)
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rGeometric(pSuccess: Double, streamNum: Int): Int {
        return rGeometric(pSuccess, rnStream(streamNum))
    }

    /**
     * @param pSuccess the probability of success, must be in (0,1)
     * @param rng      the RNStreamIfc, must not be null
     * @return the random value
     */
    fun rGeometric(pSuccess: Double, rng: RNStreamIfc = defaultRNStream()): Int {
        if (pSuccess < 0.0 || pSuccess > 1.0) {
            throw IllegalArgumentException("Success Probability must be [0,1]")
        }
        val u = rng.randU01()
        return Math.ceil(Math.log(1.0 - u) / Math.log(1.0 - pSuccess) - 1.0).toInt()
    }

    /**
     * @param pSuccess   the probability of success
     * @param rSuccesses number of trials until rth success
     * @param streamNum  the stream number from the stream provider to use
     * @return the random value
     */
    fun rNegBinomial(pSuccess: Double, rSuccesses: Double, streamNum: Int): Int {
        return rNegBinomial(pSuccess, rSuccesses, rnStream(streamNum))
    }

    /**
     * @param pSuccess   the probability of success
     * @param rSuccesses number of trials until rth success
     * @param rng        the RNStreamIfc, must not be null
     * @return the random value
     */
    fun rNegBinomial(
        pSuccess: Double,
        rSuccesses: Double,
        rng: RNStreamIfc = defaultRNStream()
    ): Int {
        return NegativeBinomial.negBinomialInvCDF(rng.randU01(), pSuccess, rSuccesses)
    }

    /**
     * Generates a continuous U(0,1) using the supplied stream number
     *
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rUniform(streamNum: Int): Double {
        return rUniform(0.0, 1.0, streamNum)
    }

    /**
     * Generates a continuous U(0,1) using the supplied stream
     *
     * @param rnStream the RNStreamIfc, must not be null
     * @return the random value
     */
    fun rUniform(rnStream: RNStreamIfc = defaultRNStream()): Double {
        return rUniform(0.0, 1.0, rnStream)
    }

    /**
     * Generates a continuous uniform over the range
     *
     * @param minimum   the minimum of the range, must be less than maximum
     * @param maximum   the maximum of the range
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rUniform(minimum: Double, maximum: Double, streamNum: Int): Double {
        return rUniform(minimum, maximum, rnStream(streamNum))
    }

    /**
     * Generates a continuous uniform over the range
     *
     * @param minimum the minimum of the range, must be less than maximum
     * @param maximum the maximum of the range
     * @param rng     the RNStreamIfc, must not be null
     * @return the random value
     */
    fun rUniform(
        minimum: Double = 0.0,
        maximum: Double = 1.0,
        rng: RNStreamIfc = defaultRNStream()
    ): Double {
        if (minimum >= maximum) {
            throw IllegalArgumentException(
                "Lower limit must be < upper "
                        + "limit. lower limit = " + minimum + " upper limit = " + maximum
            )
        }
        return minimum + (maximum - minimum) * rng.randU01()
    }

    /**
     * Generates a N(0,1) random value using the supplied stream number
     *
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rNormal(streamNum: Int): Double {
        return rNormal(0.0, 1.0, streamNum)
    }

    /**
     * Generates a N(0,1) random value using the supplied stream
     *
     * @param rng the RNStreamIfc, must not null
     * @return the random value
     */
    fun rNormal(rng: RNStreamIfc): Double {
        return rNormal(0.0, 1.0, rng)
    }

    /**
     * @param mean      the mean of the normal
     * @param variance  the variance of the normal, must be greater than 0
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rNormal(mean: Double, variance: Double, streamNum: Int): Double {
        return rNormal(mean, variance, rnStream(streamNum))
    }

    /**
     * @param mean     the mean of the normal
     * @param variance the variance of the normal, must be greater than 0
     * @param rng      the RNStreamIfc, must not null
     * @return the random value
     */
    fun rNormal(
        mean: Double = 0.0,
        variance: Double = 1.0,
        rng: RNStreamIfc = defaultRNStream()
    ): Double {
        if (variance <= 0) {
            throw IllegalArgumentException("Variance must be positive")
        }
        val z = Normal.stdNormalInvCDF(rng.randU01())
        val stdDev = Math.sqrt(variance)
        return (z * stdDev + mean)
    }

    /**
     * @param mean      the mean of the lognormal, must be greater than 0
     * @param variance  the variance of the lognormal, must be greater than 0
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rLogNormal(mean: Double, variance: Double, streamNum: Int): Double {
        return rLogNormal(mean, variance, rnStream(streamNum))
    }

    /**
     * @param mean     the mean of the lognormal, must be greater than 0
     * @param variance the variance of the lognormal, must be greater than 0
     * @param rng      the RNStreamIfc, must not be null
     * @return the random value
     */
    fun rLogNormal(
        mean: Double,
        variance: Double,
        rng: RNStreamIfc = defaultRNStream()
    ): Double {
        if (mean <= 0) {
            throw IllegalArgumentException("Mean must be positive")
        }
        if (variance <= 0) {
            throw IllegalArgumentException("Variance must be positive")
        }
        val z = Normal.stdNormalInvCDF(rng.randU01())
        val d = variance + mean * mean
        val t = mean * mean
        val normalMu = Math.log((t) / Math.sqrt(d))
        val normalSigma = Math.sqrt(Math.log(d / t))
        val x = z * normalSigma + normalMu
        return (Math.exp(x))
    }

    /**
     * @param shape     the shape, must be greater than 0
     * @param scale     the scale, must be greater than 0
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rWeibull(shape: Double, scale: Double, streamNum: Int): Double {
        return rWeibull(shape, scale, rnStream(streamNum))
    }

    /**
     * @param shape the shape, must be greater than 0
     * @param scale the scale, must be greater than 0
     * @param rng   the RNStreamIfc, must not null
     * @return the random value
     */
    fun rWeibull(shape: Double, scale: Double, rng: RNStreamIfc = defaultRNStream()): Double {
        checkShapeAndScale(shape, scale)
        val u = rng.randU01()
        return scale * (-ln(1.0 - u)).pow(1.0 / shape)
    }

    /**
     * Throws an exception if shape or scale are invalid
     *
     * @param shape the shape, must be greater than 0
     * @param scale the scale, must be greater than 0
     */
    private fun checkShapeAndScale(shape: Double, scale: Double) {
        if (shape <= 0) {
            throw IllegalArgumentException("Shape parameter must be positive")
        }
        if (scale <= 0) {
            throw IllegalArgumentException("Scale parameter must be positive")
        }
    }

    /**
     * @param mean      the mean, must be greater than 0
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rExponential(mean: Double, streamNum: Int): Double {
        return rExponential(mean, rnStream(streamNum))
    }

    /**
     * @param mean the mean, must be greater than 0
     * @param rng  the RNStreamIfc, must not null
     * @return the random value
     */
    fun rExponential(mean: Double, rng: RNStreamIfc = defaultRNStream()): Double {
        if (mean <= 0.0) {
            throw IllegalArgumentException("Exponential mean must be > 0.0")
        }
        val u = rng.randU01()
        return (-mean * ln(1.0 - u))
    }

    /**
     * @param alpha1    alpha1 parameter
     * @param alpha2    alpha2 parameter, must be greater than zero
     * @param min       the min, must be less than max
     * @param max       the max
     * @param streamNum the stream number from the stream provider to use
     * @return the generated value
     */
    fun rJohnsonB(
        alpha1: Double, alpha2: Double,
        min: Double, max: Double, streamNum: Int
    ): Double {
        return rJohnsonB(alpha1, alpha2, min, max, rnStream(streamNum))
    }

    /**
     * @param alpha1 alpha1 parameter
     * @param alpha2 alpha2 parameter, must be greater than zero
     * @param min    the min, must be less than max
     * @param max    the max
     * @param rng    the RNStreamIfc, must not be null
     * @return the generated value
     */
    fun rJohnsonB(
        alpha1: Double, alpha2: Double,
        min: Double, max: Double, rng: RNStreamIfc = defaultRNStream()
    ): Double {
        if (alpha2 <= 0) {
            throw IllegalArgumentException("alpha2 must be > 0")
        }
        if (max <= min) {
            throw IllegalArgumentException("the min must be < than the max")
        }
        val u = rng.randU01()
        val z = Normal.stdNormalInvCDF(u)
        val y = exp((z - alpha1) / alpha2)
        return (min + max * y) / (y + 1.0)
    }

    /**
     * @param shape     the shape, must be greater than 0
     * @param scale     the scale, must be greater than 0
     * @param streamNum the stream number from the stream provider to use
     * @return the generated value
     */
    fun rLogLogistic(shape: Double, scale: Double, streamNum: Int): Double {
        return rLogLogistic(shape, scale, rnStream(streamNum))
    }

    /**
     * @param shape the shape, must be greater than 0
     * @param scale the scale, must be greater than 0
     * @param rng   the RNStreamIfc, must not be null
     * @return the generated value
     */
    fun rLogLogistic(
        shape: Double,
        scale: Double,
        rng: RNStreamIfc = defaultRNStream()
    ): Double {
        checkShapeAndScale(shape, scale)
        val u = rng.randU01()
        val c = u / (1.0 - u)
        return (scale * c.pow(1.0 / shape))
    }

    /**
     * @param min       the min, must be less than or equal to mode
     * @param mode      the mode, must be less than or equal to max
     * @param max       the max
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rTriangular(
        min: Double, mode: Double,
        max: Double, streamNum: Int
    ): Double {
        return rTriangular(min, mode, max, rnStream(streamNum))
    }

    /**
     * @param min  the min, must be less than or equal to mode
     * @param mode the mode, must be less than or equal to max
     * @param max  the max
     * @param rng  the RNStreamIfc, must not be null
     * @return the random value
     */
    fun rTriangular(
        min: Double, mode: Double,
        max: Double, rng: RNStreamIfc = defaultRNStream()
    ): Double {
        if (min > mode) {
            throw IllegalArgumentException("min must be <= mode")
        }
        if (min >= max) {
            throw IllegalArgumentException("min must be < max")
        }
        if (mode > max) {
            throw IllegalArgumentException("mode must be <= max")
        }
        val range = max - min
        val c = (mode - min) / range
        // get the invCDF for a triang(0,c,1)
        val x: Double
        val p = rng.randU01()
        if (c == 0.0) { // left triangular, mode equals min
            x = 1.0 - sqrt(1 - p)
        } else if (c == 1.0) { //right triangular, mode equals max
            x = sqrt(p)
        } else if (p < c) {
            x = sqrt(c * p)
        } else {
            x = 1.0 - sqrt((1.0 - c) * (1.0 - p))
        }
        // scale it back to original scale
        return (min + range * x)
    }

    /**
     * @param shape     the shape, must be greater than 0.0
     * @param scale     the scale, must be greater than 0.0
     * @param streamNum the stream number from the stream provider to use
     * @param type,     must be appropriate algorithm type, if null then inverse transform is the default
     * @return the generated value
     */
    fun rGamma(
        shape: Double,
        scale: Double,
        streamNum: Int,
        type: AlgoType = AlgoType.Inverse
    ): Double {
        return rGamma(shape, scale, rnStream(streamNum), type)
    }

    /**
     * @param shape the shape, must be greater than 0.0
     * @param scale the scale, must be greater than 0.0
     * @param rng   the RNStreamIfc, must not null
     * @param type, must be appropriate algorithm type, if null then inverse transform is the default
     * @return the generated value
     */
    fun rGamma(
        shape: Double,
        scale: Double,
        rng: RNStreamIfc = defaultRNStream(),
        type: AlgoType = AlgoType.Inverse
    ): Double {
        return if (type == AlgoType.AcceptanceRejection) {
            rARGamma(shape, scale, rng)
        } else {
            rInvGamma(shape, scale, rng)
        }
    }

    /**
     * Uses the inverse transform technique for generating from the gamma
     *
     * @param shape the shape, must be greater than 0.0
     * @param scale the scale, must be greater than 0.0
     * @param rng   the RNStreamIfc, must not null
     * @return the generated value
     */
    private fun rInvGamma(shape: Double, scale: Double, rng: RNStreamIfc): Double {
        checkShapeAndScale(shape, scale)
        val p = rng.randU01()
        val x: Double
        /* ...special case: exponential distribution */if (shape == 1.0) {
            x = -scale * Math.log(1.0 - p)
            return (x)
        }
        /* ...compute the gamma(alpha, beta) inverse.                   *
         *    ...compute the chi-square inverse with 2*alpha degrees of *
         *       freedom, which is equivalent to gamma(alpha, 2).       */
        val dof = 2.0 * shape
//    val g = Gamma.logGammaFunction(shape)
        val chi2 = Gamma.invChiSquareDistribution(p, dof)
        /* ...transfer chi-square to gamma. */
        x = scale * chi2 / 2.0
        return (x)
    }

    /**
     * Uses the acceptance-rejection technique for generating from the gamma
     *
     * @param shape the shape, must be greater than 0.0
     * @param scale the scale, must be greater than 0.0
     * @param rng   the RNStreamIfc, must not null
     * @return the generated value
     */
    private fun rARGamma(shape: Double, scale: Double, rng: RNStreamIfc): Double {
        checkShapeAndScale(shape, scale)
        // first get gamma(shape, 1)
        val x = rARGammaScaleEQ1(shape, rng)
        // now scale to proper scale
        return (x * scale)
    }

    /**
     * Generates a gamma(shape, scale=1) random variable via acceptance rejection. Uses
     * Ahrens and Dieter (1974) for shape between 0 and 1 and uses Marsaglia and Tsang (2000) for
     * shape greater than 1
     *
     * @param shape the shape, must be positive
     * @param rng   the random number stream, must not be null
     * @return the randomly generated value
     */
    private fun rARGammaScaleEQ1(shape: Double, rng: RNStreamIfc): Double {
        if (shape <= 0) {
            throw IllegalArgumentException("Shape parameter must be positive")
        }
        return if ((0.0 < shape) && (shape < 1.0)) {
            rARGammaScaleEQ1ShapeBTW01(shape, rng)
        } else {
            rARGammaScaleEQ1ShapeGT1(shape, rng)
        }
    }

    /**
     * Generates a gamma(shape, scale=1) random variable via acceptance rejection. Uses
     * Ahrens and Dieter (1974)
     *
     * @param shape the shape, must be in (0,1)
     * @param rng   the random number stream, must not be null
     * @return the randomly generated value
     */
    private fun rARGammaScaleEQ1ShapeBTW01(shape: Double, rng: RNStreamIfc): Double {
        val e = Math.E
        val b = (e + shape) / e
        while (true) {
            val u1 = rng.randU01()
            val p = b * u1
            if (p > 1) {
                val y = -Math.log((b - p) / shape)
                val u2 = rng.randU01()
                if (u2 <= Math.pow(y, shape - 1.0)) {
                    return y
                }
            } else {
                val y = Math.pow(p, 1.0 / shape)
                val u2 = rng.randU01()
                if (u2 <= Math.exp(-y)) {
                    return y
                }
            }
        }
    }

    /**
     * Generates a gamma(shape, scale=1) random variable via acceptance rejection. Uses
     * uses Marsaglia and Tsang (2000) for shape greater than 1
     *
     * @param shape the shape, must be greater than 1
     * @param rng   the random number stream, must not be null
     * @return the randomly generated value
     */
    private fun rARGammaScaleEQ1ShapeGT1(shape: Double, rng: RNStreamIfc): Double {
        val d = shape - 1.0 / 3.0
        val c = 1.0 / Math.sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            var u: Double
            do {
                x = rNormal(0.0, 1.0, rng)
                v = 1.0 + (c * x)
            } while (v <= 0.0)
            v = v * v * v
            u = rng.randU01()
            if (u < 1.0 - .0331 * (x * x) * (x * x)) return (d * v)
            if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) return (d * v)
        }
    }

    /**
     * @param dof       degrees of freedom, must be greater than 0
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rChiSquared(dof: Double, streamNum: Int): Double {
        return rChiSquared(dof, rnStream(streamNum))
    }

    /**
     * @param dof degrees of freedom, must be greater than 0
     * @param rng the RNStreamIfc, must not be null
     * @return the random value
     */
    fun rChiSquared(dof: Double, rng: RNStreamIfc = defaultRNStream()): Double {
        if (dof <= 0) {
            throw IllegalArgumentException("The degrees of freedom should be > 0")
        }
        return Gamma.invChiSquareDistribution(rng.randU01(), dof)
    }

    /**
     * @param shape     the shape, must be greater than 0
     * @param scale     the scale, must be greater than 0
     * @param streamNum the stream number from the stream provider to use
     * @return the generated value
     */
    fun rPearsonType5(shape: Double, scale: Double, streamNum: Int): Double {
        return rPearsonType5(shape, scale, rnStream(streamNum))
    }

    /**
     * @param shape the shape, must be greater than 0
     * @param scale the scale, must be greater than 0
     * @param rng   the RNStreamIfc, must not be null
     * @return the generated value
     */
    fun rPearsonType5(
        shape: Double,
        scale: Double,
        rng: RNStreamIfc = defaultRNStream()
    ): Double {
        checkShapeAndScale(shape, scale)
        val GShape = shape
        val GScale = 1.0 / scale
        val y = rGamma(GShape, GScale, rng)
        return 1.0 / y
    }

    /**
     * This beta is restricted to the range of (0,1)
     *
     * @param alpha1    alpha1 parameter
     * @param alpha2    alpha2 parameter
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rBeta(alpha1: Double, alpha2: Double, streamNum: Int): Double {
        return rBeta(alpha1, alpha2, rnStream(streamNum))
    }

    /**
     * This beta is restricted to the range of (0,1)
     *
     * @param alpha1 alpha1 parameter
     * @param alpha2 alpha2 parameter
     * @param rng    the RNStreamIfc
     * @return the random value
     */
    fun rBeta(alpha1: Double, alpha2: Double, rng: RNStreamIfc = defaultRNStream()): Double {
        return Beta.stdBetaInvCDF(rng.randU01(), alpha1, alpha2);
    }

    /**
     * This beta is restricted to the range of (minimum,maximum)
     *
     * @param alpha1    alpha1 parameter
     * @param alpha2    alpha2 parameter
     * @param minimum   the minimum of the range, must be less than maximum
     * @param maximum   the maximum of the range
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rBetaG(
        alpha1: Double, alpha2: Double,
        minimum: Double, maximum: Double, streamNum: Int
    ): Double {
        return rBetaG(alpha1, alpha2, minimum, maximum, rnStream(streamNum))
    }

    /**
     * This beta is restricted to the range of (minimum,maximum)
     *
     * @param alpha1  alpha1 parameter
     * @param alpha2  alpha2 parameter
     * @param minimum the minimum of the range, must be less than maximum
     * @param maximum the maximum of the range
     * @param rng     the RNStreamIfc
     * @return the random value
     */
    fun rBetaG(
        alpha1: Double, alpha2: Double,
        minimum: Double, maximum: Double, rng: RNStreamIfc = defaultRNStream()
    ): Double {
        if (minimum >= maximum) {
            throw IllegalArgumentException(
                ("Lower limit must be < upper "
                        + "limit. lower limit = " + minimum + " upper limit = " + maximum)
            )
        }
        val x = rBeta(alpha1, alpha2, rng)
        return minimum + (maximum - minimum) * x
    }

    /**
     * Pearson Type 6
     *
     * @param alpha1    alpha1 parameter
     * @param alpha2    alpha2 parameter
     * @param beta      the beta parameter, must be greater than 0
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rPearsonType6(
        alpha1: Double, alpha2: Double,
        beta: Double, streamNum: Int
    ): Double {
        return rPearsonType6(alpha1, alpha2, beta, rnStream(streamNum))
    }

    /**
     * Pearson Type 6
     *
     * @param alpha1 alpha1 parameter
     * @param alpha2 alpha2 parameter
     * @param beta   the beta parameter, must be greater than 0
     * @param rng    the RNStreamIfc, must not be null
     * @return the random value
     */
    fun rPearsonType6(
        alpha1: Double, alpha2: Double,
        beta: Double, rng: RNStreamIfc = defaultRNStream()
    ): Double {
        if (beta <= 0.0) {
            throw IllegalArgumentException("The scale parameter must be > 0.0")
        }
        val fib = rBeta(alpha1, alpha2, rng)
        return (beta * fib) / (1.0 - fib)
    }

    /**
     * Generates according to a Laplace(mean, scale)
     *
     * @param mean      mean or location parameter
     * @param scale     scale parameter, must be greater than 0
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rLaplace(mean: Double, scale: Double, streamNum: Int): Double {
        return rLaplace(mean, scale, rnStream(streamNum))
    }

    /**
     * Generates according to a Laplace(mean, scale)
     *
     * @param mean  mean or location parameter
     * @param scale scale parameter, must be greater than 0
     * @param rng   the RNStreamIfc, must not be null
     * @return the random value
     */
    fun rLaplace(mean: Double, scale: Double, rng: RNStreamIfc = defaultRNStream()): Double {
        if (scale <= 0.0) {
            throw IllegalArgumentException("The scale parameter must be > 0.0")
        }
        val p = rng.randU01()
        val u = p - 0.5
        return mean - scale * Math.signum(u) * Math.log(1.0 - 2.0 * Math.abs(u))
    }

    /**
     * Randomly select an element from the array
     *
     * @param array     the array to select from
     * @param streamNum the stream number from the stream provider to use
     * @return the randomly selected value
     */
    fun randomlySelect(array: IntArray, streamNum: Int): Int {
        return randomlySelect(array, rnStream(streamNum))
    }

    /**
     * Randomly select an element from the array
     *
     * @param array the array to select from
     * @param rng   the source of randomness
     * @return the randomly selected value
     */
    fun randomlySelect(array: IntArray, rng: RNStreamIfc = defaultRNStream()): Int {
        if (array.size == 1) {
            return array[0]
        }
        val randInt = rng.randInt(0, array.size - 1)
        return array[randInt]
    }

    /**
     * Randomly select an element from the array
     *
     * @param array     the array to select from
     * @param streamNum the stream number from the stream provider to use
     * @return the randomly selected value
     */
    fun randomlySelect(array: DoubleArray, streamNum: Int): Double {
        return randomlySelect(array, rnStream(streamNum))
    }

    /**
     * Randomly select an element from the array
     *
     * @param array the array to select from
     * @param rng   the source of randomness
     * @return the randomly selected value
     */
    fun randomlySelect(array: DoubleArray, rng: RNStreamIfc = defaultRNStream()): Double {
        if (array.size == 1) {
            return array[0]
        }
        val randInt = rng.randInt(0, array.size - 1)
        return array[randInt]
    }

    /**
     * Randomly selects from the array using the supplied cdf
     *
     * @param array     array to select from
     * @param cdf       the cumulative probability associated with each element of
     * array
     * @param streamNum the stream number from the stream provider to use
     * @return the randomly selected value
     */
    fun randomlySelect(array: DoubleArray, cdf: DoubleArray, streamNum: Int): Double {
        return randomlySelect(array, cdf, rnStream(streamNum))
    }

    /**
     * Randomly selects from the array using the supplied cdf
     *
     * @param array array to select from
     * @param cdf   the cumulative probability associated with each element of
     * array
     * @param rng   the source of randomness
     * @return the randomly selected value
     */
    fun randomlySelect(
        array: DoubleArray,
        cdf: DoubleArray,
        rng: RNStreamIfc = defaultRNStream()
    ): Double {
        if (!isValidCDF(cdf)) {
            throw IllegalArgumentException("The supplied cdf was not valid")
        }
        require(array.size == cdf.size) {
            "The list size, $array.size, and cdf size, $cdf.size, must be equal!"
        }
        if (cdf.size == 1) {
            return array[0]
        }
        var i = 0
        var value = array[i]
        val u = rng.randU01()
        while (cdf[i] <= u) {
            i = i + 1
            value = array[i]
        }
        return value
    }

    /**
     * Randomly selects from the array using the supplied cdf
     *
     * @param array     array to select from
     * @param cdf       the cumulative probability associated with each element of
     * array
     * @param streamNum the stream number from the stream provider to use
     * @return the randomly selected value
     */
    fun randomlySelect(array: IntArray, cdf: DoubleArray, streamNum: Int): Int {
        return randomlySelect(array, cdf, rnStream(streamNum))
    }

    /**
     * Randomly selects from the array using the supplied cdf
     *
     * @param array array to select from
     * @param cdf   the cumulative probability associated with each element of array
     * @param rng   the source of randomness
     * @return the randomly selected value
     */
    fun randomlySelect(
        array: IntArray,
        cdf: DoubleArray,
        rng: RNStreamIfc = defaultRNStream()
    ): Int {
        if (!isValidCDF(cdf)) {
            throw IllegalArgumentException("The supplied cdf was not valid")
        }
        require(array.size == cdf.size) {
            "The list size, $array.size, and cdf size, $cdf.size, must be equal!"
        }
        if (cdf.size == 1) {
            return array[0]
        }
        var i = 0
        var value = array[i]
        val u = rng.randU01()
        while (cdf[i] <= u) {
            i = i + 1
            value = array[i]
        }
        return value
    }

    /**
     * Randomly selects from the list using the supplied cdf
     *
     * @param <T>       the type returned
     * @param list      list to select from
     * @param cdf       the cumulative probability associated with each element of
     * array
     * @param streamNum the stream number from the stream provider to use
     * @return the randomly selected value
    </T> */
    fun <T> randomlySelect(list: List<T>, cdf: DoubleArray, streamNum: Int): T {
        return randomlySelect(list, cdf, rnStream(streamNum))
    }

    /**
     * Randomly selects from the list using the supplied cdf
     *
     * @param <T>  the type returned
     * @param list list to select from
     * @param cdf  the cumulative probability associated with each element of
     * array
     * @param rng  the source of randomness
     * @return the randomly selected value
    </T> */
    fun <T> randomlySelect(
        list: List<T>,
        cdf: DoubleArray,
        rng: RNStreamIfc = defaultRNStream()
    ): T {
        if (!isValidCDF(cdf)) {
            throw IllegalArgumentException("The supplied cdf was not valid")
        }
        require(list.size == cdf.size) {
            "The list size, $list.size, and cdf size, $cdf.size, must be equal!"
        }
        if (cdf.size == 1) {
            return list[0]
        }
        var i = 0
        var value = list[i]
        val u = rng.randU01()
        while (cdf[i] <= u) {
            i = i + 1
            value = list[i]
        }
        return value
    }

    /**
     * @param cdf the probability array. must have valid probability elements
     * and last element equal to 1. Every element must be greater than or equal
     * to the previous element. That is, monotonically increasing.
     * @return true if valid cdf
     */
    fun isValidCDF(cdf: DoubleArray): Boolean {
        if (cdf[cdf.size - 1] != 1.0) {
            return false
        }
        for (i in cdf.indices) {
            if ((cdf[i] < 0.0) || (cdf[i] > 1.0)) {
                return false
            }
            if (i < cdf.size - 1) {
                if (cdf[i + 1] < cdf[i]) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Each element must be in (0,1) and sum of elements must be less than or equal to 1.0
     *
     * @param prob the array to check, must not be null, must have at least two elements
     * @return true if the array represents a probability mass function
     */
    fun isValidPMF(prob: DoubleArray): Boolean {
        if (prob.size < 2) {
            return false
        }
        var sum = 0.0
        for (i in prob.indices) {
            if ((prob[i] <= 0.0) || (prob[i] >= 1.0)) {
                return false
            }
            sum = sum + prob[i]
            if (sum > 1.0) {
                return false
            }
        }
        return sum <= 1.0
    }

    /**
     * @param prob the array representing a PMF
     * @return a valid CDF
     */
    fun makeCDF(prob: DoubleArray): DoubleArray {
        if (!isValidPMF(prob)) {
            throw IllegalArgumentException("The supplied array was not a valid PMF")
        }
        val cdf = DoubleArray(prob.size)
        var sum = 0.0
        for (i in 0 until prob.size - 1) {
            sum = sum + prob[i]
            cdf[i] = sum
        }
        cdf[prob.size - 1] = 1.0
        return cdf
    }

    /**
     * Randomly select from the list
     *
     * @param <T>       The type of element in the list
     * @param list      the list
     * @param streamNum the stream number from the stream provider to use
     * @return the randomly selected element
    </T> */
    fun <T> randomlySelect(list: List<T>, streamNum: Int): T? {
        return randomlySelect(list, rnStream(streamNum))
    }

    /**
     * Randomly select from the list
     *
     * @param <T>  The type of element in the list
     * @param list the list
     * @param rng  the source of randomness
     * @return the randomly selected element
    </T> */
    fun <T> randomlySelect(list: List<T>, rng: RNStreamIfc = defaultRNStream()): T? {
        if (list.isEmpty()) {
            return null
        }
        return if (list.size == 1) {
            list.get(0)
        } else list.get(rng.randInt(0, list.size - 1))

        // more than 1, need to randomly pick
    }

    /**
     * Randomly permutes the supplied array using the supplied stream
     * number, the array is changed
     *
     * @param x         the array
     * @param streamNum the stream number from the stream provider to use
     */
    fun permutation(x: DoubleArray, streamNum: Int) {
        permutation(x, rnStream(streamNum))
    }

    /**
     * Randomly permutes the supplied array using the supplied stream
     * number, the array is changed
     *
     * @param x   the array
     * @param rng the source of randomness
     */
    fun permutation(x: DoubleArray, rng: RNStreamIfc = defaultRNStream()) {
        sampleWithoutReplacement(x, x.size, rng)
    }

    /**
     * The array x is changed, such that the first sampleSize elements contain the sample.
     * That is, x[0], x[1], ... , x[sampleSize-1] is the random sample without replacement
     *
     * @param x          the array
     * @param sampleSize the size to generate
     * @param streamNum  the stream number from the stream provider to use
     */
    fun sampleWithoutReplacement(x: DoubleArray, sampleSize: Int, streamNum: Int) {
        sampleWithoutReplacement(x, sampleSize, rnStream(streamNum))
    }

    /**
     * The array x is changed, such that the first sampleSize elements contain the sample.
     * That is, x[0], x[1], ... , x[sampleSize-1] is the random sample without replacement
     *
     * @param x          the array
     * @param sampleSize the size to generate
     * @param rng        the source of randomness
     */
    fun sampleWithoutReplacement(
        x: DoubleArray,
        sampleSize: Int,
        rng: RNStreamIfc = defaultRNStream()
    ) {
        require(sampleSize <= x.size) {
            "Cannot draw without replacement for more than the number of elements $x.size"
        }
        for (j in 0 until sampleSize) {
            val i = rng.randInt(j, x.size - 1)
            val temp = x[j]
            x[j] = x[i]
            x[i] = temp
        }
    }

    /**
     * Randomly permutes the supplied array using the supplied stream number, the array is changed.
     *
     * @param x         the array
     * @param streamNum the stream number from the stream provider to use
     */
    fun permutation(x: IntArray, streamNum: Int) {
        permutation(x, rnStream(streamNum))
    }

    /**
     * Randomly permutes the supplied array using the supplied random
     * number generator, the array is changed.
     *
     * @param x   the array
     * @param rng the source of randomness
     */
    fun permutation(x: IntArray, rng: RNStreamIfc = defaultRNStream()) {
        sampleWithoutReplacement(x, x.size, rng)
    }

    /**
     * The array x is changed, such that the first sampleSize elements contain the generated sample.
     * That is, x[0], x[1], ... , x[sampleSize-1] is the random sample without replacement
     *
     * @param x          the array
     * @param sampleSize the size to generate
     * @param streamNum  the stream number from the stream provider to use
     */
    fun sampleWithoutReplacement(x: IntArray, sampleSize: Int, streamNum: Int) {
        sampleWithoutReplacement(x, sampleSize, rnStream(streamNum))
    }

    /**
     * The array x is changed, such that the first sampleSize elements contain the generated sample.
     * That is, x[0], x[1], ... , x[sampleSize-1] is the random sample without replacement
     *
     * @param x          the array
     * @param sampleSize the size to generate
     * @param rng        the source of randomness
     */
    fun sampleWithoutReplacement(
        x: IntArray,
        sampleSize: Int,
        rng: RNStreamIfc = defaultRNStream()
    ) {
        require(sampleSize <= x.size) {
            "Cannot draw without replacement for more than the number of elements $x.size"
        }
        for (j in 0 until sampleSize) {
            val i = rng.randInt(j, x.size - 1)
            val temp = x[j]
            x[j] = x[i]
            x[i] = temp
        }
    }

    /**
     * Randomly permutes the supplied array using the supplied
     * stream number, the array is changed.
     *
     * @param x         the array
     * @param streamNum the stream number from the stream provider to use
     */
    fun permutation(x: BooleanArray, streamNum: Int) {
        permutation(x, rnStream(streamNum))
    }

    /**
     * Randomly permutes the supplied array using the supplied random
     * number generator, the array is changed.
     *
     * @param x   the array
     * @param rng the source of randomness
     */
    fun permutation(x: BooleanArray, rng: RNStreamIfc = defaultRNStream()) {
        sampleWithoutReplacement(x, x.size, rng)
    }

    /**
     * The array x is changed, such that the first sampleSize elements contain the generated sample.
     * That is, x[0], x[1], ... , x[sampleSize-1] is the random sample without replacement
     *
     * @param x          the array
     * @param sampleSize the size to generate
     * @param streamNum  the stream number from the stream provider to use
     */
    fun sampleWithoutReplacement(x: BooleanArray, sampleSize: Int, streamNum: Int) {
        sampleWithoutReplacement(x, sampleSize, rnStream(streamNum))
    }

    /**
     * The array x is changed, such that the first sampleSize elements contain the generated sample.
     * That is, x[0], x[1], ... , x[sampleSize-1] is the random sample without replacement
     *
     * @param x          the array
     * @param sampleSize the size to generate
     * @param rng        the source of randomness
     */
    fun sampleWithoutReplacement(
        x: BooleanArray,
        sampleSize: Int,
        rng: RNStreamIfc = defaultRNStream()
    ) {
        require(sampleSize <= x.size) {
            "Cannot draw without replacement for more than the number of elements $x.size"
        }
        for (j in 0 until sampleSize) {
            val i = rng.randInt(j, x.size - 1)
            val temp = x[j]
            x[j] = x[i]
            x[i] = temp
        }
    }

    /**
     * Randomly permutes the supplied array using the supplied stream
     * number, the array is changed
     *
     * @param x         the array
     * @param streamNum the stream number from the stream provider to use
     */
    fun <T> permutation(x: Array<T>, streamNum: Int) {
        permutation(x, rnStream(streamNum))
    }

    /**
     * Randomly permutes the supplied array using the supplied random
     * number generator, the array is changed
     *
     * @param x   the array
     * @param rng the source of randomness
     */
    fun <T> permutation(x: Array<T>, rng: RNStreamIfc = defaultRNStream()) {
        sampleWithoutReplacement(x, x.size, rng)
    }

    /**
     * The array x is changed, such that the first sampleSize elements contain the generated sample.
     * That is, x[0], x[1], ... , x[sampleSize-1] is the randomly sampled values without replacement
     *
     * @param x          the array
     * @param sampleSize the size to generate
     * @param streamNum  the stream number from the stream provider to use
     */
    fun <T> sampleWithoutReplacement(x: Array<T>, sampleSize: Int, streamNum: Int) {
        sampleWithoutReplacement(x, sampleSize, rnStream(streamNum))
    }

    /**
     * The array x is changed, such that the first sampleSize elements contain the generated sample.
     * That is, x[0], x[1], ... , x[sampleSize-1] is the randomly sampled values without replacement
     *
     * @param x          the array
     * @param sampleSize the size to generate
     * @param rng        the source of randomness
     */
    fun <T> sampleWithoutReplacement(
        x: Array<T>,
        sampleSize: Int,
        rng: RNStreamIfc = defaultRNStream()
    ) {
        require(sampleSize <= x.size) {
            "Cannot draw without replacement for more than the number of elements $x.size"
        }
        for (j in 0 until sampleSize) {
            val i = rng.randInt(j, x.size - 1)
            val temp = x[j]
            x[j] = x[i]
            x[i] = temp
        }
    }

    /**
     * Randomly permutes the supplied List using the supplied stream
     * number, the list is changed
     *
     * @param <T>       the type of the list
     * @param x         the list
     * @param streamNum the stream number from the stream provider to use
    </T> */
    fun <T> permutation(x: MutableList<T>, streamNum: Int) {
        permutation(x, rnStream(streamNum))
    }

    /**
     * Randomly permutes the supplied List using the supplied random
     * number generator, the list is changed
     *
     * @param <T> the type of the list
     * @param x   the list
     * @param rng the source of randomness
    </T> */
    fun <T> permutation(x: MutableList<T>, rng: RNStreamIfc = defaultRNStream()) {
        sampleWithoutReplacement(x, x.size, rng)
    }

    /**
     * The List x is changed, such that the first sampleSize elements contain the generate.
     * That is, x.get(0), x.get(1), ... , x.get(sampleSize-1) is the random sample without replacement
     *
     * @param <T>        the type of the list
     * @param x          the list
     * @param sampleSize the size to generate
    </T> */
    fun <T> sampleWithoutReplacement(x: MutableList<T>, sampleSize: Int, streamNum: Int) {
        sampleWithoutReplacement(x, sampleSize, rnStream(streamNum))
    }

    /**
     * The List x is changed, such that the first sampleSize elements contain the sampled values.
     * That is, x.get(0), x.get(1), ... , x.get(sampleSize-1) is the random sample without replacement
     *
     * @param <T>        the type of the list
     * @param x          the list
     * @param sampleSize the size to generate
     * @param rng        the source of randomness
    </T> */
    fun <T> sampleWithoutReplacement(
        x: MutableList<T>, sampleSize: Int, rng: RNStreamIfc = defaultRNStream()
    ) {
        require(sampleSize <= x.size) {
            "Cannot draw without replacement for more than the number of elements $x.size"
        }
        for (j in 0 until sampleSize) {
            val i = rng.randInt(j, x.size - 1)
            val temp = x[j]
            x[j] = x[i]
            x[i] = temp
        }
    }
}

/* extension functions */
fun Boolean.toInt() = if (this) 1 else 0
fun Boolean.toDouble() = if (this) 1.0 else 0.0

//TODO many additional extension functions for working with arrays