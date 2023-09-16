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

package ksl.utilities.distributions.fitting

import ksl.utilities.KSLArrays
import ksl.utilities.isAllEqual
import ksl.utilities.math.KSLMath
import ksl.utilities.orderStatistics
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 *  A data class to hold information from a parameter fitting algorithm.
 *  In general the algorithm may fail due to data or numerical computation issues.
 *  The [parameters] may be null because of such issues; however,
 *  there may be cases where the parameters are produced but the algorithm
 *  still considers the process a failure as indicated in the [success] field.
 *  The string [message] allows a general diagnostic explanation of success,
 *  failure, or other information about the estimation process. In the case
 *  of uni-variate distributions, there may be a [shift] parameter estimated
 *  in order to handle data that has a lower range of domain that does not
 *  match well with the distribution.
 */
data class EstimatedParameters(
    val parameters: RVParameters? = null,
    var shiftedData: ShiftedData? = null,
    var message: String? = null,
    var success: Boolean
)

data class ShiftedData (
    val shift: Double,
    val data: DoubleArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ShiftedData

        if (shift != other.shift) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shift.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 *  This interface defines a general function that uses
 *  1-dimensional data, and estimates the parameters of
 *  some uni-variate distribution via some estimation algorithm.
 *
 *  The basic contract is that the returned EstimatedParameters
 *  is consistent with the required parameter estimation.
 *

 */
fun interface ParameterEstimatorIfc {

    /**
     *  Estimates the parameters associated with some distribution
     *  based on the supplied [data]. The returned [EstimatedParameters]
     *  needs to be consistent with the intent of the desired distribution.
     *  Note the meaning of the fields associated with [EstimatedParameters]
     */
    fun estimate(data: DoubleArray): EstimatedParameters

    /**
     *  The companion object associated with the ParameterEstimatorIfc interface
     *  has some functions that may be of use during the estimation process.
     *  It also has two properties that enumerate which distributions can be
     *  fit by the base classes within the fitting package.
     */
    companion object {

        /**
         *  How close we consider a double is to 0.0 to call it 0.0
         *  Default is 0.001
         */
        var defaultZeroTolerance = 0.001
            set(value) {
                require(value > 0.0){"The default zero precision must be > 0.0"}
                field = value
            }

        val discreteDistributions = setOf<RVType>(
            RVType.Bernoulli, RVType.Geometric, RVType.NegativeBinomial, RVType.Poisson
        )

        val continuousDistributions = setOf<RVType>(
            RVType.Exponential, RVType.Gamma, RVType.Lognormal, RVType.Normal, RVType.Triangular,
            RVType.Uniform, RVType.Weibull, RVType.Beta, RVType.PearsonType5, RVType.PearsonType6
        )

        /**
         *  Uses the method described on page 360 of Law (2007)
         *  Simulation Modeling and Analysis, ISBN 0073294411, 9780073294414
         *  There must be at least two observations within the data and there
         *  must be at least two different values.  The value [tolerance] is
         *  used to consider whether the computed shift value is close enough
         *  to zero to consider the shift 0.0.  The default is 0.001
         *
         */
        fun estimateShiftParameter(data: DoubleArray, tolerance: Double = defaultZeroTolerance): Double {
            require(data.size >= 2) { "There must be at least two observations" }
            require(!data.isAllEqual()) {"The observations were all equal."}
            val sorted = data.orderStatistics()
            val min = sorted.first()
            val max = sorted.last()
            var xk = sorted[1]
            for (k in 1 until sorted.size - 1) {
                if (sorted[k] > min) {
                    xk = sorted[k]
                    break
                }
            }
            val top = min * max - xk * xk
            if (top == 0.0){
                return 0.0
            }
            val bottom = min + max - 2.0 * xk
            val shift = top/bottom
            return if (KSLMath.within(shift, 0.0, tolerance)) 0.0 else shift
        }

        /**
         *  Estimates the shift parameter and then shifts the
         *  data by the estimated quantity. Also returns the computed
         *  shift. Use destructuring if you want:
         *
         *  val (shift, shiftedData) = shiftData(data)
         */
        fun shiftData(data: DoubleArray, tolerance: Double = defaultZeroTolerance) :  ShiftedData {
            val shift = estimateShiftParameter(data,  tolerance)
            return ShiftedData(shift, KSLArrays.subtractConstant(data, shift))
        }
    }
}

