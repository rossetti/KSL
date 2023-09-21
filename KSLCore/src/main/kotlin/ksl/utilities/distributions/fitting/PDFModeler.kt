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
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.StatisticIfc

/**
 *  The purpose of this object is to serve as the general location
 *  for implementing the estimation of distribution parameter across
 *  many distributions.
 */
class PDFModeler(private val data: DoubleArray) {

    val histogram : Histogram
    init{
        val breakPoints = Histogram.recommendBreakPoints(data)
        histogram = Histogram(breakPoints)
        histogram.collect(data)

    }

    val hasZeroes: Boolean
        get() = histogram.zeroCount > 0

    val hasNegatives: Boolean
        get() = histogram.negativeCount > 0

    /**
     *  How close we consider a double is to 0.0 to consider it 0.0
     *  Default is 0.001
     */
    var defaultZeroTolerance = PDFModeler.defaultZeroTolerance
        set(value) {
            require(value > 0.0) { "The default zero precision must be > 0.0" }
            field = value
        }

    /**
     *  Estimates a possible shift parameter for the data.
     *  See PDFModeler.estimateLeftShiftParameter(). If any of the data are negative
     *  then there will be no shift. There must be at least 3 different positive values
     *  for a shift to be estimated; otherwise, it will be 0.0. Any estimated shift
     *  that is less that the defaultZeroTolerance will be set to 0.0.
     */
    val leftShift: Double
        get() = estimateLeftShiftParameter(data, defaultZeroTolerance)

    // check for need to shift data
    // provide methods to construct appropriate distributions for modeling given data characteristics
    // positive range distributions, full range distributions
    // use a map keyed by RVType holding a list of parameter estimator types (defined by an enum) available,
    // allow the creation of the estimator from the defined types (like how RVType works)
    // facilitate plotting

    val continuousEstimators: MutableMap<ParameterEstimatorIfc, RVType> = mutableMapOf(
        ExponentialMLEParameterEstimator(data, histogram) to RVType.Exponential,
        UniformParameterEstimator(data, histogram) to RVType.Uniform,
        TriangularParameterEstimator(data, histogram) to RVType.Triangular,
        NormalMLEParameterEstimator(data, histogram) to RVType.Normal,
        LognormalMLEParameterEstimator(data, histogram) to RVType.Lognormal,
        GammaMOMParameterEstimator(data, histogram) to RVType.Gamma,
        GammaMLEParameterEstimator(data, histogram) to RVType.Gamma,
        WeibullMLEParameterEstimator(data, histogram) to RVType.Weibull,
        WeibullPercentileParameterEstimator(data, histogram) to RVType.Weibull,
        BetaMOMParameterEstimator(data, histogram) to RVType.Beta
    )


//    val discreteDistributions = setOf<RVType>(
//        RVType.Bernoulli, RVType.Geometric, RVType.NegativeBinomial, RVType.Poisson
//    )
//
//    val continuousDistributions = setOf<RVType>(
//        RVType.Exponential, RVType.Gamma, RVType.Lognormal, RVType.Normal, RVType.Triangular,
//        RVType.Uniform, RVType.Weibull, RVType.Beta, RVType.PearsonType5, RVType.PearsonType6
//    )

    fun estimateAllContinuous(
        estimators: MutableMap<ParameterEstimatorIfc, RVType> = continuousEstimators,
    ): List<EstimationResults> {
        val estimatedParameters = mutableListOf<EstimationResults>()
        for ((estimator, type) in estimators) {
            estimatedParameters.add(estimator.estimate())
        }
        return estimatedParameters
    }

//    fun estimateParameters(
//        data: DoubleArray,
//        parameterEstimator: ParameterEstimatorIfc,
//        shift: Boolean = true
//    ): EstimationResults {
//        if (shift) {
//            val shiftedData = leftShiftData(data)
//            val parameters = parameterEstimator.estimate(shiftedData.data)
//            parameters.shiftedData = shiftedData
//            return parameters
//        }
//        return parameterEstimator.estimate(data)
//    }

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

