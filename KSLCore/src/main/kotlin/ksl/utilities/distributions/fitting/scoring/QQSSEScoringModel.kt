package ksl.utilities.distributions.fitting.scoring

import ksl.utilities.KSLArrays
import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.moda.Score
import ksl.utilities.orderStatistics
import ksl.utilities.statistic.EmpDistType
import ksl.utilities.statistic.Statistic
import ksl.utilities.sumOfSquares

/**
 *  This scoring model represents the sum of squared errors of a Q-Q plot
 *  for a fitted distribution. The sum of squared difference between the
 *  empirical quantiles and the order statistics
 *  for the data set is computed and returned as the score.
 */
class QQSSEScoringModel(
    var empDistType: EmpDistType = EmpDistType.Continuity1
) : PDFScoringModel("QQSSE", allowLowerLimitAdjustment = false, allowUpperLimitAdjustment = true) {

    override fun score(data: DoubleArray, cdf: ContinuousDistributionIfc): Score {
        if (data.isEmpty()){
            return Score(this, Double.MAX_VALUE, true)
        }
        val orderStats = data.orderStatistics()
        val empProbabilities = Statistic.empiricalProbabilities(orderStats.size, empDistType)
        val empiricalQuantiles: DoubleArray = DoubleArray(orderStats.size) { i -> cdf.invCDF(empProbabilities[i]) }
        val errors = KSLArrays.subtractElements(empiricalQuantiles, orderStats)
        val sse = errors.sumOfSquares()
        return Score(this, sse, true)
    }

    override fun newInstance(): QQSSEScoringModel {
        return QQSSEScoringModel()
    }
}