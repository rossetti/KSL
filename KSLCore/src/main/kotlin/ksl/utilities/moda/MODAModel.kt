package ksl.utilities.moda

//import com.google.common.collect.HashBasedTable
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.Interval
import ksl.utilities.collections.HashBasedTable
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.Database
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.DbTableData
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.Statistic
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.DataColumn
import org.jetbrains.kotlinx.dataframe.api.*
import org.jetbrains.kotlinx.dataframe.impl.asList
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 *  Defines a base class for creating multi-objective decision analysis (MODA) models.
 */
abstract class MODAModel(
    metricDefinitions: Map<MetricIfc, ValueFunctionIfc>,
    name: String? = null
) : IdentityIfc by Identity(name) {

    protected val metricFunctionMap: MutableMap<MetricIfc, ValueFunctionIfc> = mutableMapOf()
    protected val myAlternatives: MutableMap<String, Map<MetricIfc, Score>> = mutableMapOf()

    /**
     *  Maps a metric supplied by the caller to the metric this model evaluates it against.
     *
     *  An entry appears only where the metric's domain has been adjusted to the realized scores;
     *  no entry means the caller's metric is used exactly as supplied. Holding the adjustment here
     *  rather than writing it into the caller's metric means evaluating a model no longer alters
     *  an object the caller owns.
     *
     *  The scores, keys, data frames, ranks, and database records all continue to be held against
     *  the caller's metric. Only the application of a value function consults a domain, so only
     *  that consults this map.
     */
    private val myEffectiveMetrics: MutableMap<MetricIfc, MetricIfc> = mutableMapOf()

    /**
     *  Warnings raised while defining metrics or alternatives, or while adjusting metric domains.
     *  Cleared whenever metrics or alternatives are redefined, so they always describe the current
     *  contents of the model.
     */
    private val myWarnings: MutableList<ModaWarning> = mutableListOf()

    /**
     *  Anything worth reporting that came up while the model was being set up or its metric domains
     *  adjusted. These are not errors; the model has a defined result in every case. See
     *  [ModaWarning].
     */
    val warnings: List<ModaWarning>
        get() = myWarnings.toList()

    init {
        defineMetrics(metricDefinitions)
    }

    /**
     *  The metric this model evaluates the supplied [metric] against. This is the caller's metric
     *  unless its domain was adjusted to the realized scores, in which case it is a decoration
     *  presenting the adjusted domain.
     */
    protected fun effectiveMetricFor(metric: MetricIfc): MetricIfc =
        myEffectiveMetrics[metric] ?: metric

    /**
     *  The domain the supplied [metric] is actually evaluated against, which is its declared domain
     *  unless it was adjusted to fit the realized scores.
     *
     *  Use this rather than reading the metric's own domain when reporting how a model was
     *  evaluated. The metric's own domain is what the caller declared and is never modified by the
     *  model, so on its own it does not tell you what the value functions were applied over.
     *
     *  The returned interval is a copy, so changing it has no effect on the model.
     */
    fun effectiveDomainOf(metric: MetricIfc): Interval {
        require(metricFunctionMap.containsKey(metric)) { "The metric (${metric.name}) is not part of the model" }
        return effectiveMetricFor(metric).domain.instance()
    }

    /**
     *  Indicates whether the supplied [metric] is evaluated against a domain other than the one it
     *  declares.
     */
    fun wasRescaled(metric: MetricIfc): Boolean {
        require(metricFunctionMap.containsKey(metric)) { "The metric (${metric.name}) is not part of the model" }
        return myEffectiveMetrics.containsKey(metric)
    }

    /**
     *  The single place a value function is applied to a score.
     *
     *  When a metric's domain has been adjusted, the value function has to see the adjusted domain,
     *  and it reads that domain from the score's metric. A transient score against the effective
     *  metric is therefore constructed to carry it. That allocation happens only where an
     *  adjustment is actually in force; otherwise the caller's own score is passed straight through.
     */
    private fun valueOf(metric: MetricIfc, score: Score): Double {
        val valueFunction = metricFunctionMap[metric]!!
        val effective = effectiveMetricFor(metric)
        val evaluated = if (effective === metric) score else Score(effective, score.value, score.valid)
        return valueFunction.value(evaluated)
    }

    /**
     *  For rank based evaluation, this specifies the default parameter value
     *  for those methods the perform rank based evaluation calculations.
     */
    var defaultRankingMethod: Statistic.Companion.Ranking = Statistic.Companion.Ranking.Ordinal

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
        // Any domain adjustment was derived from metrics and alternatives that are now gone, so it
        // must not be left behind to be applied to the new ones.
        myEffectiveMetrics.clear()
        myWarnings.clear()
        for ((metric, valFunc) in definitions) {
            metricFunctionMap[metric] = valFunc
        }
    }

    /**
     *  Removes (clears) all the defined alternatives.  Consider
     *  using this to clear out data associated with previous alternatives
     *  in preparation for a new evaluation across the same metrics.
     */
    fun clearAlternatives() {
        myAlternatives.clear()
        // The domain adjustments were fitted to these alternatives' scores, so they go with them.
        myEffectiveMetrics.clear()
        myWarnings.clear()
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
     *  Metrics specify whether their domain limits may be adjusted based on realized scores.
     *
     *  @param allowRescalingByMetrics indicates if rescaling is permitted by metrics or not.
     *  True is the default.
     */
    fun defineAlternatives(
        alternatives: Map<String, List<Score>>,
        allowRescalingByMetrics: Boolean = true
    ) {
        if (metricFunctionMap.isEmpty()) {
            throw IllegalStateException("There were no metrics defined for the model")
        }
        // Any previous adjustment was fitted to a different set of alternatives. Discarding it here
        // means the domains are always refitted from the metrics as declared, so adding
        // alternatives a few at a time ends up in the same place as supplying them all at once,
        // rather than narrowing the domain a little further on each call.
        myEffectiveMetrics.clear()
        myWarnings.clear()
        for ((name, list) in alternatives) {
            if (hasValidScores(list)) {
                myAlternatives[name] = makeMetricScoreMap(list)
            }
        }
        // actual scores may not encompass entire domain of metric
        // it may be useful to adjust domain limits based on realized scores
        // to improve scalability. Only rescale if requested.
        if (allowRescalingByMetrics) {
            rescaleMetricDomains()
        }
    }

    /**
     *  Fits each metric's domain to the scores the alternatives actually realized, so that the
     *  value functions discriminate over the range that matters rather than over a declared range
     *  no alternative approaches.
     *
     *  The adjustment is recorded against the model rather than written into the caller's metrics,
     *  which are left exactly as supplied. A metric is adjusted only if the resulting domain
     *  contains every one of its realized scores, so a metric is never evaluated against two
     *  different domains within a study. Where an adjustment is not made, the declared domain
     *  stands and the reason is recorded in [warnings].
     */
    private fun rescaleMetricDomains() {
        val statisticsByMetric = scoreStatisticsByMetric()
        for ((metric, stat) in statisticsByMetric) {
            val proposed = proposeInterval(metric, stat) ?: continue
            val combined = combineWithDeclared(metric, proposed)
            if (combined.width <= 0.0) {
                myWarnings.add(ModaWarning.DegenerateDomain(metric.name, combined.toString()))
                continue
            }
            if (!containsAllScores(metric, combined)) {
                myWarnings.add(ModaWarning.DomainNotApplied(metric.name, combined.toString()))
                continue
            }
            myEffectiveMetrics[metric] = RescaledMetric(metric, combined)
        }
    }

    /**
     *  The domain suggested by the realized scores for a metric, before the metric's own limits are
     *  taken into account, or null when the metric permits no adjustment at all or has no scores.
     *
     *  The realized scores can all be the same, which is ordinary rather than exceptional: every
     *  alternative may score zero on a metric, or a simulated response may not vary across the
     *  scenarios being compared. That case cannot be fitted the usual way, which needs a minimum
     *  strictly below the maximum, and it cannot be rounded outward either, because a whole-numbered
     *  common score rounds to a domain of zero width and a value function then divides by that
     *  width.
     *
     *  Tied scores are therefore given a small interval centred on the common value. Every
     *  alternative then takes the midpoint of the value range, which is the meaningful answer: a
     *  metric on which everything ties holds nothing that could separate the alternatives, so it
     *  contributes equally to each and leaves the decision to the metrics that do discriminate. The
     *  width chosen does not affect any value, since the scores sit at the centre whatever the
     *  width; it only affects what the domain is reported as.
     *
     *  Scores count as tied when their spread is within a small fraction of their own magnitude, so
     *  values differing only by floating point noise are treated as the ties they are.
     */
    private fun proposeInterval(metric: MetricIfc, stat: Statistic): Interval? {
        if (!metric.allowLowerLimitAdjustment && !metric.allowUpperLimitAdjustment) return null
        if (stat.count < 1.0) return null
        val spread = stat.max - stat.min
        val scale = max(1.0, abs(stat.average))
        if (spread <= tiedScoreRelativeTolerance * scale) {
            val score = stat.min
            val halfWidth = max(tiedScoreHalfWidth, abs(score) * tiedScoreRelativeTolerance)
            myWarnings.add(ModaWarning.TiedScores(metric.name, score))
            return Interval(score - halfWidth, score + halfWidth)
        }
        if (stat.count > 2.0) {
            return PDFModeler.rangeEstimate(stat.min, stat.max, stat.count.toInt())
        }
        // Two observations that differ. Rounding outward normally widens the interval, but it
        // leaves it unchanged when both values already sit on whole numbers, so guard the width
        // rather than assume it.
        val lower = floor(stat.min)
        val upper = ceil(stat.max)
        return if (upper > lower) {
            Interval(lower, upper)
        } else {
            Interval(lower - tiedScoreHalfWidth, upper + tiedScoreHalfWidth)
        }
    }

    /**
     *  Applies the metric's own limits to a proposed domain, keeping the declared limit wherever the
     *  metric does not permit that limit to be adjusted.
     */
    private fun combineWithDeclared(metric: MetricIfc, proposed: Interval): Interval {
        val lower = if (metric.allowLowerLimitAdjustment) proposed.lowerLimit else metric.domain.lowerLimit
        val upper = if (metric.allowUpperLimitAdjustment) proposed.upperLimit else metric.domain.upperLimit
        if (lower > upper) {
            // A one-sided adjustment can cross the limit it was not allowed to move. Report it as
            // the degenerate case rather than failing to construct the interval.
            return Interval(lower, lower)
        }
        return Interval(lower, upper)
    }

    /**
     *  Indicates whether every realized score for the supplied metric falls inside the candidate
     *  domain.
     *
     *  A domain that does not contain all of them is not applied at all. Applying it to just the
     *  alternatives it fits would evaluate one metric against two different domains within a single
     *  study, and the resulting values would not be comparable with each other.
     */
    private fun containsAllScores(metric: MetricIfc, candidate: Interval): Boolean {
        for ((_, map) in myAlternatives) {
            val score = map[metric] ?: continue
            if (!candidate.contains(score.value)) return false
        }
        return true
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
        for ((_, map) in myAlternatives) {
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
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod
    ): Map<MetricIfc, List<Double>> {
        val map = mutableMapOf<MetricIfc, List<Double>>()
        for (metric in metrics) {
            map[metric] = metricRanks(metric, rankingMethod)
        }
        return map
    }

    /**
     *   Constructs a map of maps with the key to the outer map
     *   being the alternative name and the inner map holding the rank
     *   of the associated metric. Allows the lookup of the rank for
     *   a metric by alternative.
     */
    fun metricRankByAlternative(
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod
    ): Map<String, Map<MetricIfc, Double>> {
        val table = HashBasedTable.create<String, MetricIfc, Double>()
        val ranksByMetric = ranksByMetric(rankingMethod)
        val alternatives = alternatives
        for ((metric, ranks) in ranksByMetric) {
            for ((i, rank) in ranks.withIndex()) {
                val alternative = alternatives[i]
                table.put(alternative, metric, rank)
            }
        }
        return table.rowMap
    }

    /**
     *  Retrieves the values from the value functions for each alternative as a
     *  list of transformed values based on the supplied [metric]. The supplied
     *  metric must be part of the model.
     */
    fun metricValues(metric: MetricIfc): List<Double> {
        require(metrics.contains(metric)) { "The metric (${metric.name} is not part of the model" }
        val list = mutableListOf<Double>()
        for ((_, map) in myAlternatives) {
            list.add(valueOf(metric, map[metric]!!))
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
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod
    ): List<Double> {
        val mv = metricValues(metric).toDoubleArray()
        return Statistic.ranks(mv, rankingMethod, true).toList()
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
                valMap[metric] = valueOf(metric, score)
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
            map[metric] = valueOf(metric, score)
        }
        return map
    }

    private fun applyValueFunction(alternative: String, metric: MetricIfc): Double {
        require(myAlternatives.contains(alternative)) { "The supplied alternative = $alternative is not part of the model." }
        require(metrics.contains(metric)) { "The metric (${metric.name} is not part of the model" }
        val metricMap = myAlternatives[alternative]!!
        return valueOf(metric, metricMap[metric]!!)
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
     *  @param rankingMethod provides the type of ranking. By default, it is ordinal.
     */
    fun alternativeRanksAsDataFrame(
        firstColumnName: String = "Alternatives",
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod
    ): AnyFrame {
        // make the alternative column
        val alternativeColumn = alternatives.toColumn(firstColumnName)
        // then make columns for each metric
        val columns = mutableListOf<DataColumn<*>>()
        columns.add(alternativeColumn)
        val ranksByMetricMap = ranksByMetric(rankingMethod)
        for ((metric, ranks) in ranksByMetricMap) {
            val dataColumn = ranks.toColumn("${metric.name}_Rank")
            columns.add(dataColumn)
        }
        val rankCounts = alternativeFirstRankCounts(false).toMap()
        val countsCol = rankCounts.values.toColumn("First Rank Counts")
        val rankAvgs = alternativeAverageRanking(false, rankingMethod).toMap()
        val rankAvgCol = rankAvgs.values.toColumn("Avg Rank")
        columns.add(countsCol)
        columns.add(rankAvgCol)
        return dataFrameOf(columns).sortBy(rankAvgCol.name())
    }

    /**
     *  Returns a data frame with the first column being the alternatives
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
        // now add the overall value for each alternative
        val valuesByAlternative = multiObjectiveValuesByAlternative()
        val overallValue = valuesByAlternative.values.toColumn("Overall Value")
        columns.add(overallValue)
        return dataFrameOf(columns).sortByDesc(overallValue.name())
    }

    override fun toString(): String {
        val sb = StringBuilder().apply {
            appendLine("MODA Results")
            appendLine("-------------------------------------------")
            appendLine("Metrics")
            for (metric in metrics) {
                append("\t ${metric.name}")
                // The domain the model evaluated against, which is what explains the values below.
                append("\t domain = ${effectiveDomainOf(metric)}")
                append("\t direction = ${metric.direction}")
                if (metric.unitsOfMeasure != null) {
                    append("\t units = ${metric.unitsOfMeasure}")
                }
                appendLine()
            }
            appendLine()
            appendLine("-------------------------------------------")
            appendLine("Alternative Scores:")
            appendLine(alternativeScoresAsDataFrame())
            appendLine("Alternative Values:")
            appendLine(alternativeValuesAsDataFrame())
            appendLine("Alternative Ranks:")
            appendLine(alternativeRanksAsDataFrame())
        }
        return sb.toString()
    }

    fun print() {
        print(toString())
    }

    /**
     *  Returns a data frame with the first column being the alternatives
     *  by name, a column of rank counts for each alternative,
     *  and a final column representing the average rank for the alternative.
     *  The parameter [firstColumnName] can be used to name the first column of the
     *  returned data frame. By default, the first column name is "Alternatives".
     *  The resulting data frame will be sorted by average rank column with
     *  lower value being preferred.
     */
    fun alternativeRankingsAsDataFrame(
        firstColumnName: String = "Alternatives",
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod
    ): AnyFrame {
        // make the alternative column
        val rankCounts = alternativeFirstRankCounts(false).toMap()
        val altCol = rankCounts.keys.toColumn(firstColumnName)
        val countsCol = rankCounts.values.toColumn("First Rank Counts")
        val rankAvgs = alternativeAverageRanking(false, rankingMethod).toMap()
        val rankAvgCol = rankAvgs.values.toColumn("Avg Rank")
        val columns = mutableListOf<DataColumn<*>>()
        columns.add(altCol)
        columns.add(countsCol)
        columns.add(rankAvgCol)
        return dataFrameOf(columns).sortBy(rankAvgCol.name())
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

    /**
     *  The list of alternatives sorted by their multi-objective value
     *  The returned list has pairs (alternative name, multi-objective value)
     */
    fun sortedMultiObjectiveValuesByAlternative(): List<Pair<String, Double>> {
        val map = multiObjectiveValuesByAlternative()
        val result = map.toList().sortedByDescending { it.second }
        return result
    }

    /**
     *  The alternatives and their rank based on largest to smallest multi-objective value
     */
    fun alternativeRankedByMultiObjectiveValue(): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        val list = sortedMultiObjectiveValuesByAlternative()
        for ((i, element) in list.withIndex()) {
            map[element.first] = i + 1
        }
        return map
    }

    /**
     *  The names of the alternatives that are considered first based on the multi-objective values.
     *  The set may have more than one alternative if the alternatives tie based on
     *  multi-objective values.
     */
    fun topAlternativesByMultiObjectiveValue(): Set<String> {
        val set = mutableSetOf<String>()
        val altList = sortedMultiObjectiveValuesByAlternative()
        val first = altList.first()
        for ((alternative, value) in altList) {
            if (value == first.second) {
                set.add(alternative)
            }
        }
        return set
    }

    /**
     *  Returns a list of ScoreData which holds for each alternative-metric raw score combination.
     *  (id, alternativeName, scoreName, scoreValue)
     */
    fun alternativeScoreData(): List<ScoreData> {
        val list = mutableListOf<ScoreData>()
        for ((alternative, metric) in myAlternatives) {
            for ((m, v) in metric) {
                list.add(
                    ScoreData(
                        modaName = this.label ?: this.name,
                        alternative = alternative,
                        scoreName = m.name,
                        scoreValue = v.value
                    )
                )
            }
        }
        return list
    }

    /**
     *  Returns a list of ValueData which holds for each alternative-metric value combination.
     *  (id, alternativeName, metricName, metricValue)
     */
    fun alternativeValueData(
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod
    ): List<ValueData> {
        val list = mutableListOf<ValueData>()
        var id = 1
        val alternativeValuesByMetric = alternativeValuesByMetric()
        val ranksByMetricMap = ranksByMetric(rankingMethod)
        for ((alternative, metricMap) in alternativeValuesByMetric) {
            for ((m, v) in metricMap) {
                list.add(
                    ValueData(
                        modaName = this.label ?: this.name,
                        alternative = alternative,
                        metricName = m.name,
                        metricValue = v,
                        rank = ranksByMetricMap[m]!![id - 1]
                    )
                )
            }
            id = id + 1
        }
        return list
    }

    /**
     *  Collects the ranking frequencies across all metrics for each alternative.
     *
     *  @param sortByAvgRanking If true, the resulting map is sorted by the average frequency
     *  across the observed ranks. The default is true.
     */
    fun alternativeMetricRankFrequencies(
        sortByAvgRanking: Boolean = true,
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod
    ): Map<String, IntegerFrequency> {
        // make frequencies
        val altFreqMap = mutableMapOf<String, IntegerFrequency>()
        for (alternative in alternatives) {
            altFreqMap[alternative] = IntegerFrequency(name = "$alternative Metric Rank Frequencies")
        }
        val vdList = alternativeValueData(rankingMethod)
        for (vd in vdList) {
            altFreqMap[vd.alternative]!!.collect(vd.rank)
        }
        if (!sortByAvgRanking) {
            return altFreqMap
        }
        val sortedMap = altFreqMap.toList().sortedBy { (_, freq) -> freq.average }.toMap()
        return sortedMap
    }

    /**
     *   The alternatives that were ranked first by some metric along with the metric
     *   frequency distribution.
     *  @param sortByAvgRanking If true, the resulting map is sorted by the average frequency
     *  across the observed ranks. The default is true.
     */
    fun alternativeFirstRankMetricFrequencies(
        sortByAvgRanking: Boolean = true,
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod
    ): Map<String, IntegerFrequency> {
        val altSubMap = mutableMapOf<String, IntegerFrequency>()
        val altFreqMap = alternativeMetricRankFrequencies(sortByAvgRanking, rankingMethod)
        for ((alternative, freq) in altFreqMap) {
            if (freq.closedRange.contains(1)) {
                altSubMap[alternative] = freq
            }
        }
        return altSubMap
    }

    /**
     *  Captures the alternative metric rank frequency data to a list.
     *  @param sortByAvgRanking If true, the resulting map is sorted by the average frequency
     *  across the observed ranks. The default is true.
     */
    fun alternativeRankFrequencyData(
        sortByAvgRanking: Boolean = true,
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod
    ): List<AlternativeRankFrequencyData> {
        val list = mutableListOf<AlternativeRankFrequencyData>()
        val altFreqMap = alternativeMetricRankFrequencies(sortByAvgRanking, rankingMethod)
        for ((alternative, freq) in altFreqMap) {
            val fData = freq.frequencyData()
            for (fd in fData) {
                val arfd = AlternativeRankFrequencyData(
                    modaName = this.label ?: this.name,
                    alternative = alternative,
                    value = fd.value,
                    count = fd.count,
                    proportion = fd.proportion,
                    cumProportion = fd.cumProportion
                )
                list.add(arfd)
            }
        }
        return list
    }

    /**
     *  Returns the alternatives with the count of the number of times some metric
     *  ranked the alternative first based on the value scores.
     *
     *  @param sortByCounts If true, the resulting list is sorted by based on the counts in descending order.
     *  The default is true.
     */
    fun alternativeFirstRankCounts(
        sortByCounts: Boolean = true,
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod
    ): List<Pair<String, Int>> {
        val map = mutableMapOf<String, Int>()
        val altFreqMap = alternativeMetricRankFrequencies(false, rankingMethod)
        for ((alternative, freq) in altFreqMap) {
            map[alternative] = freq.frequency(1).toInt()
        }
        if (!sortByCounts) {
            return map.toList()
        }
        return map.toList().sortedByDescending { it.second }
    }

    /**
     *  Returns the alternatives with the average across the observed ranks. The returned list
     *  of pairs (alternative, average rank) is ordered based on the averages smallest to largest.
     *
     *  @param sortByAvgRanking If true, the resulting list is sorted by the average frequency
     *  across the observed ranks. The default is true.
     */
    fun alternativeAverageRanking(
        sortByAvgRanking: Boolean = true,
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod
    ): List<Pair<String, Double>> {
        val list = mutableListOf<Pair<String, Double>>()
        val altFreqMap = alternativeMetricRankFrequencies(sortByAvgRanking, rankingMethod)
        for ((alternative, freq) in altFreqMap) {
            list.add(Pair(alternative, freq.average))
        }
        return list
    }

    /**
     *  The names of the alternatives that are considered first based on the number
     *  of times the metrics ranked the alternative first.
     *  The set may have more than one alternative if the alternatives tie based on
     *  the count rankings.
     */
    fun topAlternativesByFirstRankCounts(
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod
    ): Set<String> {
        val set = mutableSetOf<String>()
        val altList = alternativeFirstRankCounts(true, rankingMethod)
        val first = altList.first()
        for ((alternative, value) in altList) {
            if (value == first.second) {
                set.add(alternative)
            }
        }
        return set
    }

    /**
     *  Returns a list of OverallValueData which holds for each alternative overall value combination.
     *  (id, alternativeName, overall value, first rank count, average ranking)
     */
    fun alternativeOverallValueData(
        rankingMethod: Statistic.Companion.Ranking = defaultRankingMethod
    ): List<OverallValueData> {
        val list = mutableListOf<OverallValueData>()
        val valuesByAlternative = multiObjectiveValuesByAlternative()
        val counts = alternativeFirstRankCounts().toMap()
        val averages = alternativeAverageRanking(true, rankingMethod).toMap()
        var id = 1
        for ((alternative, v) in valuesByAlternative) {
            val cnt = counts[alternative]!!
            val avg = averages[alternative]!!
            list.add(
                OverallValueData(
                    modaName =  this.label ?: this.name,
                    alternative = alternative,
                    weightedValue = v,
                    firstRankCount = cnt,
                    averageRank = avg
                )
            )
            id = id + 1
        }
        return list
    }

    companion object {

        /**
         *  How close together the realized scores for a metric have to be, as a fraction of their
         *  own magnitude, before they are treated as all being the same.
         *
         *  Expressed relative to magnitude rather than as an absolute amount so that it means the
         *  same thing for a metric measured in fractions of a second as for one measured in
         *  millions. The default is small enough that only differences at the level of floating
         *  point noise are absorbed.
         */
        var tiedScoreRelativeTolerance: Double = 1.0e-12
            set(value) {
                require(value >= 0.0) { "The tied score relative tolerance must be >= 0.0" }
                field = value
            }

        /**
         *  Half the width of the domain given to a metric on which every alternative scored the
         *  same.
         *
         *  This does not affect any computed value: the tied scores sit at the centre of whatever
         *  domain is chosen, so every alternative takes the midpoint of the value range regardless.
         *  It only determines what such a metric's domain is reported as.
         */
        var tiedScoreHalfWidth: Double = 0.5
            set(value) {
                require(value > 0.0) { "The tied score half-width must be > 0.0" }
                field = value
            }

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

        /**
         *  Assigns a linear value function to each metric.
         */
        fun assignLinearValueFunctions(
            metrics: List<MetricIfc>
        ): Map<MetricIfc, ValueFunctionIfc> {
            val map = mutableMapOf<MetricIfc, ValueFunctionIfc>()
            for (metric in metrics) {
                map[metric] = LinearValueFunction()
            }
            return map
        }

        /**
         *  Creates a list of metrics with the supplied names. Each metric
         *  has the default settings.
         */
        fun createDefaultMetrics(names: Set<String>): List<MetricIfc> {
            val list = mutableListOf<MetricIfc>()
            for (name in names) {
                list.add(Metric(name))
            }
            return list
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

//TODO make id fields

data class MetricData(
    var id: Int = metricDataCounter++,
    var modaName: String = "",
    var metricName: String = "",
    var direction: String = "",
    var weight: Double = 1.0,
    var domainLowerLimit: Double = 0.0,
    var domainUpperLimit: Double = Double.POSITIVE_INFINITY,
    var unitsOfMeasure: String? = null,
    var description: String? = null,
    var allowLowerLimitAdjustment: Boolean = false,
    var allowUpperLimitAdjustment: Boolean = false
) : DbTableData("tblMetrics", listOf("id")) {

    companion object {
        var metricDataCounter = 0
    }
}

data class ScoreData(
    var id: Int = scoreDataCounter++,
    var modaName: String = "",
    var alternative: String = "",
    var scoreName: String = "",
    var scoreValue: Double = 0.0
) : DbTableData("tblScores", listOf("id")) {

    companion object {
        var scoreDataCounter : Int = 0
    }
}

data class ValueData(
    var id: Int = valueDataCounter++,
    var modaName: String = "",
    var alternative: String = "",
    var metricName: String = "",
    var metricValue: Double = 0.0,
    var rank: Double = 0.0
) : DbTableData("tblValues", listOf("id")) {

    companion object {
        var valueDataCounter : Int = 0
    }
}

data class OverallValueData(
    var id: Int = overallValueDataCounter++,
    var modaName: String = "",
    var alternative: String = "",
    var weightedValue: Double = 0.0,
    var firstRankCount: Int = 0,
    var averageRank: Double = 0.0,
) : DbTableData("tblOverall", listOf("id")) {

    companion object {
        var overallValueDataCounter : Int = 0
    }
}

data class AlternativeRankFrequencyData(
    var id: Int = altRankFreqDataCounter++,
    var modaName: String = "",
    var alternative: String = "",
    var value: Int = 0,
    var count: Double = 0.0,
    var proportion: Double = 0.0,
    var cumProportion: Double = 0.0
) : DbTableData("tblRankFrequency", listOf("id")) {

    companion object {
        var altRankFreqDataCounter : Int = 0
    }
}