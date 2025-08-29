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
package ksl.utilities.random.rng

import ksl.utilities.Interval
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.KSLRandom.AlgoType
import ksl.utilities.random.rvariable.KSLRandom.defaultRNStream

/**
 * Represents a random number stream with stream control
 *
 * @author rossetti
 */
interface RNStreamIfc : RandU01Ifc, RNStreamControlIfc, RNStreamNewInstanceIfc, GetAntitheticStreamIfc {

    /**
     *  If the stream has been provided by a RNStreamProvider, then
     *  this returns the reference to the provider.
     */
    val streamProvider: RNStreamProviderIfc?

    /**
     *  If the stream has been provided by a RNStreamProvider, then
     *  its assigned stream number is returned.
     */
    val streamNumber: Int?
        get() = streamProvider?.streamNumber(this)

    /**
     *  An identifier assigned by the underlying stream factory to identity the stream
     *  for reporting purposes
     */
    val id: Int

    /**
     * Returns a (pseudo)random number from the discrete uniform distribution
     * over the integers {i, i + 1, . . . , j }, using this stream. Calls randU01 once.
     *
     * @param i start of range
     * @param j end of range
     * @return The integer pseudo random number
     */
    fun randInt(i: Int, j: Int): Int {
        require(i <= j) { "The lower limit must be <= the upper limit" }
        return i + (randU01() * (j - i + 1)).toInt()
    }

    /** A convenience function for allowing the range to be specified via a range
     *
     *  @param range the integer range to generate over
     */
    fun randInt(range: IntRange): Int {
        return randInt(range.first, range.last)
    }

    /**
     *  Returns a randomly generated sign -1 or +1
     *  @param pSuccess the probability of getting + 1. The default is 0.5.
     */
    fun rSign(pSuccess: Double = 0.5): Double {
        return if (randU01() < pSuccess) -1.0 else 1.0
    }

    /**
     * Returns a randomly generated sign -1, 0, +1
     * all equally likely.
     */
    fun rSignWithZero() : Double {
        return randInt(-1, 1).toDouble()
    }

    /**
     * @param pSuccess the probability of success, must be in (0,1)
     * @return the random value
     */
    fun rBernoulli(pSuccess: Double = 0.5): Double {
        return KSLRandom.rBernoulli(pSuccess, this)
    }

    /**
     * @param pSuccess the probability of success, must be in (0,1)
     * @return the random value as a Boolean value
     */
    fun rBernoulliBoolean(pSuccess: Double = 0.5): Boolean {
        return KSLRandom.rBernoulliBoolean(pSuccess, this)
    }

    /**
     * @param pSuccess the probability of success, must be in (0,1)
     * @param nTrials  the number of trials, must be greater than 0
     * @return the random value
     */
    fun rBinomial(pSuccess: Double, nTrials: Int): Int {
        return KSLRandom.rBinomial(pSuccess, nTrials, this)
    }

    /**
     * @param mean the mean of the Poisson, must be greater than 0
     * @return the random value
     */
    fun rPoisson(mean: Double): Int {
        return KSLRandom.rPoisson(mean, this)
    }

    /**
     * Generates a discrete uniform over the range
     *
     * @param minimum   the minimum of the range
     * @param maximum   the maximum of the range
     * @return the random value
     */
    fun rDUniform(minimum: Int, maximum: Int): Int {
        return randInt(minimum, maximum)
    }

    /**
     * Generates a discrete uniform over the range
     *
     * @param range the range of the random variate
     * @return the random value
     */
    fun rDUniform(range: IntRange): Int {
        return randInt(range.first, range.last)
    }

    /**
     * @param pSuccess the probability of success, must be in (0,1)
     * @return the random value on range 0, 1, 2,...
     */
    fun rGeometric(pSuccess: Double): Int {
        return KSLRandom.rGeometric(pSuccess, this)
    }

