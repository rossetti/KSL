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
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.*
import kotlin.math.pow

/**
 * Represents a Pearson Type VI distribution,
 * see Law (2007) Simulation Modeling and Analysis, McGraw-Hill, pg 294
 *
 *
 * @param theShape1 shape 1 must be greater than 0.0
 * @param theShape2 shape 2 must be greater than 0.0
 * @param theScale   scale must be greater than 0.0
 * @param name   an optional name/label
 */
class PearsonType6 (
    theShape1: Double = 2.0,
    theShape2: Double = 3.0,
    theScale: Double = 1.0,
    name: String? = null
) : Distribution(name), ContinuousDistributionIfc, InverseCDFIfc, GetRVariableIfc, RVParametersTypeIfc by RVType.PearsonType6 {

    init {
        require(theShape1 > 0.0) { "The 1st shape parameter must be > 0.0" }
        require(theShape2 > 0.0) { "The 2nd shape parameter must be > 0.0" }
        require(theScale > 0.0) { "The scale parameter must be > 0.0" }
    }

    var shape1 = theShape1
        private set

    var shape2 = theShape2
        private set

    /**
     * the scale must be greater than 0.0
     */
    var scale = theScale
     set(value) {
         require(value > 0.0) { "The scale parameter must be > 0.0" }
         field = value
     }

    private var myBetaCDF: Beta = Beta(theShape1, theShape2)

    private var myBetaA1A2 = Beta.betaFunction(theShape1, theShape2)

    /**
     * Creates a PearsonTypeVI distribution
     *
     *
     * parameters[0] = alpha1
     * parameters[1] = alpha2
     * parameters[2] = beta
     *
     * @param parameters the parameter array
     */
    constructor(parameters: DoubleArray) : this(parameters[0], parameters[1], parameters[2], null)

    override fun instance(): PearsonType6 {
        return PearsonType6(shape1, shape2, scale)
    }

    override fun domain(): Interval {
        return Interval(0.0, Double.POSITIVE_INFINITY)
    }

    /**
     * @param alpha1 shape 1 must be greater than 0.0
     * @param alpha2 shape 2 must be greater than 0.0
     * @param beta   scale must be greater than 0.0
     */
    fun setParameters(alpha1: Double, alpha2: Double, beta: Double) {
        scale = beta
        setShapeParameters(alpha1, alpha2)
    }

    /**
     * @param alpha1 shape 1 must be greater than 0.0
     * @param alpha2 shape 2 must be greater than 0.0
     */
    fun setShapeParameters(alpha1: Double, alpha2: Double) {
        require(alpha1 > 0.0) { "The 1st shape parameter must be > 0.0" }
        require(alpha2 > 0.0) { "The 2nd shape parameter must be > 0.0" }
        shape1 = alpha1
        shape2 = alpha2
        myBetaA1A2 = Beta.betaFunction(alpha1, alpha2)
        myBetaCDF.parameters(alpha1, alpha2)
    }

    /**
     * params[0] = alpha1
     * params[1] = alpha2
     * params[2] = beta
     *
     * @param params the parameter array
     */
    override fun parameters(params: DoubleArray) {
        setParameters(params[0], params[1], params[2])
    }

    /**
     * params[0] = alpha1
     * params[1] = alpha2
     * params[2] = beta
     *
     * @return the parameter array
     */
    override fun parameters(): DoubleArray {
        return doubleArrayOf(shape1, shape2, scale)
    }

    /**
     * @return
     */
    override fun pdf(x: Double): Double {
        return if (x <= 0.0) {
            0.0
        } else Math.pow(
            x / scale,
            shape1 - 1.0
        ) / (scale * myBetaA1A2 * (1.0 + x / scale).pow(shape1 + shape2))
    }

    override fun cdf(x: Double): Double {
        return if (x <= 0.0) {
            0.0
        } else myBetaCDF.cdf(x / (x + scale))
    }

    override fun invCDF(p: Double): Double {
        require(!(p < 0 || p > 1)) { "Probability must be [0,1]" }
        val fib = myBetaCDF.invCDF(p)
        return scale * fib / (1.0 - fib)
    }

    /**
     * @return Returns the mean or Double.NaN if alpha2 &lt;= 1.0
     */
    override fun mean(): Double {
        return if (shape2 <= 1) {
            Double.NaN
        } else scale * shape1 / (shape2 - 1)
    }

    /**
     * @return Returns the variance or Double.NaN if alpha2 &lt;= 2.0
     */
    override fun variance(): Double {
        return if (shape2 <= 2) {
            Double.NaN
        } else scale * scale * shape1 * (shape1 + shape2 - 1) / ((shape2 - 2) * (shape2 - 1.0) * (shape2 - 1.0))
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return PearsonType6RV(shape1, shape2, scale, stream)
    }

    override fun toString(): String {
        return "PearsonType6(shape1=$shape1, shape2=$shape2, scale=$scale)"
    }

}