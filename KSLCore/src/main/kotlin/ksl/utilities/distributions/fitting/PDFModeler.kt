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
import ksl.utilities.distributions.*
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.*
import ksl.utilities.statistic.*

/**
 *  The purpose of this object is to serve as the general location
 *  for implementing the estimation of distribution parameter across
 *  many distributions.
 */
class PDFModeler(private val data: DoubleArray) {

    private val myHistogram: Histogram

    val histogram: HistogramIfc
        get() = myHistogram

    val statistics: StatisticIfc
        get() = myHistogram

    init {
        val breakPoints = Histogram.recommendBreakPoints(data)
        myHistogram = Histogram(breakPoints)
        myHistogram.collect(data)
    }

    val hasZeroes: Boolean
        get() = myHistogram.zeroCount > 0

    val hasNegatives: Boolean
        get() = myHistogram.negativeCount > 0

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
     *  Uses bootstrapping to estimate a confidence interval for the minimum
     */
    fun confidenceIntervalForMinimum(numBootstrapSamples: Int = 399, level: Double = 0.95): Interval {
        val bootStrap: Bootstrap = Bootstrap(data)
        bootStrap.generateSamples(numBootstrapSamples, BSEstimatorIfc.Minimum())
        return bootStrap.percentileBootstrapCI(level)
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

    // facilitate plotting

    /**
     *  This set contains all the known estimators for estimating continuous
     *  distributions. This is the union of nonRestrictedEstimators and positiveRestrictedEstimators
     */
    val allEstimators: Set<ParameterEstimatorIfc>
        get() = nonRestrictedEstimators union positiveRestrictedEstimators

    /**
     *  This set holds estimators that can fit distributions for which
     *  the domain is not restricted.
     */
    val nonRestrictedEstimators: Set<ParameterEstimatorIfc>
        get() = setOf(
            UniformParameterEstimator,
            TriangularParameterEstimator,
            NormalMLEParameterEstimator,
            GeneralizedBetaMOMParameterEstimator
        )

    /**
     *  This set holds the recommended estimators for estimating the
     *  parameters of distributions on the positive domain x in (0, infinity)
     */
    val positiveRestrictedEstimators: Set<ParameterEstimatorIfc>
        get() = setOf(
            ExponentialMLEParameterEstimator,
            LognormalMLEParameterEstimator,
            GammaMLEParameterEstimator(),
            WeibullMLEParameterEstimator()
        )

    /**
     *  This set holds all defined scoring models for evaluating
     *  the distribution goodness of fit.
     */
    val allScoringModels: Set<PDFScoringModel>
        get() = setOf(
            ChiSquaredScoringModel,
            KSScoringModel,
            SquaredErrorScoringModel,
            AndersonDarlingScoringModel,
            AkaikeInfoCriterionScoringModel,
            CramerVonMisesScoringModel,
            BayesianInfoCriterionScoringModel
        )

    /**
     *  Estimates the parameters for all estimators represented by
     *  the set of [estimators]. The parameter [automaticShifting] controls
     *  whether the data will be automatically shifted.
     *
     *  If the automatic shift parameter is true (the default), then a
     *  confidence interval for the minimum of the data is estimated from the data.
     *  If the confidence interval does not contain the value specified by the default
     *  zero tolerance property, then the data is shifted to the left and used in the estimation process.
     *  The estimated shift will be recorded in the result.  Automated shift estimation
     *  will occur only if the automatic shifting parameter is true, and
     *  the estimator requires that its range be checked and
     *  if the data actually requires a shift.  If the automatic shifting
     *  parameter is false, then no shifting will occur.  In this case it is up to
     *  the user to ensure that the supplied data is representative of the set
     *  of estimators to be estimated.
     *
     *  The returned list will contain the results for
     *  each estimator.  Keep in mind that some estimators may fail the estimation
     *  process, which will be noted in the success property of the estimation results.
     */
    fun estimateParameters(
        estimators: Set<ParameterEstimatorIfc>,
        automaticShifting: Boolean = true
    ): List<EstimationResult> {
        // estimate a confidence interval on the minimum value
        var shiftedData: ShiftedData? = null
        if (automaticShifting){
            val minCI = confidenceIntervalForMinimum()
            if (!minCI.contains(defaultZeroTolerance)) {
                shiftedData  = leftShiftData(data)
            }
        }
        val shiftedStats = shiftedData?.shiftedData?.statistics()
        val estimatedParameters = mutableListOf<EstimationResult>()
        for (estimator in estimators) {
            val result = if (estimator.checkRange && (shiftedData != null)) {
                val r = estimator.estimate(shiftedData.shiftedData, shiftedStats!!)
                r.shiftedData = shiftedData
                r
            } else {
                estimator.estimate(data, statistics)
            }
            estimatedParameters.add(result)
        }
        return estimatedParameters
    }

    /**
     *  Every result in the list of [results] is scored by each scoring model in
     *  the supplied set [scoringModels].  The score is added to the map of
     *  scores for the result.
     */
    fun scoreResults(results: List<EstimationResult>, scoringModels: Set<PDFScoringModel> = allScoringModels){
        for(result in results){
            for(model in scoringModels){
                model.score(result)
            }
        }
    }

    companion object {

        /**
         *  How close we consider a double is to 0.0 to consider it 0.0
         *  Default is 0.95
         */
        var defaultConfidenceLevel = 0.95
            set(level) {
                require(!(level <= 0.0 || level >= 1.0)) { "Confidence Level must be (0,1)" }
                field = level
            }

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
            if (data.size < 3) {
                return 0.0
            }
            val min = data.min()
            if (min < 0.0) {
                return 0.0
            }
            val max = data.max()
            if (max < 0.0) {
                return 0.0
            }
            if (min == max) {
                return 0.0
            }
            val nextSmallest = findNextLargest(data, min)
            if (nextSmallest == max) {
                return 0.0
            }
            return estimateLeftShift(min, nextSmallest, max, tolerance)
        }

        /**
         *  If [min] is the minimum of the [data], then this computes the observation
         *  that is strictly larger than the observed minimum.  This is the minimum
         *  of the observations if all observations of [min] are removed from
         *  the data. There must be at least 2 observations and the observations
         *  cannot all be equal.  There must be at least one observation that is
         *  not equal to [min].
         */
        fun findNextLargest(data: DoubleArray, min: Double): Double {
            require(data.size >= 2) { "There must be at least two observations." }
            require(!data.isAllEqual()) { "The observations cannot all be equal." }
            val remaining = data.removeValue(min)
            require(remaining.isNotEmpty()) { "All observations were equal to the minimum." }
            return remaining.min()
        }

        /**
         *  Estimates the range based on uniform distribution theory.
         *   Uses the minimum unbiased estimators based on the order statistics.
         *   See: 1. Castillo E, Hadi AS. A method for estimating parameters and quantiles of
         *   distributions of continuous random variables. Computational Statistics & Data Analysis.
         *   1995 Oct;20(4):421–39.
         */
        fun rangeEstimate(min: Double, max: Double, n: Int): Interval {
            require(n >= 2) { "There must be at least two observations." }
            require(min < max) { "The minimum must be strictly less than the maximum." }
            val range = max - min
            val a = min - (range / (n - 1.0))
            val b = max + (range / (n - 1.0))
            return Interval(a, b)
        }

        private fun findNextSmallestV2(data: DoubleArray, min: Double): Double {
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
        fun estimateLeftShift(
            min: Double,
            nextSmallest: Double,
            max: Double,
            tolerance: Double = defaultZeroTolerance
        ): Double {
            require(min > 0.0) { "The minimum must be > 0.0" }
            require(min < max) { "The minimum must be strictly less than the maximum." }
            require(nextSmallest > min) { "The next smallest value must not be equal to the minimum." }
            require(nextSmallest < max) { "The next smallest value must be strictly less than the maximum." }
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

        /**
         *  Computes breakpoints for the distribution that ensures (approximately) that
         *  the expected number of observations within the intervals defined by the breakpoints
         *  will be equal. That is, the probability associated with each interval is
         *  equal. In addition, the expected number of observations will be approximately
         *  greater than or equal to 5.  There will be at least two breakpoints and thus at least
         *  3 intervals defined by the breakpoints.
         *
         *  If the sample size [sampleSize] is less than 15, then the approximate
         *  expected number of observations within the intervals may not be greater than or equal to 5.
         *  Note that the returned break points do not consider the range of the CDF
         *  and may require end points to be added to the beginning or end of the array
         *  to adjust for the range of the CDF.
         *
         *  The returned break points are based on the natural domain of the implied
         *  CDF and do not account for any shift that may be needed during the modeling
         *  process.
         */
        fun equalizedCDFBreakPoints(sampleSize: Int, inverse: InverseCDFIfc): DoubleArray {
            if (sampleSize < 15) {
                // there should be at least two breakpoints, dividing U(0,1) equally
                return inverse.invCDF(doubleArrayOf(1.0 / 3.0, 2.0 / 3.0))
            }
            val p = U01Test.recommendedU01BreakPoints(sampleSize, defaultConfidenceLevel)
            return inverse.invCDF(p)
        }

        /**
         *  Uses the method of moments to fit a gamma distribution to the supplied data.
         *  The supplied statistics must be the statistics for the supplied data for
         *  this method to return results consistent with the supplied data.
         */
        internal fun gammaMOMEstimator(data: DoubleArray, statistics: StatisticIfc): EstimationResult {
            if (data.size < 2) {
                return EstimationResult(
                    originalData = data,
                    statistics = statistics,
                    message = "There must be at least two observations",
                    success = false
                )
            }
            if (data.countLessThan(0.0) > 0) {
                return EstimationResult(
                    originalData = data,
                    statistics = statistics,
                    message = "Cannot fit gamma distribution when some observations are less than 0.0",
                    success = false
                )
            }
            if (statistics.average <= 0.0) {
                return EstimationResult(
                    originalData = data,
                    statistics = statistics,
                    message = "The sample average of the data was <= 0.0",
                    success = false
                )
            }
            if (statistics.variance <= 0.0) {
                return EstimationResult(
                    originalData = data,
                    statistics = statistics,
                    message = "The sample variance of the data was <= 0.0",
                    success = false
                )
            }
            val params = Gamma.parametersFromMeanAndVariance(statistics.average, statistics.variance)
            val parameters = GammaRVParameters()
            parameters.changeDoubleParameter("shape", params[0])
            parameters.changeDoubleParameter("scale", params[1])
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                parameters = parameters,
                message = "The gamma parameters were estimated successfully using a MOM technique",
                success = true
            )
        }

        /**
         *  Constructs an instance of the appropriate continuous probability distribution
         *  for the provided random variable [parameters].  If no probability distribution
         *  is defined for the supplied type of random variable, then null is returned.
         */
        fun createDistribution(parameters: RVParameters): ContinuousDistributionIfc? {

            return when (parameters.rvType) {
                RVType.Beta -> {
                    val alpha = parameters.doubleParameter("alpha")
                    val beta = parameters.doubleParameter("beta")
                    return Beta(alpha, beta)
                }

                RVType.Exponential -> {
                    val mean = parameters.doubleParameter("mean")
                    return Exponential(mean)
                }

                RVType.Gamma -> {
                    val scale = parameters.doubleParameter("scale")
                    val shape = parameters.doubleParameter("shape")
                    Gamma(shape, scale)
                }

                RVType.GeneralizedBeta -> {
                    val alpha = parameters.doubleParameter("alpha")
                    val beta = parameters.doubleParameter("beta")
                    val min = parameters.doubleParameter("min")
                    val max = parameters.doubleParameter("max")
                    return GeneralizedBeta(alpha, beta, min, max)
                }

                RVType.Lognormal -> {
                    val mean = parameters.doubleParameter("mean")
                    val variance = parameters.doubleParameter("variance")
                    return Lognormal(mean, variance)
                }

                RVType.Normal -> {
                    val mean = parameters.doubleParameter("mean")
                    val variance = parameters.doubleParameter("variance")
                    return Normal(mean, variance)
                }

                RVType.Triangular -> {
                    val mode = parameters.doubleParameter("mode")
                    val min = parameters.doubleParameter("min")
                    val max = parameters.doubleParameter("max")
                    return Triangular(min, mode, max)
                }

                RVType.Uniform -> {
                    val min = parameters.doubleParameter("min")
                    val max = parameters.doubleParameter("max")
                    return Uniform(min, max)
                }

                RVType.Weibull -> {
                    val scale = parameters.doubleParameter("scale")
                    val shape = parameters.doubleParameter("shape")
                    return Weibull(shape, scale)
                }
//                RVType.JohnsonB -> TODO()
//                RVType.Laplace -> TODO()
//                RVType.LogLogistic -> TODO()
//                RVType.ChiSquared -> TODO()
//                RVType.PearsonType5 -> TODO()
//                RVType.PearsonType6 -> TODO()
                else -> null
            }
        }
    }

}

fun main() {
    val e = ExponentialRV(10.0)
    //   val se = ShiftedRV(5.0, e)
    val n = 1000
    val data = e.sample(n)
    testModeler(data)
 //   testEstimation(data)
}

private fun testModeler(data: DoubleArray) {
    val d = PDFModeler(data)
    val list = d.estimateParameters(d.allEstimators)
    d.scoreResults(list)
    for (element in list) {
        println(element.toString())
    }
}

private fun testEstimation(data: DoubleArray) {
    val estimator = ExponentialMLEParameterEstimator

    val result = estimator.estimate(data)

    val d = PDFModeler.createDistribution(result.parameters!!)!!
    println(d)
    val params = result.parameters
    val mean = params.doubleParameter("mean")
    //val d = Exponential(mean)
    var bp = PDFModeler.equalizedCDFBreakPoints(data.size, d)
    bp = Histogram.addLowerLimit(0.0, bp)
    bp = Histogram.addPositiveInfinity(bp)
    val h = Histogram(bp)
    h.collect(data)
//    println(h)

    val ec = h.expectedCounts(d)
    println("number of counts = ${ec.size}")
    println("number of bins = ${h.numberBins}")
//    println(ec.joinToString())

    val chiSq = Statistic.chiSqTestStatistic(h.binCounts, ec)
    println("Chi-squared test statistic = $chiSq")
    val dof = h.numberBins - 1 - 1
    val chiDist = ChiSquaredDistribution(dof.toDouble())
    val pValue = chiDist.complementaryCDF(chiSq)
    println("P-Value = $pValue")

    // test the scoring
    //val models = setOf(ChiSquaredScoringModel)
    val score = ChiSquaredScoringModel.score(result)
    println(score)
}