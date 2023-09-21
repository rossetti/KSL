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

import ksl.utilities.*
import ksl.utilities.distributions.Gamma
import ksl.utilities.math.KSLMath
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.GammaRVParameters
import ksl.utilities.statistic.Statistic

/**
 *  The purpose of this object is to serve as the general location
 *  for implementing the estimation of distribution parameter across
 *  many distributions.
 */
class DistributionModeler(private val data: DoubleArray) {

    val continuousEstimators: MutableMap<ParameterEstimatorIfc, RVType> = mutableMapOf(
        ExponentialParameterEstimator() to RVType.Exponential,
        UniformParameterEstimator() to RVType.Uniform,
        TriangularParameterEstimator() to RVType.Triangular,
        NormalMLEParameterEstimator() to RVType.Normal,
        LognormalMLEParameterEstimator() to RVType.Lognormal,
        GammaMOMParameterEstimator() to RVType.Gamma,
        GammaMLEParameterEstimator() to RVType.Gamma,
        WeibullMLEParameterEstimator() to RVType.Weibull,
        WeibullPercentileParameterEstimator() to RVType.Weibull,
        BetaMOMParameterEstimator() to RVType.Beta
    )

    /**
     *  Uses the method described on page 360 of Law (2007)
     *  Simulation Modeling and Analysis, ISBN 0073294411, 9780073294414
     *  There must be at least two observations within the [data] and there
     *  must be at least two different values.  That is, all the values must not be the same.
     *  The observations should all be greater than or equal to 0.0. That is,
     *  no negative values are allowed within the [data].
     *
     *  The value [tolerance] is used to consider whether the computed shift value is close enough
     *  to zero to consider the shift 0.0.  The default is 0.001.  That is, a value
     *  of the estimated shift that is less than the tolerance are considered 0.0.
     *
     *  This approach estimates a shift parameter that is intended to
     *  shift the distribution to the left.  If X(i) is the original datum,
     *  then the shifted data is intended to be Y(i) = X(i) - shift.  Thus, the distribution
     *  is shifted to the left.  The estimated shift should be a positive quantity.
     */
    fun estimateLeftShiftParameter(data: DoubleArray, tolerance: Double = defaultZeroTolerance): Double {
        require(data.size >= 2) { "There must be at least two observations" }
        require(!data.isAllEqual()) { "The observations were all equal." }
        require(data.countLessThan(0.0) == 0) {"There were negative values in the data."}
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
        if (top == 0.0) {
            return 0.0
        }
        val bottom = min + max - 2.0 * xk
        val shift = top / bottom
        return if (KSLMath.within(shift, 0.0, tolerance)) 0.0 else shift
    }

    /**
     *  Estimates the shift parameter and then shifts the
     *  data by the estimated quantity. Also returns the computed
     *  shift. Use destructuring if you want:
     *
     *  val (shift, shiftedData) = shiftData(data)
     */
    fun leftShiftData(data: DoubleArray, tolerance: Double = defaultZeroTolerance): ShiftedData {
        val shift = estimateLeftShiftParameter(data, tolerance)
        return ShiftedData(shift, KSLArrays.subtractConstant(data, shift))
    }

//    val discreteDistributions = setOf<RVType>(
//        RVType.Bernoulli, RVType.Geometric, RVType.NegativeBinomial, RVType.Poisson
//    )
//
//    val continuousDistributions = setOf<RVType>(
//        RVType.Exponential, RVType.Gamma, RVType.Lognormal, RVType.Normal, RVType.Triangular,
//        RVType.Uniform, RVType.Weibull, RVType.Beta, RVType.PearsonType5, RVType.PearsonType6
//    )

    fun estimateAllContinuous(
        data: DoubleArray,
        estimators: MutableMap<ParameterEstimatorIfc, RVType> = continuousEstimators,
        shift: Boolean = true
    ): List<EstimationResults> {
        val estimatedParameters = mutableListOf<EstimationResults>()
        for ((estimator, type) in estimators) {
            estimatedParameters.add(estimateParameters(data, estimator, shift))
        }
        return estimatedParameters
    }

    fun estimateParameters(
        data: DoubleArray,
        parameterEstimator: ParameterEstimatorIfc,
        shift: Boolean = true
    ): EstimationResults {
        if (shift) {
            val shiftedData = leftShiftData(data)
            val parameters = parameterEstimator.estimate(shiftedData.data)
            parameters.shiftedData = shiftedData
            return parameters
        }
        return parameterEstimator.estimate(data)
    }

    companion object {

        /**
         *  How close we consider a double is to 0.0 to consider it 0.0
         *  Default is 0.001
         */
        var defaultZeroTolerance = 0.001
            set(value) {
                require(value > 0.0) { "The default zero precision must be > 0.0" }
                field = value
            }

        internal fun gammaMOMEstimator(data: DoubleArray, statistics: Statistic): EstimationResults {
            if (data.size < 2) {
                return EstimationResults(
                    statistics = statistics,
                    message = "There must be at least two observations",
                    success = false
                )
            }
            if (data.countLessThan(0.0) > 0) {
                return EstimationResults(
                    statistics = statistics,
                    message = "Cannot fit gamma distribution when some observations are less than 0.0",
                    success = false
                )
            }
            if (statistics.average <= 0.0) {
                return EstimationResults(
                    statistics = statistics,
                    message = "The sample average of the data was <= 0.0",
                    success = false
                )
            }
            if (statistics.variance <= 0.0) {
                return EstimationResults(
                    statistics = statistics,
                    message = "The sample variance of the data was <= 0.0",
                    success = false
                )
            }
            val params = Gamma.parametersFromMeanAndVariance(statistics.average, statistics.variance)
            val parameters = GammaRVParameters()
            parameters.changeDoubleParameter("shape", params[0])
            parameters.changeDoubleParameter("scale", params[1])
            return EstimationResults(
                statistics = statistics,
                parameters = parameters,
                message = "The gamma parameters were estimated successfully using a MOM technique",
                success = true
            )
        }
    }

}

fun main(){
    val e = ExponentialRV(10.0)
    val data = e.sample(2000)
    val d = DistributionModeler(data)

    val list = d.estimateAllContinuous(data, shift = false)

    for(element in list){
        println(element.toString())
    }
}