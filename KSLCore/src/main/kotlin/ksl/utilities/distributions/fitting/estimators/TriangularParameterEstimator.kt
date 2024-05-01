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

package ksl.utilities.distributions.fitting.estimators

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.isAllEqual
import ksl.utilities.random.rvariable.parameters.TriangularRVParameters
import ksl.utilities.statistic.MVBSEstimatorIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

/**
 *  The approach is based on estimating the min and max via
 *  the recommended approach for the uniform distribution. Then,
 *  the mode is estimated by solving for it in terms of the sample
 *  average.
 */
object TriangularParameterEstimator : ParameterEstimatorIfc,
    MVBSEstimatorIfc, IdentityIfc by Identity("TriangularParameterEstimator") {

    override val checkRange: Boolean = false

    override val names: List<String> = listOf("min", "mode", "max")

    /**
     *  If the estimation process is not successful, then an
     *  empty array is returned.
     */
    override fun estimate(data: DoubleArray): DoubleArray {
        val er = estimateParameters(data, Statistic(data))
        if (!er.success || er.parameters == null) {
            return doubleArrayOf()
        }
        return doubleArrayOf(
            er.parameters.doubleParameter("min"),
            er.parameters.doubleParameter("mode"),
            er.parameters.doubleParameter("max")
        )
    }
    override fun estimateParameters(data: DoubleArray, statistics: StatisticIfc): EstimationResult {
        if (data.size < 2){
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "There must be at least two observations",
                success = false,
                estimator = this
            )
        }
        if (data.isAllEqual()){
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "The observations were all equal.",
                success = false,
                estimator = this
            )
        }
        val interval = PDFModeler.rangeEstimate(statistics.min, statistics.max, data.size)
        // estimate a and b like the uniform distribution
        val a = interval.lowerLimit
        val b = interval.upperLimit
        // compute the mode by matching moments with the mean.
        // the mean = (a + c + b)/3.0
        var c = 3.0*statistics.average - a - b
        // ensure that the mode estimate is inside the range
        if(c > b){
            c = b
        }
        if(c < a){
            c = a
        }
        val parameters = TriangularRVParameters()
        parameters.changeDoubleParameter("min", a)
        parameters.changeDoubleParameter("max", b)
        parameters.changeDoubleParameter("mode", c)
        return EstimationResult(
            originalData = data,
            statistics = statistics,
            parameters = parameters,
            message = "The triangular parameters were estimated successfully.",
            success = true,
            estimator = this
        )
    }
}