package ksl.utilities.distributions.fitting.scoring

import ksl.utilities.Interval
import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.moda.MetricIfc
import ksl.utilities.moda.Score
import ksl.utilities.orderStatistics
import ksl.utilities.statistic.EmpDistType
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticXY

/**
 *  This scoring model represents the linear correlation of a P-P plot
 *  for a fitted distribution. The correlation between the
 *  empirical probabilities and the theoretical probabilities
 *  for the data set is computed and returned as the score.
 */
class AdjustedPPCorrelationScoringModel(
    var empDistType: EmpDistType = EmpDistType.Continuity1,
    domain: Interval = Interval(0.0, 1.0)
) : PDFScoringModel("AdjPPC", domain, allowLowerLimitAdjustment = false, allowUpperLimitAdjustment = false) {

    init {
        metric.direction = MetricIfc.Direction.BiggerIsBetter
    }

    override fun score(data: DoubleArray, cdf: ContinuousDistributionIfc): Score {
        if (data.isEmpty()) {
            return Score(metric, 0.0, true)
        }
        val orderStats = data.orderStatistics()
        val empProbabilities = Statistic.empiricalProbabilities(orderStats.size, empDistType)
        val theoreticalProbabilities: DoubleArray = DoubleArray(orderStats.size) { i -> cdf.cdf(orderStats[i]) }
        val stat = StatisticXY()
        stat.collectXY(theoreticalProbabilities, empProbabilities)
        val r = if (stat.correlationXY.isNaN()) {
            0.0
        } else {
            stat.correlationXY
        }
        // if r <= 0.0, then it is a bad fit
        if (r <= 0.0) {
            return Score(metric, 0.0, true)
        }
        // adjust for number of parameters
        val n = orderStats.size.toDouble()
        val d = cdf.parameters().size.toDouble()
        val adjRSq = 1.0 - (1.0 - r * r) * ((n - 1.0) / (n - d))
        // in theory adjRSq could be less than 0.0, bound it to worst score
        if (adjRSq <= 0.0) {
            return Score(metric, 0.0, true)
        }
        return Score(metric, adjRSq, true)
    }

    override fun newInstance(): AdjustedPPCorrelationScoringModel {
        return AdjustedPPCorrelationScoringModel()
    }
}