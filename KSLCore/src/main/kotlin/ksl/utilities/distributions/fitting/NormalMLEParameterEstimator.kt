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

import ksl.utilities.random.rvariable.parameters.NormalRVParameters
import ksl.utilities.statistic.Statistic

/**
 *  Uses the sample average and sample variance of the data, which
 *  are the MLE estimators.  There must be at least two observations.
 */
object NormalMLEParameterEstimator : ParameterEstimatorIfc {

    override fun estimate(data: DoubleArray, statistics: Statistic): EstimationResults {
        if (data.size < 2){
            return EstimationResults(
                statistics = statistics,
                message = "There must be at least two observations",
                success = false
            )
        }
        val parameters = NormalRVParameters()
        parameters.changeDoubleParameter("mean", statistics.average)
        parameters.changeDoubleParameter("variance", statistics.variance)
        return EstimationResults(
            statistics = statistics,
            parameters = parameters,
            message = "The normal parameters were estimated successfully using a MLE technique",
            success = true
        )
    }
}