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

import ksl.utilities.Interval
import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.moda.MetricIfc
import ksl.utilities.moda.Score
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.Statistic

class ChiSquaredScoringModel : PDFScoringModel("Chi-Squared Test Statistic") {

    override fun score(data: DoubleArray, cdf: ContinuousDistributionIfc): Score {
        var bp = PDFModeler.equalizedCDFBreakPoints(data.size, cdf)
        val domain = cdf.domain()
        bp = Histogram.addLowerLimit(domain.lowerLimit, bp)
        bp = Histogram.addUpperLimit(domain.upperLimit, bp)
        val h = Histogram(bp)
        h.collect(data)
        val ec = h.expectedCounts(cdf)
        val chiSq = Statistic.chiSqTestStatistic(h.binCounts, ec)
        return Score(this, chiSq,true)
    }
}