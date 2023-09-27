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
import ksl.utilities.countLessEqualTo
import ksl.utilities.countLessThan
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.WeibullRV
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** This class defines a Weibull distribution
 * @param theShape The shape parameter of the distribution
 * @param theScale The scale parameter of the distribution
 * @param name an optional name/label
 */
class Weibull(theShape: Double = 1.0, theScale: Double = 1.0, name: String? = null) :
    Distribution<Weibull>(name), ContinuousDistributionIfc, InverseCDFIfc, GetRVariableIfc {
    init {
        require(theShape > 0) { "Shape parameter must be positive" }
        require(theScale > 0) { "Scale parameter must be positive" }
    }

    var shape = theShape
        set(value) {
            require(value > 0) { "Shape parameter must be positive" }
            field = value
        }

    var scale = theScale
        set(value) {
            require(value > 0) { "Scale parameter must be positive" }
            field = value
        }

    /** Constructs a weibull distribution with
     * shape = parameters[0] and scale = parameters[1]
     * @param parameters An array with the shape and scale
     */
    constructor(parameters: DoubleArray) : this(parameters[0], parameters[1], null)

    override fun instance(): Weibull {
        return Weibull(shape, scale)
    }

    override fun domain(): Interval {
        return Interval(0.0, Double.POSITIVE_INFINITY)
    }

    /** Sets the parameters
     * @param theShape The shape parameter must &gt; 0.0
     * @param theScale The scale parameter must be &gt; 0.0
     */
    fun parameters(theShape: Double, theScale: Double) {
        shape = theShape
        scale = theScale
    }

    /** Sets the parameters for the distribution with
     * shape = parameters[0] and scale = parameters[1]
     *
     * @param params an array of doubles representing the parameters for
     * the distribution
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

    override fun mean(): Double { // shape = alpha, scale = beta
        val ia = 1.0 / shape
        val gia = Gamma.gammaFunction(ia)
        return scale * ia * gia
    }

    override fun variance(): Double {
        val ia = 1.0 / shape
        val gia = Gamma.gammaFunction(ia)
        val g2ia = Gamma.gammaFunction(2.0 * ia)
        return scale * scale * ia * (2.0 * g2ia - ia * gia * gia)
    }

    override fun cdf(x: Double): Double {
        if ((x == Double.POSITIVE_INFINITY) || (x == Double.MAX_VALUE)) {
            return 1.0
        }
        return if (x > 0.0) {
            1.0 - exp(-(x / scale).pow(shape))
        } else {
            0.0
        }
    }

    override fun pdf(x: Double): Double {
        if (x <= 0) {
            return 0.0
        }
        val e1 = -(x / scale).pow(shape)
        var f = shape * scale.pow(-shape)
        f = f * x.pow(shape - 1.0)
        f = f * exp(e1)
        return f
    }

    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Supplied probability was $p Probability must be [0,1]" }
        if (p <= 0.0) {
            return 0.0
        }
        return if (p >= 1.0) {
            Double.POSITIVE_INFINITY
        } else scale * (-ln(1.0 - p)).pow(1.0 / shape)
    }

    /**
     *
     * @return the 3rd moment
     */
    val moment3: Double
        get() = shape.pow(3.0) * exp(Gamma.logGammaFunction(1.0 + 3.0 * (1.0 / scale)))

    /**
     *
     * @return the 4th moment
     */
    val moment4: Double
        get() = shape.pow(4.0) * exp(Gamma.logGammaFunction(1.0 + 4.0 * (1.0 / scale)))

    /** Gets the kurtosis of the distribution
     * www.mathworld.wolfram.com/WeibullDistribution.html
     * @return the kurtosis
     */
    fun kurtosis(): Double {
        val c1 = (shape + 1.0) / shape
        val c2 = (shape + 2.0) / shape
        val c3 = (shape + 3.0) / shape
        val c4 = (shape + 4.0) / shape
        val gc1 = Gamma.gammaFunction(c1)
        val gc2 = Gamma.gammaFunction(c2)
        val gc3 = Gamma.gammaFunction(c3)
        val gc4 = Gamma.gammaFunction(c4)
        val n = -3.0 * gc1 * gc1 * gc1 * gc1 + 6.0 * gc1 * gc1 * gc2 - 4.0 * gc1 * gc3 + gc4
        val d = (gc1 * gc1 - gc2) * (gc1 * gc1 - gc2)
        return n / d - 3.0
    }

    /** Gets the skewness of the distribution
     * www.mathworld.wolfram.com/WeibullDistribution.html
     * @return the skewness
     */
    fun skewness(): Double {
        val c1 = (shape + 1.0) / shape
        val c2 = (shape + 2.0) / shape
        val c3 = (shape + 3.0) / shape
        val gc1 = Gamma.gammaFunction(c1)
        val gc2 = Gamma.gammaFunction(c2)
        val gc3 = Gamma.gammaFunction(c3)
        val n = 2.0 * gc1 * gc1 * gc1 - 3.0 * gc1 * gc2 + gc3
        val d = sqrt((gc2 - gc1 * gc1) * (gc2 - gc1 * gc1) * (gc2 - gc1 * gc1))
        return n / d
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return WeibullRV(shape, scale, stream)
    }

    companion object {

        /**
         *  Estimates the shape and scale parameters based on supplied values of the percentiles
         *  [xp1] represents the estimated (from a sample) quantile for [p1]
         *  [xp2] represents the estimated (from a sample) quantile for [p2]
         *  The quantiles must be non-negative and not equal. The probabilities must be within (0,1) and not equal.
         *  The returned pair has:
         *  component1 = shape
         *  component2 = scale
         *  See: Marks, Neil B. “Estimation of Weibull Parameters from Common Percentiles.”
         *  Journal of Applied Statistics 32, no. 1 (January 2005): 17–24.
         *  https://doi.org/10.1080/0266476042000305122.
         *
         */
        fun parametersFromPercentiles(xp1: Double, xp2: Double, p1: Double, p2: Double): Pair<Double, Double> {
            require(xp1 > 0.0) { "The first quantile estimate was <= 0.0" }
            require(xp2 > 0.0) { "The second quantile estimate was <= 0.0" }
            require(xp1 != xp2) { "The quantile estimates must not be equal" }
            require((0.0 < p1) && (p1 < 1.0)) { "The value of p1 must be within (0,1)" }
            require((0.0 < p2) && (p2 < 1.0)) { "The value of p2 must be within (0,1)" }
            require(p1 != p2) { "The probabilities must not be equal" }
            val c1 = -ln(1.0 - p1)
            val c2 = -ln(1.0 - p2)
            val shape = (ln(c1) - ln(c2)) / (ln(xp1) - ln(xp2))
            val scale = xp1.pow(shape) / c1
            return Pair(shape, scale)
        }

        /**
         *  Based on the recommendation on page 188 of Law(2007)
         *  There must be at least two observations. Returns
         *  an estimated initial shape parameter. There should not be any negative
         *  or zero values in the data.
         */
        fun initialShapeEstimate(data: DoubleArray): Double {
            require(data.size >= 2) { "There must be at least two observations" }
            require(data.countLessEqualTo(0.0) == 0) {"There were negative or zero values in the data."}
            val n = data.size.toDouble()
            var sumlnx = 0.0
            var sumlnxsq = 0.0
            for (x in data) {
                val lnx = ln(x)
                sumlnx = sumlnx + lnx
                sumlnxsq = sumlnxsq + lnx * lnx
            }
            val coefficient = 6.0 / (Math.PI * Math.PI)
            val diff = sumlnxsq - (sumlnx * sumlnx / n)
            val f = sqrt(coefficient * diff / (n - 1.0))
            return (1.0 / f)
        }

        /**
         *  Given a [shape] parameter estimate the corresponding
         *  scale parameter based on the data. The shape parameter must be greater
         *  than 0.0. The data must not be empty and there should not be any negative
         *  or zero values in the data.
         */
        fun estimateScale(shape: Double, data: DoubleArray) : Double {
            require(shape > 0.0) {"The shape parameter must be > 0.0"}
            require(data.isNotEmpty()) { "There must be at least one observation." }
            require(data.countLessEqualTo(0.0) == 0) {"There were negative or zero values in the data."}
            var sumB = 0.0
            for (x in data) {
                sumB = sumB + x.pow(shape)
            }
            val n = data.size.toDouble()
            return (1.0/(sumB/n).pow(shape))
        }

    }
}