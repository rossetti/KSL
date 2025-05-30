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

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.InverseCDFRV
import ksl.utilities.random.rvariable.RVariableIfc


/**
 * Represents a Distribution that has been Shifted (translated to the right)
 * The shift must be &gt;= 0.0
 *
 * Constructs a shifted distribution based on the provided distribution
 *
 * @param theDistribution the distribution to shift
 * @param theShift        The linear shift
 * @param name         an optional name/label
 */
open class ShiftedDistribution(theDistribution: DistributionIfc, theShift: Double, name: String? = null) :
    Distribution(name) {

    init {
        require(theShift >= 0.0) { "The shift should not be < 0.0" }
    }

    protected var distribution: DistributionIfc = theDistribution

//    protected var myLossFunctionDistribution: LossFunctionDistributionIfc? = null

    var shift : Double = theShift
        set(value) {
            require(value >= 0.0) { "The shift should not be < 0.0" }
            field = value
        }

    override fun instance(): ShiftedDistribution {
        val d = distribution.instance()
        return ShiftedDistribution(d, shift)
    }

    /**
     * Changes the underlying distribution and the shift
     *
     * @param distribution must not be null
     * @param shift        must be &gt;=0.0
     */
    fun setDistribution(distribution: DistributionIfc, shift: Double) {
        this.distribution = distribution
        this.shift = shift
    }

    /**
     * Sets the parameters of the shifted distribution
     * shift = param[0]
     * If supplied, the other elements of the array are used in setting the
     * parameters of the underlying distribution.  If only the shift is supplied
     * as a parameter, then the underlying distribution's parameters are not changed
     * (and do not need to be supplied)
     * @param params the shift as param[0]
     */
    override fun parameters(params: DoubleArray) {
        shift = params[0]
        if (params.size == 1) {
            return
        }
        val y = DoubleArray(params.size - 1)
        for (i in y.indices) {
            y[i] = params[i + 1]
        }
        distribution.parameters(y)
    }

    override fun cdf(x: Double): Double {
        return if (x < shift) {
            0.0
        } else {
            distribution.cdf(x - shift)
        }
    }

    override fun mean(): Double {
        return shift + distribution.mean()
    }

    /**
     * Gets the parameters for the shifted distribution
     * shift = parameter[0]
     * The other elements of the returned array are
     * the parameters of the underlying distribution
     */
    override fun parameters(): DoubleArray {
        val x = distribution.parameters()
        val y = DoubleArray(x.size + 1)
        y[0] = shift
        for (i in x.indices) {
            y[i + 1] = x[i]
        }
        return y
    }

    override fun variance(): Double {
        return distribution.variance()
    }

    override fun invCDF(p: Double): Double {
        return distribution.invCDF(p) + shift
    }

//    override fun firstOrderLossFunction(x: Double): Double {
//        val cdf = distribution as LossFunctionDistributionIfc
//        return cdf.firstOrderLossFunction(x - shift)
//    }

//    override fun secondOrderLossFunction(x: Double): Double {
//        val cdf = distribution as LossFunctionDistributionIfc
//        return cdf.secondOrderLossFunction(x - shift)
//    }

//    fun thirdOrderLossFunction(x: Double): Double {
//        val first = myDistribution as Poisson?
//        return first!!.thirdOrderLossFunction(x - myShift)
//    }

    override fun randomVariable(streamNumber: Int, streamProvider: RNStreamProviderIfc): RVariableIfc {
        return InverseCDFRV(instance(), streamNumber, streamProvider)
    }
}