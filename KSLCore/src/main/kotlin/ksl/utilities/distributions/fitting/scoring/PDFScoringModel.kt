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
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.moda.Metric
import ksl.utilities.moda.MetricIfc
import ksl.utilities.moda.Score
import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.statistic.BSEstimatorIfc
import ksl.utilities.statistic.Bootstrap

/**
 *  Computes a score to indicate the quality of fit for the proposed
 *  continuous distribution for the supplied data
 */
abstract class PDFScoringModel(
    name: String,
    domain: Interval = Interval(0.0, Double.MAX_VALUE),
    allowLowerLimitAdjustment: Boolean = true,
    allowUpperLimitAdjustment: Boolean = true
) {

    val metric = Metric(name, domain, allowLowerLimitAdjustment, allowUpperLimitAdjustment)
    val domain: Interval = metric.domain

    // for possible extensions that use the number of parameters as part of the scoring
    private var useNumParametersOption: Boolean = DEFAULT_NUM_PARAMETER_OPTION

    protected abstract fun score(data: DoubleArray, cdf: ContinuousDistributionIfc) : Score

    abstract fun newInstance(): PDFScoringModel

    open fun score(result: EstimationResult) : Score {
        val parameters = result.parameters
        return if (parameters == null){
            metric.badScore()
        } else {
            val cdf = PDFModeler.createDistribution(parameters) ?: return metric.badScore()
            // need to score the model based on data it was fit on
            val data = if (result.shiftedData != null){
                result.shiftedData!!.shiftedData
            } else {
                result.originalData
            }
            score(data, cdf)
        }
    }

    // for possible extensions that use the number of parameters as part of the scoring
    protected fun numParameters(cdf: ContinuousDistributionIfc): Double {
        if (!useNumParametersOption) {
            return 1.0
        }
        val n = cdf.parameters().size.toDouble()
        return if (n == 0.0) {
            1.0
        } else {
            n
        }
    }

    // for possible extensions that use the number of parameters as part of the scoring
    protected open fun parameterScalingFactor(cdf: ContinuousDistributionIfc) : Double {
        val n = numParameters(cdf)
        if (metric.direction == MetricIfc.Direction.BiggerIsBetter){
            return 1.0/n
        } else {
            return n
        }
    }

    companion object{

        var LOWER_LIMIT = -10000000.0
        var UPPER_LIMIT =  10000000.0

        val DEFAULT_BIG_RANGE
            get () = Interval(LOWER_LIMIT, UPPER_LIMIT)

        // for possible extensions that use the number of parameters as part of the scoring
        private var DEFAULT_NUM_PARAMETER_OPTION = false
    }
}