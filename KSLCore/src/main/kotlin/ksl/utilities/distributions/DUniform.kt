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
import ksl.utilities.random.rvariable.DUniformRV
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.floor


/** Models discrete random variables that are uniformly distributed
 * over a contiguous range of integers.
 * the lower limit must be &lt; upper limit
 * @param min The lower limit of the range
 * @param max The upper limit of the range
 * @param name an optional name/label
 */
class DUniform(min: Int = 0, max: Int = 1, name: String? = null) :
    Distribution(name), DiscreteDistributionIfc, RVParametersTypeIfc by RVType.DUniform {

    /** Constructs a discrete uniform where parameter[0] is the
     * lower limit and parameter[1] is the upper limit of the range.
     * the lower limit must be &lt; upper limit
     * @param parameters An array containing the lower limit and upper limit
     */
    constructor(parameters: DoubleArray) : this(parameters[0].toInt(), parameters[1].toInt(), null)

    /**
     * @param range an integer range
     */
    constructor(range: IntRange) : this(range.first, range.last, null)

    init {
        require(min < max) { "Lower limit must be < upper limit." }
    }

    /** The distribution's lower limit
     * @return The lower limit
     */
    var minimum = min
        private set

    /** The distribution's upper limit
     * @return The upper limit
     */
    var maximum = max
        private set

    /** The discrete maximum - minimum + 1
     *
     * @return the returned range
     */
    val range = maximum - minimum + 1

    override fun instance(): DUniform {
        return DUniform(minimum, maximum)
    }

    /** Sets the range for the distribution
     * the lower limit must be &lt; upper limit
     * @param minimum The lower limit for the range
     * @param maximum The upper limit for the range
     */
    fun setRange(minimum: Int, maximum: Int) {
        require(minimum < maximum) { "Lower limit must be < upper limit." }
        this.minimum = minimum
        this.maximum = maximum
    }

    /**
     *  @param range sets the range using an IntRange
     */
    fun setRange (range: IntRange) {
        setRange(range.first, range.last)
    }

    override fun cdf(x: Double): Double {
        return if (x < minimum) {
            0.0
        } else if (x >= minimum && x <= maximum) {
            (floor(x) - minimum + 1) / range
        } else  //if (x > myMaximum)
        {
            1.0
        }
    }

    /** Provides the inverse cumulative distribution function for the distribution
     * @param p The probability to be evaluated for the inverse, prob must be [0,1] or
     * an IllegalArgumentException is thrown
     */
    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Probability must be [0,1]" }
        return minimum + floor(range * p)
    }

    override fun mean(): Double {
        return (minimum + maximum) / 2.0
    }

    override fun variance(): Double {
        return (range * range - 1) / 12.0
    }

    /** Returns the probability associated with x
     *
     * @param i the value to evaluate
     * @return the associated probability
     */
    override fun pmf(i: Int): Double {
        return if (i < minimum || i > maximum) {
            0.0
        } else 1.0 / range
    }

    /** Sets the parameters for the distribution where parameters[0] is the
     * lower limit and parameters[1] is the upper limit of the range.
     * the lower limit must be &lt; upper limit
     *
     * @param params an array of doubles representing the parameters for
     * the distribution
     */
    override fun parameters(params: DoubleArray) {
        setRange(params[0].toInt(), params[1].toInt())
    }

    /** Gets the parameters for the distribution where parameters[0] is the
     * lower limit and parameters[1] is the upper limit of the range.
     *
     * @return Returns an array of the parameters for the distribution
     */
    override fun parameters(): DoubleArray {
        val param = DoubleArray(2)
        param[0] = minimum.toDouble()
        param[1] = maximum.toDouble()
        return param
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return DUniformRV(minimum, maximum, stream)
    }

    override fun toString(): String {
        return "DUniform(minimum=$minimum, maximum=$maximum)"
    }
}