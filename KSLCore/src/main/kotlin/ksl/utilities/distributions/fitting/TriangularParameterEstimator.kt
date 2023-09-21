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
import ksl.utilities.random.rvariable.parameters.TriangularRVParameters
import ksl.utilities.statistic.Statistic

/**
 *  The approach is based on estimating the min and max via
 *  the recommended approach for the uniform distribution. Then,
 *  the mode is estimated by solving for it in terms of the sample
 *  average.
 */
class TriangularParameterEstimator: ParameterEstimatorIfc {

    override fun estimate(data: DoubleArray, statistics: Statistic): EstimationResults {
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
        val x1 = statistics.min
        val xn = statistics.max
        val n = data.size.toDouble()
        val range = xn - x1
        // estimate a and b like the uniform distribution
        val a = x1 - (range/(n - 1))
        val b = xn + (range/(n - 1))
        // compute the mode by matching moments with the mean.
        // the mean = (a + c + b)/3.0
        var c = 3.0*statistics.average - a - b
        // ensure that the mode estimate is inside the range
        if(c > b){
            c = b
        }
        if(c < a){
            c = a
        }
        val parameters = TriangularRVParameters()
        parameters.changeDoubleParameter("min", a)
        parameters.changeDoubleParameter("max", b)
        parameters.changeDoubleParameter("mode", c)
        return EstimationResults(
            statistics = statistics,
            parameters = parameters,
            message = "The triangular parameters were estimated successfully.",
            success = true
        )
    }
}