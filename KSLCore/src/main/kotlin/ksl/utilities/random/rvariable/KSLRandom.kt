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
package ksl.utilities.random.rvariable

//TODO extension functions for array and collection work
//TODO change argument checks to require()

import ksl.utilities.distributions.*
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc
import kotlin.math.*

/**
 * The purpose of this class is to facilitate random variate generation from
 * various distributions without having to create object instances.
 *
 * Each function marked rXXXX will generate random variates from the named
 * distribution. The user has the option of supplying a RNStreamIfc as the source of
 * the randomness. Functions that do not have a RNStreamIfc parameter use,
 * defaultRNStream() as the default source of randomness. That is, they all **share** the same
 * stream, which is the default stream from the default random number stream provider.
 * The user has the option of supplying a stream number to identify the stream
 * from the underlying stream provider. By default, stream 1 is the default stream
 * for the default provider. Stream 2 refers to the 2nd stream, etc.
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
     *  The default stream number for the underlying provider
     */
    val defaultStreamNumber
        get() = DefaultRNStreamProvider.defaultStreamNumber

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
     * @param stream the random number stream
     * @return the random value
     */
    fun rBernoulli(pSuccess: Double, stream: RNStreamIfc = defaultRNStream()): Double {
        require(!(pSuccess <= 0.0 || pSuccess >= 1.0)) { "Success Probability must be in (0,1)" }
        return (stream.randU01() <= pSuccess).toDouble()
    }

    /**
     * @param pSuccess the probability of success, must be in (0,1)
     * @param stream the random number stream
     * @return the random value as a Boolean value
     */
    fun rBernoulliBoolean(pSuccess: Double, stream: RNStreamIfc = defaultRNStream()): Boolean {
        require(!(pSuccess <= 0.0 || pSuccess >= 1.0)) { "Success Probability must be in (0,1)" }
        return (stream.randU01() <= pSuccess)
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
     * @param stream   the random number stream
     * @return the random value
     */
    fun rBinomial(pSuccess: Double, nTrials: Int, stream: RNStreamIfc = defaultRNStream()): Int {
        require(nTrials > 0) { "The number of trials must be > 0" }
        require(!(pSuccess <= 0.0 || pSuccess >= 1.0)) { "Success Probability must be in (0,1)" }
        return Binomial.binomialInvCDF(stream.randU01(), nTrials, pSuccess)
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
     * @param stream  the random number stream
     * @return the random value
     */
    fun rPoisson(mean: Double, stream: RNStreamIfc = defaultRNStream()): Int {
        return Poisson.poissonInvCDF(stream.randU01(), mean)
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
     * @param stream     the random number stream
     * @return the random value
     *
     */
    fun rDUniform(minimum: Int, maximum: Int, stream: RNStreamIfc = defaultRNStream()): Int {
        return stream.randInt(minimum, maximum)
    }

    /**
     * Generates a discrete uniform over the range
     *
     * @param range the range of the random variate
     * @param stream     the random number stream
     * @return the random value
     */
    fun rDUniform(range: IntRange, streamNum: Int): Int {
        return rDUniform(range.first, range.last, streamNum)
    }

    /**
     * Generates a discrete uniform over the range
     *
     * @param range the range of the random variate
     * @param stream     the random number stream
     * @return the random value
     */
    fun rDUniform(range: IntRange, stream: RNStreamIfc = defaultRNStream()): Int {
        return stream.randInt(range.first, range.last)
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
     * @param stream      the random number stream
     * @return the random value
     */
    fun rGeometric(pSuccess: Double, stream: RNStreamIfc = defaultRNStream()): Int {
        require(!(pSuccess <= 0.0 || pSuccess >= 1.0)) { "Success Probability must be in (0,1)" }
        val u = stream.randU01()
        return ceil(ln(1.0 - u) / ln(1.0 - pSuccess) - 1.0).toInt()
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
     * @param stream        the random number stream
     * @return the random value
     */
    fun rNegBinomial(
        pSuccess: Double,
        rSuccesses: Double,
        stream: RNStreamIfc = defaultRNStream()
    ): Int {
        return NegativeBinomial.negBinomialInvCDF(stream.randU01(), rSuccesses, pSuccess)
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
     * @param stream the random number stream
     * @return the random value
     */
    fun rUniform(stream: RNStreamIfc = defaultRNStream()): Double {
        return rUniform(0.0, 1.0, stream)
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
     * @param stream     the random number stream
     * @return the random value
     */
    fun rUniform(
        minimum: Double = 0.0,
        maximum: Double = 1.0,
        stream: RNStreamIfc = defaultRNStream()
    ): Double {
        require(minimum < maximum) { "Lower limit must be < upper limit. lower limit = $minimum upper limit = $maximum" }
        return minimum + (maximum - minimum) * stream.randU01()
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
     * @param stream the random number stream
     * @return the random value
     */
    fun rNormal(stream: RNStreamIfc): Double {
        return rNormal(0.0, 1.0, stream)
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
     * @param stream      the random number stream
     * @return the random value
     */
    fun rNormal(
        mean: Double = 0.0,
        variance: Double = 1.0,
        stream: RNStreamIfc = defaultRNStream()
    ): Double {
        require(variance > 0) { "The variance must be > 0" }
        val z = Normal.stdNormalInvCDF(stream.randU01())
        val stdDev = sqrt(variance)
        return (z * stdDev + mean)
    }

    /** Generated a pair of normal random variates via the Box-Muller transform method
     *  The user can use destructuring to access the individual values
     * @param mean     the mean of the normal
     * @param variance the variance of the normal, must be greater than 0
     * @param stream      the random number stream
     * @return the pair of random values
     */
    fun rNormalBoxMuller(
        mean: Double = 0.0,
        variance: Double = 1.0,
        stream: RNStreamIfc = defaultRNStream()
    ): Pair<Double, Double> {
        require(variance > 0) { "The variance must be > 0" }
        val u1 = stream.randU01()
        val u2 = stream.randU01()
        val k = sqrt(-2.0 * ln(u2))
        val z1 = k * sin(2.0 * PI * u1)
        val z2 = k * cos(2.0 * PI * u1)
        val s = sqrt(variance)
        val x1 = z1 * s + mean
        val x2 = z2 * s + mean
        return Pair(x1, x2)
    }

    /** Generated a pair of normal random variates via the Box-Muller transform method
     *  The user can use destructuring to access the individual values
     * @param mean     the mean of the normal
     * @param variance the variance of the normal, must be greater than 0
     * @param streamNum      the random number stream number
     * @return the pair of random values
     */
    fun rNormalBoxMuller(
        mean: Double = 0.0,
        variance: Double = 1.0,
        streamNum: Int
    ): Pair<Double, Double> {
        return rNormalBoxMuller(mean, variance, rnStream(streamNum))
    }

    /** Generated a pair of normal random variates via the Box-Muller transform method
     *  The user can use destructuring to access the individual values
     * @param mean     the mean of the normal
     * @param variance the variance of the normal, must be greater than 0
     * @param stream      the random number stream
     * @return the pair of random values
     */
    fun rNormalPolar(
        mean: Double = 0.0,
        variance: Double = 1.0,
        stream: RNStreamIfc = defaultRNStream()
    ): Pair<Double, Double> {
        require(variance > 0) { "The variance must be > 0" }
        var v1: Double
        var v2: Double
        var w: Double
        do {
            v1 = 2.0 * stream.randU01() - 1.0
            v2 = 2.0 * stream.randU01() - 1.0
            w = v1 * v1 + v2 * v2
        } while (w > 1.0)
        val y = sqrt(-2.0 * ln(w) / w)
        val z1 = v2 * y
        val z2 = v1 * y
        val s = sqrt(variance)
        val x1 = z1 * s + mean
        val x2 = z2 * s + mean
        return Pair(x1, x2)
    }

    /** Generated a pair of normal random variates via the Box-Muller transform method
     *  The user can use destructuring to access the individual values
     * @param mean     the mean of the normal
     * @param variance the variance of the normal, must be greater than 0
     * @param streamNum      the random number stream number
     * @return the pair of random values
     */
    fun rNormalPolar(
        mean: Double = 0.0,
        variance: Double = 1.0,
        streamNum: Int
    ): Pair<Double, Double> {
        return rNormalPolar(mean, variance, rnStream(streamNum))
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
     * @param stream      the random number stream
     * @return the random value
     */
    fun rLogNormal(
        mean: Double,
        variance: Double,
        stream: RNStreamIfc = defaultRNStream()
    ): Double {
        require(mean > 0) { "The mean must be > 0" }
        require(variance > 0) { "The variance must be > 0" }
        val z = Normal.stdNormalInvCDF(stream.randU01())
        val d = variance + mean * mean
        val t = mean * mean
        val normalMu = ln((t) / sqrt(d))
        val normalSigma = sqrt(ln(d / t))
        val x = z * normalSigma + normalMu
        return (exp(x))
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
     * @param stream   the random number stream
     * @return the random value
     */
    fun rWeibull(shape: Double, scale: Double, stream: RNStreamIfc = defaultRNStream()): Double {
        checkShapeAndScale(shape, scale)
        val u = stream.randU01()
        return scale * (-ln(1.0 - u)).pow(1.0 / shape)
    }

    /**
     * Throws an exception if shape or scale are invalid
     *
     * @param shape the shape, must be greater than 0
     * @param scale the scale, must be greater than 0
     */
    private fun checkShapeAndScale(shape: Double, scale: Double) {
        require(shape > 0) { "Shape parameter must be > 0" }
        require(scale > 0) { "Scale parameter must be > 0" }
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
     * @param stream  the random number stream
     * @return the random value
     */
    fun rExponential(mean: Double, stream: RNStreamIfc = defaultRNStream()): Double {
        require(mean > 0) { "The mean of the exponential distribution must be > 0" }
        val u = stream.randU01()
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
     * @param stream    the random number stream
     * @return the generated value
     */
    fun rJohnsonB(
        alpha1: Double, alpha2: Double,
        min: Double, max: Double, stream: RNStreamIfc = defaultRNStream()
    ): Double {
        require(alpha2 > 0) { "alpha2 must be > 0" }
        require(max > min) { "the min must be < than the max" }
        val u = stream.randU01()
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
     * @param stream  the random number stream
     * @return the random value
     */
    fun rTriangular(
        min: Double, mode: Double,
        max: Double, stream: RNStreamIfc = defaultRNStream()
    ): Double {
        require(min <= mode) { "min must be <= mode" }
        require(min < max) { "min must be < max" }
        require(mode <= max) { "mode must be <= max" }
        val range = max - min
        val c = (mode - min) / range
        // get the invCDF for a triang(0,c,1)
        val x: Double
        val p = stream.randU01()
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
            x = -scale * ln(1.0 - p)
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
        val b = (E + shape) / E
        while (true) {
            val u1 = rng.randU01()
            val p = b * u1
            if (p > 1) {
                val y = -ln((b - p) / shape)
                val u2 = rng.randU01()
                if (u2 <= y.pow(shape - 1.0)) {
                    return y
                }
            } else {
                val y = p.pow(1.0 / shape)
                val u2 = rng.randU01()
                if (u2 <= exp(-y)) {
                    return y
                }
            }
        }
    }

    /**
     * Generates a gamma(shape, scale=1) random variable via acceptance rejection. Uses
     * the algorithm in Marsaglia and Tsang (2000) for shape greater than 1
     *
     * @param shape the shape, must be greater than 1
     * @param rng   the random number stream, must not be null
     * @return the randomly generated value
     */
    private fun rARGammaScaleEQ1ShapeGT1(shape: Double, rng: RNStreamIfc): Double {
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = rNormal(0.0, 1.0, rng)
                v = 1.0 + (c * x)
            } while (v <= 0.0)
            v = v * v * v
            val u = rng.randU01()
            if (u < 1.0 - .0331 * (x * x) * (x * x)) return (d * v)
            if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return (d * v)
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
     * @param alpha  alpha (first shape) parameter
     * @param beta  beta (second shape) parameter
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rBeta(alpha: Double, beta: Double, streamNum: Int): Double {
        return rBeta(alpha, beta, rnStream(streamNum))
    }

    /**
     * This beta is restricted to the range of (0,1)
     *
     * @param alpha  alpha (first shape) parameter
     * @param beta  beta (second shape) parameter
     * @param rng    the RNStreamIfc
     * @return the random value
     */
    fun rBeta(alpha: Double, beta: Double, rng: RNStreamIfc = defaultRNStream()): Double {
        return Beta.stdBetaInvCDF(rng.randU01(), alpha, beta);
    }

    /**
     * This beta is restricted to the range of (minimum,maximum)
     *
     * @param alpha  alpha (first shape) parameter
     * @param beta  beta (second shape) parameter
     * @param minimum   the minimum of the range, must be less than maximum
     * @param maximum   the maximum of the range
     * @param streamNum the stream number from the stream provider to use
     * @return the random value
     */
    fun rBetaG(
        alpha: Double, beta: Double,
        minimum: Double, maximum: Double, streamNum: Int
    ): Double {
        return rBetaG(alpha, beta, minimum, maximum, rnStream(streamNum))
    }

    /**
     * This beta is restricted to the range of (minimum,maximum)
     *
     * @param alpha  alpha (first shape) parameter
     * @param beta  beta (second shape) parameter
     * @param minimum the minimum of the range, must be less than maximum
     * @param maximum the maximum of the range
     * @param rng     the RNStreamIfc
     * @return the random value
     */
    fun rBetaG(
        alpha: Double, beta: Double,
        minimum: Double, maximum: Double, rng: RNStreamIfc = defaultRNStream()
    ): Double {
        if (minimum >= maximum) {
            throw IllegalArgumentException(
                ("Lower limit must be < upper "
                        + "limit. lower limit = " + minimum + " upper limit = " + maximum)
            )
        }
        val x = rBeta(alpha, beta, rng)
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
        return mean - scale * sign(u) * ln(1.0 - 2.0 * abs(u))
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
     * @param array the array to select from, must not be empty
     * @param stream   the source of randomness
     * @return the randomly selected value
     */
    fun randomlySelect(array: IntArray, stream: RNStreamIfc = defaultRNStream()): Int {
        require(array.isNotEmpty()) { "The array had no elements." }
        if (array.size == 1) {
            return array[0]
        }
        val randInt = stream.randInt(0, array.size - 1)
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
     * @param array the array to select from, must not be empty
     * @param stream   the source of randomness
     * @return the randomly selected value
     */
    fun randomlySelect(array: DoubleArray, stream: RNStreamIfc = defaultRNStream()): Double {
        require(array.isNotEmpty()) { "The array had no elements." }
        if (array.size == 1) {
            return array[0]
        }
        val randInt = stream.randInt(0, array.size - 1)
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
     * @param stream   the source of randomness
     * @return the randomly selected value
     */
    fun randomlySelect(
        array: DoubleArray,
        cdf: DoubleArray,
        stream: RNStreamIfc = defaultRNStream()
    ): Double {
        if (!isValidCDF(cdf)) {
            throw IllegalArgumentException("The supplied cdf was not valid")
        }
        require(array.size == cdf.size) {
            "The list size, $array.size, and cdf size, $cdf.size, must be equal!"
        }
        return discreteInverseCDF(array, cdf, stream)
    }

    /**
     * Randomly selects from the array using the supplied cdf, NO checking of arrays
     *
     * @param array array to select from
     * @param cdf   the cumulative probability associated with each element of array
     * @param stream   the source of randomness
     * @return the randomly selected value
     */
    fun discreteInverseCDF(array: DoubleArray, cdf: DoubleArray, stream: RNStreamIfc): Double {
        if (cdf.size == 1) {
            return array[0]
        }
        var i = 0
        var value = array[i]
        val u = stream.randU01()
        while (cdf[i] <= u) {
            i = i + 1
            value = array[i]
        }
        return value
    }

    /**
     * Randomly selects from the array using the supplied cdf, NO checking of arrays
     *
     * @param array array to select from
     * @param cdf   the cumulative probability associated with each element of array
     * @param stream   the source of randomness
     * @return the randomly selected value
     */
    fun discreteInverseCDF(array: IntArray, cdf: DoubleArray, stream: RNStreamIfc): Int {
        if (cdf.size == 1) {
            return array[0]
        }
        var i = 0
        var value = array[i]
        val u = stream.randU01()
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
     * @param stream   the source of randomness
     * @return the randomly selected value
     */
    fun randomlySelect(
        array: IntArray,
        cdf: DoubleArray,
        stream: RNStreamIfc = defaultRNStream()
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
        val u = stream.randU01()
        while (cdf[i] <= u) {
            i = i + 1
            value = array[i]
        }
        return value
    }

    /**
     * Randomly selects from the list using the supplied cdf
     *
     * @param T       the type returned
     * @param list      list to select from
     * @param cdf       the cumulative probability associated with each element of
     * array
     * @param streamNum the stream number from the stream provider to use
     * @return the randomly selected value
    */
    fun <T> randomlySelect(list: List<T>, cdf: DoubleArray, streamNum: Int): T {
        return randomlySelect(list, cdf, rnStream(streamNum))
    }

    /**
     * Randomly selects from the list using the supplied cdf
     *
     * @param T  the type returned
     * @param list list to select from
     * @param cdf  the cumulative probability associated with each element of
     * array
     * @param stream  the source of randomness
     * @return the randomly selected value
    */
    fun <T> randomlySelect(
        list: List<T>,
        cdf: DoubleArray,
        stream: RNStreamIfc = defaultRNStream()
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
        val u = stream.randU01()
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
        require(isValidPMF(prob)) { "The supplied array was not a valid PMF" }
        val cdf = DoubleArray(prob.size)
        var sum = 0.0
        for (i in 0 until prob.size - 1) {
            sum = sum + prob[i]
            cdf[i] = sum
        }
        cdf[prob.size - 1] = 1.0
        return cdf
    }

    /** Makes an array that holds the probability mass function associated with
     *  the supplied discrete cumulative distribution function.
     *
     * @param cdf an array representing a valid cumulative distribution function
     */
    fun makePMF(cdf: DoubleArray): DoubleArray {
        require(isValidCDF(cdf)) { "The supplied array was not a CDF!" }
        val pmf = DoubleArray(cdf.size)
        for (i in pmf.indices) {
            if (i == 0) {
                pmf[i] = cdf[i]
            } else {
                pmf[i] = cdf[i] - cdf[i - 1]
            }
        }
        return pmf
    }

    /**
     * Randomly select from the list
     *
     * @param T      The type of element in the list
     * @param list      the list
     * @param streamNum the stream number from the stream provider to use
     * @return the randomly selected element
    */
    fun <T> randomlySelect(list: List<T>, streamNum: Int): T {
        return randomlySelect(list, rnStream(streamNum))
    }

    /**
     * Randomly select from the list
     *
     * @param T  The type of element in the list
     * @param list the list
     * @param stream  the source of randomness
     * @return the randomly selected element
    */
    fun <T> randomlySelect(list: List<T>, stream: RNStreamIfc = defaultRNStream()): T {
        require(list.isNotEmpty()){"Cannot select from an empty list"}
        return if (list.size == 1) {
            list[0]
        } else list.get(stream.randInt(0, list.size - 1))
    }

    /**
     * Randomly permutes the supplied array using the supplied stream
     * number, the array is changed
     *
     * @param x         the array
     * @param streamNum the stream number from the stream provider to use
     */
    fun permute(x: DoubleArray, streamNum: Int) {
        permute(x, rnStream(streamNum))
    }

    /**
     * Randomly permutes the supplied array using the supplied stream
     * number, the array is changed
     *
     * @param x   the array
     * @param stream the source of randomness
     */
    fun permute(x: DoubleArray, stream: RNStreamIfc = defaultRNStream()) {
        sampleWithoutReplacement(x, x.size, stream)
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
            "Cannot draw without replacement for more than the number of elements ${x.size}"
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
    fun permute(x: IntArray, streamNum: Int) {
        permute(x, rnStream(streamNum))
    }

    /**
     * Randomly permutes the supplied array using the supplied random
     * number generator, the array is changed.
     *
     * @param x   the array
     * @param rng the source of randomness
     */
    fun permute(x: IntArray, rng: RNStreamIfc = defaultRNStream()) {
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
            "Cannot draw without replacement for more than the number of elements ${x.size}"
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
    fun permute(x: BooleanArray, streamNum: Int) {
        permute(x, rnStream(streamNum))
    }

    /**
     * Randomly permutes the supplied array using the supplied random
     * number generator, the array is changed.
     *
     * @param x   the array
     * @param rng the source of randomness
     */
    fun permute(x: BooleanArray, rng: RNStreamIfc = defaultRNStream()) {
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
            "Cannot draw without replacement for more than the number of elements ${x.size}"
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
     * @param T the type of the array
     * @param x         the array
     * @param streamNum the stream number from the stream provider to use
     */
    fun <T> permute(x: Array<T>, streamNum: Int) {
        permute(x, rnStream(streamNum))
    }

    /**
     * Randomly permutes the supplied array using the supplied random
     * number generator, the array is changed
     *
     * @param T the type of the array
     * @param x   the array
     * @param rng the source of randomness
     */
    fun <T> permute(x: Array<T>, rng: RNStreamIfc = defaultRNStream()) {
        sampleWithoutReplacement(x, x.size, rng)
    }

    /**
     * The array x is changed, such that the first sampleSize elements contain the generated sample.
     * That is, x[0], x[1], ... , x[sampleSize-1] is the randomly sampled values without replacement
     *
     * @param T the type of the array
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
     * @param T the type of the array
     * @param x          the array
     * @param sampleSize the size to generate
     * @param stream        the source of randomness
     */
    fun <T> sampleWithoutReplacement(
        x: Array<T>,
        sampleSize: Int,
        stream: RNStreamIfc = defaultRNStream()
    ) {
        require(sampleSize <= x.size) {
            "Cannot draw without replacement for more than the number of elements ${x.size}"
        }
        for (j in 0 until sampleSize) {
            val i = stream.randInt(j, x.size - 1)
            val temp = x[j]
            x[j] = x[i]
            x[i] = temp
        }
    }

    /**
     * Randomly permutes the supplied List using the supplied stream
     * number, the list is changed
     *
     * @param T      the type of the list
     * @param x         the list
     * @param streamNum the stream number from the stream provider to use
    */
    fun <T> permute(x: MutableList<T>, streamNum: Int) {
        permute(x, rnStream(streamNum))
    }

    /**
     * Randomly permutes the supplied List using the supplied random
     * number generator, the list is changed
     *
     * @param T the type of the list
     * @param x   the list
     * @param stream the source of randomness
    */
    fun <T> permute(x: MutableList<T>, stream: RNStreamIfc = defaultRNStream()) {
        sampleWithoutReplacement(x, x.size, stream)
    }

    /**
     * The List x is changed, such that the first sampleSize elements contain the generate.
     * That is, x.get(0), x.get(1), ... , x.get(sampleSize-1) is the random sample without replacement
     *
     * @param T        the type of the list
     * @param x          the list
     * @param sampleSize the size to generate
     */
    fun <T> sampleWithoutReplacement(x: MutableList<T>, sampleSize: Int, streamNum: Int) {
        sampleWithoutReplacement(x, sampleSize, rnStream(streamNum))
    }

    /**
     * The List x is changed, such that the first sampleSize elements contain the sampled values.
     * That is, x.get(0), x.get(1), ... , x.get(sampleSize-1) is the random sample without replacement
     *
     * @param T        the type of the list
     * @param x          the list
     * @param sampleSize the size to generate
     * @param stream        the source of randomness
     */
    fun <T> sampleWithoutReplacement(
        x: MutableList<T>,
        sampleSize: Int,
        stream: RNStreamIfc = defaultRNStream()
    ) {
        require(sampleSize <= x.size) {
            "Cannot draw without replacement for more than the number of elements ${x.size}"
        }
        for (j in 0 until sampleSize) {
            val i = stream.randInt(j, x.size - 1)
            val temp = x[j]
            x[j] = x[i]
            x[i] = temp
        }
    }
}

/* extension functions */
fun Boolean.toInt() = if (this) 1 else 0
fun Boolean.toDouble() = if (this) 1.0 else 0.0

fun Double.toBoolean() = this == 1.0
//fun Double.toBoolean() = this > 0.0

fun Int.toBoolean() = this == 1

fun <T> List<T>.randomlySelect(stream: RNStreamIfc = KSLRandom.defaultRNStream()): T {
    return KSLRandom.randomlySelect(this, stream)
}

fun <T> List<T>.randomlySelect(streamNum: Int): T {
    return KSLRandom.randomlySelect(this, streamNum)
}

fun <T> MutableList<T>.permute(stream: RNStreamIfc = KSLRandom.defaultRNStream()) {
    KSLRandom.permute(this, stream)
}

fun <T> MutableList<T>.permute(streamNum: Int){
    KSLRandom.permute(this, streamNum)
}