    /**
     * @param pSuccess   the probability of success
     * @param rSuccesses number of trials until rth success
     * @return the random value on range 1, 2, 3
     */
    fun rNegBinomial(
        pSuccess: Double,
        rSuccesses: Double
    ): Int {
        return KSLRandom.rNegBinomial(pSuccess, rSuccesses, this)
    }

    /**
     * Generates a continuous uniform over the range
     *
     * @param minimum the minimum of the range, must be less than maximum
     * @param maximum the maximum of the range
     * @return the random value
     */
    fun rUniform(
        minimum: Double = 0.0,
        maximum: Double = 1.0,
    ): Double {
        return KSLRandom.rUniform(minimum, maximum, this)
    }

    /**
     * @param mean     the mean of the normal
     * @param variance the variance of the normal, must be greater than 0
     * @return the random value
     */
    fun rNormal(
        mean: Double = 0.0,
        variance: Double = 1.0,
    ): Double {
        return KSLRandom.rNormal(mean, variance, this)
    }

    /**
     * @param mean     the mean of the lognormal, must be greater than 0
     * @param variance the variance of the lognormal, must be greater than 0
     * @return the random value
     */
    fun rLogNormal(
        mean: Double,
        variance: Double,
    ): Double {
        return KSLRandom.rLogNormal(mean, variance, this)
    }

    /**
     * @param shape the shape, must be greater than 0
     * @param scale the scale, must be greater than 0
     * @return the random value
     */
    fun rWeibull(shape: Double, scale: Double): Double {
        return KSLRandom.rWeibull(shape, scale, this)
    }

    /**
     * @param mean the mean, must be greater than 0
     * @return the random value
     */
    fun rExponential(mean: Double): Double {
        return KSLRandom.rExponential(mean, this)
    }

    /**
     * @param alpha1 alpha1 parameter
     * @param alpha2 alpha2 parameter, must be greater than zero
     * @param min    the min, must be less than max
     * @param max    the max
     * @return the generated value
     */
    fun rJohnsonB(
        alpha1: Double, alpha2: Double, min: Double, max: Double
    ): Double {
        return KSLRandom.rJohnsonB(alpha1, alpha2, min, max, this)
    }

    /**
     * @param location the location a real number
     * @param scale the scale, must be greater than 0
     * @return the generated value
     */
    fun rLogistic(location: Double, scale: Double): Double {
        return KSLRandom.rLogistic(location, scale, this)
    }

    /**
     * @param shape the shape, must be greater than 0
     * @param scale the scale, must be greater than 0
     * @return the generated value
     */
    fun rLogLogistic(shape: Double, scale: Double): Double {
        return KSLRandom.rLogLogistic(shape, scale, this)
    }

    /**
     * @param min  the min, must be less than or equal to mode
     * @param mode the mode, must be less than or equal to max
     * @param max  the max
     * @return the random value
     */
    fun rTriangular(
        min: Double, mode: Double, max: Double
    ): Double {
        return KSLRandom.rTriangular(min, mode, max, this)
    }

    /**
     * @param shape the shape, must be greater than 0.0
     * @param scale the scale, must be greater than 0.0
     * @param type, must be appropriate algorithm type, if null then inverse transform is the default
     * @return the generated value
     */
    fun rGamma(
        shape: Double, scale: Double, type: AlgoType = AlgoType.Inverse
    ): Double {
        return KSLRandom.rGamma(shape, scale, this, type)
    }

    /**
     * @param dof degrees of freedom, must be greater than 0
     * @return the random value
     */
    fun rChiSquared(dof: Double): Double {
        return KSLRandom.rChiSquared(dof, this)
    }

    /**
     * @param shape the shape, must be greater than 0
     * @param scale the scale, must be greater than 0
     * @return the generated value
     */
    fun rPearsonType5(
        shape: Double, scale: Double,
    ): Double {
        return KSLRandom.rPearsonType5(shape, scale, this)
    }

