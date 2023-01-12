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
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.PearsonType5RV
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.exp
import kotlin.math.pow

/** Represents a Pearson Type V distribution,
 * see Law (2007) Simulation Modeling and Analysis, McGraw-Hill, pg 293
 *
 * @param theShape must be &gt;0
 * @param theScale must be &gt; 0
 * @param name an optional label/name
 */
class PearsonType5 (theShape: Double = 1.0, theScale: Double = 1.0, name: String? = null) :
    Distribution<PearsonType5>(name), ContinuousDistributionIfc, InverseCDFIfc, GetRVariableIfc {

    init {
        require(theShape > 0) { "Alpha (shape parameter) should be > 0" }
        require(theScale > 0) { "Beta (scale parameter) should > 0" }
    }
    /** Gets the shape parameter
     *
     * @return the shape parameter
     */
    var shape = theShape
        private set

    /** Gets the scale parameter
     *
     * @return the scale parameter
     */
    var scale = theScale
        private set

    private var myGammaCDF: Gamma = Gamma(shape, 1.0 / scale)

    private var myGAlpha = Gamma.gammaFunction(shape)

    /** Creates a PearsonType5 distribution
     * parameters[0] = shape
     * parameters[1] = scale
     *
     * @param parameters the parameter array
     */
    constructor(parameters: DoubleArray) : this(parameters[0], parameters[1], null)

    override fun instance(): PearsonType5 {
        return PearsonType5(shape, scale)
    }

    /** Sets the shape and scale parameters
     *
     * @param shape must be &gt; 0
     * @param scale must be &gt; 0
     */
    fun setParameters(shape: Double, scale: Double) {
        require(shape > 0) { "Alpha (shape parameter) should be > 0" }
        require(scale > 0) { "Beta (scale parameter) should > 0" }
        this.shape = shape
        myGAlpha = Gamma.gammaFunction(shape)
        this.scale = scale
        myGammaCDF.shape = shape
        myGammaCDF.scale = 1.0 / scale
    }

    override fun domain(): Interval {
        return Interval(0.0, Double.POSITIVE_INFINITY)
    }

    override fun cdf(x: Double): Double {
        return if (x > 0) {
            1 - myGammaCDF.cdf(1 / x)
        } else 0.0
    }

    /**
     *
     * @return If shape &lt;= 1.0, returns Double.NaN, otherwise, returns the mean
     */
    override fun mean(): Double {
        return if (shape <= 1.0) {
            Double.NaN
        } else scale / (shape - 1.0)
    }

    /** Gets the parameters
     * parameters[0] = shape
     * parameters[1] = scale
     *
     */
    override fun parameters(): DoubleArray {
        return doubleArrayOf(shape, scale)
    }

    /**
     *
     * @return If shape &lt;= 2.0, returns Double.NaN, otherwise returns the variance
     */
    override fun variance(): Double {
        return if (shape <= 2.0) {
            Double.NaN
        } else scale * scale / ((shape - 2.0) * (shape - 1.0) * (shape - 1.0))
    }

    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Probability must be [0,1]" }
        return 1.0 / myGammaCDF.invCDF(p)
    }

    override fun pdf(x: Double): Double {
        return if (x > 0.0) {
            x.pow(-(shape + 1.0)) * exp(-scale / x) / (scale.pow(-shape) * myGAlpha)
        } else 0.0
    }

    /** Sets the parameters of the distribution
     *
     * parameters[0] = shape
     * parameters[1] = scale
     *
     * @param params the parameter array
     */
    override fun parameters(params: DoubleArray) {
        setParameters(params[0], params[1])
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return PearsonType5RV(shape, scale, stream)
    }

}