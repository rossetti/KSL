package ksl.utilities.distributions.fitting.estimators

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.PearsonType5RVParameters
import ksl.utilities.statistic.MVBSEstimatorIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

/**
 *  Estimates the parameters of the Pearson Type 5 distribution based on a MLE algorithm.
 *  See page 293-294 of Law (2007) Simulation Modeling and Analysis.  Uses
 *  bi-section search seeded by initial estimates based on MOM estimates.  Convergence is not
 *  guaranteed and will be indicated in the EstimatedParameters success property
 *  and the message.  Requires that the data be strictly positive and that there
 *  are at least two observations. Also, requires that all the supplied data
 *  are not equal. The user may vary some of the search control parameters
 *  to assist with convergence.
 *  The algorithm relies on the fact if X ~ PT5(shape, scale) if and only
 *  if 1/X ~ gamma(shape, 1/scale).  Thus, the data is transformed as 1/X, and
 *  a gamma distribution is fit. If the MLE of the gamma is successful
 *  the correct parameters are returned.
 */
class PearsonType5MLEParameterEstimator(name: String? = "PearsonType5MLEParameterEstimator") : ParameterEstimatorIfc,
    MVBSEstimatorIfc, IdentityIfc by Identity(name) {

    override val rvType: RVParametersTypeIfc
        get() = RVType.PearsonType5

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

    override fun estimateParameters(data: DoubleArray, statistics: StatisticIfc): EstimationResult {
        // fit 1/X(i) as Gamma
        val xData = DoubleArray(data.size) { 1.0 / data[it] }
        val est = gammaMLEEstimator.estimateParameters(xData, Statistic(xData))
        if (!est.success) {
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                parameters = null,
                message = "The parameters were not estimated successfully using a MLE technique",
                success = false,
                estimator = this
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
            success = true,
            estimator = this
        )
    }

}