    /**
     * This beta is restricted to the range of (0,1)
     *
     * @param alpha  alpha (first shape) parameter
     * @param beta  beta (second shape) parameter
     * @return the random value
     */
    fun rBeta(alpha: Double, beta: Double): Double {
        return KSLRandom.rBeta(alpha, beta, this)
    }

    /**
     * This beta is restricted to the range of (minimum,maximum)
     *
     * @param alpha  alpha (first shape) parameter
     * @param beta  beta (second shape) parameter
     * @param minimum the minimum of the range, must be less than maximum
     * @param maximum the maximum of the range
     * @return the random value
     */
    fun rBetaG(alpha: Double, beta: Double, minimum: Double, maximum: Double): Double {
        return KSLRandom.rBetaG(alpha, beta, minimum, maximum, this)
    }

    /**
     * Pearson Type 6
     *
     * @param alpha1 alpha1 parameter
     * @param alpha2 alpha2 parameter
     * @param beta   the beta parameter, must be greater than 0
     * @return the random value
     */
    fun rPearsonType6(alpha1: Double, alpha2: Double, beta: Double): Double {
        return KSLRandom.rPearsonType6(alpha1, alpha2, beta, this)
    }

    /**
     * Generates according to a Laplace(location, scale)
     *
     * @param location  mean or location parameter
     * @param scale scale parameter, must be greater than 0
     * @return the random value
     */
    fun rLaplace(location: Double, scale: Double): Double {
        return KSLRandom.rLaplace(location, scale, this)
    }

    /**
     * Randomly select an element from the array
     *
     * @param array the array to select from, must not be empty
     * @return the randomly selected value
     */
    fun randomlySelect(array: IntArray): Int {
        return KSLRandom.randomlySelect(array, this)
    }

    /**
     * Randomly select an element from the array
     *
     * @param array the array to select from, must not be empty
     * @return the randomly selected value
     */
    fun randomlySelect(array: DoubleArray): Double {
        return KSLRandom.randomlySelect(array, this)
    }

    /**
     * Randomly selects from the array using the supplied cdf
     *
     * @param array array to select from
     * @param cdf   the cumulative probability associated with each element of
     * array
     * @return the randomly selected value
     */
    fun randomlySelect(
        array: DoubleArray, cdf: DoubleArray,
    ): Double {
        return KSLRandom.randomlySelect(array, cdf, this)
    }

    /**
     * Randomly selects from the array using the supplied cdf, NO checking of arrays
     *
     * @param array array to select from
     * @param cdf   the cumulative probability associated with each element of array
     * @return the randomly selected value
     */
    fun discreteInverseCDF(array: IntArray, cdf: DoubleArray): Int {
        return KSLRandom.discreteInverseCDF(array, cdf, this)
    }

    /**
     * Randomly selects from the array using the supplied cdf
     *
     * @param array array to select from
     * @param cdf   the cumulative probability associated with each element of array
     * @return the randomly selected value
     */
    fun randomlySelect(array: IntArray, cdf: DoubleArray): Int {
        return KSLRandom.randomlySelect(array, cdf, this)
    }

    /**
     * Randomly permutes the supplied array
     *
     * @param x   the array
     */
    fun permute(x: DoubleArray) {
        return KSLRandom.permute(x, this)
    }

    /**
     * The array x is changed, such that the first sampleSize elements contain the sample.
     * That is, x[0], x[1], ... , x[sampleSize-1] is the random sample without replacement
     *
     * @param x          the array
     * @param sampleSize the size to generate
     */
    fun sampleWithoutReplacement(x: DoubleArray, sampleSize: Int) {
        KSLRandom.sampleWithoutReplacement(x, sampleSize, this)
    }

    /**
     * Randomly permutes the supplied array, the array is changed.
     *
     * @param x   the array
     */
    fun permute(x: IntArray) {
        KSLRandom.permute(x, this)
    }

