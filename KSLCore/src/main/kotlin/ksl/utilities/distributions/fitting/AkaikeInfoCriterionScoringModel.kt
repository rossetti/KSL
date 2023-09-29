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
import ksl.utilities.statistic.Statistic

/**
 *   Computes the Akaike Information Criterion (AIC) based on
 *   the data as the score.  This assumes that the parameters of
 *   the supplied distribution have been estimated from the data
 *   and evaluates the likelihood associated with the current
 *   parameters of the distribution. The parameters of
 *   the distribution are not assumed to have been estimated from
 *   a maximum likelihood approach.
 */
object AkaikeInfoCriterionScoringModel : PDFScoringModel("Akaike Info Criterion") {

    override val range = Interval(0.0, Double.POSITIVE_INFINITY)

    override val direction = MetricIfc.Direction.SmallerIsBetter

    override fun score(data: DoubleArray, cdf: ContinuousDistributionIfc): Score {
        val k = cdf.parameters().size
        val lm = cdf.sumLogLikelihood(data)
        val score = Statistic.akaikeInfoCriterion(data.size, k, lm)
        return Score(this, score, true)
    }
}