package ksl.utilities.distributions.fitting

import ksl.utilities.random.rvariable.RVType
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

/**
 *  Estimates the shape and scale of a gamma distribution based on
 *  the method of moments. Observations must be greater than or
 *  equal to zero and must not all be equal. There must be
 *  at least two observations. The sample average and sample variance
 *  of the observations must be strictly greater than zero.
 */
class GammaMOMParameterEstimator(
    data: DoubleArray,
    statistics: StatisticIfc = Statistic(data)
) : ParameterEstimator(data, statistics){

    override val checkForShift: Boolean = true

    override fun estimate(): EstimationResults {
        return PDFModeler.gammaMOMEstimator(data, statistics)
    }
}