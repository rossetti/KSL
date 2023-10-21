package ksl.utilities.distributions.fitting

import ksl.utilities.statistic.MVBSEstimatorIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

/**
 *  Estimates the shape and scale of a gamma distribution based on
 *  the method of moments. Observations must be greater than or
 *  equal to zero and must not all be equal. There must be
 *  at least two observations. The sample average and sample variance
 *  of the observations must be strictly greater than zero.
 */
object GammaMOMParameterEstimator : ParameterEstimatorIfc, MVBSEstimatorIfc {

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

    override val checkRange: Boolean = true

    override fun estimateParameters(data: DoubleArray, statistics: StatisticIfc): EstimationResult {
        return PDFModeler.gammaMOMEstimator(data, statistics)
    }
}