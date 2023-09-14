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

import ksl.utilities.orderStatistics
import ksl.utilities.random.rvariable.RVType

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
}