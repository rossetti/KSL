package ksl.utilities.distributions.fitting

import ksl.utilities.countLessThan
import ksl.utilities.random.rvariable.parameters.BinomialRVParameters
import ksl.utilities.random.rvariable.parameters.NegativeBinomialRVParameters
import ksl.utilities.statistic.StatisticIfc

/**
 *  Estimates the probability of success (p) and number of trials (n)
 *  for the binomial distribution based. The number of trials is estimated
 *  based on the maximum observed value adjusted based on range estimation
 *  for uniform(min, max) distribution.
 *
 *  The data must not contain negative values. The estimation
 *  process assumes that the supplied data are integer valued counts
 *  over the range {0,1,2,..., n}.  That is this parameterization represents
 *  the number of success in n trials.
 *  The estimation process does not ensure that n is integer valued.
 */
object BinomialMaxParameterEstimator : ParameterEstimatorIfc {

    override val checkRange: Boolean = true

    override fun estimate(data: DoubleArray, statistics: StatisticIfc): EstimationResult {
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

        val range = PDFModeler.rangeEstimate(statistics.min, statistics.max, statistics.count.toInt())
        val n = range.upperLimit
        val p = statistics.average/n
        val parameters = BinomialRVParameters()
        parameters.changeDoubleParameter("probOfSuccess", p)
        parameters.changeDoubleParameter("numTrials", n)
        return EstimationResult(
            originalData = data,
            statistics = statistics,
            parameters = parameters,
            message = "The binomial parameters were estimated successfully",
            success = true
        )
    }
}