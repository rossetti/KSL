package ksl.utilities.distributions.fitting

import ksl.utilities.Interval
import ksl.utilities.distributions.Gamma
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.GammaRVParameters
import ksl.utilities.rootfinding.BisectionRootFinder
import ksl.utilities.rootfinding.RootFinder
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc
import kotlin.math.ln

/**
 *  Estimates the parameters of the Gamma distribution based on a MLE algorithm.
 *  See page 285-286 of Law (2007) Simulation Modeling and Analysis.  Uses
 *  bi-section search seeded by initial estimates based on MOM estimates.  Convergence is not
 *  guaranteed and will be indicated in the EstimatedParameters success property
 *  and the message.  Requires that the data be strictly positive and that there
 *  are at least two observations. Also, requires that all the supplied data
 *  are not equal. The user may vary some of the search control parameters
 *  to assist with convergence.
 */
class GammaMLEParameterEstimator(
    data: DoubleArray,
    statistics: StatisticIfc = Statistic(data)
) : ParameterEstimator(data, statistics){

    override val checkForShift: Boolean = true

    /**
     * Desired precision. The default is 0.0001.
     */
    var desiredPrecision = 0.0001
        set(value) {
            require(value > 0) { "The desired precision must be > 0: $value" }
            field = value
        }

    /**
     * Maximum allowed number of iterations. The default is 100.
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

    /**
     *  How close we consider a double is to 0.0 to consider it 0.0
     *  Default is 0.001
     */
    var defaultZeroTolerance = 0.001
        set(value) {
            require(value > 0.0) { "The default zero precision must be > 0.0" }
            field = value
        }

    override fun estimate(): EstimationResults {
        // use the MOM estimator to find a starting estimate
        val start = PDFModeler.gammaMOMEstimator(data, statistics)
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
        val mean = start.statistics.average
        // define the function
        val fn: (Double) -> Double = { alpha: Double -> ln(mean / alpha) + Gamma.diGammaFunction(alpha) - rhs }
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
            // if a suitable interval was found, the search interval object was changed to reflect the changes
        }
        // if we get here then the interval should have a root
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
        // use the estimates from the MLE approach
        val alpha = solver.result
        val beta = mean * alpha
        val parameters = GammaRVParameters()
        parameters.changeDoubleParameter("shape", alpha)
        parameters.changeDoubleParameter("scale", beta)
        return EstimationResults(
            statistics = statistics,
            parameters = parameters,
            message = "The gamma parameters were estimated successfully using a MLE technique",
            success = true
        )
    }

    /**
     *  The strategy is to return a range over the possible shape values that
     *  are determined by the variability associated with the data. A rough
     *  prediction interval is formed based on the estimated sample average
     *  a MOM approximation for the shape parameter.  The returned
     *  interval might not contain the root. It may need further refinement.
     *  The lower value of the interval is limited to be no smaller than
     *  the default zero tolerance as defined by the companion object of ParameterEstimatorIfc.
     *  The property intervalFactor can be used to adjust the width of the interval around the
     *  initial estimate of the shape.
     */
    private fun findInitialInterval(estimationResults: EstimationResults): Interval {
        val s = estimationResults.statistics!!
        val mean = s.average
        val me = intervalFactor * s.standardDeviation
        val ulm = mean + me
        val llm = (mean - me).coerceAtLeast(defaultZeroTolerance)
        var params = Gamma.parametersFromMeanAndVariance(ulm, s.variance)
        val shapeUL = params[0]
        params = Gamma.parametersFromMeanAndVariance(llm, s.variance)
        val shapeLL = params[0].coerceAtLeast(defaultZeroTolerance)
        return Interval(shapeLL, shapeUL)
    }

}