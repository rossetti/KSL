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
package ksl.utilities.distributions

import ksl.utilities.random.rng.RNStreamIfc
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
open class ShiftedDistribution(theDistribution: DistributionIfc<*>, theShift: Double, name: String? = null) :
    Distribution<ShiftedDistribution>(name) {

    init {
        require(theShift >= 0.0) { "The shift should not be < 0.0" }
    }

    protected var distribution: DistributionIfc<*> = theDistribution

//    protected var myLossFunctionDistribution: LossFunctionDistributionIfc? = null

    var shift = theShift
        set(value) {
            require(value >= 0.0) { "The shift should not be < 0.0" }
            field = value
        }

    override fun instance(): ShiftedDistribution {
        val d = distribution.instance() as DistributionIfc<*>
        return ShiftedDistribution(d, shift)
    }

    /**
     * Changes the underlying distribution and the shift
     *
     * @param distribution must not be null
     * @param shift        must be &gt;=0.0
     */
    fun setDistribution(distribution: DistributionIfc<*>, shift: Double) {
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

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return InverseCDFRV(instance(), stream)
    }


}