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