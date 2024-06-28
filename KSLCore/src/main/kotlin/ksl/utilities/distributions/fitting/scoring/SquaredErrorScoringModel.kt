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

package ksl.utilities.distributions.fitting.scoring

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.moda.Score
import ksl.utilities.statistic.Histogram

/**
 *  This scoring model represents the sum of squared error between
 *  the predicted probabilities (based on the assumed distribution)
 *  and the observed probabilities. The break points for the histogram
 *  are specified by PDFModeler.equalizedCDFBreakPoints()
 */
class SquaredErrorScoringModel : PDFScoringModel("SSE") {

    override val allowLowerLimitAdjustment: Boolean = false
    override val allowUpperLimitAdjustment: Boolean = true

    override fun score(data: DoubleArray, cdf: ContinuousDistributionIfc): Score {
        if (data.isEmpty()){
            return Score(this, Double.MAX_VALUE, true)
        }
        var bp = PDFModeler.equalizedCDFBreakPoints(data.size, cdf)
//        bp.sort()
//        // make sure that they are unique
//        bp = bp.toSet().toDoubleArray()
        val domain = cdf.domain()
        bp = Histogram.addLowerLimit(domain.lowerLimit, bp)
        bp = Histogram.addUpperLimit(domain.upperLimit, bp)
        val h = Histogram(bp)
        h.collect(data)
        val predicted = PDFModeler.binProbabilities(h.bins, cdf)
        val observed = h.binFractions
        val n = predicted.size.coerceAtMost(observed.size)
        if (n == 0){
            return Score(this, Double.MAX_VALUE, true)
        }
        var sum = 0.0
        for (i in 0.until(n)) {
            sum = sum + (predicted[i] - observed[i]) * (predicted[i] - observed[i])
        }
        return Score(this, sum,true)
    }

    override fun newInstance(): SquaredErrorScoringModel {
        return SquaredErrorScoringModel()
    }
}