        /**
         *  Uses the method described on page 360 of Law (2007)
         *  Simulation Modeling and Analysis, ISBN 0073294411, 9780073294414
         *  There must be at least three observations within the [data] and there
         *  must be at least three different values.  That is, all the values must not be the same.
         *  The observations should all be greater than or equal to 0.0. That is,
         *  no negative values are allowed within the [data]. If these conditions do not hold,
         *  then 0.0 is returned for the shift. That is, no shift.
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
            if (data.size < 3){
                return 0.0
            }
            val min = data.min()
            if (min < 0.0){
                return 0.0
            }
            val max = data.max()
            if (max < 0.0){
                return 0.0
            }
            if (min == max){
                return 0.0
            }
            val nextSmallest = findNextSmallest(data, min)
            if (nextSmallest == max){
                return 0.0
            }
            return estimateLeftShift(min, nextSmallest, max, tolerance)
        }

        fun findNextSmallest(data: DoubleArray, min: Double): Double{
            return data.removeValue(min).min()
        }

        /**
         *  Estimates the range on uniform distribution theory.
         *   Uses the minimum unbiased estimators based on the order statistics.
         *   See: 1. Castillo E, Hadi AS. A method for estimating parameters and quantiles of
         *   distributions of continuous random variables. Computational Statistics & Data Analysis.
         *   1995 Oct;20(4):421–39.
         */
        fun rangeEstimate(min: Double, max: Double, n: Int) : Interval {
            require(n >= 2) {"There must be at least two observations."}
            require(min < max) {"The minimum must be strictly less than the maximum."}
            val range = max - min
            val a = min - (range/(n - 1.0))
            val b = max + (range/(n - 1.0))
            return Interval(a, b)
        }

        private fun findNextSmallestV2(data: DoubleArray, min: Double): Double{
            val sorted = data.orderStatistics()
            var xk = sorted[1]
            for (k in 1 until sorted.size - 1) {
                if (sorted[k] > min) {
                    xk = sorted[k]
                    break
                }
            }
            return xk
        }

        /**
         *  Uses the method described on page 360 of Law (2007)
         *  Simulation Modeling and Analysis, ISBN 0073294411, 9780073294414
         *  The [min] must be strictly less than the [max].  The [nextSmallest] is x(k)
         *  where x(k) is the kth order statistic and k is the value in {2, 3, ..., n-1}
         *  such that x(k) is strictly greater than x(1).
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
        fun estimateLeftShift(min: Double, nextSmallest:Double, max:  Double, tolerance: Double = defaultZeroTolerance) :  Double {
            require(min > 0.0) {"The minimum must be > 0.0"}
            require( min < max) {"The minimum must be strictly less than the maximum."}
            require(nextSmallest > min) {"The next smallest value must not be equal to the minimum."}
            require(nextSmallest < max) {"The next smallest value must be strictly less than the maximum."}
            val top = min * max - nextSmallest * nextSmallest
            if (top == 0.0) {
                return 0.0
            }
            val bottom = min + max - 2.0 * nextSmallest
            val shift = top / bottom
            return if (shift <= tolerance) 0.0 else shift
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

        internal fun gammaMOMEstimator(data: DoubleArray, statistics: StatisticIfc): EstimationResults {
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
    val shift = PDFModeler.estimateLeftShiftParameter(data, 0.000001)
    val min = data.min()
    println("min = $min")
    println("next smallest = ${PDFModeler.findNextSmallest(data, min)}")
    println("max = ${data.max()}")
    println("shift = $shift")
    assert(shift < min)
    println(min - shift)
//    data.sort()
//    println(data.joinToString())

//    val d = PDFModeler(data)
//    val list = d.estimateAllContinuous()
//
//    for(element in list){
//        println(element.toString())
//    }
}