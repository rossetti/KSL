package ksl.utilities.distributions.fitting.estimators

import ksl.utilities.*
import ksl.utilities.distributions.Weibull
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.random.rvariable.PearsonType5RV
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.WeibullRVParameters
import ksl.utilities.rootfinding.BisectionRootFinder
import ksl.utilities.rootfinding.RootFinder
import ksl.utilities.statistic.MVBSEstimatorIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc
import kotlin.math.ln
import kotlin.math.pow

/**
 *  Estimates the parameters of the Weibull distribution based on a MLE algorithm.
 *  See page 287-288 of Law (2007) Simulation Modeling and Analysis.  Uses
 *  Newton steps followed by bi-section search (if needed).  Convergence is not
 *  guaranteed and will be indicated in the EstimatedParameters success property
 *  and the message.  Requires that the data be strictly positive and that there
 *  are at least two observations. Also, requires that all the supplied data
 *  are not equal. The user may vary some of the search control parameters
 *  to assist with convergence.
 */
class WeibullMLEParameterEstimator(name: String? = "WeibullMLEParameterEstimator") : ParameterEstimatorIfc,
    MVBSEstimatorIfc, IdentityIfc by Identity(name) {

    override val rvType: RVParametersTypeIfc
        get() = RVType.Weibull

    override val checkRange: Boolean = true

    override val names: List<String> = listOf("shape", "scale")

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
            er.parameters.doubleParameter("shape"),
            er.parameters.doubleParameter("scale")
        )
    }

    /**
     * Desired precision. The default is 0.0001.
     */
    var desiredPrecision : Double = 0.0001
        set(value) {
            require(value > 0) { "The desired precision must be > 0: $value" }
            field = value
        }

    /**
     * Maximum allowed number of iterations. The default is 100
     */
    var maximumIterations : Int = 100
        set(value) {
            require(value >= 1) { "The maximum number of iterations must be >= 1: $value" }
            field = value
        }

    /**
     *  The default number of Newton steps.  The default is 10. On average 3.5 steps
     *  should provide 4 digit accuracy.
     */
    var defaultNumNewtonSteps : Int = 10
        set(value) {
            require(value >= 1) { "The maximum number of iterations must be >= 1: $value" }
            field = value
        }

    /**
     *  Default size of the bi-section search interval around the initial Newton
     *  refined estimate. The default is 10.0.
     */
    var defaultBiSectionSearchIntervalWidth : Double = 10.0
        set(value) {
            require(value > 0) { "The search interval width must be > 0: $value" }
            field = value
        }

    /**
     *  How close we consider a double is to 0.0 to consider it 0.0
     *  Default is 0.001
     */
    var defaultZeroTolerance : Double = 0.001
        set(value) {
            require(value > 0.0) { "The default zero precision must be > 0.0" }
            field = value
        }

    override fun estimateParameters(data: DoubleArray, statistics: StatisticIfc): EstimationResult {
        if (data.size < 2) {
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "There must be at least two observations",
                success = false,
                estimator = this
            )
        }
        if (data.countLessEqualTo(0.0) > 0) {
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "Cannot fit Weibull distribution when some observations are less than or equal to 0.0",
                success = false,
                estimator = this
            )
        }
        if (data.isAllEqual()) {
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "Cannot estimate parameters.  The observations were all equal.",
                success = false,
                estimator = this
            )
        }
        // get an initial estimate of the shape parameter
        var shape = estimateInitialShape(data)
        // I think that the shape can't be bad, but just in case, catch it here
        if ((shape <= 0.0) || (shape.isNaN() || shape.isInfinite())) {
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "Cannot estimate parameters.  No viable initial shape estimate could be found!",
                success = false,
                estimator = this
            )
        }
        // compute the initial scale
        var scale = Weibull.estimateScale(shape, data)
        // prepare for bi-section search to converge to root after newton steps
        // define the root function for the search
        val fn: (Double) -> Double = { x: Double -> rootFunction(x, data) }
        val ll = (shape - defaultBiSectionSearchIntervalWidth).coerceAtLeast(defaultZeroTolerance)
        // define the search interval
        val searchInterval = Interval(ll, shape + defaultBiSectionSearchIntervalWidth)
        // need to test interval
        if (!RootFinder.hasRoot(fn, searchInterval)) {
            // expand to find interval
            if (!RootFinder.findInterval(fn, searchInterval)) {
                // a suitable interval was not found via iterations, return the initial estimator with a new message
                val parameters = WeibullRVParameters()
                parameters.changeDoubleParameter("shape", shape)
                parameters.changeDoubleParameter("scale", scale)
                return EstimationResult(
                    originalData = data,
                    statistics = statistics,
                    parameters = parameters,
                    message = "MLE search failed to find suitable search interval. Returned initial estimate.",
                    success = false,
                    estimator = this
                )
            }
            // if a suitable interval was found, the search interval object was changed to reflect the search
        }
        // if we get here then the interval should have a root
        val solver = BisectionRootFinder(
            fn, searchInterval, shape,
            maxIter = maximumIterations, desiredPrec = desiredPrecision
        )
        solver.evaluate()
        // need to check for convergence
        shape = solver.result
        scale = Weibull.estimateScale(shape, data)
        if (!solver.hasConverged()) {
            // a suitable root was not found via bi-section iterations, return the current estimate with a new message
            val parameters = WeibullRVParameters()
            parameters.changeDoubleParameter("shape", shape)
            parameters.changeDoubleParameter("scale", scale)
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                parameters = parameters,
                message = "MLE search failed to converge. Returned the current estimates based on failed search.",
                success = false,
                estimator = this
            )
        }
        val parameters = WeibullRVParameters()
        parameters.changeDoubleParameter("shape", shape)
        parameters.changeDoubleParameter("scale", scale)
        return EstimationResult(
            originalData = data,
            statistics = statistics,
            parameters = parameters,
            message = "The Weibull parameters were estimated successfully using a MLE technique",
            success = true,
            estimator = this
        )
    }

    /**
     *   Uses the recommendation on page 280 Law(2007) to find an initial
     *   starting shape by trying some Newton-Raphson steps.  If the steps
     *   result in a negative shape, the approach reverts to an adhoc method
     *   that combines an initial estimate of the shape with an estimate
     *   from the percentile method.
     */
    private fun estimateInitialShape(data: DoubleArray): Double {
        var shape = Weibull.initialShapeEstimate(data)
        // if initial shape was negative, can't take newton steps, try the percentile method
        if ((shape <= 0.0) || (shape.isNaN()) || (shape.isInfinite())){
            val wppe = WeibullPercentileParameterEstimator()
            val params = wppe.estimate(data)
            if (params.isNotEmpty()) {
                if ((params[0] > 0.0) && (params[0].isFinite()) && (!params[0].isNaN())){
                    return params[0]
                } else {
                    // no viable estimate of the shape was available
                    return shape
                }
            } else {
                // no viable estimate of the shape was available
                return shape
            }
        }
        // take some newton iteration steps to refine estimated shape
        // on average 3.5 steps gets to within 4 place accuracy, page 280 Law(2007)
        for (i in 1..defaultNumNewtonSteps) {
            shape = newtonStep(shape, data)
        }
        // unfortunately newton steps may result in a negative shape estimate
        // if negative go back to original shape and adjust it using the
        // percentile estimate of shape
        if ((shape <= 0.0) || (shape.isNaN()) || (shape.isInfinite())) {
            // fix it
            shape = Weibull.initialShapeEstimate(data)
            // this shape must be positive because of initial check
            val wppe = WeibullPercentileParameterEstimator()
            val params = wppe.estimate(data)
            if (params.isNotEmpty()) {
                val estShape = params[0]
                if ((estShape <= 0.0) || (estShape.isNaN()) || (estShape.isInfinite())) {
                    return shape // should be positive
                } else {
                    return (shape + estShape) / 2.0 // the avg of 2 positives is positive
                }
            }
            // if we get here shape must be positive and will be returned at the end
        }
        return shape
    }

    private fun rootFunction(shape: Double, data: DoubleArray): Double {
        var sumB = 0.0
        var sumC = 0.0
        var sumH = 0.0
        var sumLnX = 0.0
        for (x in data) {
            val xa = x.pow(shape)
            val lnx = ln(x)
            sumLnX = sumLnX + lnx
            sumB = sumB + xa
            sumC = sumC + xa * lnx
            sumH = sumH + xa * lnx * lnx
        }
        val n = data.size.toDouble()
        val avgLnX = sumLnX / n
        return (sumC / sumB) - (1.0 / shape) - avgLnX
    }

    private fun newtonStep(previousShape: Double, data: DoubleArray): Double {
        var sumB = 0.0
        var sumC = 0.0
        var sumH = 0.0
        var sumLnX = 0.0
        for (x in data) {
            val xa = x.pow(previousShape)
            val lnx = ln(x)
            sumLnX = sumLnX + lnx
            sumB = sumB + xa
            sumC = sumC + xa * lnx
            sumH = sumH + xa * lnx * lnx
        }
        val n = data.size.toDouble()
        val avgLnX = sumLnX / n
        val numerator = (avgLnX) + (1 / previousShape) - (sumC / sumB)
        val denominator = (1.0 / (previousShape * previousShape)) + (((sumB * sumH) - sumC * sumC) / (sumB * sumB))
        return previousShape + (numerator / denominator)
    }
}

//fun main() {
//    val rv = PearsonType5RV(7.0, 15.0)
//    var data = rv.sample(40)
////    data = rv.sample(40)
//    val pe = WeibullMLEParameterEstimator()
//    val stat = Statistic(data)
//    val est = pe.estimateParameters(data, stat)
//}
