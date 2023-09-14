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
import ksl.utilities.distributions.Lognormal
import ksl.utilities.orderStatistics
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.LognormalRVParameters
import ksl.utilities.random.rvariable.parameters.NormalRVParameters
import ksl.utilities.statistics
import kotlin.math.exp
import kotlin.math.ln

object FitAlgorithms {

    val discreteDistributions = setOf<RVType>(
        RVType.Bernoulli, RVType.Geometric, RVType.NegativeBinomial, RVType.Poisson
    )

    val continuousDistributions = setOf<RVType>(
        RVType.Exponential, RVType.Gamma, RVType.Lognormal, RVType.Normal, RVType.Triangular,
        RVType.Uniform, RVType.Weibull, RVType.Beta, RVType.PearsonType5, RVType.PearsonType6
    )

    fun estimateShiftParameter(data: DoubleArray): Double {
        require(data.size >= 2) { "There must be at least two observations" }
        // page 367 of Law(2007)
        val sorted = data.orderStatistics()
        val min = sorted.first()
        val max = sorted.last()
        var xk = sorted[1]
        for (k in 1 until sorted.size - 1) {
            if (sorted[k] > min) {
                xk = sorted[k]
                break
            }
        }
        val top = min * max - xk * xk
        val bottom = min + max - 2.0 * xk
        return top / bottom
    }

    fun estimateNormal(data: DoubleArray): EstimatedParameters {
        require(data.size >= 2) { "There must be at least two observations" }
        val s = data.statistics()
        val parameters = NormalRVParameters()
        parameters.changeDoubleParameter("average", s.average)
        parameters.changeDoubleParameter("variance", s.variance)
        return EstimatedParameters(parameters, success = true)
    }

    fun estimateLognormal(data: DoubleArray): EstimatedParameters {
        require(data.size >= 2) { "There must be at least two observations" }
        if (data.countLessEqualTo(0.0) == 0) {
            return EstimatedParameters(
                null,
                message = "Cannot fit lognormal distribution when observations are <= 0.0",
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
        val mean = exp(mu + sigma2 / 2.0)
        val variance = exp(2.0 * mu + sigma2) * (exp(sigma2) - 1.0)
        val parameters = LognormalRVParameters()
        parameters.changeDoubleParameter("average", mean)
        parameters.changeDoubleParameter("variance", variance)
        return EstimatedParameters(parameters, success = true)
    }
}