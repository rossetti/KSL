package ksl.utilities.distributions.fitting.scoring

import ksl.utilities.KSLArrays
import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.moda.Score
import ksl.utilities.orderStatistics
import ksl.utilities.statistic.EmpDistType
import ksl.utilities.statistic.Statistic
import ksl.utilities.sumOfSquares

/**
 *  This scoring model represents the sum of squared errors of a P-P plot
 *  for a fitted distribution. The sum of squared errors between the
 *  empirical probabilities and the theoretical probabilities
 *  for the data set is computed and returned as the score.
 */
class PPSSEScoringModel(
    var empDistType: EmpDistType = EmpDistType.Continuity1
) : PDFScoringModel("PPSSE", allowLowerLimitAdjustment = false, allowUpperLimitAdjustment = true) {

    override fun score(data: DoubleArray, cdf: ContinuousDistributionIfc): Score {
        if (data.isEmpty()){
            return Score(metric, Double.MAX_VALUE, true)
        }
        val orderStats = data.orderStatistics()
        val empProbabilities = Statistic.empiricalProbabilities(orderStats.size, empDistType)
        val theoreticalProbabilities: DoubleArray = DoubleArray(orderStats.size) { i -> cdf.cdf(orderStats[i]) }
        val errors = KSLArrays.subtractElements(theoreticalProbabilities, empProbabilities)
        val sse = errors.sumOfSquares()
        val f = parameterScalingFactor(data.size.toDouble(), cdf)
        return Score(metric, f*sse, true)
    }

    override fun newInstance(): PPSSEScoringModel {
        return PPSSEScoringModel()
    }
}