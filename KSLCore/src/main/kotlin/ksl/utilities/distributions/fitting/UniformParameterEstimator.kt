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
import ksl.utilities.random.rvariable.parameters.UniformRVParameters
import ksl.utilities.statistics

/**
 *   Uses the minimum unbiased estimators based on the order statistics.
 *   See: 1. Castillo E, Hadi AS. A method for estimating parameters and quantiles of
 *   distributions of continuous random variables. Computational Statistics & Data Analysis.
 *   1995 Oct;20(4):421–39.
 *   There must be at least two observations and the observations cannot all be the same.
 *
 */
object UniformParameterEstimator : ParameterEstimatorIfc{
    override fun estimate(data: DoubleArray): EstimatedParameters {
        require(data.size >= 2) { "There must be at least two observations" }
        require(!data.isAllEqual()) {"The observations were all equal."}
        val s = data.statistics()
        val x1 = s.min
        val x2 = s.max
        val n = data.size
        val r = x2 - x1
        val a = x1 - r/(n - 1)
        val b = x2 + r/(n - 1)
        val parameters = UniformRVParameters()
        parameters.changeDoubleParameter("min", a)
        parameters.changeDoubleParameter("max", b)
        return EstimatedParameters(parameters, success = true)
    }
}