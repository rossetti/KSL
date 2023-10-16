package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.moda.MODAModel
import ksl.utilities.moda.MetricIfc
import ksl.utilities.moda.Score

data class ScoringResult(
    val name: String,
    val distribution: ContinuousDistributionIfc,
    val estimationResult: EstimationResult,
    val scores: List<Score>
) : Comparable<ScoringResult> {

    val metrics: List<MetricIfc> = MODAModel.extractMetrics(scores)

    var values: Map<MetricIfc, Double> = emptyMap()
        internal set

    var weightedValue: Double = 0.0
        internal set

    override fun compareTo(other: ScoringResult): Int = compareBy(
        ScoringResult::weightedValue,
        ScoringResult::name
    ).compare(this, other)

}