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
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.moda.Metric
import ksl.utilities.moda.MetricIfc
import ksl.utilities.moda.Score
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 *  Computes a score to indicate the quality of fit for the proposed
 *  continuous distribution for the supplied data
 */
abstract class PDFScoringModel(name: String) : Metric(name){
    //TODO a scoring model should probably not be a sub-class of metric
    // it should have an instance of one, but not be one
    // if necessary consider using delegation, but that may not even be necessary.
    // it appears that it needs to be a metric because the metric is needed
    // to create the score and because the MODA model needs metrics.

    abstract fun score(data: DoubleArray, cdf: ContinuousDistributionIfc) : Score

    abstract fun newInstance(): PDFScoringModel

    fun score(data: DoubleArray, parameters: RVParameters) : Score {
        val cdf = PDFModeler.createDistribution(parameters)
        return if (cdf == null){
            badScore()
        } else {
            score(data, cdf)
        }
    }

    fun score(result: EstimationResult) : Score {
        val parameters = result.parameters
        return if (parameters == null){
            badScore()
        } else {
            // need to score the model based on data it was fit on
            val data = if (result.shiftedData != null){
                result.shiftedData!!.shiftedData
            } else {
                result.originalData
            }
            score(data, parameters)
        }
    }

    /**
     *  Returns an invalid score that has the worst possible value
     *  according to the direction of the meaning of better.
     */
    fun badScore() : Score {
        return if (direction == MetricIfc.Direction.BiggerIsBetter){
            Score(this, domain.lowerLimit, false)
        } else {
            Score(this, domain.upperLimit, false)
        }
    }
}