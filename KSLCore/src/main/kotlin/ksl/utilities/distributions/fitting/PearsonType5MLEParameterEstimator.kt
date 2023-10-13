package ksl.utilities.distributions.fitting

import ksl.utilities.Interval
import ksl.utilities.distributions.Gamma
import ksl.utilities.random.rvariable.parameters.GammaRVParameters
import ksl.utilities.random.rvariable.parameters.PearsonType5RVParameters
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
class PearsonType5MLEParameterEstimator() : ParameterEstimatorIfc {

    override val checkRange: Boolean = true
    private val gammaMLEEstimator = GammaMLEParameterEstimator()

    /**
     * Desired precision. The default is 0.0001.
     */
    var desiredPrecision: Double
        get() = gammaMLEEstimator.desiredPrecision
        set(value) {
            gammaMLEEstimator.desiredPrecision = value
        }

    /**
     * Maximum allowed number of iterations. The default is 100.
     */
    var maximumIterations: Int
        get() = gammaMLEEstimator.maximumIterations
        set(value) {
            gammaMLEEstimator.maximumIterations = value
        }

    /**
     *  The factor used to form initial search interval around initial MOM estimate of the shape.
     *  The default is 3.0, as in a 3-sigma range.
     */
    var intervalFactor: Double
        get() = gammaMLEEstimator.intervalFactor
        set(value) {
            gammaMLEEstimator.intervalFactor = value
        }

    /**
     *  How close we consider a double is to 0.0 to consider it 0.0
     *  Default is 0.001
     */
    var defaultZeroTolerance: Double
        get() = gammaMLEEstimator.defaultZeroTolerance
        set(value) {
            gammaMLEEstimator.defaultZeroTolerance = value
        }

    override fun estimate(data: DoubleArray, statistics: StatisticIfc): EstimationResult {
        // fit 1/X(i) as Gamma
        val xData = DoubleArray(data.size) { 1.0 / data[it] }
        val est = gammaMLEEstimator.estimate(xData, Statistic(xData))
        if (!est.success) {
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                parameters = null,
                message = "The parameters were not estimated successfully using a MLE technique",
                success = true
            )
        }

        // use the estimates from the gamma MLE fit
        val alpha = est.parameters!!.doubleParameter("shape")
        val beta = 1.0 / est.parameters.doubleParameter("scale")
        val parameters = PearsonType5RVParameters()
        parameters.changeDoubleParameter("shape", alpha)
        parameters.changeDoubleParameter("scale", beta)
        return EstimationResult(
            originalData = data,
            statistics = statistics,
            parameters = parameters,
            message = "The Pearson Type 5 parameters were estimated successfully using a MLE technique",
            success = true
        )
    }

}