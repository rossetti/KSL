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
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.UniformRV

/** Defines a uniform distribution over the given range.
 * @param theMinimum limit of the distribution
 * @param theMaximum limit of the distribution
 * @param name an optional name/label
 */
class Uniform (theMinimum: Double = 0.0, theMaximum: Double = 1.0, name: String? = null) :
    Distribution<Uniform>(name), ContinuousDistributionIfc, InverseCDFIfc, GetRVariableIfc {

    init {
        require(theMinimum < theMaximum) { "Lower limit must be < upper limit. lower limit = $theMinimum upper limit = $theMaximum" }
    }

    var minimum = theMinimum
        private set

    var maximum = theMaximum
        private set

    val range : Double
        get() = maximum - minimum

    /** Constructs a uniform distribution with
     * lower limit = parameters[0], upper limit = parameters[1]
     * @param parameters The array of parameters
     */
    constructor(parameters: DoubleArray) : this(parameters[0], parameters[1], null)

    override fun instance(): Uniform {
        return Uniform(minimum, maximum)
    }

    override fun domain(): Interval {
        return Interval(minimum, maximum)
    }

    /** Sets the range
     * @param min The lower limit for the distribution, must be less than max
     * @param max The upper limit for the distribution, must be greater than min
     */
    fun setRange(min: Double, max: Double) {
        require(min < max) { "Lower limit must be < upper limit. lower limit = $min upper limit = $max" }
        minimum = min
        maximum = max
    }

    override fun cdf(x: Double): Double {
        return if (x < minimum) {
            0.0
        } else if (x in minimum..maximum) {
            (x - minimum) / range
        } else {
            //if (x > myMax)
            1.0
        }
    }

    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Probability must be [0,1]" }
        return minimum + range * p
    }

    override fun pdf(x: Double): Double {
        return if (x < minimum || x > maximum) {
            0.0
        } else 1.0 / range
    }

    override fun mean(): Double {
        return (minimum + maximum) / 2.0
    }

    /**
     *
     * @return the 3rd moment
     */
    val moment3: Double
        get() = 1.0 / 4.0 * ((minimum + maximum) * (minimum * minimum + maximum * maximum))

    /**
     *
     * @return the 4th moment
     */
    val moment4: Double
        get() {
            val min2 = minimum * minimum
            val max2 = maximum * maximum
            return 1.0 / 5.0 * (min2 * min2 + min2 * minimum * maximum + min2 * max2 + minimum * maximum * max2 + max2 * max2)
        }

    override fun variance(): Double {
        return range * range / 12.0
    }

    /** Gets the kurtosis of the distribution
     * www.mathworld.wolfram.com/UniformDistribution.html
     * @return the kurtosis
     */
    val kurtosis: Double
        get() = -6.0 / 5.0

    /** Gets the skewness of the distribution
     * www.mathworld.wolfram.com/UniformDistribution.html
     * @return the skewness
     */
    val skewness: Double
        get() = 0.0

    /** Sets the parameters for the distribution where parameters[0] is the
     * minimum and parameters[1] is the maximum of the range.
     * the minimum must be &lt; maximum
     *
     * @param params an array of doubles representing the parameters for
     * the distribution
     */
    override fun parameters(params: DoubleArray) {
        setRange(params[0], params[1])
    }

    /** Gets the parameters for the distribution
     *
     * @return Returns an array of the parameters for the distribution
     */
    override fun parameters(): DoubleArray {
        return doubleArrayOf(minimum, maximum)
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return UniformRV(minimum, maximum, stream)
    }

    override fun toString(): String {
        return "Uniform(minimum=$minimum, maximum=$maximum)"
    }

}