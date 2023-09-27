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
import ksl.utilities.moda.Score
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 *  Computes a score to indicate the quality of fit for the proposed
 *  continuous distribution for the supplied data
 */
interface PDFScoringModel {

    val name: String
    val range: Interval
    val direction: Score.Direction

    fun score(data: DoubleArray, cdf: ContinuousDistributionIfc) : Score

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
            val score = score(data, parameters)
            result.scores[name] = score
            score
        }
    }

    fun badScore() : Score {
        return if (direction == Score.Direction.BiggerIsBetter){
            Score(name, range.lowerLimit, range, direction,false)
        } else {
            Score(name, range.upperLimit, range, direction,false)
        }
    }
}