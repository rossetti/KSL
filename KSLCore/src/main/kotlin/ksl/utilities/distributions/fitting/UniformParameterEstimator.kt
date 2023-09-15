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

import ksl.utilities.random.rvariable.parameters.UniformRVParameters
import ksl.utilities.statistics

object UniformParameterEstimator : ParameterEstimatorIfc{
    override fun estimate(data: DoubleArray): EstimatedParameters {
        require(data.size >= 2) { "There must be at least two observations" }
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