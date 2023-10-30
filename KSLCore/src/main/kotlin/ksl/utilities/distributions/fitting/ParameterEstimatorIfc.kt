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

import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

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
    fun estimateParameters(data: DoubleArray, statistics: StatisticIfc = Statistic(data)): EstimationResult

}


