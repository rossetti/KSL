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

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.TriangularRV
import kotlin.math.pow
import kotlin.math.sqrt

/** Represents the Triangular distribution with
 * parameters - minimum value, maximum value and most likely value
 * @param theMin The minimum value of the distribution
 * @param theMode The mode of the distribution
 * @param theMax The maximum value of the distribution
 * @param name an optional label/name
 */
class Triangular(
    theMin: Double = 0.0,
    theMode: Double = 0.0,
    theMax: Double = 1.0,
    name: String? = null
) : Distribution<Triangular>(name), ContinuousDistributionIfc, GetRVariableIfc {
    init {
        require(theMin <= theMode) { "min must be <= mode" }
        require(theMin < theMax) { "min must be < max" }
        require(theMode <= theMax) { "mode must be <= max" }
    }

    /**
     * myMin the minimum value of the distribution
     */
    var minimum = theMin
        private set

    /**
     * myMax the maximum value of the distribution
     */
    var maximum = theMax
        private set

    /**
     * myMax the maximum value of the distribution
     */
    var mode = theMode
        private set

    /**
     * myRange = myMax - myMin
     */
    val range
        get() = maximum - minimum

    /** Constructs a Triangular distribution with
     * min = parameters[0], mode = parameters[1], max = parameters[2]
     * @param parameters The array of parameters
     */
    constructor(parameters: DoubleArray) : this(parameters[0], parameters[1], parameters[2], null)

    override fun instance(): Triangular {
        return Triangular(minimum, mode, maximum)
    }

    override fun domain(): Interval {
        return Interval(minimum, maximum)
    }

    /** Sets the minimum, most likely and maximum value of the triangular distribution to the private data members myMin, myMode and myMax resp
     * throws IllegalArgumentException when the min &gt;mode, min &gt;= max, mode &gt; max
     *
     * @param min The minimum value of the distribution
     * @param mode The mode of the distribution
     * @param max The maximum value of the distribution
     */
    fun setParameters(min: Double, mode: Double, max: Double) {
        require(min <= mode) { "min must be <= mode" }
        require(min < max) { "min must be < max" }
        require(mode <= max) { "mode must be <= max" }
        this.mode = mode
        minimum = min
        maximum = max
    }

    override fun mean(): Double {
        return (minimum + maximum + mode) / 3.0
    }

    val moment3: Double
        get() {
            return 1.0 / 10.0 * (minimum * minimum * minimum +
                    mode * mode * mode +
                    maximum * maximum * maximum +
                    minimum * minimum * mode +
                    minimum * minimum * maximum +
                    mode * mode * minimum +
                    mode * mode * maximum +
                    maximum * maximum * minimum +
                    maximum * maximum * mode +
                    minimum * mode * maximum)
        }

    val moment4: Double
        get() {
            return 1.0 / 135.0 * (
                    (minimum * minimum + mode * mode + maximum * maximum - minimum * mode - minimum * maximum - mode * maximum) *
                            (minimum * minimum + mode * mode + maximum * maximum - minimum * mode - minimum * maximum - mode * maximum)) +
                    4.0 * (1.0 / 270.0 * (minimum + mode - 2.0 * maximum) *
                    (minimum + maximum - 2.0 * mode) *
                    (mode + maximum - 2.0 * minimum) *
                    ((minimum + mode + maximum) / 3.0)) +
                    1.0 / 3.0 * (minimum * minimum + mode * mode + maximum * maximum - minimum * mode - minimum * maximum - mode * maximum) *
                    ((minimum + mode + maximum) / 3.0) *
                    ((minimum + mode + maximum) / 3.0) +
                    (minimum + mode + maximum) / 3.0 *
                    ((minimum + mode + maximum) / 3.0) *
                    ((minimum + mode + maximum) / 3.0) *
                    ((minimum + mode + maximum) / 3.0)
        }

    override fun variance(): Double {
        return (minimum * minimum + maximum * maximum + mode * mode - maximum * minimum - minimum * mode - maximum * mode) / 18.0
    }

    override fun pdf(x: Double): Double {

        // Right triangular, mode = max
        if (mode == maximum) {
            return if (x in minimum..maximum) {
                2.0 * (x - minimum) / (range * range)
            } else {
                0.0
            }
        }

        // Left triangular, min = mode
        if (minimum == mode) {
            return if (x in minimum..maximum) {
                2.0 * (maximum - x) / (range * range)
            } else {
                0.0
            }
        }

        // regular triangular min < mode < max
        return if (x in minimum..mode) {
            2.0 * (x - minimum) / (range * (mode - minimum))
        } else if (mode < x && x <= maximum) {
            2.0 * (maximum - x) / (range * (maximum - mode))
        } else {
            0.0
        }
    }

    override fun cdf(x: Double): Double {

        // Right triangular, mode = max
        if (mode == maximum) {
            return if (x < minimum) {
                0.0
            } else if (x in minimum..maximum) {
                val y = (x - minimum) / range
                y * y
            } else {
                1.0
            }
        }

        // Left triangular, min = mode
        if (minimum == mode) {
            return if (x < minimum) {
                0.0
            } else if (x in minimum..maximum) {
                val y = (maximum - x) / range
                1.0 - y * y
            } else {
                1.0
            }
        }

        // regular triangular min < mode < max
        return if (x < minimum) {
            0.0
        } else if (x in minimum..mode) {
            (x - minimum) * (x - minimum) / (range * (mode - minimum))
        } else if (mode < x && x <= maximum) {
            1.0 - (maximum - x) * (maximum - x) / (range * (maximum - mode))
        } else {
            1.0
        }
    }

    override fun invCDF(p: Double): Double {
        require(!(p < 0.0 || p > 1.0)) { "Probability must be [0,1]" }

        // if X ~ triang(0,(mode-min)/(max-min),1) then Y = min + (max-min)*X ~ triang(min, mode, max)
        // get parameters for triang(0,(mode-min)/(max-min),1)
        val c = (mode - minimum) / range

        // get the invCDF for a triang(0,c,1)
        val x: Double = if (c == 0.0) { // left triangular, mode equals min
            1.0 - sqrt(1 - p)
        } else if (c == 1.0) { //right triangular, mode equals max
            sqrt(p)
        } else {
            if (p < c) {
                sqrt(c * p)
            } else {
                1.0 - sqrt((1.0 - c) * (1.0 - p))
            }
        }
        // scale it back to original scale
        return minimum + range * x
    }

    /** Gets the kurtosis of the distribution
     * mu4/mu2^2, www.mathworld.wolfram.com/Kurtosis.html
     * www.mathworld.wolfram.com/TriangularDistribution.html
     * @return the kurtosis
     */
    val kurtosis: Double
        get() = 2.4

    /** Gets the skewness of the distribution
     * mu3/mu2^(3/2), www.mathworld.wolfram.com/Skewness.html
     * www.mathworld.wolfram.com/TriangularDistribution.html
     * @return the skewness
     */
    val skewness: Double
        get() {
            val mu3 =
                -(minimum + maximum - 2.0 * mode) * (minimum + mode - 2.0 * maximum) * (maximum + mode - 2.0 * minimum) / 270.0
            val mu2 = variance()
            return mu3 / mu2.pow(3.0 / 2.0)
        }

    /** Sets the parameters for the distribution
     * params[0] min The minimum value of the distribution
     * params[1] mode The mode of the distribution
     * params[2] max The maximum value of the distribution
     *
     * @param params an array of doubles representing the parameters for
     * the distribution
     */
    override fun parameters(params: DoubleArray) {
        setParameters(params[0], params[1], params[2])
    }

    /** Gets the parameters for the distribution
     * params[0] min The minimum value of the distribution
     * params[1] mode The mode of the distribution
     * params[2] max The maximum value of the distribution
     * @return Returns an array of the parameters for the distribution
     */
    override fun parameters(): DoubleArray {
        return doubleArrayOf(minimum, mode, maximum)
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return TriangularRV(minimum, mode, maximum, stream)
    }

    companion object {
        /** Returns true if the parameters are valid for the distribution
         *
         * min = param[0]
         * mode = param[1]
         * max = param[2]
         * @param param the parameter array
         * @return true if the parameters are valid
         */
        fun checkParameters(param: DoubleArray): Boolean {
            if (param.size != 3) {
                return false
            }
            val min = param[0]
            val mode = param[1]
            val max = param[2]
            if (min > mode) {
                return false
            }
            if (min >= max) {
                return false
            }
            return mode <= max
        }
    }

}