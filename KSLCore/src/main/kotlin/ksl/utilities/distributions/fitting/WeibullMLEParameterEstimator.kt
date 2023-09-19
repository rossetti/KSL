package ksl.utilities.distributions.fitting

import ksl.utilities.*
import ksl.utilities.distributions.Weibull
import ksl.utilities.distributions.fitting.DistributionParameterEstimator.defaultZeroTolerance
import ksl.utilities.random.rvariable.parameters.WeibullRVParameters
import ksl.utilities.rootfinding.BisectionRootFinder
import ksl.utilities.rootfinding.RootFinder
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
object WeibullMLEParameterEstimator : ParameterEstimatorIfc {

    /**
     * Desired precision. The default is 0.0001.
     */
    var desiredPrecision = 0.0001
        set(value) {
            require(value > 0) { "The desired precision must be > 0: $value" }
            field = value
        }

    /**
     * Maximum allowed number of iterations. The default is 100
     */
    var maximumIterations = 100
        set(value) {
            require(value >= 1) { "The maximum number of iterations must be >= 1: $value" }
            field = value
        }

    /**
     *  The default number of Newton steps.  The default is 10. On average 3.5 steps
     *  should provide 4 digit accuracy.
     */
    var defaultNumNewtonSteps = 10
        set(value) {
            require(value >= 1) { "The maximum number of iterations must be >= 1: $value" }
            field = value
        }

    /**
     *  Default size of the bi-section search interval around the initial Newton
     *  refined estimate. The default is 10.0.
     */
    var defaultBiSectionSearchIntervalWidth = 10.0
        set(value) {
            require(value > 0) { "The search interval width must be > 0: $value" }
            field = value
        }

    override fun estimate(data: DoubleArray): EstimatedParameters {
        if (data.size < 2) {
            return EstimatedParameters(
                message = "There must be at least two observations",
                success = false
            )
        }
        if (data.countLessEqualTo(0.0) > 0) {
            return EstimatedParameters(
                null,
                message = "Cannot fit Weibull distribution when some observations are less than or equal to 0.0",
                success = false
            )
        }
        if (data.isAllEqual()) {
            return EstimatedParameters(
                message = "Cannot estimate parameters.  The observations were all equal.",
                success = false
            )
        }
        // get an initial estimate of the shape parameter
        var shape = Weibull.initialShapeEstimate(data)
        // take some newton iteration steps to refine estimated shape
        // on average 3.5 steps gets to within 4 place accuracy, page 280 Law(2007)
        for (i in 1..defaultNumNewtonSteps){
            shape = newtonStep(shape, data)
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
        if (!RootFinder.hasRoot(fn, searchInterval)){
            // expand to find interval
            if (!RootFinder.findInterval(fn, searchInterval)){
                // a suitable interval was not found via iterations, return the initial estimator with a new message
                val parameters = WeibullRVParameters()
                parameters.changeDoubleParameter("shape", shape)
                parameters.changeDoubleParameter("scale", scale)
                return EstimatedParameters(parameters,
                    statistics = data.statistics(),
                    message = "MLE search failed to find suitable search interval. Returned initial estimate.",
                    success = false)
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
        if (!solver.hasConverged()){
            // a suitable root was not found via bi-section iterations, return the current estimate with a new message
            val parameters = WeibullRVParameters()
            parameters.changeDoubleParameter("shape", shape)
            parameters.changeDoubleParameter("scale", scale)
            return EstimatedParameters(parameters,
                statistics = data.statistics(),
                message = "MLE search failed to converge. Returned the current estimates based on failed search.",
                success = false)
        }
        val parameters = WeibullRVParameters()
        parameters.changeDoubleParameter("shape", shape)
        parameters.changeDoubleParameter("scale", scale)
        return EstimatedParameters(parameters,
            statistics = data.statistics(),
            message = "MLE estimates for Weibull distribution were successfully estimated.",
            success = true)
    }

    private fun rootFunction(shape: Double, data: DoubleArray) : Double {
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
        return (sumC/sumB) - (1.0/shape) - avgLnX
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
