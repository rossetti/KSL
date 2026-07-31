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
 *  @param name a string representation of the fitted distribution with parameters and shift (if applicable).
 *  @param distribution the object representing the continuous distribution that was fit and scored
 *  @param estimationResult the estimation results for the parameter estimation of the fitted distribution
 *  @param rvType the type of the associated random variable (its family)
 *  @param scores a list containing the scores for each metric that the distribution was evaluated on. This
 *  is the raw (untransformed) value of the metric (not on a common scale).
 */
data class ScoringResult(
    val name: String,
    val distribution: ContinuousDistributionIfc,
    val estimationResult: EstimationResult,
    val rvType: RVParametersTypeIfc,
    val scores: List<Score>
) : Comparable<ScoringResult> {

    val metrics: List<MetricIfc> = MODAModel.extractMetrics(scores)

    /**
     *  How many parameters the estimator actually estimated from the data.
     *
     *  This is the estimator's own count rather than the size of the distribution type's declared
     *  parameter set, and the two can differ. A distribution type has a fixed parameter schema, so
     *  a family whose members vary in how many parameters they fit will declare slots that a
     *  particular fit did not estimate. The metalog is the case in the library: boundedness is
     *  derived from the bound values rather than being part of the type, so every metalog type
     *  declares both bounds, and an unbounded fit estimated two fewer parameters than the schema
     *  lists.
     *
     *  This count is the one goodness-of-fit tests need, since it is subtracted from the degrees
     *  of freedom. It is not what the information criteria use: the AIC and BIC scoring models
     *  take their parameter count from the distribution itself, so changing this does not affect
     *  any score or ranking.
     *
     *  One imprecision remains. An estimator that is handed a parameter rather than estimating it
     *  still declares a name for it, so a metalog fitted with a supplied bound counts that bound.
     */
    val numberOfParameters: Int
        get() = estimationResult.estimator.names.size

    /**
     *  This holds the metric and its value based on the transformed
     *  score to the value function domain.
     */
    var values: Map<MetricIfc, Double> = emptyMap()
        internal set

    var weightedValue: Double = 0.0
        internal set

    var firstRankCount: Int = 0
        internal set

    var averageRanking: Double = 0.0
        internal set

    var weights: Map<MetricIfc, Double> = emptyMap()
        internal set

    override fun compareTo(other: ScoringResult): Int = -(weightedValue.compareTo(other.weightedValue))

    override fun toString(): String {
        return "weighted value = $weightedValue \t distribution = $name \t rv type = $rvType"
    }

    fun distributionFitPlot(): FitDistPlot {
        val data = if (estimationResult.shiftedData != null) {
            estimationResult.shiftedData!!.shiftedData
        } else {
            estimationResult.originalData
        }
        return FitDistPlot(data, distribution, distribution)
    }

    /**
     *  Shows the fit distribution plot and the goodness of fit
     *  results for the scoring result
     */
    fun displayFittingResults() {
        val distPlot = distributionFitPlot()
        distPlot.showInBrowser("Distribution $name")
        println()
        println("** Distribution ** $name")
        println()
        val gof = ContinuousCDFGoodnessOfFit(estimationResult.testData,
            distribution,
            numEstimatedParameters = numberOfParameters
        )
        println(gof)
    }

}