    /**
     * The array x is changed, such that the first sampleSize elements contain the generated sample.
     * That is, x[0], x[1], ... , x[sampleSize-1] is the random sample without replacement
     *
     * @param x          the array
     * @param sampleSize the size to generate
     */
    fun sampleWithoutReplacement(x: IntArray, sampleSize: Int) {
        KSLRandom.sampleWithoutReplacement(x, sampleSize, this)
    }

    /**
     * Randomly permutes the supplied array using the stream. The array is changed.
     *
     * @param x   the array
     */
    fun permute(x: BooleanArray) {
        KSLRandom.permute(x, this)
    }

    /**
     * The array x is changed, such that the first sampleSize elements contain the generated sample.
     * That is, x[0], x[1], ... , x[sampleSize-1] is the random sample without replacement
     *
     * @param x          the array
     * @param sampleSize the size to generate
     */
    fun sampleWithoutReplacement(x: BooleanArray, sampleSize: Int) {
        KSLRandom.sampleWithoutReplacement(x, sampleSize, this)
    }

    /**
     * Randomly permutes the supplied array using the stream. The array is changed.
     *
     * @param T the type of the array
     * @param x   the array
     */
    fun <T> permute(x: Array<T>) {
        KSLRandom.permute(x, this)
    }

    /**
     * The array x is changed, such that the first sampleSize elements contain the generated sample.
     * That is, x[0], x[1], ... , x[sampleSize-1] is the randomly sampled values without replacement
     *
     * @param T the type of the array
     * @param x          the array
     * @param sampleSize the size to generate
     */
    fun <T> sampleWithoutReplacement(x: Array<T>, sampleSize: Int) {
        KSLRandom.sampleWithoutReplacement(x, sampleSize, this)
    }

    /**
     * Randomly permutes the supplied List using the stream. The list is changed.
     *
     * @param T the type of the list
     * @param x   the list
     */
    fun <T> permute(x: MutableList<T>) {
        KSLRandom.permute(x, this)
    }

    /**
     * The List x is changed, such that the first sampleSize elements contain the sampled values.
     * That is, x.get(0), x.get(1), ... , x.get(sampleSize-1) is the random sample without replacement
     *
     * @param T        the type of the list
     * @param x          the list
     * @param sampleSize the size to generate
     */
    fun <T> sampleWithoutReplacement(x: MutableList<T>, sampleSize: Int) {
        KSLRandom.sampleWithoutReplacement(x, sampleSize, this)
    }

    /**
     *  Randomly generates [sampleSize] points from a unit Latin hyper-cube for the
     *  specified [dimension] using the supplied stream. A Latin hypercube sample generates n points in
     *  [0,1)^d, placing exactly one point in [j/n, (j+1)/n) for j = 0,1,2, ..,n-1.
     *
     *  @param sampleSize the number of points to generate.
     *  @param dimension the size (dimension) of the hyper-cube.
     *  @return an array of DoubleArray. The rows represent the samples each of size (dimension)
     */
    fun rLatinHyperCube(
        sampleSize: Int,
        dimension: Int,
    ): Array<DoubleArray> {
        return KSLRandom.rLatinHyperCube(sampleSize, dimension, this)
    }

    /**
     *  Randomly generates [sampleSize] points from a unit Latin hyper-cube for the
     *  specified intervals using the supplied stream. A Latin hypercube sample generates n points in
     *  hyper-cube defined by the intervals.
     *
     *  @param sampleSize the number of points to generate.
     *  @param intervals the intervals that will be divided into points. The list must
     *  not be empty and each interval must be finite with a width greater than 0.0
     *  @return an array of DoubleArray. The rows represent the samples each of size (dimension)
     */
    @Suppress("unused")
    fun rLatinHyperCube(
        sampleSize: Int,
        intervals: List<Interval>,
    ) : Array<DoubleArray> {
        return KSLRandom.rLatinHyperCube(sampleSize, intervals, this)
    }

}