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

import ksl.utilities.countLessThan
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.ExponentialRVParameters
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

/**
 *  Uses the sample average of the observations, which is the MLE
 *  estimator. The data must not contain negative values.
 */
class ExponentialMLEParameterEstimator(
    data: DoubleArray,
    statistics: StatisticIfc = Statistic(data)
) : ParameterEstimator(data, statistics){

    override fun estimate(): EstimationResults {
        if (data.isEmpty()){
            return EstimationResults(
                statistics = statistics,
                message = "There must be at least one observations",
                success = false
            )
        }
        if (data.countLessThan(0.0) > 0) {
            return EstimationResults(
                statistics = statistics,
                message = "Cannot fit exponential distribution when some observations are less than 0.0",
                success = false
            )
        }
        val parameters = ExponentialRVParameters()
        parameters.changeDoubleParameter("mean", statistics.average)
        return EstimationResults(
            statistics = statistics,
            parameters = parameters,
            message = "The exponential parameters were estimated successfully using a MLE technique",
            success = true
        )
    }
}