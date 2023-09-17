package ksl.utilities.distributions.fitting

import ksl.utilities.Interval
import ksl.utilities.distributions.Gamma
import ksl.utilities.distributions.fitting.ParameterEstimatorIfc.Companion.defaultZeroTolerance
import ksl.utilities.random.rvariable.parameters.GammaRVParameters
import ksl.utilities.rootfinding.BisectionRootFinder
import ksl.utilities.rootfinding.RootFinder
import kotlin.math.ln


object GammaMLEParameterEstimator : ParameterEstimatorIfc {

    /**
     * Desired precision.
     */
    var desiredPrecision = 0.001
        set(value) {
            require(value > 0) { "The desired precision must be > 0: $value" }
            field = value
        }

    /**
     * Maximum allowed number of iterations.
     */
    var maximumIterations = 100
        set(value) {
            require(value >= 1) { "The maximum number of iterations must be >= 1: $value" }
            field = value
        }

    /**
     *  The factor used to form initial search interval around initial MOM estimate of the shape.
     *  The default is 3.0, as in a 3-sigma range.
     */
    var intervalFactor = 3.0
        set(value) {
            require(value >= 1.0) { "The desired precision must be > 1.0: $value" }
            field = value
        }

    override fun estimate(data: DoubleArray): EstimatedParameters {
        val start = ParameterEstimatorIfc.gammaMOMEstimator(data)
        if (!start.success) {
            return start
        }
        // use starting estimator to seed the MLE search
        val shape = start.parameters!!.doubleParameter("shape")
        val scale = start.parameters.doubleParameter("scale")
        var lnsum = 0.0
        for (x in data) {
            lnsum = lnsum + ln(x)
        }
        val rhs = lnsum / data.size
        val mean = start.statistics!!.average
        // define the function
        val fn: (Double) -> Double = { x: Double -> ln(mean / x) + Gamma.diGammaFunction(x) - rhs }
        // define an initial search interval for the shape parameter search
        val searchInterval = findInitialInterval(start)
        // need to test interval
        if (!RootFinder.hasRoot(fn, searchInterval)){
            // expand to find interval
            if (!RootFinder.findInterval(fn, searchInterval)){
                // a suitable interval was not found via iterations, return the MOM estimator with a new message
                start.message = "MLE search failed to find suitable search interval. the MOM estimator was returned."
                return start
            }
        }
        val solver = BisectionRootFinder(
            fn, searchInterval, shape,
            maxIter = maximumIterations, desiredPrec = desiredPrecision
        )
        solver.evaluate()
        // need to check for convergence
        if (!solver.hasConverged()){
            // a suitable root was not found via iterations, return the MOM estimator with a new message
            start.message = "MLE search failed to converge. The MOM estimator was returned."
            return start
        }
        val alpha = solver.result
        val beta = mean * alpha
        val parameters = GammaRVParameters()
        parameters.changeDoubleParameter("shape", alpha)
        parameters.changeDoubleParameter("scale", beta)
        return EstimatedParameters(parameters, statistics = start.statistics, success = true)
    }

    /**
     *  The strategy is to return a range over the possible shape values that
     *  are determined by the variability associated with the data. A rough
     *  prediction interval is formed based on the estimated sample average
     *  a MOM approximation for the shape parameter.  The returned
     *  interval might not contain the root. It may need further refinement.
     *  The lower value of the range is limited to be no smaller than
     *  the default zero tolerance as defined by the companion object of ParameterEstimatorIfc.
     */
    private fun findInitialInterval(estimatedParameters: EstimatedParameters): Interval {
        val s = estimatedParameters.statistics!!
        val mean = s.average
        val me = intervalFactor * s.variance
        val ulm = mean + me
        val llm = (mean - me).coerceAtLeast(defaultZeroTolerance)
        var params = Gamma.parametersFromMeanAndVariance(ulm, s.variance)
        val shapeUL = params[0]
        params = Gamma.parametersFromMeanAndVariance(llm, s.variance)
        val shapeLL = params[0].coerceAtLeast(defaultZeroTolerance)
        return Interval(shapeLL, shapeUL)
    }

}

/*
public class GammaParameters : ParameterEstimatorIfc {
    /**
     * Estimate the parameters for a gamma distribution
     * Returns parameters in the form `[shape, scale`].
     * @param [data] Input data.
     * @return Array containing `[shape, scale`]
     */
    override fun estimate(data: DoubleArray): Result<DoubleArray> {
        if (data.any { it <= 0 }) { return estimateFailure("Data must be positive") }
        val solver = BisectionRootFinder(
            FuncToSolve(data),
            Interval(KSLMath.machinePrecision, Int.MAX_VALUE.toDouble())
        )
        solver.evaluate()
        val alpha = solver.result
        val beta = Statistic(data).average / alpha
        return estimateSuccess(alpha, beta)
    }

    private class FuncToSolve(data: DoubleArray) : FunctionIfc {
        val rhs = sumLog(data) / data.size
        val mean = Statistic(data).average

        override fun f(x: Double): Double = ln(mean / x) + Digamma.value(x) - rhs
    }
}
 */