package ksl.utilities.moda

import ksl.utilities.Interval
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.statistic.Statistic
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.DataColumn
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf
import org.jetbrains.kotlinx.dataframe.api.toColumn
import org.jetbrains.kotlinx.dataframe.impl.asList

/**
 *  Defines a base class for creating multi-objective decision analysis (MODA) models.
 */
abstract class MODAModel(
    val valueRange: Interval = Interval(0.0, 100.0)
) {

    init {
        require(valueRange.width > 0.0) { "The range of the value functions must have a width > 0.0" }
    }

    private val metricFunctionMap: MutableMap<MetricIfc, ValueFunctionIfc> = mutableMapOf()
    private val myAlternatives: MutableMap<String, Map<MetricIfc, Score>> = mutableMapOf()

    /**
     *  The list of metrics defined for the model. The order of the metrics
     *  is defined by the order entered into the map that was supplied by
     *  the defineMetrics() function.
     */
    val metrics: List<MetricIfc>
        get() = metricFunctionMap.keys.asList()

    /**
     *  The list of alternative within the model. The order of the alternatives
     *  is defined by the order entered into the map that was supplied by
     *  the defineAlternatives() function.
     */
    val alternatives: List<String>
        get() = myAlternatives.keys.asList()

    /**
     *   Defines the metrics to be used in the evaluation of the alternatives.
     *   Each metric must be associated with the related value function. If not
     *   it is not added to the model.  If there are previously defined metrics, they will be
     *   cleared and replaced by the supplied definitions.  If there were previously
     *   defined alternatives they will be cleared because they might not have
     *   the defined metrics.
     */
    fun defineMetrics(definitions: Map<MetricIfc, ValueFunctionIfc>) {
        if (metricFunctionMap.isNotEmpty()) {
            metricFunctionMap.clear()
            myAlternatives.clear()
        }
        for ((metric, valFunc) in definitions) {
            if (metric == valFunc.metric) {
                metricFunctionMap[metric] = valFunc
            }
        }
    }

    /**
     *  Defines the alternatives and their scores that should be evaluated by the
     *  model. The metrics for the model must have been previously defined prior
     *  to specifying the alternatives. The scores supplied for each alternative must have
     *  been created for each metric. If insufficient scores or incompatible
     *  scores are provided, the alternative is not added to the model.
     */
    fun defineAlternatives(
        alternatives: Map<String, List<Score>>,
        adjustMetricLowerLimits: Boolean = false,
        adjustMetricUpperLimits: Boolean = true
    ) {
        if (metricFunctionMap.isEmpty()) {
            throw IllegalStateException("There were no metrics defined for the model")
        }
        for ((name, list) in alternatives) {
            if (hasValidScores(list)) {
                myAlternatives[name] = makeMetricScoreMap(list)
            }
        }
        // actual scores may not encompass entire domain of metric
        // it may be useful to adjust domain limits based on realized scores
        // to improve scalability. Only rescale if requested.
        if (adjustMetricLowerLimits || adjustMetricUpperLimits) {
            rescaleMetricDomains(adjustMetricLowerLimits, adjustMetricUpperLimits)
        }
    }

    private fun rescaleMetricDomains(
        adjustMetricLowerLimits: Boolean,
        adjustMetricUpperLimits: Boolean
    ) {
        // need statistics for each alternative's metrics
        val statisticsByMetric = scoreStatisticsByMetric()
        for ((metric, stat) in statisticsByMetric) {
            val interval = PDFModeler.rangeEstimate(stat.min, stat.max, stat.count.toInt())
            if (!adjustMetricLowerLimits && adjustMetricUpperLimits) {
                // no lower limit but yes on upper limit
                metric.domain.setInterval(metric.domain.lowerLimit, interval.upperLimit)
            } else if (adjustMetricLowerLimits && !adjustMetricUpperLimits) {
                // yes on lower limit, no on upper limit
                metric.domain.setInterval(interval.lowerLimit, metric.domain.upperLimit)
            } else {
                // adjust both
                metric.domain.setInterval(interval.lowerLimit, interval.upperLimit)
            }
        }
    }

    /**
     *  Converts a list of scores to a map based on the metric for the score.
     *  This facilitates accessing the scores by metric.
     */
    private fun makeMetricScoreMap(scores: List<Score>): Map<MetricIfc, Score> {
        val map = mutableMapOf<MetricIfc, Score>()
        for (score in scores) {
            map[score.metric] = score
        }
        return map
    }

    /**
     *  Returns the scores as doubles for each metric with each element
     *  of the returned list for a different alternative in the order
     *  that the alternatives are listed.
     */
    fun scoresByMetric(): Map<MetricIfc, List<Double>> {
        val map = mutableMapOf<MetricIfc, List<Double>>()
        for (metric in metrics) {
            map[metric] = metricScores(metric)
        }
        return map
    }

    /**
     *  Retrieves the scores for each alternative as a list of raw score values
     *  based on the supplied [metric].
     */
    fun metricScores(metric: MetricIfc): List<Double> {
        val list = mutableListOf<Double>()
        for ((alternative, map) in myAlternatives) {
            val score = map[metric]!!
            list.add(score.value)
        }
        return list
    }

    /**
     *  Returns the transformed metric scores as values from the assigned
     *  value function for each metric with each element
     *  of the returned list for a different alternative in the order
     *  that the alternatives are listed.
     */
    fun valuesByMetric(): Map<MetricIfc, List<Double>> {
        val map = mutableMapOf<MetricIfc, List<Double>>()
        for (metric in metrics) {
            map[metric] = metricValues(metric)
        }
        return map
    }

    /**
     *  Retrieves the values from the value functions for each alternative as a
     *  list of transformed values based on the supplied [metric]
     */
    fun metricValues(metric: MetricIfc): List<Double>{
        val list = mutableListOf<Double>()
        for ((alternative, map) in myAlternatives) {
            val score = map[metric]!!
            val vf = metricFunctionMap[metric]!!
            // apply the value function to the score
            val v = vf.value(score.value)
            list.add(v)
        }
        return list
    }

    /**
     *   Applies the value function to the scores associated with each alternative
     *   and metric combination to determine the associated value.
     */
    fun alternativeValuesByMetric(): Map<String, Map<MetricIfc, Double>> {
        val map = mutableMapOf<String, Map<MetricIfc, Double>>()
        for ((alternative, metricMap) in myAlternatives) {
            // create the map to hold the values for each metric for the alternative
            val valMap = mutableMapOf<MetricIfc, Double>()
            // process the scores for the alternative
            for ((metric, score) in metricMap) {
                // get the value function for the metric
                val vf = metricFunctionMap[metric]!!
                // apply the value function to the score
                val v = vf.value(score.value)
                // now store it in the map
                valMap[metric] = v
            }
            // save the created map for the alternative
            map[alternative] = valMap
        }
        return map
    }

    fun alternativeScoresAsDataFrame(): AnyFrame {
        // make the alternative column
        val alternativeColumn = alternatives.toColumn("Alternatives")
        // then make columns for each metric
        val columns = mutableListOf<DataColumn<*>>()
        columns.add(alternativeColumn)
        val metrics = scoresByMetric()
        for((metric, score) in metrics){
            val dataColumn = score.toColumn(metric.name)
            columns.add(dataColumn)
        }
        return dataFrameOf(columns)
    }

    fun alternativeValuesAsDataFrame(): AnyFrame {
        // make the alternative column
        val alternativeColumn = alternatives.toColumn("Alternatives")
        // then make columns for each metric
        val columns = mutableListOf<DataColumn<*>>()
        columns.add(alternativeColumn)
        val metrics = valuesByMetric()
        for((metric, score) in metrics){
            val dataColumn = score.toColumn(metric.name)
            columns.add(dataColumn)
        }
        return dataFrameOf(columns)
    }

    fun scoreStatisticsByMetric(): MutableMap<MetricIfc, Statistic> {
        // need to compute statistics (across alternatives) for the raw scores for each metric
        val metricStats = mutableMapOf<MetricIfc, Statistic>()
        val metricScores = scoresByMetric()
        for ((metric, scores) in metricScores) {
            val stat = Statistic(metric.name)
            stat.collect(scores)
            metricStats[metric] = stat
        }
        return metricStats
    }

    /**
     *  Checks if there are sufficient metrics and if the metrics associated with
     *  each score are related to the defined metrics.
     */
    private fun hasValidScores(list: List<Score>): Boolean {
        if (metrics.size != list.size) {
            return false
        }
        for (score in list) {
            if (!metrics.contains(score.metric)) {
                return false
            }
        }
        return true
    }


    /**
     *  Computes the multi-objective (overall) value for the specified
     *  [alternative].
     */
    abstract fun multiObjectiveValue(alternative: String): Double

    /**
     *  Computes the overall values for all defined alternatives
     *  based on the defined multi-objective value function.
     *  The key to the map is the alternative name and the associated
     *  value for the key is the overall multi-objective value for the
     *  associated alternative.
     */
    fun multiObjectiveValues(): Map<String, Double> {
        val map = mutableMapOf<String, Double>()
        for (alternative in alternatives) {
            map[alternative] = multiObjectiveValue(alternative)
        }
        return map
    }

    companion object {

        fun assignLinearValueFunctions(
            metrics: Set<MetricIfc>
        ): Map<MetricIfc, ValueFunctionIfc> {
            val map = mutableMapOf<MetricIfc, ValueFunctionIfc>()
            for (metric in metrics) {
                map[metric] = LinearValueFunction(metric)
            }
            return map
        }
    }
}