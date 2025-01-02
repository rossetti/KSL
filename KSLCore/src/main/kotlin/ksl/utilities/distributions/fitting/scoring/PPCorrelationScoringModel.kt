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
class PPCorrelationScoringModel(
    var empDistType: EmpDistType = EmpDistType.Continuity1,
    domain: Interval = Interval(-1.0, 1.0)
) : PDFScoringModel("PPC", domain, allowLowerLimitAdjustment = false, allowUpperLimitAdjustment = false) {

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
        val s = if (stat.correlationXY.isNaN()) {
            0.0
        } else {
            stat.correlationXY
        }
        val f = parameterScalingFactor(cdf)
        return Score(metric, f*s, true)
    }

    override fun newInstance(): PPCorrelationScoringModel {
        return PPCorrelationScoringModel()
    }
}