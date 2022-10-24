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
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.LogLogisticRV
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin


/**
 *
 * @param theShape the shape parameter
 * @param theScale the scale parameter
 * @param name an optional label/name
 * @author rossetti
 */
class LogLogistic (theShape: Double = 1.0, theScale: Double = 1.0, name: String? = null) : Distribution<LogLogistic>(name),
    ContinuousDistributionIfc, InverseCDFIfc, GetRVariableIfc {

    init {
        require(theShape > 0) { "Shape parameter must be positive" }
        require(theScale > 0) { "Scale parameter must be positive" }
    }

    var shape = theShape
        set(theShape) {
            require(theShape > 0) { "Shape parameter must be positive" }
            field = theShape
        }

    var scale = theScale
        set(theScale) {
            require(theScale > 0) { "Scale parameter must be positive" }
            field = theScale
        }

    /**
     *
     * @param parameters the parameter array parameter[0] = shape, parameter[1] = scale
     */
    constructor(parameters: DoubleArray) : this(parameters[0], parameters[1], null)

    override fun instance(): LogLogistic {
        return LogLogistic(shape, scale)
    }

    override fun domain(): Interval {
        return Interval(0.0, Double.POSITIVE_INFINITY)
    }

    override fun pdf(x: Double): Double {
        return if (x > 0.0) {
            val t1 = x / scale
            val n = shape * t1.pow(shape - 1.0)
            val t2 = t1.pow(shape)
            val d = scale * (1.0 + t2) * (1.0 + t2)
            n / d
        } else {
            0.0
        }
    }

    override fun cdf(x: Double): Double { // alpha = shape, beta = scale
        return if (x > 0.0) {
            val y = (x / scale).pow(-shape)
            1.0 / (1.0 + y)
        } else {
            0.0
        }
    }

    override fun mean(): Double {
        if (shape <= 1.0) {
            return Double.NaN
        }
        val theta = PI / shape
        val csctheta = 1.0 / sin(theta)
        return scale * theta * csctheta
    }

    override fun variance(): Double { // alpha = shape, beta = scale
        if (shape <= 2.0) {
            return Double.NaN
        }
        val theta = PI / shape
        val csctheta = 1.0 / sin(theta)
        val csc2theta = 1.0 / sin(2.0 * theta)
        return scale * scale * theta * (2.0 * csc2theta - theta * csctheta * csctheta)
    }

    override fun invCDF(p: Double): Double { // alpha = shape, beta = scale
        require(!(p < 0.0 || p > 1.0)) { "Probability must be [0,1]" }
        if (p <= 0.0) {
            return 0.0
        }
        if (p >= 1.0) {
            return Double.POSITIVE_INFINITY
        }
        val c = p / (1.0 - p)
        return scale * c.pow(1.0 / shape)
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

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return LogLogisticRV(shape, scale, stream)
    }


}