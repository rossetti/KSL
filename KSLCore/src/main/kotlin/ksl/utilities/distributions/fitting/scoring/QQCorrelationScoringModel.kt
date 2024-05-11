package ksl.utilities.distributions.fitting.scoring

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.moda.MetricIfc
import ksl.utilities.moda.Score
import ksl.utilities.orderStatistics
import ksl.utilities.statistic.EmpDistType
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticXY

/**
 *  This scoring model represents the linear correlation of a Q-Q plot
 *  for a fitted distribution. The correlation between the
 *  empirical quantiles and the order statistics
 *  for the data set is computed and returned as the score.
 */
class QQCorrelationScoringModel(
    var empDistType: EmpDistType = EmpDistType.Continuity1
) : PDFScoringModel("QQCorrelation") {

    init {
        direction = MetricIfc.Direction.BiggerIsBetter
    }

    override fun score(data: DoubleArray, cdf: ContinuousDistributionIfc): Score {
        if (data.isEmpty()){
            return Score(this, 0.0, true)
        }
        val orderStats = data.orderStatistics()
        val empProbabilities = Statistic.empiricalProbabilities(orderStats.size, empDistType)
        val empiricalQuantiles: DoubleArray = DoubleArray(orderStats.size) { i -> cdf.invCDF(empProbabilities[i]) }
        val stat = StatisticXY()
        stat.collectXY(empiricalQuantiles, orderStats)
        val s = if (stat.correlationXY.isNaN()){
            0.0
        } else {
            stat.correlationXY
        }
        return Score(this, s, true)
    }

    override fun newInstance(): QQCorrelationScoringModel {
        return QQCorrelationScoringModel()
    }
}