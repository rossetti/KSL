/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Models the lognormal distribution
 * This distribution is commonly used to model the time of a task
 *
 * Parameters: mean and variance.
 *
 * Note: these parameters are the actual mean and variance of the lognormal distribution, not some underlying
 * normal distribution as in many implementations.
 *
 * @param theMean must be &gt; 0
 * @param theVariance must be &gt; 0
 * @param name an optional name/label
 */
class Lognormal(theMean: Double = 1.0, theVariance: Double = 1.0, name: String? = null) :
    Distribution<Lognormal>(name), ContinuousDistributionIfc, LossFunctionDistributionIfc, InverseCDFIfc,
    GetRVariableIfc {

    init {
        require(theMean > 0) { "Mean must be positive" }
        require(theVariance > 0) { "Variance must be positive" }
        parameters(theMean, theVariance)
    }

    var mean = theMean
        private set

    var variance = theVariance
        private set

    /** Sets the parameters of a lognormal distribution to
     * mean and variance.  Note: these parameters are the
     * actual mean and variance of the lognormal, not the underlying
     * normal as in many other implementations.
     *
     * @param theMean must be &gt; 0
     * @param theVariance must be &gt; 0
     */
    fun parameters(theMean: Double, theVariance: Double) {
        require(theMean > 0) { "Mean must be positive" }
        mean = theMean
        require(theVariance > 0) { "Variance must be positive" }
        variance = theVariance
        val d = variance + mean * mean
        val t = mean * mean
        normalMean = ln(t / sqrt(d))
        normalStdDev = sqrt(ln(d / t))
    }

    override fun mean() = mean

    override fun variance() = variance

    /** The mean of the underlying normal
     *
     * @return mean of the underlying normal
     */
    var normalMean = 0.0
        private set

    /** The standard deviation of the underlying normal
     *
     * @return standard deviation of the underlying normal
     */
    var normalStdDev = 0.0
        private set

    /** The variance of the underlying normal
     *
     * @return variance of the underlying normal
     */
    val normalVariance = normalStdDev * normalStdDev

    /** Provides a normal distribution with correct parameters
     * as related to this lognormal distribution
     * @return The Normal distribution
     */
    fun normal() = Normal(normalMean, normalStdDev * normalStdDev)

    /** Constructs a lognormal distribution with
     * mean = parameters[0] and variance = parameters[1]
     * @param parameters An array with the mean and variance
     */
    constructor(parameters: DoubleArray) : this(parameters[0], parameters[1], null)

    override fun instance(): Lognormal {
        return Lognormal(mean, variance)
    }

    override fun domain(): Interval {
        return Interval(0.0, Double.POSITIVE_INFINITY)
    }

    /**
     *
     * @return the 3rd moment
     */
    val moment3: Double
        get() {
            val calculatingM = -(1.0 / 2.0) * ln(variance / (mean * mean * mean * mean) + 1.0)
            val calculatingS = ln(variance / (mean * mean) + mean * mean)
            return exp(3.0 * calculatingM + 9.0 * calculatingS / 2.0)
        }

    /**
     *
     * @return the 4th moment
     */
    val moment4: Double
        get() {
            val calculatingM = -(1.0 / 2.0) * ln(variance / (mean * mean * mean * mean) + 1.0)
            val calculatingS = ln(variance / (mean * mean) + mean * mean)
            return exp(4.0 * calculatingM + 8.0 * calculatingS)
        }

    override fun cdf(x: Double): Double {
        if (x <= 0) {
            return 0.0
        }
        val z = (ln(x) - normalMean) / normalStdDev
        return Normal.stdNormalCDF(z)
    }

    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Supplied probability was $p Probability must be [0,1)" }
        if (p <= 0.0) {
            return 0.0
        }
        if (p >= 1.0) {
            return Double.POSITIVE_INFINITY
        }
        val z = Normal.stdNormalInvCDF(p)
        val x = z * normalStdDev + normalMean
        return exp(x)
    }

    override fun pdf(x: Double): Double {
        if (x <= 0) {
            return 0.0
        }
        val z = (ln(x) - normalMean) / normalStdDev
        return Normal.stdNormalPDF(z) / x
    }

    /** Gets the skewness of the distribution
     * @return the skewness
     */
    val skewness: Double
        get() {
            val t = exp(normalStdDev * normalStdDev)
            return sqrt(t - 1.0) * (t + 2.0)
        }

    /** Gets the kurtosis of the distribution
     * @return the kurtosis
     */
    val kurtosis: Double
        get() {
            val t1 = exp(4.0 * normalStdDev * normalStdDev)
            val t2 = exp(3.0 * normalStdDev * normalStdDev)
            val t3 = exp(2.0 * normalStdDev * normalStdDev)
            return t1 + 2.0 * t2 + 3.0 * t3 - 6.0
        }

    /** Sets the parameters for the distribution
     * mean = parameters[0] and variance = parameters[1]
     * @param params an array of doubles representing the parameters for the distribution
     */
    override fun parameters(params: DoubleArray) {
        parameters(params[0], params[1])
    }

    /** Gets the parameters for the distribution
     *
     * @return Returns an array of the parameters for the distribution
     */
    override fun parameters(): DoubleArray {
        return doubleArrayOf(mean, variance)
    }

    override fun firstOrderLossFunction(x: Double): Double {
        if (x <= 0.0) {
            return mean - x
        }
        val z = (ln(x) - normalMean) / normalStdDev
        val t1 = Normal.stdNormalCDF(normalStdDev - z)
        val t2 = Normal.stdNormalCDF(-z)
        return mean() * t1 - x * t2
    }

    override fun secondOrderLossFunction(x: Double): Double {
        val m = mean
        val m2 = variance + m * m
        return if (x <= 0.0) {
            0.5 * (m2 - 2.0 * x * m + x * x)
        } else {
            val z = (ln(x) - normalMean) / normalStdDev
            val t1 = Normal.stdNormalCDF(2.0 * normalStdDev - z)
            val t2 = Normal.stdNormalCDF(normalStdDev - z)
            val t3 = Normal.stdNormalCDF(-z)
            0.5 * (m2 * t1 - 2.0 * x * m * t2 + x * x * t3)
        }
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return LognormalRV(mean, variance, stream)
    }

}