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
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Constructs a degenerate distribution with all probability at the provided
 * point. Once made the value of the constant cannot be changed.
 *
 * Construct a constant using the supplied value
 *
 * @param value the value for the constant
 * @param name a string name/label
*/
class Constant (var value: Double = 0.0, name: String? = null) : Distribution<Constant>(name),
    DiscreteDistributionIfc, GetRVariableIfc {

    /**
     *
     * @param parameters the parameter[0] is the value
     */
    constructor(parameters: DoubleArray, name: String? = null) : this(parameters[0], name)

    override fun instance(): Constant {
        return Constant(value)
    }

    override fun parameters(params: DoubleArray) {}

    override fun parameters(): DoubleArray {
        return doubleArrayOf(value)
    }

    override fun pmf(x: Double): Double {
        return if (x == value) {
            1.0
        } else {
            0.0
        }
    }

    override fun cdf(x: Double): Double {
        return if (x < value) {
            0.0
        } else {
            1.0
        }
    }

    override fun mean(): Double {
        return value
    }

    override fun variance(): Double {
        return 0.0
    }

    override fun invCDF(p: Double): Double {
        return value
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return ConstantRV(value)
    }

    companion object {
        /**
         * A constant to represent zero for sharing
         */
        val ZERO = Constant(0.0)

        /**
         * A constant to represent one for sharing
         */
        val ONE = Constant(1.0)

        /**
         * A constant to represent two for sharing
         */
        val TWO = Constant(2.0)

        /**
         * A constant to represent positive infinity for sharing
         */
        val POSITIVE_INFINITY = Constant(Double.POSITIVE_INFINITY)
    }

}