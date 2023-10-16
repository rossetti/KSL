package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.moda.MODAModel
import ksl.utilities.moda.MetricIfc
import ksl.utilities.moda.Score

data class ScoringResult(
    val distribution: ContinuousDistributionIfc,
    val estimationResult: EstimationResult,
    val scores: List<Score>,
    val values: List<Double>,
    var totalValue: Double = 0.0
) {

    val metrics: List<MetricIfc> = MODAModel.extractMetrics(scores)
}