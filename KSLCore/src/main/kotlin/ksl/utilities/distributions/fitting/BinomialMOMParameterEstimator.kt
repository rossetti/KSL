package ksl.utilities.distributions.fitting

import ksl.utilities.countLessThan
import ksl.utilities.random.rvariable.parameters.BinomialRVParameters
import ksl.utilities.statistic.MVBSEstimatorIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

/**
 *  Estimates the probability of success (p) and number of trials (n)
 *  for the binomial distribution based on
 *  the method of moments. The data must not contain negative values. The estimation
 *  process assumes that the supplied data are integer valued counts
 *  over the range {0,1,2,..., n}.  That is this parameterization represents
 *  the number of success in n trials.
 *  To ensure moment matching, the estimation process does not ensure that n is integer valued.
 */
object BinomialMOMParameterEstimator : ParameterEstimatorIfc, MVBSEstimatorIfc {

    override val checkRange: Boolean = true

    override val names: List<String> = listOf("probOfSuccess", "numTrials")

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
            er.parameters.doubleParameter("probOfSuccess"),
            er.parameters.doubleParameter("numTrials")
        )
    }

    override fun estimateParameters(data: DoubleArray, statistics: StatisticIfc): EstimationResult {
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
                message = "Cannot fit binomial distribution when some observations are less than 0.0",
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
        val sigma2 = statistics.variance * (statistics.count - 1.0) / statistics.count
        if (statistics.average <= sigma2) {
            return EstimationResult(
                originalData = data,
                statistics = statistics,
                message = "Cannot match moments when sample average <= estimated variance",
                success = false
            )
        }
        val p = 1.0 - (sigma2 / statistics.average)
        val n = statistics.average / p
        val parameters = BinomialRVParameters()
        parameters.changeDoubleParameter("probOfSuccess", p)
        parameters.changeDoubleParameter("numTrials", n)
        return EstimationResult(
            originalData = data,
            statistics = statistics,
            parameters = parameters,
            message = "The binomial parameters were estimated successfully using a MOM technique",
            success = true
        )
    }
}