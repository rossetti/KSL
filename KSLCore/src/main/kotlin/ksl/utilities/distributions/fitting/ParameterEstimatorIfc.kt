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

import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

/**
 *  A data class to hold information from a parameter estimation algorithm.
 *  In general the algorithm may fail due to data or numerical computation issues.
 *  The [parameters] may be null because of such issues; however,
 *  there may be cases where the parameters are produced but the algorithm
 *  still considers the process a failure as indicated in the [success] field.
 *  The string [message] allows a general diagnostic explanation of success,
 *  failure, or other information about the estimation process. In the case
 *  of uni-variate distributions, there may be a shift parameter estimated on [shiftedData]
 *  in order to handle data that has a lower range of domain that does not
 *  match well with the distribution. The algorithm may compute [statistics] on the
 *  supplied data.
 */
data class EstimationResult(
    var statistics: StatisticIfc,
    var shiftedData: ShiftedData? = null,
    val parameters: RVParameters? = null,
    var message: String? = null,
    var success: Boolean
){
    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Estimation Results:")
        if (success){
            sb.appendLine("The estimation was a SUCCESS!")
        } else {
            sb.appendLine("The estimation was a FAILURE!")
        }
        sb.appendLine("Estimation message:")
        sb.appendLine(message)
        sb.appendLine()
        sb.appendLine("Statistics for the data:")
        sb.appendLine(statistics)
        sb.appendLine()
        sb.appendLine("Shift estimation results:")
        sb.appendLine(shiftedData)
        sb.appendLine()
        sb.appendLine("Estimated parameters:")
        sb.appendLine(parameters)
        return sb.toString()
    }
}

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

    override fun toString(): String {
        return "shift = $shift"
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
interface ParameterEstimatorIfc {

    /**
     *  Indicates if the estimator requires that the range of the data be checked for a shift
     *  before the estimation process.
     */
    val checkRange: Boolean

    /**
     *  Estimates the parameters associated with some distribution. The returned [EstimationResult]
     *  needs to be consistent with the intent of the desired distribution.
     *  Note the meaning of the fields associated with [EstimationResult]
     */
    fun estimate(data: DoubleArray, statistics: StatisticIfc = Statistic(data)): EstimationResult
}


