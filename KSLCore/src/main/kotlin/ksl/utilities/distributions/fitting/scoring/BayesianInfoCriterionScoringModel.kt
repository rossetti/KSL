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

import ksl.utilities.Interval
import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.io.KSL
import ksl.utilities.moda.Score
import ksl.utilities.statistic.Statistic

/**
 *   Computes the Bayesian Information Criterion (BIC) based on
 *   the data as the score.  This assumes that the parameters of
 *   the supplied distribution have been estimated from the data
 *   and evaluates the likelihood associated with the current
 *   parameters of the distribution. The parameters of
 *   the distribution are not assumed to have been estimated from
 *   a maximum likelihood approach.
 */
class BayesianInfoCriterionScoringModel(
    domain : Interval = DEFAULT_BIG_RANGE
) : PDFScoringModel("BIC", domain, allowLowerLimitAdjustment = true, allowUpperLimitAdjustment = true ) {

    override fun score(data: DoubleArray, cdf: ContinuousDistributionIfc): Score {
        if (data.isEmpty()){
            return Score(this, domain.upperLimit, true)
        }
        val k = cdf.parameters().size
        val lm = cdf.sumLogLikelihood(data)
        // if there is a problem, just return bad score.
        if (!lm.isFinite()) {
            KSL.logger.warn { "BIC scoring model: Bounded score: ${domain.upperLimit}  made for $cdf : log-likelihood was not finite" }
            return Score(this, domain.upperLimit, true)
        }
        val score = Statistic.bayesianInfoCriterion(data.size, k, lm)
        // if there is a problem, just return bad score.
        if (!score.isFinite() || score.isNaN()) {
            KSL.logger.warn { "BIC scoring model: Bounded score: ${domain.upperLimit} made for $cdf : BIC score was infinite/NaN" }
            return Score(this, domain.upperLimit, true)
        }
        // bound the score within the domain
        if (score <= domain.lowerLimit){
            KSL.logger.warn { "BIC scoring model: Bounded score: ${domain.upperLimit} made for $cdf : BIC score was outside lower domain" }
            return Score(this, domain.lowerLimit, true)
        }
        if (score >= domain.upperLimit){
            KSL.logger.warn { "BIC scoring model: Bounded score: ${domain.upperLimit} made for $cdf : BIC score was outside upper domain" }
            return Score(this, domain.upperLimit, true)
        }
        return Score(this, score, true)
    }

    override fun newInstance(): BayesianInfoCriterionScoringModel {
        return BayesianInfoCriterionScoringModel()
    }
}