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

import ksl.utilities.distributions.ChiSquaredDistribution
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.HistogramIfc
import ksl.utilities.statistic.Statistic

abstract class DistributionGOF(
    protected val data: DoubleArray,
    final override val numEstimatedParameters: Int = 1,
    breakPoints: DoubleArray
) : DistributionGOFIfc {

    init {
        require(numEstimatedParameters >= 0) { "The number of estimated parameters must be >= 0" }
    }

    final override val histogram: HistogramIfc = Histogram.create(data, breakPoints)

    final override val breakPoints = histogram.breakPoints

    final override val binCounts = histogram.binCounts

    final override val chiSquaredTestDOF = histogram.numberBins - 1 - numEstimatedParameters

    final override val chiSquaredTestStatistic
        get() = Statistic.chiSqTestStatistic(binCounts, expectedCounts)

    final override val chiSquaredPValue: Double
        get() {
            val chiDist = ChiSquaredDistribution(chiSquaredTestDOF.toDouble())
            return chiDist.complementaryCDF(chiSquaredTestStatistic)
        }
}