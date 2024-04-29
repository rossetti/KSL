package ksl.utilities.moda

import ksl.utilities.Interval
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.statistic.Statistic
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.DataColumn
import org.jetbrains.kotlinx.dataframe.api.*
import org.jetbrains.kotlinx.dataframe.impl.asList

/**
 *  Defines a base class for creating multi-objective decision analysis (MODA) models.
 */
abstract class MODAModel(
    metricDefinitions: Map<MetricIfc, ValueFunctionIfc>
) {

    protected val metricFunctionMap: MutableMap<MetricIfc, ValueFunctionIfc> = mutableMapOf()
    protected val myAlternatives: MutableMap<String, Map<MetricIfc, Score>> = mutableMapOf()

    init {
        defineMetrics(metricDefinitions)
    }

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
     *   Each metric must be associated with the related value function. If not,
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
     *  Removes (clears) all the defined alternatives.  Consider
     *  using this to clear out data associated with previous alternatives
     *  in preparation for a new evaluation across the same metrics.
     */
    fun clearAlternatives() {
        myAlternatives.clear()
    }

    /**
     *  Defines the [alternatives] and their scores that should be evaluated by the
     *  model. The metrics for the model must have been previously defined prior
     *  to specifying the alternatives. The scores supplied for each alternative must have
     *  been created for each metric. If insufficient scores or incompatible
     *  scores are provided, the alternative is not added to the model. If the alternative
     *  has been previously defined, its data will be overwritten by the newly supplied
     *  scores.  Any alternatives that are new will be added to the alternatives to
     *  be evaluated (provided that they have scores for all defined metrics).
     *  The supplied scores may not encompass the entire domain of the related metrics.
     *  It may be useful to adjust the domain limits (of the metrics) based on the actual (realized)
     *  scores that were supplied.
     *  @param adjustMetricLowerLimits indicates that the metric domain's lower limits should be
     *  rescaled if true. The default is false.
     *  @param adjustMetricUpperLimits indicates that the metric domain's upper limits should be
     *  rescaled if true. The default is true.
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
            //TODO this causes the side-effect that the domain of the metrics for the scores are changed.
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
            val interval = if (stat.count >= 2) {
                PDFModeler.rangeEstimate(stat.min, stat.max, stat.count.toInt())
            } else {
                Interval(stat.min, stat.max)
            }
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
     *  based on the supplied [metric].  The supplied metric must be part of the model.
     */
    fun metricScores(metric: MetricIfc): List<Double> {
        require(metrics.contains(metric)) { "The metric (${metric.name} is not part of the model" }
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
     *  Returns the ranks of the transformed metric scores as values from the assigned
     *  value function for each metric with each element
     *  of the returned list for a different alternative in the order
     *  that the alternatives are listed. The default ranking method is Ordinal.
     */
    fun ranksByMetric(
        rankingMethod: Statistic.Companion.Ranking = Statistic.Companion.Ranking.Ordinal
    ): Map<MetricIfc, List<Double>> {
        val map = mutableMapOf<MetricIfc, List<Double>>()
        for (metric in metrics) {
            map[metric] = metricRanks(metric, rankingMethod)
        }
        return map
    }

    /**
     *  Retrieves the values from the value functions for each alternative as a
     *  list of transformed values based on the supplied [metric]. The supplied
     *  metric must be part of the model.
     */
    fun metricValues(metric: MetricIfc): List<Double> {
        require(metrics.contains(metric)) { "The metric (${metric.name} is not part of the model" }
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
     *  Retrieves the rank of each value for each alternative as a
     *  list of ranks based on the supplied [metric]. The supplied
     *  metric must be part of the model. The elements of the list
     *  return the ranking of the alternatives with respect to the supplied
     *  [metric].  The number of elements is the number of alternatives.
     *  Thus, element 0 has the rank of the alternative 0 based on the metric.
     *  Thus, each alternative may have a different ranking based on the different
     *  metrics.
     */
    fun metricRanks(
        metric: MetricIfc,
        rankingMethod: Statistic.Companion.Ranking = Statistic.Companion.Ranking.Ordinal
    ): List<Double> {
        val mv = metricValues(metric).toDoubleArray()
        return Statistic.ranks(mv, rankingMethod).toList()
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

    /**
     *  Retrieves the value function values for each metric for the named [alternative]. The
     *  alternative must be defined as part of the model.
     */
    fun valuesByAlternative(alternative: String): Map<MetricIfc, Double> {
        require(myAlternatives.contains(alternative)) { "The supplied alternative = $alternative is not part of the model." }
        val map = mutableMapOf<MetricIfc, Double>()
        val metricMap = myAlternatives[alternative]!!
        for ((metric, score) in metricMap) {
            // get the value function for the metric
            val vf = metricFunctionMap[metric]!!
            // apply the value function to the score
            map[metric] = vf.value(score.value)
        }
        return map
    }

    private fun applyValueFunction(alternative: String, metric: MetricIfc): Double {
        require(myAlternatives.contains(alternative)) { "The supplied alternative = $alternative is not part of the model." }
        require(metrics.contains(metric)) { "The metric (${metric.name} is not part of the model" }
        val metricMap = myAlternatives[alternative]!!
        val score = metricMap[metric]!!
        // get the value function for the metric
        val vf = metricFunctionMap[metric]!!
        // apply the value function to the score
        return vf.value(score.value)
    }

    /**
     *  Returns a data from with the first column being the alternatives
     *  by name, a column of raw score values for each metric for each alternative.
     *  The parameter [firstColumnName] can be used to name the first column of the
     *  returned data frame. By default, the first column name is "Alternatives".
     */
    fun alternativeScoresAsDataFrame(firstColumnName: String = "Alternatives"): AnyFrame {
        // make the alternative column
        val alternativeColumn = alternatives.toColumn(firstColumnName)
        // then make columns for each metric
        val columns = mutableListOf<DataColumn<*>>()
        columns.add(alternativeColumn)
        val metrics = scoresByMetric()
        for ((metric, score) in metrics) {
            val dataColumn = score.toColumn(metric.name)
            columns.add(dataColumn)
        }
        return dataFrameOf(columns)
    }

    /**
     *  Returns a data frame with the first column being the alternatives
     *  by name, a column of ranks for each metric for each alternative.
     *  The parameter [firstColumnName] can be used to name the first column of the
     *  returned data frame. By default, the first column name is "Alternatives".
     *  The metric ranking columns are labeled as "${metric.name}_Rank"
     */
    fun alternativeRanksAsDataFrame(firstColumnName: String = "Alternatives"): AnyFrame {
        // make the alternative column
        val alternativeColumn = alternatives.toColumn(firstColumnName)
        // then make columns for each metric
        val columns = mutableListOf<DataColumn<*>>()
        columns.add(alternativeColumn)
        val metrics = ranksByMetric()
        for ((metric, score) in metrics) {
            val dataColumn = score.toColumn("${metric.name}_Rank")
            columns.add(dataColumn)
        }
        return dataFrameOf(columns)
    }

    /**
     *  Returns a data framed with the first column being the alternatives
     *  by name, a column of values for each metric for each alternative,
     *  and a final column representing the overall value for the alternative.
     *  The parameter [firstColumnName] can be used to name the first column of the
     *  returned data frame. By default, the first column name is "Alternatives".
     *  The resulting data frame will be sorted by the overall value column with
     *  higher value being preferred.
     */
    fun alternativeValuesAsDataFrame(firstColumnName: String = "Alternatives"): AnyFrame {
        // make the alternative column
        val alternativeColumn = alternatives.toColumn(firstColumnName)
        // then make columns for each metric
        val columns = mutableListOf<DataColumn<*>>()
        columns.add(alternativeColumn)
        val metrics = valuesByMetric()
        for ((metric, score) in metrics) {
            val dataColumn = score.toColumn(metric.name)
            columns.add(dataColumn)
        }
        // no add the overall value for each alternative
        val valuesByAlternative = multiObjectiveValuesByAlternative()
        val overallValue = valuesByAlternative.values.toColumn("Overall Value")
        columns.add(overallValue)
        return dataFrameOf(columns).sortByDesc(overallValue)
    }

    /**
     *   Returns a data from with the first column being the alternatives
     *   by name, a column of raw score values for each metric for each alternative, and
     *   a column of values for each metric for each alternative,
     *  and a final column representing the overall value for the alternative.
     *  The parameter [firstColumnName] can be used to name the first column of the
     *  returned data frame. By default, the first column name is "Alternatives".
     *
     *  This function essentially combines alternativeScoresAsDataFrame() and
     *  alternativeValuesAsDataFrame() into one data frame. The score column names
     *  have _Score appended and the value column names have _Value appended.
     */
    fun alternativeResultsAsDataFrame(firstColumnName: String = "Alternatives"): AnyFrame {
        val alternativeColumn = alternatives.toColumn(firstColumnName)
        // then make columns for each metric
        val columns = mutableListOf<DataColumn<*>>()
        columns.add(alternativeColumn)
        val metrics = scoresByMetric()
        for ((metric, score) in metrics) {
            val dataColumn = score.toColumn("${metric.name}_Score")
            columns.add(dataColumn)
        }
        val values = valuesByMetric()
        for ((metric, value) in values) {
            val dataColumn = value.toColumn("${metric.name}_Value")
            columns.add(dataColumn)
        }
        // now add the overall value for each alternative
        val valuesByAlternative = multiObjectiveValuesByAlternative()
        val overallValue = valuesByAlternative.values.toColumn("Overall Value")
        columns.add(overallValue)
        val ranks = rankLargestToSmallest(overallValue.toDoubleArray()).toList()
        val rankColumn = ranks.toColumn("Overall Rank")
        columns.add(rankColumn)
        return dataFrameOf(columns)
    }

    /**
     *  Computes statistics for each metric across the alternatives.
     */
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
     *  [alternative]. The supplied alternative (name) must be within
     *  the model.
     */
    abstract fun multiObjectiveValue(alternative: String): Double

    /**
     *  Computes the overall values for all defined alternatives
     *  based on the defined multi-objective value function.
     *  The key to the map is the alternative name and the associated
     *  value for the key is the overall multi-objective value for the
     *  associated alternative.
     */
    fun multiObjectiveValuesByAlternative(): Map<String, Double> {
        val map = mutableMapOf<String, Double>()
        for (alternative in alternatives) {
            map[alternative] = multiObjectiveValue(alternative)
        }
        return map
    }

    companion object {

        /**
         *  Ranks the array elements from the largest as 1 and smallest as the size of the data array.
         *  using ordinal ranking.
         */
        fun rankLargestToSmallest(data: DoubleArray): DoubleArray {
            // create the ranks array
            val ranks = DoubleArray(data.size) { 0.0 }
            // Create an auxiliary array of pairs, each pair stores the data as well as its index
            val pairs = Array<Pair<Double, Int>>(data.size) { Pair(data[it], it) }
            // sort according to the data (first element in the pair
            val comparator = compareBy<Pair<Double, Int>> { it.first }
            pairs.sortWith(comparator.reversed())
            for ((k, pair) in pairs.withIndex()) {
                ranks[pair.second] = k + 1.0
            }
            return ranks
        }

        /**
         *  The [alternativeColumn] is the column index for the data frame that
         *  represents the column holding the alternative names. The type of the
         *  column must be String. The array [metricColumns] holds the indices of the
         *  columns that hold the scores for each metric as Double values. Each of
         *  the metric columns must be of type Double. The [dataFrame] is processed by
         *  rows and the returned Map<String, List<Score>> hold the alternatives and
         *  their scores suitable for use in the defineAlternatives() method. The second
         *  element of the returned pair holds a list of metrics that were defined
         *  for each score. The user may want to change the mutable properties of the
         *  metrics before constructing a Map<MetricIfc, ValueFunctionIfc> for use
         *  in the defineMetrics() method.
         */
        fun readDataFrame(
            alternativeColumn: Int,
            metricColumns: IntArray,
            dataFrame: AnyFrame
        ): Pair<Map<String, List<Score>>, List<Metric>> {
            val columns = dataFrame.columns()
            require(columns[alternativeColumn].type().classifier == String::class) { "The alternative column must hold Strings" }
            val metrics = mutableMapOf<String, Metric>()
            for (col in metricColumns) {
                require(columns[col].type().classifier == Double::class) { "The metric columns must hold Doubles" }
                val colName = columns[col].name()
                metrics[colName] = Metric(colName)
            }
            val map = mutableMapOf<String, List<Score>>()
            // process the rows of the data frame
            for (row in dataFrame.rows()) {
                val name = row[alternativeColumn] as String
                val list = mutableListOf<Score>()
                for (col in metricColumns) {
                    val value = row[col] as Double
                    val colName = columns[col].name()
                    val score = Score(metrics[colName]!!, value)
                    list.add(score)
                }
                map[name] = list
            }
            return Pair(map, metrics.values.asList())
        }

        fun assignLinearValueFunctions(
            metrics: List<MetricIfc>
        ): Map<MetricIfc, ValueFunctionIfc> {
            val map = mutableMapOf<MetricIfc, ValueFunctionIfc>()
            for (metric in metrics) {
                map[metric] = LinearValueFunction(metric)
            }
            return map
        }

        /**
         *  Creates a map of weights for each metric such that all weights are equal,
         *  and they sum to 1.0
         */
        fun makeEqualWeights(metrics: Collection<MetricIfc>): Map<MetricIfc, Double> {
            val map = mutableMapOf<MetricIfc, Double>()
            val n = metrics.size.toDouble()
            for (metric in metrics) {
                map[metric] = 1.0 / n
            }
            return map
        }

        /**
         *  Extracts the metrics associated with each score.
         */
        fun extractMetrics(scores: List<Score>): List<MetricIfc> {
            return List(scores.size) { scores[it].metric }
        }

        /**
         *  Extracts the values of each score
         */
        fun extractScoreValue(scores: List<Score>): DoubleArray {
            return DoubleArray(scores.size) { scores[it].value }
        }
    }
}