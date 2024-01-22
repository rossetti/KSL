package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.io.plotting.FitDistPlot
import ksl.utilities.moda.AdditiveMODAModel
import ksl.utilities.moda.MODAModel
import ksl.utilities.moda.MetricIfc
import ksl.utilities.moda.Score
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.random.rvariable.RVType

/**
 *  The natural ordering is descending by weighted value.
 *  That is scoring results with higher weighted value
 *  are considered better (more value is better). The weighted
 *  value will be a number within [0,1]. Thus, a natural
 *  sort will cause elements with higher value to be first
 *  in the list. If there are no values, then the weighted
 *  value will be zero.  The weighting is determined
 *  by the scoring method.
 */
data class ScoringResult(
    val name: String,
    val distribution: ContinuousDistributionIfc,
    val estimationResult: EstimationResult,
    val rvType: RVParametersTypeIfc,
    val scores: List<Score>
) : Comparable<ScoringResult> {

    val metrics: List<MetricIfc> = MODAModel.extractMetrics(scores)

    val numberOfParameters: Int
        get() = rvType.rvParameters.numberOfParameters

    var values: Map<MetricIfc, Double> = emptyMap()
        internal set

    var weightedValue: Double = 0.0
        internal set

    var weights: Map<MetricIfc, Double> = emptyMap()
        internal set

    override fun compareTo(other: ScoringResult): Int = -(weightedValue.compareTo(other.weightedValue))

    override fun toString(): String {
        return "weighted value = $weightedValue \t distribution = $name \t rv type = $rvType"
    }

    fun distributionFitPlot() : FitDistPlot {
        val data = if (estimationResult.shiftedData != null){
            estimationResult.shiftedData!!.shiftedData
        } else {
            estimationResult.originalData
        }
        return  FitDistPlot(data, distribution, distribution)
    }

}