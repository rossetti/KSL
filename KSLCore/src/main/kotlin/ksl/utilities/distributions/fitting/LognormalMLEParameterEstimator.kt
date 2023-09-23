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

import ksl.utilities.countLessEqualTo
import ksl.utilities.random.rvariable.parameters.LognormalRVParameters
import ksl.utilities.statistic.StatisticIfc
import ksl.utilities.statistics
import kotlin.math.exp
import kotlin.math.ln

/**
 *   Takes the natural logarithm of the data and then estimates
 *   the mean and variance of the associated normal distribution.
 *   Then the parameters are converted to the mean and variance of
 *   the lognormal distribution.  The supplied data must be strictly
 *   positive and their must be at least 2 observations.
 */
object LognormalMLEParameterEstimator : ParameterEstimatorIfc{

    override val checkRange: Boolean = true

    override fun estimate(data: DoubleArray, statistics: StatisticIfc): EstimationResults {
        if (data.size < 2){
            return EstimationResults(
                statistics = statistics,
                message = "There must be at least two observations",
                success = false
            )
        }
        if (data.countLessEqualTo(0.0) > 0) {
            return EstimationResults(
                statistics = statistics,
                message = "Cannot fit lognormal distribution when some observations are <= 0.0",
                success = false
            )
        }
        // transform to normal on ln scale
        val lnData = DoubleArray(data.size) { ln(data[it]) }
        // estimate parameters of normal distribution
        val s = lnData.statistics()
        val mu = s.average
        val sigma2 = s.variance
        // compute the parameters of the log-normal distribution
        val mean = exp(mu + (sigma2 / 2.0))
        val variance = exp(2.0 * mu + sigma2) * (exp(sigma2) - 1.0)
        val parameters = LognormalRVParameters()
        parameters.changeDoubleParameter("mean", mean)
        parameters.changeDoubleParameter("variance", variance)
        return EstimationResults(
            statistics = statistics,
            parameters = parameters,
            message = "The lognormal parameters were estimated successfully using a MLE technique",
            success = true
        )
    }
}