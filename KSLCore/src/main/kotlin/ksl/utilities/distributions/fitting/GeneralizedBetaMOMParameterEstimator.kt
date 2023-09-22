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

import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.GeneralizedBetaRVParameters
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

class GeneralizedBetaMOMParameterEstimator(
    data: DoubleArray,
    statistics: StatisticIfc = Statistic(data)
) : ParameterEstimator(data, statistics) {

    override val checkForShift: Boolean = false

    override fun estimate(): EstimationResults {
        if (data.size < 2) {
            return EstimationResults(
                statistics = statistics,
                message = "There must be at least two observations",
                success = false
            )
        }
        if (statistics.variance == 0.0) {
            return EstimationResults(
                statistics = statistics,
                message = "The sample variance of the data was = 0.0",
                success = false
            )
        }
        if ((statistics.max - statistics.min) == 0.0) {
            return EstimationResults(
                statistics = statistics,
                message = "The sample range of the data was = 0.0",
                success = false
            )
        }
        // estimate the range of the data
        val range = PDFModeler.rangeEstimate(statistics.min, statistics.max, data.size)
        val a = range.lowerLimit
        val c = range.upperLimit
        val mu = statistics.average
        val s2 = statistics.variance
        val denominator = s2 * (c - a)
        val num1 = (a * c - a * mu - c * mu + mu * mu + s2)
        val alpha = ((a - mu) * num1) / denominator
        val beta = ((mu - c) * num1) / denominator
        if (alpha <= 0.0) {
            return EstimationResults(
                statistics = statistics,
                message = "The estimated alpha (first shape) value was <= 0.0",
                success = false
            )
        }
        if (beta <= 0.0) {
            return EstimationResults(
                statistics = statistics,
                message = "The estimated beta (second shape) value was <= 0.0",
                success = false
            )
        }
        val parameters = GeneralizedBetaRVParameters()
        parameters.changeDoubleParameter("alpha1", alpha)
        parameters.changeDoubleParameter("alpha2", beta)
        parameters.changeDoubleParameter("min", a)
        parameters.changeDoubleParameter("max", c)
        return EstimationResults(
            statistics = statistics,
            parameters = parameters,
            message = "The generalized beta parameters were estimated successfully using a MOM technique",
            success = true
        )
    }

}