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

import ksl.utilities.isAllEqual
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.UniformRVParameters
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

/**
 *   Uses the minimum unbiased estimators based on the order statistics.
 *   See: 1. Castillo E, Hadi AS. A method for estimating parameters and quantiles of
 *   distributions of continuous random variables. Computational Statistics & Data Analysis.
 *   1995 Oct;20(4):421–39.
 *   There must be at least two observations and the observations cannot all be the same.
 *
 */
class UniformParameterEstimator(
    data: DoubleArray,
    statistics: StatisticIfc = Statistic(data)
) : ParameterEstimator(data, statistics){

    override val checkForShift: Boolean = false

    override fun estimate(): EstimationResults {
        if (data.size < 2){
            return EstimationResults(
                statistics = statistics,
                message = "There must be at least two observations",
                success = false
            )
        }
        if (data.isAllEqual()){
            return EstimationResults(
                statistics = statistics,
                message = "The observations were all equal.",
                success = false
            )
        }
        val interval = PDFModeler.rangeEstimate(statistics.min, statistics.max, data.size)
        val parameters = UniformRVParameters()
        parameters.changeDoubleParameter("min", interval.lowerLimit)
        parameters.changeDoubleParameter("max", interval.upperLimit)
        return EstimationResults(
            statistics = statistics,
            parameters = parameters,
            message = "The uniform parameters were estimated successfully.",
            success = true
        )
    }
}